package com.fabapps.gomonitor.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Mantiene la conexión con un agente: prueba `/health`, abre el WebSocket, se autentica, publica
 * las muestras y reconecta con espera creciente cuando se corta.
 *
 * Solo hay una sesión a la vez. Cada sesión es una corrutina; cancelarla (al desconectar o al
 * conectar con otro equipo) corta su socket y sus esperas, así que no quedan timers ni sockets
 * colgados.
 */
class ConnectionManager(
    private val transport: AgentTransport,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val network: () -> NetworkStatus = { NetworkStatus.UNKNOWN },
    /** Se llama cada vez que una conexión llega a autenticarse. */
    private val onAuthenticated: (SavedConnection) -> Unit = {},
) {
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.NotConfigured)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _snapshot = MutableStateFlow<Snapshot?>(null)
    val snapshot: StateFlow<Snapshot?> = _snapshot.asStateFlow()

    /** Momento en que llegó la última muestra, según [clock]. */
    private val _lastUpdateMillis = MutableStateFlow<Long?>(null)
    val lastUpdateMillis: StateFlow<Long?> = _lastUpdateMillis.asStateFlow()

    private val _target = MutableStateFlow<SavedConnection?>(null)
    val target: StateFlow<SavedConnection?> = _target.asStateFlow()

    private var session: Job? = null

    /** Conecta con [connection], cerrando antes cualquier conexión anterior. */
    fun connect(connection: SavedConnection) {
        session?.cancel()
        if (_target.value?.id != connection.id) {
            // Los datos del equipo anterior no deben verse bajo el nombre del nuevo.
            _snapshot.value = null
            _lastUpdateMillis.value = null
        }
        _target.value = connection
        _state.value = ConnectionState.Connecting
        session = scope.launch { runSession(connection) }
    }

    /** Cierra la conexión y deja de reintentar. Conserva la última muestra en pantalla. */
    fun disconnect() {
        session?.cancel()
        session = null
        _state.value = if (_target.value == null) ConnectionState.NotConfigured else ConnectionState.Disconnected
    }

    /** Olvida el equipo actual, por ejemplo cuando el usuario lo elimina. */
    fun clear() {
        session?.cancel()
        session = null
        _target.value = null
        _snapshot.value = null
        _lastUpdateMillis.value = null
        _state.value = ConnectionState.NotConfigured
    }

    /** Reintenta ya, sin esperar el backoff. También sirve tras una falla definitiva. */
    fun retryNow() {
        _target.value?.let(::connect)
    }

    private sealed interface Outcome {
        /** [wasConnected] indica que llegó a autenticarse, lo que reinicia el backoff. */
        data class Retry(val reason: FailureReason, val wasConnected: Boolean) : Outcome
        data class Fatal(val reason: FailureReason) : Outcome
    }

    private suspend fun runSession(target: SavedConnection) {
        var attempt = 0
        while (true) {
            when (val outcome = runAttempt(target)) {
                is Outcome.Fatal -> {
                    _state.value = ConnectionState.Failed(outcome.reason)
                    return
                }
                is Outcome.Retry -> {
                    if (outcome.wasConnected) attempt = 0
                    attempt++
                    val wait = Backoff.delayMillis(attempt, target.reconnectSeconds)
                    _state.value = ConnectionState.Reconnecting(attempt, wait, outcome.reason)
                    delay(wait)
                }
            }
        }
    }

    private sealed interface SocketEvent {
        data object Open : SocketEvent
        data class Message(val text: String) : SocketEvent
        data class Closed(val code: Int) : SocketEvent
        data class Failure(val error: Throwable) : SocketEvent
    }

    /** Un intento completo. Devuelve cuando la conexión termina, por el motivo que sea. */
    private suspend fun runAttempt(target: SavedConnection): Outcome {
        // 1. /health distingue "no hay nadie" de "hay alguien pero el token está mal".
        try {
            val health = withTimeoutOrNull(HEALTH_TIMEOUT_MILLIS) { transport.health(target) }
                ?: return Outcome.Retry(classify(java.net.SocketTimeoutException(), target), wasConnected = false)
            if (health.status != "ok") return Outcome.Fatal(FailureReason.NOT_AN_AGENT)
            if (health.protocolVersion > SUPPORTED_PROTOCOL_VERSION) return Outcome.Fatal(FailureReason.INCOMPATIBLE)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val reason = classify(e, target)
            return if (reason.isFatal) Outcome.Fatal(reason) else Outcome.Retry(reason, wasConnected = false)
        }

        // 2. WebSocket. Los eventos del socket llegan por un canal para procesarlos en orden.
        val events = Channel<SocketEvent>(Channel.UNLIMITED)
        val socket = transport.openSocket(target, object : SocketListener {
            override fun onOpen() { events.trySend(SocketEvent.Open) }
            override fun onMessage(text: String) { events.trySend(SocketEvent.Message(text)) }
            override fun onClosed(code: Int, reason: String) { events.trySend(SocketEvent.Closed(code)) }
            override fun onFailure(error: Throwable) { events.trySend(SocketEvent.Failure(error)) }
        })

        var authenticated = false
        // Hasta saber cada cuánto envía el agente, se espera lo que dura el plazo de autenticación.
        var silenceLimit = HANDSHAKE_TIMEOUT_MILLIS
        var serverError: String? = null
        try {
            while (true) {
                // Vigilancia: una red que desaparece no avisa; el silencio es la única señal.
                val event = withTimeoutOrNull(silenceLimit) { events.receive() }
                    ?: return Outcome.Retry(if (authenticated) FailureReason.NO_DATA else FailureReason.TIMEOUT, authenticated)

                when (event) {
                    SocketEvent.Open -> {
                        _state.value = ConnectionState.Authenticating
                        socket.send(WsProtocol.authenticate(target.token))
                    }

                    is SocketEvent.Message -> when (val message = WsProtocol.parse(event.text)) {
                        is ServerMessage.Authenticated -> {
                            if (message.protocolVersion > SUPPORTED_PROTOCOL_VERSION) {
                                return Outcome.Fatal(FailureReason.INCOMPATIBLE)
                            }
                            authenticated = true
                            silenceLimit = silenceLimitFor(message.intervalSeconds)
                            _state.value = ConnectionState.Connected
                            onAuthenticated(target)
                        }

                        is ServerMessage.Metrics -> if (authenticated) {
                            _snapshot.value = message.snapshot
                            _lastUpdateMillis.value = clock()
                        }

                        is ServerMessage.Error -> serverError = message.code
                        ServerMessage.Pong, is ServerMessage.Unknown -> Unit
                    }

                    is SocketEvent.Closed -> {
                        val verdict = WsProtocol.onClose(event.code)
                        return if (verdict.fatal) Outcome.Fatal(verdict.reason) else Outcome.Retry(verdict.reason, authenticated)
                    }

                    is SocketEvent.Failure -> {
                        // Si el servidor explicó el motivo antes de cortar, vale más que el corte.
                        if (serverError == "unauthorized") return Outcome.Fatal(FailureReason.INVALID_TOKEN)
                        val reason = classify(event.error, target)
                        return if (reason.isFatal) Outcome.Fatal(reason) else Outcome.Retry(reason, authenticated)
                    }
                }
            }
        } finally {
            // Se ejecuta también al cancelar la sesión: garantiza un solo socket abierto.
            socket.cancel()
            events.close()
        }
    }

    private fun classify(error: Throwable, target: SavedConnection): FailureReason =
        ErrorClassifier.classify(error, target, network())

    /** Fallas que reintentar no arregla: hace falta que el usuario cambie algo. */
    private val FailureReason.isFatal: Boolean
        get() = this == FailureReason.INVALID_TOKEN || this == FailureReason.INVALID_ADDRESS ||
            this == FailureReason.NOT_AN_AGENT || this == FailureReason.INCOMPATIBLE

    companion object {
        const val HEALTH_TIMEOUT_MILLIS = 6_000L
        const val HANDSHAKE_TIMEOUT_MILLIS = 8_000L
        private const val MIN_SILENCE_MILLIS = 10_000L

        /** El protocolo pide dar por muerta la conexión tras tres intervalos sin mensajes. */
        fun silenceLimitFor(intervalSeconds: Int): Long =
            maxOf(MIN_SILENCE_MILLIS, intervalSeconds.coerceAtLeast(1) * 3_000L)
    }
}

