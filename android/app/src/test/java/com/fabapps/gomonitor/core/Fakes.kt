package com.fabapps.gomonitor.core

class FakeSocket(val listener: SocketListener) : SocketHandle {
    val sent = mutableListOf<String>()
    var cancelled = false
        private set

    override fun send(text: String): Boolean {
        sent += text
        return !cancelled
    }

    override fun cancel() {
        cancelled = true
    }

    fun open() = listener.onOpen()
    fun message(text: String) = listener.onMessage(text)
    fun authenticated(interval: Int = 5, version: Int = 1) =
        message("""{"type":"authenticated","agent_id":"office-pc","protocol_version":$version,"collection_interval_seconds":$interval}""")

    fun metrics(uptime: Long) =
        message("""{"type":"metrics","data":{"agent_id":"office-pc","hostname":"pc","uptime_seconds":$uptime,"readings":[]}}""")
}

class FakeTransport : AgentTransport {
    var health: suspend () -> Health = { Health(status = "ok", agentId = "office-pc", protocolVersion = 1) }
    var get: suspend (String) -> String = { "{}" }
    var healthCalls = 0
    val paths = mutableListOf<String>()
    val sockets = mutableListOf<FakeSocket>()

    val openSockets: List<FakeSocket> get() = sockets.filterNot { it.cancelled }
    val socket: FakeSocket get() = sockets.last()

    override suspend fun health(target: SavedConnection): Health {
        healthCalls++
        return health.invoke()
    }

    override suspend fun get(target: SavedConnection, path: String): String {
        paths += path
        return get.invoke(path)
    }

    override fun openSocket(target: SavedConnection, listener: SocketListener): SocketHandle =
        FakeSocket(listener).also { sockets += it }
}

fun connection(
    id: String = "a",
    host: String = "192.168.1.50",
    token: String = "secret",
    reconnectSeconds: Int = 2,
    autoConnect: Boolean = false,
) = SavedConnection(id = id, name = "PC $id", host = host, port = 8080, token = token,
    autoConnect = autoConnect, reconnectSeconds = reconnectSeconds)
