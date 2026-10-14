package com.fabapps.gomonitor.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/** Una computadora guardada por el usuario. */
@Serializable
data class SavedConnection(
    val id: String,
    val name: String,
    val host: String,
    val port: Int = DEFAULT_PORT,
    val token: String,
    val autoConnect: Boolean = false,
    val reconnectSeconds: Int = DEFAULT_RECONNECT_SECONDS,
    /** Última vez que la conexión llegó a autenticarse, en milisegundos; null si nunca. */
    val lastSuccessMillis: Long? = null,
) {
    val httpBase: String get() = "http://$host:$port/api/v1"
    val webSocketUrl: String get() = "ws://$host:$port/api/v1/ws"
    val address: String get() = "$host:$port"

    companion object {
        const val DEFAULT_PORT = 8080
        const val DEFAULT_RECONNECT_SECONDS = 2
    }
}

/** Lo que el usuario escribe en el formulario, todavía sin validar. */
data class ConnectionForm(
    val name: String = "",
    val host: String = "",
    val port: String = SavedConnection.DEFAULT_PORT.toString(),
    val token: String = "",
    val autoConnect: Boolean = true,
    val reconnectSeconds: String = SavedConnection.DEFAULT_RECONNECT_SECONDS.toString(),
) {
    companion object {
        fun from(connection: SavedConnection) = ConnectionForm(
            name = connection.name,
            host = connection.host,
            port = connection.port.toString(),
            token = connection.token,
            autoConnect = connection.autoConnect,
            reconnectSeconds = connection.reconnectSeconds.toString(),
        )
    }
}

/** Mensajes de error por campo; null significa que el campo es válido. */
data class FormErrors(
    val name: String? = null,
    val host: String? = null,
    val port: String? = null,
    val token: String? = null,
    val reconnectSeconds: String? = null,
) {
    val isValid: Boolean
        get() = listOf(name, host, port, token, reconnectSeconds).all { it == null }
}

object Validation {
    const val MIN_RECONNECT_SECONDS = 1
    const val MAX_RECONNECT_SECONDS = 60
    private const val MAX_NAME_LENGTH = 40
    private const val MAX_TOKEN_LENGTH = 512

    private val hostnameLabel = Regex("^[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?$")

    // Char.isDigit acepta dígitos de otros alfabetos, que una URL no admite.
    private fun isAsciiDigit(c: Char): Boolean = c in '0'..'9'

    fun isValidIpv4(text: String): Boolean {
        val parts = text.split(".")
        if (parts.size != 4) return false
        return parts.all { part ->
            // Sin ceros a la izquierda: "010" es ambiguo (algunos sistemas lo leen como octal).
            part.isNotEmpty() && part.length <= 3 && part.all(::isAsciiDigit) &&
                (part == "0" || !part.startsWith("0")) && part.toInt() <= 255
        }
    }

    /** Nombre de equipo como `mi-pc.local`. */
    fun isValidHostname(text: String): Boolean {
        if (text.length > 253 || text.none { it in 'a'..'z' || it in 'A'..'Z' }) return false
        val labels = text.split(".")
        // Casi todo números: es una IP mal tipeada ("192.168.1.a"), no un nombre.
        if (labels.count { it.isNotEmpty() && it.all(::isAsciiDigit) } >= 3) return false
        return labels.all(hostnameLabel::matches)
    }

    fun hostError(raw: String): String? {
        val host = raw.trim()
        return when {
            host.isEmpty() -> "Ingresá la dirección IP de la computadora"
            host.contains("://") || host.contains("/") -> "Escribí solo la IP, sin http:// ni barras"
            host.contains(":") -> "El puerto va en su propio campo"
            host.contains(" ") -> "La dirección no puede tener espacios"
            isValidIpv4(host) || isValidHostname(host) -> null
            host.all { isAsciiDigit(it) || it == '.' } -> "La IP debe tener cuatro números entre 0 y 255"
            else -> "La dirección no es válida"
        }
    }

    fun portError(raw: String): String? {
        val text = raw.trim()
        val port = text.toIntOrNull()
        return when {
            text.isEmpty() -> "Ingresá el puerto"
            !text.all(::isAsciiDigit) || port == null -> "El puerto debe ser un número"
            port !in 1..65535 -> "El puerto debe estar entre 1 y 65535"
            else -> null
        }
    }