/** Resultado del botón "Probar conexión". */
sealed interface TestResult {
    data class Success(val agentId: String) : TestResult
    data class Failure(val reason: FailureReason) : TestResult
}

object ConnectionTester {
    /** Comprueba que el agente responde y que el token es válido, sin abrir el WebSocket. */
    suspend fun test(transport: AgentTransport, target: SavedConnection, network: NetworkStatus): TestResult {
        return try {
            val health = withTimeoutOrNull(ConnectionManager.HEALTH_TIMEOUT_MILLIS) { transport.health(target) }
                ?: return TestResult.Failure(ErrorClassifier.classify(java.net.SocketTimeoutException(), target, network))
            if (health.status != "ok") return TestResult.Failure(FailureReason.NOT_AN_AGENT)
            if (health.protocolVersion > SUPPORTED_PROTOCOL_VERSION) return TestResult.Failure(FailureReason.INCOMPATIBLE)
            // Cualquier ruta con token sirve; esta es la más liviana.
            withTimeoutOrNull(ConnectionManager.HEALTH_TIMEOUT_MILLIS) { transport.get(target, "/config/public") }
                ?: return TestResult.Failure(FailureReason.TIMEOUT)
            TestResult.Success(health.agentId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TestResult.Failure(ErrorClassifier.classify(e, target, network))
        }
    }
}
