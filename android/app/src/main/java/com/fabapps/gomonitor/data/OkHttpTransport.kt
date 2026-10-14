package com.fabapps.gomonitor.data

import com.fabapps.gomonitor.core.AgentHttpException
import com.fabapps.gomonitor.core.AgentTransport
import com.fabapps.gomonitor.core.ApiErrorBody
import com.fabapps.gomonitor.core.Health
import com.fabapps.gomonitor.core.ProtocolJson
import com.fabapps.gomonitor.core.SavedConnection
import com.fabapps.gomonitor.core.SocketHandle
import com.fabapps.gomonitor.core.SocketListener
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/** Acceso al agente con OkHttp. Habla HTTP y WebSocket sin TLS, dentro de la red local. */
class OkHttpTransport : AgentTransport {
    private val client = OkHttpClient.Builder()
        // Plazos cortos: en una red local, lo que no responde enseguida no va a responder.
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        // La reconexión la maneja ConnectionManager, con su propia espera.
        .retryOnConnectionFailure(false)
        .build()

    // El WebSocket no tiene plazo de lectura (los datos llegan cuando el agente los produce) y
    // envía pings para notar pronto una red que desapareció.
    private val socketClient = client.newBuilder()
        .readTimeout(0, TimeUnit.SECONDS)
        .pingInterval(10, TimeUnit.SECONDS)
        .build()

    override suspend fun health(target: SavedConnection): Health {
        val body = execute(Request.Builder().url(target.httpBase + "/health").build())
        return try {
            ProtocolJson.decodeFromString(Health.serializer(), body)
        } catch (e: Exception) {
            // Respondió 200 pero no con el formato del agente: es otro programa.
            throw AgentHttpException(200, null)
        }
    }

    override suspend fun get(target: SavedConnection, path: String): String =
        execute(
            Request.Builder()
                .url(target.httpBase + path)
                .header("Authorization", "Bearer ${target.token}")
                .build()
        )

    private suspend fun execute(request: Request): String = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val text = try {
                        it.body?.string().orEmpty()
                    } catch (e: IOException) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                        return
                    }
                    if (!continuation.isActive) return
                    if (it.isSuccessful) {
                        continuation.resume(text)
                    } else {
                        continuation.resumeWithException(AgentHttpException(it.code, errorCode(text)))
                    }
                }
            }
        })
    }

    override fun openSocket(target: SavedConnection, listener: SocketListener): SocketHandle {
        val socket = socketClient.newWebSocket(
            Request.Builder().url(target.webSocketUrl).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) = listener.onOpen()

                override fun onMessage(webSocket: WebSocket, text: String) = listener.onMessage(text)

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    // Completa el cierre ordenado que inició el servidor.
                    webSocket.close(code, null)
                    listener.onClosed(code, reason)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    // Si el servidor rechazó la conexión con un estado HTTP (503 por estar lleno),
                    // ese estado dice más que la excepción genérica de OkHttp.
                    val error = if (response != null && response.code != 101) {
                        val body = runCatching { response.body?.string().orEmpty() }.getOrDefault("")
                        AgentHttpException(response.code, errorCode(body))
                    } else {
                        t
                    }
                    listener.onFailure(error)
                }
            },
        )
        return object : SocketHandle {
            override fun send(text: String): Boolean = socket.send(text)
            override fun cancel() = socket.cancel()
        }
    }

    private fun errorCode(body: String): String? =
        runCatching { ProtocolJson.decodeFromString(ApiErrorBody.serializer(), body).error.code }
            .getOrNull()?.takeIf { it.isNotEmpty() }
}