    fun nameError(raw: String): String? = when {
        raw.isBlank() -> "Ingresá un nombre para la conexión"
        raw.trim().length > MAX_NAME_LENGTH -> "El nombre no puede superar $MAX_NAME_LENGTH caracteres"
        else -> null
    }

    fun tokenError(raw: String): String? = when {
        raw.isBlank() -> "Ingresá el token configurado en el agente"
        raw.length > MAX_TOKEN_LENGTH -> "El token es demasiado largo"
        // Un token con saltos de línea rompería la cabecera Authorization.
        raw.any { it.isISOControl() } -> "El token tiene caracteres no válidos"
        else -> null
    }

    fun reconnectError(raw: String): String? {
        val seconds = raw.trim().takeIf { it.all(::isAsciiDigit) }?.toIntOrNull()
        return when {
            seconds == null -> "Ingresá un número de segundos"
            seconds !in MIN_RECONNECT_SECONDS..MAX_RECONNECT_SECONDS ->
                "Debe estar entre $MIN_RECONNECT_SECONDS y $MAX_RECONNECT_SECONDS segundos"
            else -> null
        }
    }

    fun validate(form: ConnectionForm) = FormErrors(
        name = nameError(form.name),
        host = hostError(form.host),
        port = portError(form.port),
        token = tokenError(form.token),
        reconnectSeconds = reconnectError(form.reconnectSeconds),
    )

    /**
     * Convierte el formulario en una conexión, o null si no es válido. [existing] conserva el id
     * y la última conexión exitosa al editar.
     */
    fun toConnection(form: ConnectionForm, newId: String, existing: SavedConnection? = null): SavedConnection? {
        if (!validate(form).isValid) return null
        val host = form.host.trim()
        val port = form.port.trim().toInt()
        val token = form.token.trim()
        // Si cambió el destino o el token, la última conexión exitosa ya no dice nada.
        val sameTarget = existing != null && existing.host == host && existing.port == port && existing.token == token
        return SavedConnection(
            id = existing?.id ?: newId,
            name = form.name.trim(),
            host = host,
            port = port,
            token = token,
            autoConnect = form.autoConnect,
            reconnectSeconds = form.reconnectSeconds.trim().toInt(),
            lastSuccessMillis = if (sameTarget) existing.lastSuccessMillis else null,
        )
    }
}

/** Operaciones sobre la lista guardada. Son puras; el guardado en disco está en ConnectionStore. */
object ConnectionList {
    private val serializer = ListSerializer(SavedConnection.serializer())

    fun encode(connections: List<SavedConnection>): String = ProtocolJson.encodeToString(serializer, connections)

    /** Datos ausentes o corruptos se leen como lista vacía: la app tiene que poder abrir igual. */
    fun decode(text: String?): List<SavedConnection> {
        if (text.isNullOrBlank()) return emptyList()
        return runCatching { ProtocolJson.decodeFromString(serializer, text) }.getOrDefault(emptyList())
    }

    /** Agrega la conexión, o la reemplaza en su lugar si el id ya existe. */
    fun upsert(list: List<SavedConnection>, connection: SavedConnection): List<SavedConnection> {
        // Una sola conexión puede ser la automática; marcar otra desmarca la anterior.
        val others = if (connection.autoConnect) list.map { it.copy(autoConnect = false) } else list
        return if (others.any { it.id == connection.id }) {
            others.map { if (it.id == connection.id) connection else it }
        } else {
            others + connection
        }
    }

    fun remove(list: List<SavedConnection>, id: String): List<SavedConnection> = list.filterNot { it.id == id }

    fun markSuccess(list: List<SavedConnection>, id: String, nowMillis: Long): List<SavedConnection> =
        list.map { if (it.id == id) it.copy(lastSuccessMillis = nowMillis) else it }

    /** La conexión a abrir al iniciar la app, si hay alguna marcada. */
    fun autoConnectTarget(list: List<SavedConnection>): SavedConnection? = list.firstOrNull { it.autoConnect }
}
