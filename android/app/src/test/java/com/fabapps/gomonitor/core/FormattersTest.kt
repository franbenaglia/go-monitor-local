package com.fabapps.gomonitor.core

import org.junit.Assert.assertEquals
import org.junit.Test

class FormattersTest {
    @Test
    fun `bytes en unidades legibles`() {
        assertEquals("0 B", Formatters.bytes(0L))
        assertEquals("1023 B", Formatters.bytes(1023L))
        assertEquals("1,00 KB", Formatters.bytes(1024L))
        assertEquals("1,50 KB", Formatters.bytes(1536L))
        assertEquals("10,0 MB", Formatters.bytes(10L * 1024 * 1024))
        assertEquals("512 MB", Formatters.bytes(512L * 1024 * 1024))
        assertEquals("16,0 GB", Formatters.bytes(17_179_869_184L))
        assertEquals("1,00 TB", Formatters.bytes(1L shl 40))
        // Por encima de TB no hay unidad mayor: sigue en TB.
        assertEquals("2048 TB", Formatters.bytes(1L shl 51))
    }

    @Test
    fun `velocidades de red`() {
        assertEquals("0 B/s", Formatters.rate(0.0))
        assertEquals("999 B/s", Formatters.rate(999.4))
        assertEquals("8,20 KB/s", Formatters.rate(8400.0))
        assertEquals("1,00 MB/s", Formatters.rate(1024.0 * 1024))
        assertEquals("118 MB/s", Formatters.rate(118.0 * 1024 * 1024))
    }

    @Test
    fun `uptime en dias horas y minutos`() {
        assertEquals("0 s", Formatters.uptime(0))
        assertEquals("59 s", Formatters.uptime(59))
        assertEquals("1 min", Formatters.uptime(60))
        assertEquals("1 h", Formatters.uptime(3_600))
        assertEquals("1 h 1 min", Formatters.uptime(3_661))
        assertEquals("1 d 2 h 3 min", Formatters.uptime(93_784))
        assertEquals("1 d 10 h 17 min", Formatters.uptime(123_456))
        assertEquals("3 d", Formatters.uptime(3 * 86_400L))
        assertEquals("0 s", Formatters.uptime(-5))
    }

    @Test
    fun `tiempo transcurrido`() {
        assertEquals("recién", Formatters.elapsed(900))
        assertEquals("hace 5 s", Formatters.elapsed(5_000))
        assertEquals("hace 2 min", Formatters.elapsed(125_000))
        assertEquals("hace 3 h", Formatters.elapsed(3 * 3_600_000L))
        assertEquals("hace 2 d", Formatters.elapsed(2 * 86_400_000L))
        assertEquals("recién", Formatters.elapsed(-100))
    }

    @Test
    fun `valores segun la unidad de la serie`() {
        assertEquals("34,2 %", Formatters.value(34.2, "percent"))
        assertEquals("1,50 KB", Formatters.value(1536.0, "bytes"))
        assertEquals("8,20 KB/s", Formatters.value(8400.0, "bytes_per_second"))
        assertEquals("23,4 °C", Formatters.value(23.44, "celsius"))
        assertEquals("1013,2 hPa", Formatters.value(1013.2, "hectopascal"))
        assertEquals("3,30 V", Formatters.value(3.3, "volt"))
        assertEquals("12", Formatters.value(12.0, "count"))
        // Unidad que esta versión no conoce: número y texto de la unidad tal cual.
        assertEquals("450 lux", Formatters.value(450.0, "lux"))
        assertEquals("0,75 ppm", Formatters.value(0.75, "ppm"))
        assertEquals("7", Formatters.value(7.0, ""))
    }
}
