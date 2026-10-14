package com.fabapps.gomonitor.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryTest {
    // Respuesta real del agente (recortada).
    private val body = """
        {"bucket_seconds":5,"from":"2026-10-14T01:42:35Z","period":"15m","to":"2026-10-14T01:57:35Z","series":[
        {"id":"cpu.total_percent","unit":"percent","points":[
          {"t":"2026-10-14T01:57:10Z","avg":6.0,"min":5.5,"max":6.5},
          {"t":"2026-10-14T01:57:15Z","avg":5.38,"min":4.98,"max":5.78},
          {"t":"2026-10-14T01:57:30Z","avg":5.31,"min":5.31,"max":5.31}]},
        {"id":"sim.temperature","unit":"celsius","points":[
          {"t":"2026-10-14T01:57:25Z","avg":18.3,"min":18.0,"max":19.0},
          {"t":"2026-10-14T01:57:30Z","avg":18.4,"min":18.4,"max":18.4}]},
        {"id":"network.bytes_sent_per_second","unit":"bytes_per_second","points":[]}]}
    """.trimIndent()

    private fun chart() = HistoryTransform.toChart(ProtocolJson.decodeFromString(HistoryResponse.serializer(), body))

    @Test
    fun `convierte la respuesta en series para graficar`() {
        val (cpu, temperature, upload) = chart()
        assertEquals("cpu.total_percent", cpu.id)
        assertEquals("percent", cpu.unit)
        assertEquals(15 * 60L, cpu.toEpochSeconds - cpu.fromEpochSeconds)
        assertEquals(3, cpu.points.size)
        assertEquals(5.31, cpu.latest!!.avg, 0.0)
        assertEquals(5L, cpu.points[1].epochSeconds - cpu.points[0].epochSeconds)

        assertEquals(1, temperature.segments.size)
        assertTrue(upload.isEmpty)
        assertEquals(null, upload.latest)
    }

    @Test
    fun `parte la linea donde faltan datos`() {
        val cpu = chart().first()
        // Entre 01:57:15 y 01:57:30 faltan dos intervalos: son dos tramos, no una línea recta.
        assertEquals(listOf(2, 1), cpu.segments.map { it.size })

        fun points(vararg seconds: Long) = seconds.map { ChartPoint(it, 1.0, 1.0, 1.0) }
        assertEquals(listOf(4), HistoryTransform.split(points(0, 10, 20, 30), 10).map { it.size })
        // Hasta media cubeta de desfase no se considera hueco.
        assertEquals(listOf(3), HistoryTransform.split(points(0, 10, 25), 10).map { it.size })
        assertEquals(listOf(2, 1), HistoryTransform.split(points(0, 10, 26), 10).map { it.size })
        assertEquals(listOf(1, 1, 1), HistoryTransform.split(points(0, 100, 200), 10).map { it.size })
        assertEquals(emptyList<List<ChartPoint>>(), HistoryTransform.split(emptyList(), 10))
        assertEquals(listOf(1), HistoryTransform.split(points(5), 10).map { it.size })
    }

    @Test
    fun `ordena los puntos y descarta los que no tienen fecha valida`() {
        val response = HistoryResponse(bucketSeconds = 5, series = listOf(HistorySeries("x", "count", listOf(
            HistoryPoint("2026-10-14T01:57:10Z", 2.0),
            HistoryPoint("no es una fecha", 9.0),
            HistoryPoint("2026-10-14T01:57:05Z", 1.0),
        ))))
        val series = HistoryTransform.toChart(response).single()
        assertEquals(listOf(1.0, 2.0), series.points.map { it.avg })
        // Sin from/to en la respuesta, el rango sale de los propios puntos.
        assertEquals(series.points.first().epochSeconds, series.fromEpochSeconds)
        assertEquals(series.points.last().epochSeconds, series.toEpochSeconds)
    }

    @Test
    fun `rango vertical segun la unidad`() {
        val (cpu, temperature, upload) = chart()
        // Porcentajes: siempre 0 a 100, aunque los datos ocupen una franja chica.
        assertEquals(0.0 to 100.0, cpu.yMin to cpu.yMax)
        // Temperatura: ajustado a los datos, con margen arriba y abajo.
        assertTrue(temperature.yMin < 18.0 && temperature.yMin > 17.0)
        assertTrue(temperature.yMax > 19.0 && temperature.yMax < 20.0)
        // Sin puntos igual hay un rango dibujable.
        assertTrue(upload.yMax > upload.yMin)

        val traffic = listOf(ChartPoint(0, 500.0, 100.0, 2000.0))
        val (low, high) = HistoryTransform.yRange(traffic, "bytes_per_second")
        assertEquals(0.0, low, 0.0)
        assertTrue(high >= 2000.0)
        // Sin tráfico: no puede quedar un rango de altura cero.
        val (zeroLow, zeroHigh) = HistoryTransform.yRange(listOf(ChartPoint(0, 0.0, 0.0, 0.0)), "bytes_per_second")
        assertTrue(zeroHigh > zeroLow)
        // Valor constante en una unidad cualquiera.
        val (flatLow, flatHigh) = HistoryTransform.yRange(listOf(ChartPoint(0, 20.0, 20.0, 20.0)), "celsius")
        assertTrue(flatLow < 20.0 && flatHigh > 20.0)
        // Sin datos se usa el rango declarado por el sensor.
        val descriptor = SeriesDescriptor(id = "t", unit = "celsius", min = -10.0, max = 50.0)
        assertEquals(-10.0 to 50.0, HistoryTransform.yRange(emptyList(), "celsius", descriptor))
    }

    @Test
    fun `ultimos valores en vivo con tope de tamano`() {
        val recent = RecentReadings(capacity = 3)
        var last = emptyMap<String, List<Double>>()
        for (i in 1..5) {
            last = recent.add(Snapshot(readings = listOf(Reading("cpu.total_percent", i.toDouble()))))
        }
        assertEquals(listOf(3.0, 4.0, 5.0), last["cpu.total_percent"])
        // Una serie que falta en una muestra conserva lo que tenía.
        last = recent.add(Snapshot(readings = listOf(Reading("sim.temperature", 20.0))))
        assertEquals(listOf(3.0, 4.0, 5.0), last["cpu.total_percent"])
        assertEquals(listOf(20.0), last["sim.temperature"])
        recent.clear()
        assertTrue(recent.snapshot().isEmpty())
    }

    @Test
    fun `orden de procesos`() {
        val list = listOf(
            ProcessInfo(1, "a", cpuPercent = 30.0, memoryPercent = 1.0),
            ProcessInfo(2, "b", cpuPercent = 20.0, memoryPercent = 9.0),
            ProcessInfo(3, "c", cpuPercent = 20.0, memoryPercent = 5.0),
        )
        assertEquals(listOf(1, 2, 3), Processes.sorted(list, ProcessSort.CPU).map { it.pid })
        assertEquals(listOf(2, 3, 1), Processes.sorted(list, ProcessSort.MEMORY).map { it.pid })
        assertEquals(emptyList<ProcessInfo>(), Processes.sorted(emptyList(), ProcessSort.CPU))
    }

    @Test
    fun `los sensores son las lecturas que no vienen del sistema`() {
        val snapshot = Snapshot(readings = listOf(
            Reading("cpu.total_percent", 6.0), Reading("sim.temperature", 17.7), Reading("bme280.pressure", 1013.0),
        ))
        val catalog = listOf(
            SeriesDescriptor("cpu.total_percent", collector = "system", name = "CPU total", unit = "percent"),
            SeriesDescriptor("sim.temperature", collector = "simulated", name = "Temperatura", kind = "temperature", unit = "celsius"),
        ).associateBy { it.id }

        val sensors = Sensors.from(snapshot, catalog)
        assertEquals(listOf("sim.temperature", "bme280.pressure"), sensors.map { it.descriptor.id })
        assertEquals("Temperatura", sensors[0].descriptor.displayName)
        // Lectura que no está en el catálogo: se muestra igual, con su id como nombre.
        assertEquals("bme280.pressure", sensors[1].descriptor.displayName)

        // Antes de recibir el catálogo, las series del sistema no se confunden con sensores.
        assertEquals(listOf("sim.temperature", "bme280.pressure"), Sensors.from(snapshot, emptyMap()).map { it.descriptor.id })
    }
}
