package com.fabapps.gomonitor.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionsTest {
    private val validForm = ConnectionForm(name = "Computadora principal", host = "192.168.1.50", port = "8080", token = "local-development-token")

    @Test
    fun `direcciones IPv4 validas e invalidas`() {
        listOf("192.168.1.50", "10.0.0.1", "0.0.0.0", "255.255.255.255", "172.16.254.1").forEach {
            assertTrue(it, Validation.isValidIpv4(it))
            assertNull(it, Validation.hostError(it))
        }
        listOf("256.1.1.1", "192.168.1", "192.168.1.1.1", "192.168.1.", ".192.168.1.1", "192.168..1",
            "192.168.1.a", "192.168.01.1", "1921.68.1.1", "-1.2.3.4", "1.2.3.4 5", "", "٣.٣.٣.٣").forEach {
            assertFalse(it, Validation.isValidIpv4(it))
            assertNotNull(it, Validation.hostError(it))
        }
    }

    @Test
    fun `acepta nombres de equipo y rechaza direcciones mal escritas`() {
        listOf("raspberrypi.local", "mi-pc", "office-pc.lan", "PC1").forEach { assertNull(it, Validation.hostError(it)) }
        listOf("-pc.local", "pc-.local", "mi_pc", "pc..local", "ñandú.local").forEach { assertNotNull(it, Validation.hostError(it)) }
    }

    @Test
    fun `explica el error tipico de cada direccion`() {
        assertEquals("Ingresá la dirección IP de la computadora", Validation.hostError("  "))
        assertEquals("Escribí solo la IP, sin http:// ni barras", Validation.hostError("http://192.168.1.50"))
        assertEquals("Escribí solo la IP, sin http:// ni barras", Validation.hostError("192.168.1.50/api"))
        assertEquals("El puerto va en su propio campo", Validation.hostError("192.168.1.50:8080"))
        assertEquals("La IP debe tener cuatro números entre 0 y 255", Validation.hostError("192.168.1"))
        assertEquals("La IP debe tener cuatro números entre 0 y 255", Validation.hostError("192.168.1.256"))
        // Los espacios alrededor se toleran: es fácil pegarlos sin querer.
        assertNull(Validation.hostError("  192.168.1.50 "))
    }

    @Test
    fun `puertos validos e invalidos`() {
        listOf("1", "80", "8080", "65535", " 8080 ").forEach { assertNull(it, Validation.portError(it)) }
        listOf("", "0", "65536", "-1", "80a", "8 0", "8080.0", "99999999999", "+80").forEach { assertNotNull(it, Validation.portError(it)) }
        assertEquals("El puerto debe estar entre 1 y 65535", Validation.portError("70000"))
    }

    @Test
    fun `nombre token e intervalo`() {
        assertNotNull(Validation.nameError(" "))
        assertNotNull(Validation.nameError("x".repeat(41)))
        assertNull(Validation.nameError("x".repeat(40)))
        assertNotNull(Validation.tokenError(""))
        assertNotNull(Validation.tokenError("abc\ndef"))
        assertNull(Validation.tokenError("local-development-token"))
        listOf("1", "2", "60").forEach { assertNull(it, Validation.reconnectError(it)) }
        listOf("0", "61", "", "abc", "1.5", "-2").forEach { assertNotNull(it, Validation.reconnectError(it)) }
    }

    @Test
    fun `formulario valido se convierte en conexion`() {
        assertTrue(Validation.validate(validForm).isValid)
        val connection = Validation.toConnection(validForm.copy(host = " 192.168.1.50 ", name = " Computadora principal "), newId = "id-1")!!
        assertEquals("id-1", connection.id)
        assertEquals("Computadora principal", connection.name)
        assertEquals("192.168.1.50", connection.host)
        assertEquals(8080, connection.port)
        assertEquals("http://192.168.1.50:8080/api/v1", connection.httpBase)
        assertEquals("ws://192.168.1.50:8080/api/v1/ws", connection.webSocketUrl)

        val errors = Validation.validate(ConnectionForm(name = "", host = "x y", port = "0", token = "", reconnectSeconds = "0"))
        assertFalse(errors.isValid)
        listOf(errors.name, errors.host, errors.port, errors.token, errors.reconnectSeconds).forEach(::assertNotNull)
        assertNull(Validation.toConnection(validForm.copy(port = "abc"), newId = "x"))
    }

    @Test
    fun `editar conserva el id y solo olvida el ultimo exito si cambio el destino`() {
        val saved = Validation.toConnection(validForm, newId = "id-1")!!.copy(lastSuccessMillis = 1234)

        val renamed = Validation.toConnection(ConnectionForm.from(saved).copy(name = "Oficina"), newId = "otro", existing = saved)!!
        assertEquals("id-1", renamed.id)
        assertEquals("Oficina", renamed.name)
        assertEquals(1234L, renamed.lastSuccessMillis)

        val moved = Validation.toConnection(ConnectionForm.from(saved).copy(host = "192.168.1.99"), newId = "otro", existing = saved)!!
        assertEquals("id-1", moved.id)
        assertNull(moved.lastSuccessMillis)
    }

    @Test
    fun `la lista guardada sobrevive a codificar y decodificar`() {
        val list = listOf(connection("a", autoConnect = true).copy(lastSuccessMillis = 99), connection("b", token = "tok\"en con espacios"))
        assertEquals(list, ConnectionList.decode(ConnectionList.encode(list)))
    }

    @Test
    fun `datos guardados ausentes o corruptos dan una lista vacia`() {
        listOf(null, "", "   ", "no es json", "{}", "[{\"id\":1}]", "[{\"name\":\"sin campos\"}]").forEach {
            assertEquals(it, emptyList<SavedConnection>(), ConnectionList.decode(it))
        }
        // Un campo que una versión futura agregue no rompe la lectura.
        val future = """[{"id":"a","name":"PC","host":"10.0.0.1","port":8080,"token":"t","campoNuevo":true}]"""
        assertEquals("a", ConnectionList.decode(future).single().id)
    }

    @Test
    fun `agregar editar y eliminar`() {
        var list = emptyList<SavedConnection>()
        list = ConnectionList.upsert(list, connection("a"))
        list = ConnectionList.upsert(list, connection("b"))
        list = ConnectionList.upsert(list, connection("c"))
        assertEquals(listOf("a", "b", "c"), list.map { it.id })

        // Editar reemplaza en su lugar, sin duplicar ni reordenar.
        list = ConnectionList.upsert(list, connection("b", host = "10.0.0.9"))
        assertEquals(listOf("a", "b", "c"), list.map { it.id })
        assertEquals("10.0.0.9", list[1].host)

        list = ConnectionList.remove(list, "a")
        assertEquals(listOf("b", "c"), list.map { it.id })
        assertEquals(list, ConnectionList.remove(list, "no-existe"))
    }

    @Test
    fun `solo una conexion puede ser automatica`() {
        var list = listOf(connection("a", autoConnect = true), connection("b"))
        assertEquals("a", ConnectionList.autoConnectTarget(list)?.id)

        list = ConnectionList.upsert(list, connection("b", autoConnect = true))
        assertEquals(listOf(false, true), list.map { it.autoConnect })
        assertEquals("b", ConnectionList.autoConnectTarget(list)?.id)

        // Editar otra sin marcarla no le quita la marca a la actual.
        list = ConnectionList.upsert(list, connection("a", host = "10.0.0.2"))
        assertEquals("b", ConnectionList.autoConnectTarget(list)?.id)
        assertNull(ConnectionList.autoConnectTarget(listOf(connection("x"))))
    }

    @Test
    fun `registrar la ultima conexion exitosa`() {
        val list = ConnectionList.markSuccess(listOf(connection("a"), connection("b")), "b", 5000)
        assertNull(list[0].lastSuccessMillis)
        assertEquals(5000L, list[1].lastSuccessMillis)
    }
}
