package com.fabapps.gomonitor.core

import java.time.Instant

/** Un punto de un gráfico: tiempo en segundos desde epoch y valores del intervalo. */
data class ChartPoint(val epochSeconds: Long, val avg: Double, val min: Double, val max: Double)

/**
 * Una serie lista para dibujar. [segments] parte los puntos donde faltan datos, para dibujar un
 * hueco en lugar de una línea recta que invente valores.
 */
data class ChartSeries(
    val id: String,
    val unit: String,
    val segments: List<List<ChartPoint>>,
    val fromEpochSeconds: Long,
    val toEpochSeconds: Long,
    val yMin: Double,
    val yMax: Double,
) {
    val isEmpty: Boolean get() = segments.all { it.isEmpty() }
    val points: List<ChartPoint> get() = segments.flatten()
    val latest: ChartPoint? get() = segments.lastOrNull()?.lastOrNull()
}

object HistoryTransform {
    private fun epoch(text: String): Long? = runCatching { Instant.parse(text).epochSecond }.getOrNull()

    fun toChart(response: HistoryResponse, descriptors: Map<String, SeriesDescriptor> = emptyMap()): List<ChartSeries> {
        val from = epoch(response.from)
        val to = epoch(response.to)
        return response.series.map { series ->
            val points = series.points
                .mapNotNull { p -> epoch(p.t)?.let { ChartPoint(it, p.avg, p.min, p.max) } }
                .sortedBy { it.epochSeconds }
            val (yMin, yMax) = yRange(points, series.unit, descriptors[series.id])
            ChartSeries(
                id = series.id,
                unit = series.unit,
                segments = split(points, response.bucketSeconds),
                fromEpochSeconds = from ?: points.firstOrNull()?.epochSeconds ?: 0,
                toEpochSeconds = to ?: points.lastOrNull()?.epochSeconds ?: 0,
                yMin = yMin,
                yMax = yMax,
            )
        }
    }

    /** Corta la serie donde dos puntos consecutivos distan más de un intervalo. */
    fun split(points: List<ChartPoint>, bucketSeconds: Long): List<List<ChartPoint>> {
        if (points.isEmpty()) return emptyList()
        // Media cubeta de tolerancia: los intervalos no siempre caen exactos.
        val maxGap = if (bucketSeconds > 0) bucketSeconds + bucketSeconds / 2 else Long.MAX_VALUE
        val segments = mutableListOf(mutableListOf(points.first()))
        for (i in 1 until points.size) {
            if (points[i].epochSeconds - points[i - 1].epochSeconds > maxGap) segments.add(mutableListOf())
            segments.last().add(points[i])
        }
        return segments
    }

    /**
     * Rango del eje vertical. Los porcentajes van siempre de 0 a 100 para que dos gráficos se
     * puedan comparar; las magnitudes que no pueden ser negativas arrancan en cero; el resto
     * (temperatura, por ejemplo) se ajusta a los datos con un margen.
     */
    fun yRange(points: List<ChartPoint>, unit: String, descriptor: SeriesDescriptor? = null): Pair<Double, Double> {
        if (unit == "percent") return 0.0 to 100.0
        if (points.isEmpty()) return (descriptor?.min ?: 0.0) to (descriptor?.max ?: 1.0)
        val dataMin = points.minOf { it.min }
        val dataMax = points.maxOf { it.max }
        if (unit == "bytes" || unit == "bytes_per_second" || unit == "count") {
            // Sin tráfico el máximo sería cero; se deja un rango mínimo para poder dibujar.
            return 0.0 to maxOf(dataMax * 1.1, 1.0)
        }
        val span = dataMax - dataMin
        val margin = if (span > 0) span * 0.15 else maxOf(Math.abs(dataMax) * 0.05, 0.5)
        return (dataMin - margin) to (dataMax + margin)
    }
}

/** Últimos valores recibidos en vivo de cada serie, para los minigráficos del dashboard. */
class RecentReadings(private val capacity: Int = 60) {
    private val values = LinkedHashMap<String, ArrayDeque<Double>>()

    fun add(snapshot: Snapshot): Map<String, List<Double>> {
        for (reading in snapshot.readings) {
            val queue = values.getOrPut(reading.seriesId) { ArrayDeque() }
            queue.addLast(reading.value)
            while (queue.size > capacity) queue.removeFirst()
        }
        return snapshot()
    }

    fun clear() = values.clear()

    fun snapshot(): Map<String, List<Double>> = values.mapValues { it.value.toList() }
}

enum class ProcessSort { CPU, MEMORY }

object Processes {
    fun sorted(processes: List<ProcessInfo>, by: ProcessSort): List<ProcessInfo> = when (by) {
        ProcessSort.CPU -> processes.sortedWith(compareByDescending<ProcessInfo> { it.cpuPercent }.thenByDescending { it.memoryPercent })
        ProcessSort.MEMORY -> processes.sortedWith(compareByDescending<ProcessInfo> { it.memoryPercent }.thenByDescending { it.cpuPercent })
    }
}

/** Lecturas de la muestra que no son del sistema: los sensores que el dashboard dibuja genéricos. */
object Sensors {
    data class SensorReading(val descriptor: SeriesDescriptor, val value: Double)

    fun from(snapshot: Snapshot, catalog: Map<String, SeriesDescriptor>): List<SensorReading> =
        snapshot.readings.mapNotNull { reading ->
            val descriptor = catalog[reading.seriesId]
            when {
                // Sin catálogo todavía: se muestra con el id, salvo las series conocidas del sistema.
                descriptor == null ->
                    if (isSystemId(reading.seriesId)) null
                    else SensorReading(SeriesDescriptor(id = reading.seriesId), reading.value)
                descriptor.collector == SeriesIds.SYSTEM_COLLECTOR -> null
                else -> SensorReading(descriptor, reading.value)
            }
        }

    private fun isSystemId(id: String) = id.startsWith("cpu.") || id.startsWith("memory.") || id.startsWith("network.")
}
