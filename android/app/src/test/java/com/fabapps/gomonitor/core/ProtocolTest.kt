package com.fabapps.gomonitor.core

import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {
    // Muestra real capturada del agente (recortada).
    private val metrics = """
        {"type":"metrics","data":{"agent_id":"office-pc","hostname":"francisco-Latitude-3420","operating_system":"linux",
        "architecture":"amd64","timestamp":"2026-10-14T01:57:16Z","uptime_seconds":35482,
        "readings":[{"series_id":"cpu.total_percent","value":6.01},{"series_id":"sim.temperature","value":17.74}],
        "cpu":{"total_percent":6.01,"core_percentages":[5,7.5]},
        "memory":{"total_bytes":16501706752,"used_bytes":6511841280,"available_bytes":9989865472,"used_percent":39.46},
        "disks":[{"mount_point":"/","filesystem":"ext4","total_bytes":250375106560,"used_bytes":223443070976,"available_bytes":14139146240,"used_percent":94.05}],
        "network":{"bytes_sent_total":81096218,"bytes_received_total":663683916,"bytes_sent_per_second":6958,"bytes_received_per_second":797},
        "processes":[{"pid":62790,"name":"Isolated Web Co","cpu_percent":15,"memory_percent":3.91}],
        "campo_de_una_version_futura":{"x":1}}}
    """.trimIndent()

    @Test
    fun `interpreta una muestra completa`() {
        val message = WsProtocol.parse(metrics) as ServerMessage.Metrics
        val s = message.snapshot
        assertEquals("office-pc", s.agentId)
        assertEquals(35482L, s.uptimeSeconds)
        assertEquals(2, s.readings.size)
        assertEquals(17.74, s.readings[1].value, 0.0)
        assertEquals(listOf(5.0, 7.5), s.cpu?.corePercentages)
        assertEquals(16_501_706_752L, s.memory?.totalBytes)
        assertEquals("/", s.disks?.single()?.mountPoint)
        assertEquals(6958.0, s.network!!.bytesSentPerSecond, 0.0)
        assertEquals("Isolated Web Co", s.processes?.single()?.name)
    }

    @Test
    fun `una muestra de un agente solo de sensores no trae bloques de sistema`() {
        val text = """{"type":"metrics","data":{"agent_id":"pi","hostname":"pi","timestamp":"2026-10-14T01:57:16Z","uptime_seconds":5,
            "readings":[{"series_id":"bme280.temperature","value":21.5}]}}"""
        val s = (WsProtocol.parse(text) as ServerMessage.Metrics).snapshot
        assertNull(s.cpu)
        assertNull(s.memory)
        assertNull(s.disks)
        assertNull(s.network)
        assertNull(s.processes)
        assertEquals("bme280.temperature", s.readings.single().seriesId)
    }

    @Test
    fun `interpreta los demas mensajes`() {
        assertEquals(
            ServerMessage.Authenticated("office-pc", 1, 5),
            WsProtocol.parse("""{"type":"authenticated","agent_id":"office-pc","protocol_version":1,"collection_interval_seconds":5}"""),
        )
        assertEquals(ServerMessage.Pong, WsProtocol.parse("""{"type":"pong"}"""))
        assertEquals(
            ServerMessage.Error("unauthorized", "Invalid token"),
            WsProtocol.parse("""{"type":"error","error":{"code":"unauthorized","message":"Invalid token"}}"""),
        )
    }

    @Test
    fun `mensajes desconocidos o rotos no rompen la app`() {
        assertEquals(ServerMessage.Unknown("alert"), WsProtocol.parse("""{"type":"alert","level":"high"}"""))
        assertEquals(ServerMessage.Unknown(null), WsProtocol.parse("""{"sin_tipo":true}"""))
        assertEquals(ServerMessage.Unknown("metrics"), WsProtocol.parse("""{"type":"metrics"}"""))
        listOf("", "no es json", "[1,2,3]", "null", "{\"type\":5}", "{\"type\":\"metrics\",\"data\":\"texto\"}").forEach {
            assertTrue(it, WsProtocol.parse(it) is ServerMessage.Unknown)
        }
    }

    @Test
    fun `el mensaje de autenticacion escapa el token`() {
        assertEquals("""{"type":"authenticate","token":"abc"}""", WsProtocol.authenticate("abc"))
        assertEquals("""{"type":"authenticate","token":"a\"b\\c"}""", WsProtocol.authenticate("a\"b\\c"))
    }

    @Test
    fun `codigos de cierre`() {
        assertEquals(WsProtocol.CloseVerdict(FailureReason.INVALID_TOKEN, fatal = true), WsProtocol.onClose(4003))
        assertEquals(WsProtocol.CloseVerdict(FailureReason.AGENT_STOPPED, fatal = false), WsProtocol.onClose(1001))
        assertEquals(WsProtocol.CloseVerdict(FailureReason.TIMEOUT, fatal = false), WsProtocol.onClose(4001))
        assertFalse(WsProtocol.onClose(4008).fatal)
        assertFalse(WsProtocol.onClose(1006).fatal)
        // Errores de esta app: reintentar en bucle no los arregla.
        assertTrue(WsProtocol.onClose(1009).fatal)
        assertTrue(WsProtocol.onClose(4400).fatal)
    }

    @Test
    fun `espera entre reintentos`() {
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L, 60_000L), (1..7).map { Backoff.delayMillis(it, 2) })
        assertEquals(listOf(1_000L, 2_000L, 4_000L), (1..3).map { Backoff.delayMillis(it, 1) })
        assertEquals(60_000L, Backoff.delayMillis(1, 60))
        // Valores absurdos no desbordan ni dan esperas negativas.
        assertEquals(60_000L, Backoff.delayMillis(10_000, 2))
        assertEquals(1_000L, Backoff.delayMillis(0, 0))
        assertEquals(1_000L, Backoff.delayMillis(-5, -3))
    }

    @Test
    fun `pertenencia a una subred`() {
        assertTrue(Subnets.contains("192.168.1.23", 24, "192.168.1.50"))
        assertFalse(Subnets.contains("192.168.1.23", 24, "192.168.2.50"))
        assertTrue(Subnets.contains("10.79.150.77", 16, "10.79.3.4"))
        assertTrue(Subnets.contains("172.20.10.2", 28, "172.20.10.1")) // hotspot típico
        assertFalse(Subnets.contains("172.20.10.2", 28, "172.20.10.17"))
        assertTrue(Subnets.contains("200.1.2.3", 8, "200.9.9.9")) // primer bit en 1: no debe fallar por el signo
        assertTrue(Subnets.contains("1.2.3.4", 0, "9.9.9.9"))
        assertTrue(Subnets.contains("1.2.3.4", 32, "1.2.3.4"))
        assertFalse(Subnets.contains("1.2.3.4", 32, "1.2.3.5"))
        assertFalse(Subnets.contains("1.2.3.4", 24, "mi-pc.local"))
        assertFalse(Subnets.contains("1.2.3.4", 40, "1.2.3.4"))
    }

    @Test
    fun `distingue los motivos de una falla`() {
        val target = connection(host = "192.168.1.50")
        val sameNetwork = NetworkStatus(onWifi = true, subnets = listOf("192.168.1.23" to 24))
        val otherNetwork = NetworkStatus(onWifi = true, subnets = listOf("10.0.0.5" to 24))
        val mobileData = NetworkStatus(onWifi = false)
        fun classify(e: Throwable, n: NetworkStatus = sameNetwork, t: SavedConnection = target) = ErrorClassifier.classify(e, t, n)

        // Token inválido.
        assertEquals(FailureReason.INVALID_TOKEN, classify(AgentHttpException(401, "unauthorized")))
        // Dirección incorrecta: hay algo en esa IP, pero no es el agente o no es ese puerto.
        assertEquals(FailureReason.CONNECTION_REFUSED, classify(ConnectException("Failed to connect to /192.168.1.50:8080: Connection refused")))
        assertEquals(FailureReason.NOT_AN_AGENT, classify(AgentHttpException(404, null)))
        assertEquals(FailureReason.INVALID_ADDRESS, classify(IllegalArgumentException("unexpected host")))
        assertEquals(FailureReason.HOST_NOT_FOUND, classify(UnknownHostException("mi-pc.local")))
        // Computadora inaccesible, estando en la misma red.
        assertEquals(FailureReason.HOST_NOT_FOUND, classify(NoRouteToHostException("No route to host")))
        assertEquals(FailureReason.HOST_NOT_FOUND, classify(ConnectException("Host unreachable")))
        // Timeout.
        assertEquals(FailureReason.TIMEOUT, classify(SocketTimeoutException("connect timed out")))
        // Celular conectado a otra red: por subred distinta o por estar con datos móviles.
        assertEquals(FailureReason.WRONG_NETWORK, classify(SocketTimeoutException(), otherNetwork))
        assertEquals(FailureReason.WRONG_NETWORK, classify(NoRouteToHostException(), otherNetwork))
        assertEquals(FailureReason.WRONG_NETWORK, classify(SocketTimeoutException(), mobileData))
        // Un rechazo prueba que la computadora es alcanzable: no se culpa a la red.
        assertEquals(FailureReason.CONNECTION_REFUSED, classify(ConnectException("Connection refused"), otherNetwork))
        // Con nombre de equipo no se puede comparar subredes; sin datos de red tampoco.
        assertEquals(FailureReason.TIMEOUT, classify(SocketTimeoutException(), otherNetwork, connection(host = "mi-pc.local")))
        assertEquals(FailureReason.TIMEOUT, classify(SocketTimeoutException(), NetworkStatus.UNKNOWN))
        // Forma real de las fallas de OkHttp: el motivo viene en la causa, no en el mensaje.
        val wrappedRefusal = ConnectException("Failed to connect to /192.168.1.50:8080").apply {
            initCause(ConnectException("failed to connect to /192.168.1.50 (port 8080) after 4000ms: isConnected failed: ECONNREFUSED (Connection refused)"))
        }
        assertEquals(FailureReason.CONNECTION_REFUSED, classify(wrappedRefusal))
        assertEquals(FailureReason.CONNECTION_REFUSED, classify(wrappedRefusal, otherNetwork))
        val wrappedTimeout = IOException("unexpected end of stream").apply { initCause(SocketTimeoutException("timeout")) }
        assertEquals(FailureReason.TIMEOUT, classify(wrappedTimeout))
        val wrappedNoRoute = ConnectException("Failed to connect to /192.168.1.50:8080").apply {
            initCause(ConnectException("failed to connect: EHOSTUNREACH (No route to host)"))
        }
        assertEquals(FailureReason.HOST_NOT_FOUND, classify(wrappedNoRoute))
        assertEquals(FailureReason.WRONG_NETWORK, classify(wrappedNoRoute, otherNetwork))
        // WebSocket cerrado y agente lleno.
        assertEquals(FailureReason.SOCKET_CLOSED, classify(IOException("Socket closed")))
        assertEquals(FailureReason.SERVER_BUSY, classify(AgentHttpException(503, "too_many_connections")))
        assertEquals(FailureReason.UNKNOWN, classify(IllegalStateException("otra cosa")))
    }

    @Test
    fun `textos de estado`() {
        assertEquals("Sin configurar", ConnectionState.NotConfigured.label)
        assertEquals("Conectando", ConnectionState.Connecting.label)
        assertEquals("Autenticando", ConnectionState.Authenticating.label)
        assertEquals("Conectado", ConnectionState.Connected.label)
        assertEquals("Reconectando", ConnectionState.Reconnecting(1, 2000, FailureReason.TIMEOUT).label)
        assertEquals("Sin conexión", ConnectionState.Disconnected.label)
        assertEquals("Sin conexión", ConnectionState.Failed(FailureReason.SERVER_BUSY).label)
        assertEquals("Token incorrecto", ConnectionState.Failed(FailureReason.INVALID_TOKEN).label)
        assertEquals("Equipo no encontrado", ConnectionState.Failed(FailureReason.HOST_NOT_FOUND).label)
        assertTrue(ConnectionState.Reconnecting(1, 2000, FailureReason.TIMEOUT).isActive)
        assertFalse(ConnectionState.Failed(FailureReason.TIMEOUT).isActive)
        assertEquals(FailureReason.TIMEOUT.message, ConnectionState.Failed(FailureReason.TIMEOUT).detail)
        assertNull(ConnectionState.Connected.detail)
    }
}
