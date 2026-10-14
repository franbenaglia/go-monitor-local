package com.fabapps.gomonitor.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Tipos del protocolo, con la forma JSON de docs/PROTOCOL.md.
 *
 * Todo lo que el protocolo marca como opcional tiene valor por defecto: un agente que solo lee
 * sensores no envía los bloques de sistema, y un agente más nuevo puede enviar campos que esta
 * versión no conoce.
 */
val ProtocolJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
}

const val SUPPORTED_PROTOCOL_VERSION = 1

@Serializable
data class Reading(
    @SerialName("series_id") val seriesId: String,
    val value: Double,
)

@Serializable
data class Cpu(
    @SerialName("total_percent") val totalPercent: Double = 0.0,
    @SerialName("core_percentages") val corePercentages: List<Double> = emptyList(),
)

@Serializable
data class Memory(
    @SerialName("total_bytes") val totalBytes: Long = 0,
    @SerialName("used_bytes") val usedBytes: Long = 0,
    @SerialName("available_bytes") val availableBytes: Long = 0,
    @SerialName("used_percent") val usedPercent: Double = 0.0,
)

@Serializable
data class Disk(
    @SerialName("mount_point") val mountPoint: String = "",
    val filesystem: String = "",
    @SerialName("total_bytes") val totalBytes: Long = 0,
    @SerialName("used_bytes") val usedBytes: Long = 0,
    @SerialName("available_bytes") val availableBytes: Long = 0,
    @SerialName("used_percent") val usedPercent: Double = 0.0,
)

@Serializable
data class Network(
    @SerialName("bytes_sent_total") val bytesSentTotal: Long = 0,
    @SerialName("bytes_received_total") val bytesReceivedTotal: Long = 0,
    @SerialName("bytes_sent_per_second") val bytesSentPerSecond: Double = 0.0,
    @SerialName("bytes_received_per_second") val bytesReceivedPerSecond: Double = 0.0,
)

@Serializable
data class ProcessInfo(
    val pid: Int = 0,
    val name: String = "",
    @SerialName("cpu_percent") val cpuPercent: Double = 0.0,
    @SerialName("memory_percent") val memoryPercent: Double = 0.0,
)

@Serializable
data class Snapshot(
    @SerialName("agent_id") val agentId: String = "",
    val hostname: String = "",
    @SerialName("operating_system") val operatingSystem: String = "",
    val architecture: String = "",
    val timestamp: String = "",
    @SerialName("uptime_seconds") val uptimeSeconds: Long = 0,
    val readings: List<Reading> = emptyList(),
    val cpu: Cpu? = null,
    val memory: Memory? = null,
    val disks: List<Disk>? = null,
    val network: Network? = null,
    val processes: List<ProcessInfo>? = null,
)

@Serializable
data class SeriesDescriptor(
    val id: String,
    val collector: String = "",
    val name: String = "",
    val kind: String = "generic",
    val unit: String = "",
    val min: Double? = null,
    val max: Double? = null,
) {
    /** Nombre para mostrar; el id sirve de respaldo si el agente no envió uno. */
    val displayName: String get() = name.ifBlank { id }
}

@Serializable
data class SeriesList(val series: List<SeriesDescriptor> = emptyList())

@Serializable
data class HistoryPoint(
    val t: String,
    val avg: Double,
    val min: Double = avg,
    val max: Double = avg,
)

@Serializable
data class HistorySeries(
    val id: String,
    val unit: String = "",
    val points: List<HistoryPoint> = emptyList(),
)

@Serializable
data class HistoryResponse(
    val period: String = "",
    val from: String = "",
    val to: String = "",
    @SerialName("bucket_seconds") val bucketSeconds: Long = 0,
    val series: List<HistorySeries> = emptyList(),
)

@Serializable
data class Health(
    val status: String = "",
    @SerialName("agent_id") val agentId: String = "",
    @SerialName("protocol_version") val protocolVersion: Int = 0,
)

@Serializable
data class ApiErrorDetail(val code: String = "", val message: String = "")

@Serializable
data class ApiErrorBody(val error: ApiErrorDetail = ApiErrorDetail())

/** Identificadores y vocabulario de series definidos por el protocolo. */
object SeriesIds {
    const val CPU_TOTAL = "cpu.total_percent"
    const val MEMORY_PERCENT = "memory.used_percent"
    const val NET_SENT_RATE = "network.bytes_sent_per_second"
    const val NET_RECEIVED_RATE = "network.bytes_received_per_second"

    /** Series que el historial muestra siempre que el agente las tenga. */
    val DEFAULT_HISTORY = listOf(CPU_TOTAL, MEMORY_PERCENT, NET_SENT_RATE, NET_RECEIVED_RATE)

    const val SYSTEM_COLLECTOR = "system"
}

val HISTORY_PERIODS = listOf(
    "15m" to "15 minutos",
    "1h" to "1 hora",
    "6h" to "6 horas",
    "24h" to "24 horas",
    "7d" to "7 días",
)
