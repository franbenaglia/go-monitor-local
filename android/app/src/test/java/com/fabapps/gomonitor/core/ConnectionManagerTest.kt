package com.fabapps.gomonitor.core

import java.net.ConnectException
import java.net.SocketException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionManagerTest {
    private val transport = FakeTransport()
    private val authenticatedWith = mutableListOf<String>()
    private var network = NetworkStatus.UNKNOWN

    private fun TestScope.manager() = ConnectionManager(
        transport = transport,
        // backgroundScope se cancela al terminar la prueba, igual que el alcance del ViewModel.
        scope = backgroundScope,
        clock = { testScheduler.currentTime },
        network = { network },
        onAuthenticated = { authenticatedWith += it.id },
    )

    /** Lleva una conexión hasta "Conectado". */
    private fun TestScope.connected(manager: ConnectionManager, target: SavedConnection = connection()): FakeSocket {
        manager.connect(target)
        runCurrent()
        val socket = transport.socket
        socket.open()
        socket.authenticated()
        runCurrent()
        assertEquals(ConnectionState.Connected, manager.state.value)
        return socket
    }

    private fun reconnecting(state: ConnectionState): ConnectionState.Reconnecting {
        assertTrue("se esperaba Reconectando, hay $state", state is ConnectionState.Reconnecting)
        return state as ConnectionState.Reconnecting
    }

    @Test
    fun `recorre los estados hasta recibir metricas`() = runTest {
        val manager = manager()
        assertEquals(ConnectionState.NotConfigured, manager.state.value)

        manager.connect(connection(token = "mi-token"))
        assertEquals(ConnectionState.Connecting, manager.state.value)
        runCurrent()
        assertEquals("primero se prueba /health", 1, transport.healthCalls)
        assertEquals(1, transport.sockets.size)

        transport.socket.open()
        runCurrent()
        assertEquals(ConnectionState.Authenticating, manager.state.value)
        assertEquals(listOf("""{"type":"authenticate","token":"mi-token"}"""), transport.socket.sent)

        transport.socket.authenticated()
        runCurrent()
        assertEquals(ConnectionState.Connected, manager.state.value)
        assertEquals(listOf("a"), authenticatedWith)
        assertNull(manager.snapshot.value)

        advanceTimeBy(1_000)
        transport.socket.metrics(uptime = 42)
        runCurrent()
        assertEquals(42L, manager.snapshot.value?.uptimeSeconds)
        assertEquals(1_000L, manager.lastUpdateMillis.value)
    }

    @Test
    fun `ignora metricas que llegan antes de autenticar`() = runTest {
        val manager = manager()
        manager.connect(connection())
        runCurrent()
        transport.socket.open()
        transport.socket.metrics(uptime = 1)
        runCurrent()
        assertNull(manager.snapshot.value)
    }

    @Test
    fun `token incorrecto no se reintenta`() = runTest {
        val manager = manager()
        manager.connect(connection())
        runCurrent()
        transport.socket.open()
        transport.socket.message("""{"type":"error","error":{"code":"unauthorized","message":"Invalid token"}}""")
        transport.socket.listener.onClosed(WsProtocol.CLOSE_INVALID_TOKEN, "Invalid token")
        runCurrent()

        val state = manager.state.value
        assertEquals(ConnectionState.Failed(FailureReason.INVALID_TOKEN), state)
        assertEquals("Token incorrecto", state.label)
        assertTrue(transport.socket.cancelled)

        advanceTimeBy(10 * 60_000)
        assertEquals("no debe reintentar con un token incorrecto", 1, transport.sockets.size)
        assertEquals(1, transport.healthCalls)
    }

    @Test
    fun `token incorrecto se reconoce aunque el cierre llegue como falla`() = runTest {
        val manager = manager()
        manager.connect(connection())
        runCurrent()
        transport.socket.open()
        transport.socket.message("""{"type":"error","error":{"code":"unauthorized","message":"Invalid token"}}""")
        transport.socket.listener.onFailure(SocketException("Connection reset"))
        runCurrent()
        assertEquals(ConnectionState.Failed(FailureReason.INVALID_TOKEN), manager.state.value)
    }

    @Test
    fun `reintenta con espera creciente hasta un maximo`() = runTest {
        transport.health = { throw ConnectException("Connection refused") }
        val manager = manager()
        manager.connect(connection(reconnectSeconds = 2))
        runCurrent()

        val expected = listOf(2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L, 60_000L)
        expected.forEachIndexed { index, wait ->
            val state = reconnecting(manager.state.value)
            assertEquals(index + 1, state.attempt)
            assertEquals(wait, state.delayMillis)
            assertEquals(FailureReason.CONNECTION_REFUSED, state.reason)
            assertEquals(index + 1, transport.healthCalls)

            // Un milisegundo antes de cumplirse la espera todavía no reintentó.
            advanceTimeBy(wait - 1)
            runCurrent()
            assertEquals(index + 1, transport.healthCalls)
            advanceTimeBy(1)
            runCurrent()
        }
        assertTrue("nunca abre el socket si /health falla", transport.sockets.isEmpty())
    }

    @Test
    fun `se reconecta sola y reinicia la espera tras una conexion exitosa`() = runTest {
        val manager = manager()
        val first = connected(manager)
        first.metrics(uptime = 1)
        runCurrent()

        first.listener.onFailure(SocketException("Software caused connection abort"))
        runCurrent()
        val state = reconnecting(manager.state.value)
        assertEquals(1, state.attempt)
        assertEquals(2_000L, state.delayMillis)
        assertTrue(first.cancelled)
        assertEquals("la última muestra sigue visible mientras reconecta", 1L, manager.snapshot.value?.uptimeSeconds)

        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(2, transport.sockets.size)
        transport.socket.open()
        transport.socket.authenticated()
        runCurrent()
        assertEquals(ConnectionState.Connected, manager.state.value)
        assertEquals(listOf("a", "a"), authenticatedWith)

        // Otra caída: como llegó a conectar, la espera vuelve a empezar desde la base.
        transport.socket.listener.onFailure(SocketException("reset"))
        runCurrent()
        assertEquals(1, reconnecting(manager.state.value).attempt)
    }

    @Test
    fun `nunca hay mas de un socket abierto`() = runTest {
        val manager = manager()
        connected(manager)
        repeat(5) {
            transport.socket.listener.onFailure(SocketException("reset"))
            runCurrent()
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(1, transport.openSockets.size)
            transport.socket.open()
            transport.socket.authenticated()
            runCurrent()
        }
        // Reintento manual y cambio de equipo con una conexión viva.
        manager.retryNow()
        runCurrent()
        assertEquals(1, transport.openSockets.size)
        manager.connect(connection(id = "b"))
        runCurrent()
        assertEquals(1, transport.openSockets.size)
    }

    @Test
    fun `desconectar corta el socket y cancela los reintentos`() = runTest {
        val manager = manager()
        val socket = connected(manager)
        manager.disconnect()
        runCurrent()
        assertTrue(socket.cancelled)
        assertEquals(ConnectionState.Disconnected, manager.state.value)

        // Un evento tardío del socket viejo no revive la conexión.
        socket.listener.onFailure(SocketException("late"))
        advanceTimeBy(10 * 60_000)
        runCurrent()
        assertEquals(ConnectionState.Disconnected, manager.state.value)
        assertEquals(1, transport.sockets.size)
        assertEquals(1, transport.healthCalls)
    }

    @Test
    fun `desconectar durante la espera cancela el reintento pendiente`() = runTest {
        transport.health = { throw ConnectException("Connection refused") }
        val manager = manager()
        manager.connect(connection())
        runCurrent()
        reconnecting(manager.state.value)

        manager.disconnect()
        advanceTimeBy(10 * 60_000)
        runCurrent()
        assertEquals(ConnectionState.Disconnected, manager.state.value)
        assertEquals(1, transport.healthCalls)
    }

    @Test
    fun `cambiar de equipo cierra el anterior y borra sus datos`() = runTest {
        val manager = manager()
        val first = connected(manager, connection(id = "a"))
        first.metrics(uptime = 7)
        runCurrent()

        manager.connect(connection(id = "b", host = "192.168.1.60"))
        runCurrent()
        assertTrue(first.cancelled)
        assertNull("no mostrar datos de un equipo bajo el nombre de otro", manager.snapshot.value)
        assertNull(manager.lastUpdateMillis.value)
        assertEquals("b", manager.target.value?.id)

        // Reconectar con el mismo equipo conserva la última muestra.
        transport.socket.open()
        transport.socket.authenticated()
        transport.socket.metrics(uptime = 9)
        runCurrent()
        manager.retryNow()
        runCurrent()
        assertEquals(9L, manager.snapshot.value?.uptimeSeconds)
    }

    @Test
    fun `detecta que el agente dejo de enviar datos`() = runTest {
        val manager = manager()
        manager.connect(connection())
        runCurrent()
        val socket = transport.socket
        socket.open()
        socket.authenticated(interval = 5)
        runCurrent()

        // Cada mensaje reinicia la vigilancia.
        repeat(3) {
            advanceTimeBy(9_000)
            socket.metrics(uptime = it.toLong())
            runCurrent()
            assertEquals(ConnectionState.Connected, manager.state.value)
        }

        // Tres intervalos de silencio: la red desapareció sin avisar.
        advanceTimeBy(15_000)
        runCurrent()
        assertEquals(FailureReason.NO_DATA, reconnecting(manager.state.value).reason)
        assertTrue(socket.cancelled)
    }

    @Test
    fun `la vigilancia respeta intervalos largos`() = runTest {
        val manager = manager()
        manager.connect(connection())
        runCurrent()
        transport.socket.open()
        transport.socket.authenticated(interval = 60)
        runCurrent()
        advanceTimeBy(170_000)
        runCurrent()
        assertEquals(ConnectionState.Connected, manager.state.value)
        advanceTimeBy(11_000)
        runCurrent()
        assertEquals(FailureReason.NO_DATA, reconnecting(manager.state.value).reason)
    }

    @Test
    fun `reintenta si el socket no llega a autenticarse`() = runTest {
        val manager = manager()
        manager.connect(connection())
        runCurrent()
        transport.socket.open() // el servidor nunca responde
        advanceTimeBy(ConnectionManager.HANDSHAKE_TIMEOUT_MILLIS)
        runCurrent()
        assertEquals(FailureReason.TIMEOUT, reconnecting(manager.state.value).reason)
        assertTrue(transport.socket.cancelled)
    }

    @Test
    fun `reintenta si health no responde`() = runTest {
        transport.health = { awaitCancellation() }
        val manager = manager()
        manager.connect(connection())
        advanceTimeBy(ConnectionManager.HEALTH_TIMEOUT_MILLIS)
        runCurrent()
        assertEquals(FailureReason.TIMEOUT, reconnecting(manager.state.value).reason)
    }

    @Test
    fun `cuando el agente se detiene espera a que vuelva`() = runTest {
        val manager = manager()
        val socket = connected(manager)
        socket.listener.onClosed(WsProtocol.CLOSE_GOING_AWAY, "agent shutting down")
        runCurrent()
        assertEquals(FailureReason.AGENT_STOPPED, reconnecting(manager.state.value).reason)
    }

    @Test
    fun `agente lleno se reintenta`() = runTest {
        val manager = manager()
        manager.connect(connection())
        runCurrent()
        transport.socket.listener.onFailure(AgentHttpException(503, "too_many_connections"))
        runCurrent()
        assertEquals(FailureReason.SERVER_BUSY, reconnecting(manager.state.value).reason)
    }

    @Test
    fun `fallas que el usuario debe corregir no se reintentan`() = runTest {
        val cases: List<Pair<suspend () -> Health, FailureReason>> = listOf(
            suspend { throw AgentHttpException(404, null) } to FailureReason.NOT_AN_AGENT,
            suspend { Health(status = "weird") } to FailureReason.NOT_AN_AGENT,
            suspend { Health(status = "ok", protocolVersion = 2) } to FailureReason.INCOMPATIBLE,
            suspend { throw IllegalArgumentException("bad url") } to FailureReason.INVALID_ADDRESS,
        )
        for ((health, reason) in cases) {
            transport.health = health
            val manager = manager()
            manager.connect(connection())
            runCurrent()
            assertEquals(ConnectionState.Failed(reason), manager.state.value)
        }
        assertTrue(transport.sockets.isEmpty())
    }

    @Test
    fun `rechaza un agente con protocolo mas nuevo al autenticar`() = runTest {
        val manager = manager()
        manager.connect(connection())
        runCurrent()
        transport.socket.open()
        transport.socket.authenticated(version = 2)
        runCurrent()
        assertEquals(ConnectionState.Failed(FailureReason.INCOMPATIBLE), manager.state.value)
        assertTrue(authenticatedWith.isEmpty())
    }

    @Test
    fun `reintento manual funciona tras una falla definitiva`() = runTest {
        val manager = manager()
        manager.connect(connection())
        runCurrent()
        transport.socket.listener.onClosed(WsProtocol.CLOSE_INVALID_TOKEN, "")
        runCurrent()
        assertTrue(manager.state.value is ConnectionState.Failed)

        manager.retryNow()
        runCurrent()
        assertEquals(2, transport.sockets.size)
        transport.socket.open()
        transport.socket.authenticated()
        runCurrent()
        assertEquals(ConnectionState.Connected, manager.state.value)
    }

    @Test
    fun `explica cuando el celular esta en otra red`() = runTest {
        transport.health = { throw java.net.SocketTimeoutException("connect timed out") }
        network = NetworkStatus(onWifi = true, subnets = listOf("10.0.0.5" to 24))
        val manager = manager()
        manager.connect(connection(host = "192.168.1.50"))
        runCurrent()
        assertEquals(FailureReason.WRONG_NETWORK, reconnecting(manager.state.value).reason)
    }

    @Test
    fun `olvidar el equipo vuelve a sin configurar`() = runTest {
        val manager = manager()
        val socket = connected(manager)
        socket.metrics(uptime = 1)
        runCurrent()
        manager.clear()
        runCurrent()
        assertEquals(ConnectionState.NotConfigured, manager.state.value)
        assertNull(manager.snapshot.value)
        assertNull(manager.target.value)
        assertTrue(socket.cancelled)
        manager.retryNow() // sin equipo no hace nada
        runCurrent()
        assertEquals(1, transport.sockets.size)
        assertFalse(manager.state.value.isActive)
    }

    @Test
    fun `probar conexion verifica agente y token`() = runTest {
        val target = connection()
        assertEquals(TestResult.Success("office-pc"), ConnectionTester.test(transport, target, NetworkStatus.UNKNOWN))
        assertEquals(listOf("/config/public"), transport.paths)

        transport.get = { throw AgentHttpException(401, "unauthorized") }
        assertEquals(TestResult.Failure(FailureReason.INVALID_TOKEN), ConnectionTester.test(transport, target, NetworkStatus.UNKNOWN))

        transport.health = { throw ConnectException("Connection refused") }
        assertEquals(TestResult.Failure(FailureReason.CONNECTION_REFUSED), ConnectionTester.test(transport, target, NetworkStatus.UNKNOWN))

        transport.health = { awaitCancellation() }
        assertEquals(TestResult.Failure(FailureReason.TIMEOUT), ConnectionTester.test(transport, target, NetworkStatus.UNKNOWN))
    }
}
