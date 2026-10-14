package com.fabapps.gomonitor.core

import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Acceso de red al agente. La implementación real usa OkHttp; las pruebas usan una falsa, lo
 * que permite probar la conexión y la reconexión en la JVM, sin teléfono.
 */
interface AgentTransport {
    /** GET sin token a `/health`. Lanza [IOException] o [AgentHttpException]. */
    suspend fun health(target: SavedConnection): Health

    /** GET con token a una ruta de la API (por ejemplo `/series`). Devuelve el cuerpo JSON. */
    suspend fun get(target: SavedConnection, path: String): String

    /** Abre el WebSocket. Los eventos llegan a [listener], posiblemente desde otro hilo. */
    fun openSocket(target: SavedConnection, listener: SocketListener): SocketHandle
}

interface SocketListener {
    fun onOpen()
    fun onMessage(text: String)

    /** El servidor cerró con un código de cierre. */
    fun onClosed(code: Int, reason: String)

    /** La conexión falló o se cortó sin cierre ordenado. */
    fun onFailure(error: Throwable)
}

interface SocketHandle {
    fun send(text: String): Boolean

    /** Corta la conexión de inmediato y libera sus recursos. Se puede llamar más de una vez. */
    fun cancel()
}

/** El agente (u otro programa en esa dirección) respondió con un estado HTTP de error. */
class AgentHttpException(val status: Int, val code: String?) : IOException("HTTP $status ${code.orEmpty()}")

/** Lo que el celular sabe de su propia red, para explicar una falla. */
data class NetworkStatus(
    val onWifi: Boolean,
    /** Subredes IPv4 a las que el celular está conectado, como pares (dirección, prefijo). */
    val subnets: List<Pair<String, Int>> = emptyList(),
) {
    /** True si [host] es una IPv4 dentro de alguna de las subredes del celular. */
    fun reaches(host: String): Boolean = subnets.any { (address, prefix) -> Subnets.contains(address, prefix, host) }

    companion object {
        /** Sin información: no se usa para descartar nada. */
        val UNKNOWN = NetworkStatus(onWifi = true)
    }
}

object Subnets {
    private fun toInt(ip: String): Int? {
        if (!Validation.isValidIpv4(ip)) return null
        return ip.split(".").fold(0) { acc, part -> (acc shl 8) or part.toInt() }
    }

    fun contains(networkAddress: String, prefixLength: Int, candidate: String): Boolean {
        val network = toInt(networkAddress) ?: return false
        val address = toInt(candidate) ?: return false
        if (prefixLength !in 0..32) return false
        val mask = if (prefixLength == 0) 0 else -1 shl (32 - prefixLength)
        return (network and mask) == (address and mask)
    }
}

/** Traduce una falla de red a un motivo que el usuario pueda entender. */
object ErrorClassifier {
    fun classify(error: Throwable, target: SavedConnection, network: NetworkStatus): FailureReason {
        if (error is AgentHttpException) {
            return when (error.status) {
                401 -> FailureReason.INVALID_TOKEN
                503 -> if (error.code == "too_many_connections") FailureReason.SERVER_BUSY else FailureReason.UNKNOWN
                // Un 404 o una respuesta sin el formato del agente: hay otro servicio en ese puerto.
                else -> FailureReason.NOT_AN_AGENT
            }
        }
        if (error is IllegalArgumentException) return FailureReason.INVALID_ADDRESS

        // OkHttp envuelve la falla real: "Failed to connect to /ip:puerto" con la causa adentro
        // (en Android, "ECONNREFUSED (Connection refused)"). Hay que mirar toda la cadena.
        val chain = generateSequence(error) { it.cause }.take(8).toList()
        if (chain.any { it is UnknownHostException }) return FailureReason.HOST_NOT_FOUND
        // Rechazada: la computadora existe y contestó, pero nadie escucha en el puerto.
        if (chain.any { it.message.orEmpty().contains("refused", ignoreCase = true) }) {
            return FailureReason.CONNECTION_REFUSED
        }

        // De acá en adelante la computadora no contestó. Antes de culpar a la IP, ver si el
        // celular siquiera está en una red desde la que podría alcanzarla.
        val timedOut = chain.any { it is SocketTimeoutException }
        val unreachable = timedOut || chain.any {
            it is NoRouteToHostException || it is PortUnreachableException || it is ConnectException
        }
        if (unreachable) {
            val isIp = Validation.isValidIpv4(target.host)
            if (!network.onWifi || (isIp && network.subnets.isNotEmpty() && !network.reaches(target.host))) {
                return FailureReason.WRONG_NETWORK
            }
            return if (timedOut) FailureReason.TIMEOUT else FailureReason.HOST_NOT_FOUND
        }
        return if (error is IOException) FailureReason.SOCKET_CLOSED else FailureReason.UNKNOWN
    }
}
