package com.fabapps.gomonitor.data

import com.fabapps.gomonitor.core.ConnectionManager
import com.fabapps.gomonitor.core.ConnectionState
import com.fabapps.gomonitor.core.ConnectionTester
import com.fabapps.gomonitor.core.FailureReason
import com.fabapps.gomonitor.core.HistoryResponse
import com.fabapps.gomonitor.core.HistoryTransform
import com.fabapps.gomonitor.core.NetworkStatus
import com.fabapps.gomonitor.core.ProtocolJson
import com.fabapps.gomonitor.core.SavedConnection
import com.fabapps.gomonitor.core.SeriesList
import com.fabapps.gomonitor.core.TestResult
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Prueba de punta a punta de la capa de red de la app contra un agente real: usa el mismo
 * OkHttpTransport y el mismo ConnectionManager que el teléfono, sobre sockets de verdad.
 *
 * Necesita el binario del agente. Se ejecuta con `make check-e2e` desde la raíz del proyecto,
 * que lo compila y pasa su ruta; sin esa ruta la prueba se omite.
 */
class AgentEndToEndTest {
    private val agentBinary: String? = System.getProperty("gomonitor.agent.bin")
    private val token = "e2e-token"
    private val transport = OkHttpTransport()
    // Un solo hilo, como el hilo principal de Android donde corre el ConnectionManager.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    private val workDir: File = Files.createTempDirectory("gomonitor-e2e").toFile()
    private var agent: Process? = null
    private var port = 0

    @Before
    fun setUp() {
        assumeTrue("sin -PgomonitorAgentBin no hay agente contra el cual probar", agentBinary != null)
        port = ServerSocket(0).use { it.localPort }
    }

    @After
    fun tearDown() {
        scope.cancel()
        stopAgent()
        workDir.deleteRecursively()
    }

    private fun target(token: String = this.token, port: Int = this.port) = SavedConnection(
        id = "e2e", name = "E2E", host = "127.0.0.1", port = port, token = token, reconnectSeconds = 1,
    )

    private fun startAgent() {
        agent = ProcessBuilder(
            agentBinary, "-port", port.toString(), "-listen-address", "127.0.0.1", "-api-token", token,
            "-database-path", File(workDir, "e2e.db").path, "-collection-interval", "1s",
            "-collectors", "system,simulated", "-log-level", "warn",
        ).directory(workDir).redirectErrorStream(true).redirectOutput(File(workDir, "agent.log")).start()
        val ready = runBlocking {
            await(10_000) { runCatching { transport.health(target()) }.isSuccess }
        }
        assertTrue("el agente no respondió a /health:\n" + File(workDir, "agent.log").readText(), ready)
    }

    private fun stopAgent() {
        agent?.let {
            it.destroy() // SIGTERM: el agente avisa a sus clientes antes de salir
            if (!it.waitFor(10, TimeUnit.SECONDS)) it.destroyForcibly()
        }
        agent = null
    }

    private suspend fun await(timeoutMillis: Long, condition: suspend () -> Boolean): Boolean =
        withTimeoutOrNull(timeoutMillis) {
            while (!condition()) delay(50)
            true
        } ?: false

    private fun awaitState(manager: ConnectionManager, timeoutMillis: Long, description: String, predicate: (ConnectionState) -> Boolean) {
        val reached = runBlocking { await(timeoutMillis) { predicate(manager.state.value) } }
        assertTrue("se esperaba $description, quedó en ${manager.state.value}", reached)
    }

