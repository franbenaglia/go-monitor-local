package com.fabapps.gomonitor.core

/** Un agente anunciado en la red local por mDNS, ya resuelto a una dirección. */
data class DiscoveredAgent(
    /** Nombre de la instancia del servicio; identifica al agente mientras dura la búsqueda. */
    val serviceName: String,
    val host: String,
    val port: Int,
    val agentId: String = "",
    val hostname: String = "",
    val protocolVersion: Int? = null,
) {
    val address: String get() = "$host:$port"

    /** Lo que se le muestra al usuario: el nombre del equipo si vino, si no el id del agente. */
    val displayName: String get() = hostname.ifBlank { agentId.ifBlank { serviceName } }
}

/**
 * Lógica del descubrimiento, sin dependencias de Android. La búsqueda en sí (NsdManager) está
 * en data/NsdDiscovery.
 *
 * El anuncio es solo una pista: cualquiera en la red puede publicar uno. Por eso de acá sale un
 * formulario para que el usuario revise y cargue el token, nunca una conexión ya guardada.
 */
object Discovery {
    /** Tipo de servicio definido en la sección 7 de docs/PROTOCOL.md. */
    const val SERVICE_TYPE = "_gomonitor._tcp"

    private const val MAX_TEXT_LENGTH = 64

    /**
     * Arma un agente a partir de lo que devolvió la resolución. Devuelve null si no hay una
     * dirección IPv4 o el puerto no es válido: la app se conecta por IPv4.
     */
    fun fromResolved(serviceName: String, addresses: List<String>, port: Int, txt: Map<String, ByteArray?>): DiscoveredAgent? {
        val host = addresses.map { it.trim().removePrefix("/") }.firstOrNull(Validation::isValidIpv4) ?: return null
        if (port !in 1..65535 || serviceName.isBlank()) return null
        return DiscoveredAgent(
            serviceName = serviceName,
            host = host,
            port = port,
            agentId = text(txt["agent_id"]),
            hostname = text(txt["name"]),
            protocolVersion = text(txt["version"]).toIntOrNull(),
        )
    }

    /** Texto de un registro TXT, acotado y sin caracteres de control: viene de la red. */
    private fun text(bytes: ByteArray?): String =
        bytes?.toString(Charsets.UTF_8).orEmpty().filterNot(Char::isISOControl).trim().take(MAX_TEXT_LENGTH)

    /** Agrega el agente o actualiza el que ya estaba con ese nombre de servicio. */
    fun upsert(list: List<DiscoveredAgent>, agent: DiscoveredAgent): List<DiscoveredAgent> =
        (list.filterNot { it.serviceName == agent.serviceName } + agent)
            .sortedWith(compareBy({ it.displayName.lowercase() }, { it.address }))

    fun remove(list: List<DiscoveredAgent>, serviceName: String): List<DiscoveredAgent> =
        list.filterNot { it.serviceName == serviceName }

    /** Los encontrados que todavía no están guardados, comparando dirección y puerto. */
    fun unsaved(discovered: List<DiscoveredAgent>, saved: List<SavedConnection>): List<DiscoveredAgent> {
        val known = saved.map { it.address }.toSet()
        // El mismo agente puede anunciarse por más de una interfaz; se muestra una vez.
        return discovered.filterNot { it.address in known }.distinctBy { it.address }
    }

    /** Cuántos de los encontrados ya están guardados; distingue "no hay nada" de "no hay nada nuevo". */
    fun savedCount(discovered: List<DiscoveredAgent>, saved: List<SavedConnection>): Int {
        val known = saved.map { it.address }.toSet()
        return discovered.map { it.address }.distinct().count { it in known }
    }

    /** Formulario precargado con lo descubierto. El token queda vacío: no viaja en el anuncio. */
    fun toForm(agent: DiscoveredAgent) = ConnectionForm(
        name = agent.displayName.take(40),
        host = agent.host,
        port = agent.port.toString(),
        token = "",
    )
}
