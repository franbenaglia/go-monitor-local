package com.fabapps.gomonitor.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Mensajes del servidor por WebSocket, sección 6 de docs/PROTOCOL.md. */
sealed interface ServerMessage {
    data class Authenticated(val agentId: String, val protocolVersion: Int, val intervalSeconds: Int) : ServerMessage
    data class Metrics(val snapshot: Snapshot) : ServerMessage
    data object Pong : ServerMessage
    data class Error(val code: String, val message: String) : ServerMessage

    /** Tipo que esta versión no conoce, o texto que no es un mensaje válido. Se ignora. */
    data class Unknown(val type: String?) : ServerMessage
}

object WsProtocol {
    // Códigos de cierre de la aplicación.
    const val CLOSE_NORMAL = 1000
    const val CLOSE_GOING_AWAY = 1001
    const val CLOSE_TOO_BIG = 1009
    const val CLOSE_AUTH_TIMEOUT = 4001
    const val CLOSE_INVALID_TOKEN = 4003
    const val CLOSE_TOO_MANY_MESSAGES = 4008
    const val CLOSE_INVALID_MESSAGE = 4400

    @Serializable
    private data class Envelope(
        val type: String? = null,
        @SerialName("agent_id") val agentId: String = "",
        @SerialName("protocol_version") val protocolVersion: Int = 0,
        @SerialName("collection_interval_seconds") val intervalSeconds: Int = 0,
        val data: Snapshot? = null,
        val error: ApiErrorDetail? = null,
    )

    fun authenticate(token: String): String =
        buildJsonObject {
            put("type", "authenticate")
            put("token", token)
        }.toString()

    const val PING = """{"type":"ping"}"""

    fun parse(text: String): ServerMessage {
        val envelope = runCatching { ProtocolJson.decodeFromString(Envelope.serializer(), text) }
            .getOrElse { return ServerMessage.Unknown(null) }
        return when (envelope.type) {
            "authenticated" -> ServerMessage.Authenticated(envelope.agentId, envelope.protocolVersion, envelope.intervalSeconds)
            "metrics" -> envelope.data?.let(ServerMessage::Metrics) ?: ServerMessage.Unknown("metrics")
            "pong" -> ServerMessage.Pong
            "error" -> ServerMessage.Error(envelope.error?.code.orEmpty(), envelope.error?.message.orEmpty())
            else -> ServerMessage.Unknown(envelope.type)
        }
    }

    /** Qué hacer cuando el servidor cierra: si [fatal] es false conviene reintentar. */
    data class CloseVerdict(val reason: FailureReason, val fatal: Boolean)

    fun onClose(code: Int): CloseVerdict = when (code) {
        CLOSE_INVALID_TOKEN -> CloseVerdict(FailureReason.INVALID_TOKEN, fatal = true)
        // Mensaje demasiado grande o inválido: un error de esta app, reintentar no lo arregla.
        CLOSE_TOO_BIG, CLOSE_INVALID_MESSAGE -> CloseVerdict(FailureReason.INCOMPATIBLE, fatal = true)
        CLOSE_GOING_AWAY -> CloseVerdict(FailureReason.AGENT_STOPPED, fatal = false)
        CLOSE_AUTH_TIMEOUT -> CloseVerdict(FailureReason.TIMEOUT, fatal = false)
        else -> CloseVerdict(FailureReason.SOCKET_CLOSED, fatal = false)
    }
}