    @Test
    fun `la app se conecta recibe datos y se reconecta sola`() {
        startAgent()
        val authenticated = mutableListOf<String>()
        val manager = ConnectionManager(transport, scope, onAuthenticated = { authenticated += it.id })

        // Conexión, autenticación y métricas en tiempo real.
        manager.connect(target())
        awaitState(manager, 10_000, "Conectado") { it == ConnectionState.Connected }
        assertEquals(listOf("e2e"), authenticated)
        assertTrue("no llegó ninguna muestra", runBlocking { await(10_000) { manager.snapshot.value != null } })

        val first = manager.snapshot.value!!
        assertEquals("office-pc", first.agentId)
        assertTrue(first.hostname.isNotBlank())
        assertNotNull(first.cpu)
        assertTrue(first.cpu!!.corePercentages.isNotEmpty())
        assertTrue(first.memory!!.totalBytes > 0)
        assertNotNull(first.disks)
        assertNotNull(first.network)
        assertTrue(first.processes!!.isNotEmpty())
        assertTrue("faltan las lecturas del sensor simulado", first.readings.any { it.seriesId == "sim.temperature" })

        // Llegan muestras nuevas, una por ciclo.
        val firstUpdate = manager.lastUpdateMillis.value!!
        assertTrue("no llegó una segunda muestra", runBlocking { await(5_000) { manager.lastUpdateMillis.value!! > firstUpdate } })

        // Catálogo e historial por REST, con el mismo código que usa la pantalla de historial.
        val catalog = runBlocking { ProtocolJson.decodeFromString(SeriesList.serializer(), transport.get(target(), "/series")) }
        assertTrue(catalog.series.any { it.id == "sim.temperature" && it.unit == "celsius" })
        val charts = runBlocking {
            val body = transport.get(target(), "/history?period=15m&series=cpu.total_percent,sim.temperature")
            HistoryTransform.toChart(ProtocolJson.decodeFromString(HistoryResponse.serializer(), body))
        }
        assertEquals(listOf("cpu.total_percent", "sim.temperature"), charts.map { it.id })
        assertTrue("el historial no tiene puntos", charts.all { !it.isEmpty })

        // "Probar conexión", con el token correcto y con uno incorrecto.
        runBlocking {
            assertEquals(TestResult.Success("office-pc"), ConnectionTester.test(transport, target(), NetworkStatus.UNKNOWN))
            assertEquals(
                TestResult.Failure(FailureReason.INVALID_TOKEN),
                ConnectionTester.test(transport, target(token = "otro"), NetworkStatus.UNKNOWN),
            )
        }

        // El agente se detiene: la app lo detecta y pasa a reintentar.
        stopAgent()
        awaitState(manager, 10_000, "Reconectando") { it is ConnectionState.Reconnecting }
        assertNotNull("la última muestra debe seguir disponible", manager.snapshot.value)

        // El agente vuelve: la app se reconecta sin intervención.
        val beforeRestart = manager.lastUpdateMillis.value!!
        startAgent()
        awaitState(manager, 30_000, "Conectado tras reiniciar el agente") { it == ConnectionState.Connected }
        assertTrue("no llegaron datos tras reconectar", runBlocking { await(10_000) { manager.lastUpdateMillis.value!! > beforeRestart } })
        assertEquals(listOf("e2e", "e2e"), authenticated)

        // Desconexión manual: no vuelve a conectarse sola.
        manager.disconnect()
        assertEquals(ConnectionState.Disconnected, manager.state.value)
        Thread.sleep(2_500)
        assertEquals(ConnectionState.Disconnected, manager.state.value)
    }

    @Test
    fun `token incorrecto queda como falla definitiva`() {
        startAgent()
        val manager = ConnectionManager(transport, scope)
        manager.connect(target(token = "token-equivocado"))
        awaitState(manager, 10_000, "Token incorrecto") { it is ConnectionState.Failed }
        assertEquals(ConnectionState.Failed(FailureReason.INVALID_TOKEN), manager.state.value)
        assertEquals("Token incorrecto", manager.state.value.label)
        assertEquals(null, manager.snapshot.value)
    }

    @Test
    fun `sin agente en el puerto informa conexion rechazada y reintenta`() {
        // No se inicia el agente: el puerto está libre y nadie escucha.
        val manager = ConnectionManager(transport, scope)
        manager.connect(target())
        awaitState(manager, 10_000, "Reconectando") { it is ConnectionState.Reconnecting }
        assertEquals(FailureReason.CONNECTION_REFUSED, (manager.state.value as ConnectionState.Reconnecting).reason)
    }
}
