package com.fabapps.gomonitor.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryTest {
    // Registros TXT tal como los anuncia el agente (vistos con avahi-browse).
    private val txt = mapOf(
        "agent_id" to "office-pc".toByteArray(),
        "name" to "francisco-Latitude-3420".toByteArray(),
        "port" to "8080".toByteArray(),
        "version" to "1".toByteArray(),
    )

    @Test
    fun `arma el agente con lo que devuelve la resolucion`() {
        val agent = Discovery.fromResolved("office-pc", listOf("10.79.150.77"), 8080, txt)!!
        assertEquals("10.79.150.77", agent.host)
        assertEquals(8080, agent.port)
        assertEquals("office-pc", agent.agentId)
        assertEquals("francisco-Latitude-3420", agent.hostname)
        assertEquals(1, agent.protocolVersion)
        assertEquals("francisco-Latitude-3420", agent.displayName)
        assertEquals("10.79.150.77:8080", agent.address)
    }

    @Test
    fun `elige la direccion IPv4 entre varias`() {
        // Android puede devolver primero una IPv6 de enlace local, o la IP con una barra delante.
        val agent = Discovery.fromResolved("pc", listOf("fe80::5b4:be59:adb7:1e57%wlan0", "/192.168.1.50"), 8080, txt)!!
        assertEquals("192.168.1.50", agent.host)
        assertNull(Discovery.fromResolved("pc", listOf("fe80::1"), 8080, txt))
        assertNull(Discovery.fromResolved("pc", emptyList(), 8080, txt))
    }

    @Test
    fun `descarta anuncios invalidos`() {
        assertNull(Discovery.fromResolved("pc", listOf("192.168.1.50"), 0, txt))
        assertNull(Discovery.fromResolved("pc", listOf("192.168.1.50"), 70000, txt))
        assertNull(Discovery.fromResolved(" ", listOf("192.168.1.50"), 8080, txt))
    }

    @Test
    fun `tolera registros TXT ausentes o maliciosos`() {
        val bare = Discovery.fromResolved("mi-agente", listOf("192.168.1.50"), 8080, emptyMap())!!
        assertEquals("", bare.agentId)
        assertNull(bare.protocolVersion)
        // Sin nombre de equipo ni id, se muestra el nombre del servicio.
        assertEquals("mi-agente", bare.displayName)

        val onlyId = Discovery.fromResolved("svc", listOf("192.168.1.50"), 8080, mapOf("agent_id" to "pi".toByteArray(), "name" to null))!!
        assertEquals("pi", onlyId.displayName)

        val hostile = Discovery.fromResolved("svc", listOf("192.168.1.50"), 8080, mapOf(
            "name" to ("A".repeat(500) + "\n\u0007").toByteArray(),
            "version" to "no-es-numero".toByteArray(),
            "agent_id" to byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 'x'.code.toByte()),
        ))!!
        assertEquals(64, hostile.hostname.length)
        assertTrue(hostile.hostname.none(Char::isISOControl))
        assertNull(hostile.protocolVersion)
    }

    @Test
    fun `mantiene la lista de encontrados`() {
        fun agent(service: String, host: String, name: String) = DiscoveredAgent(service, host, 8080, hostname = name)
        var list = emptyList<DiscoveredAgent>()
        list = Discovery.upsert(list, agent("s2", "192.168.1.60", "zeta"))
        list = Discovery.upsert(list, agent("s1", "192.168.1.50", "Alfa"))
        assertEquals("ordenados por nombre, sin distinguir mayúsculas", listOf("Alfa", "zeta"), list.map { it.displayName })

        // El mismo servicio visto de nuevo (cambió de IP) se actualiza, no se duplica.
        list = Discovery.upsert(list, agent("s1", "192.168.1.99", "Alfa"))
        assertEquals(2, list.size)
        assertEquals("192.168.1.99", list.first().host)

        list = Discovery.remove(list, "s2")
        assertEquals(listOf("s1"), list.map { it.serviceName })
        assertEquals(list, Discovery.remove(list, "no-existe"))
    }

    @Test
    fun `no ofrece agentes que ya estan guardados`() {
        val discovered = listOf(
            DiscoveredAgent("a", "192.168.1.50", 8080, hostname = "guardada"),
            DiscoveredAgent("b", "192.168.1.50", 9090, hostname = "otro puerto"),
            DiscoveredAgent("c", "192.168.1.60", 8080, hostname = "nueva"),
            DiscoveredAgent("c-bis", "192.168.1.60", 8080, hostname = "nueva"), // misma por otra interfaz
        )
        val saved = listOf(connection(host = "192.168.1.50"))
        assertEquals(listOf("b", "c"), Discovery.unsaved(discovered, saved).map { it.serviceName })
        assertEquals(3, Discovery.unsaved(discovered, emptyList()).size)

        // Para no mostrar "Buscando…" cuando lo único que hay en la red ya está guardado.
        assertEquals(1, Discovery.savedCount(discovered, saved))
        assertEquals(0, Discovery.savedCount(discovered, emptyList()))
        assertEquals(0, Discovery.savedCount(emptyList(), saved))
        val all = listOf(connection(id = "a", host = "192.168.1.50"), connection(id = "b", host = "192.168.1.60"))
        assertEquals(2, Discovery.savedCount(discovered, all))
    }

    @Test
    fun `precarga el formulario sin token`() {
        val form = Discovery.toForm(DiscoveredAgent("s", "192.168.1.50", 8080, agentId = "office-pc", hostname = "francisco-pc"))
        assertEquals("francisco-pc", form.name)
        assertEquals("192.168.1.50", form.host)
        assertEquals("8080", form.port)
        assertEquals("", form.token)
        // Lo precargado pasa la validación salvo por el token, que carga el usuario.
        val errors = Validation.validate(form)
        assertNull(errors.name)
        assertNull(errors.host)
        assertNull(errors.port)
        assertTrue(errors.token != null)
        assertTrue(Validation.validate(form.copy(token = "secreto")).isValid)

        val long = Discovery.toForm(DiscoveredAgent("s", "192.168.1.50", 8080, hostname = "x".repeat(60)))
        assertNull("un nombre largo se recorta al máximo permitido", Validation.nameError(long.name))
    }
}
