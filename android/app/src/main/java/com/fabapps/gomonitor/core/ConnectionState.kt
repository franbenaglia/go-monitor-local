package com.fabapps.gomonitor.core

/** Por qué falló o se cortó una conexión. */
enum class FailureReason(val message: String) {
    INVALID_ADDRESS("La dirección no es válida. Revisá la IP y el puerto."),
    HOST_NOT_FOUND("No se encontró el equipo. Revisá la IP y que el agente esté iniciado."),
    CONNECTION_REFUSED("La computadora respondió, pero el agente no está escuchando en ese puerto."),
    TIMEOUT("La computadora no respondió a tiempo."),
    WRONG_NETWORK("El celular no parece estar en la misma red que la computadora."),
    NOT_AN_AGENT("En esa dirección responde otro programa, no GoMonitor Agent."),
    INVALID_TOKEN("El token no coincide con el configurado en el agente."),
    SERVER_BUSY("El agente ya tiene el máximo de clientes conectados."),
    SOCKET_CLOSED("Se cerró la conexión con el agente."),
    AGENT_STOPPED("El agente se detuvo."),
    NO_DATA("El agente dejó de enviar datos."),
    INCOMPATIBLE("El agente usa una versión del protocolo que esta app no entiende."),
    UNKNOWN("No se pudo conectar."),
}

/** Estado de la conexión activa. [label] es el texto corto que se muestra en pantalla. */
sealed interface ConnectionState {
    val label: String

    data object NotConfigured : ConnectionState {
        override val label = "Sin configurar"
    }

    data object Connecting : ConnectionState {
        override val label = "Conectando"
    }

    data object Authenticating : ConnectionState {
        override val label = "Autenticando"
    }

    data object Connected : ConnectionState {
        override val label = "Conectado"
    }

    /** Esperando para reintentar, o reintentando. [delayMillis] es la espera de este intento. */
    data class Reconnecting(val attempt: Int, val delayMillis: Long, val reason: FailureReason) : ConnectionState {
        override val label = "Reconectando"
    }

    /** Falla que reintentar no arregla; espera una acción del usuario. */
    data class Failed(val reason: FailureReason) : ConnectionState {
        override val label = when (reason) {
            FailureReason.INVALID_TOKEN -> "Token incorrecto"
            FailureReason.HOST_NOT_FOUND, FailureReason.INVALID_ADDRESS, FailureReason.NOT_AN_AGENT -> "Equipo no encontrado"
            else -> "Sin conexión"
        }
    }

    /** El usuario cerró la conexión. */
    data object Disconnected : ConnectionState {
        override val label = "Sin conexión"
    }

    val isActive: Boolean
        get() = this is Connecting || this is Authenticating || this is Connected || this is Reconnecting

    /** Explicación para mostrar debajo del estado, si hay algo que explicar. */
    val detail: String?
        get() = when (this) {
            is Reconnecting -> reason.message
            is Failed -> reason.message
            else -> null
        }
}

/** Espera entre reintentos: se duplica en cada intento hasta un máximo. */
object Backoff {
    const val MAX_DELAY_MILLIS = 60_000L

    /** [attempt] empieza en 1. La base es el intervalo de reconexión de la conexión. */
    fun delayMillis(attempt: Int, baseSeconds: Int): Long {
        val base = baseSeconds.coerceAtLeast(1) * 1000L
        // A partir de 2^20 el resultado ya superó cualquier máximo; evita desbordar el Long.
        val factor = 1L shl (attempt - 1).coerceIn(0, 20)
        return (base * factor).coerceAtMost(maxOf(MAX_DELAY_MILLIS, base))
    }
}
