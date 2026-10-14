package com.fabapps.gomonitor.data

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import com.fabapps.gomonitor.core.DiscoveredAgent
import com.fabapps.gomonitor.core.Discovery
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Busca agentes en la red local con el servicio de descubrimiento del sistema (mDNS/DNS-SD).
 *
 * La búsqueda dura mientras alguien recolecta el flujo y se detiene sola al dejar de hacerlo.
 * Es una ayuda: si la red filtra multicast no aparece nada y el usuario carga la IP a mano.
 */
class NsdDiscovery(context: Context) {
    private val nsd: NsdManager? = context.getSystemService(NsdManager::class.java)

    fun agents(): Flow<List<DiscoveredAgent>> = callbackFlow {
        val manager = nsd
        if (manager == null) {
            trySend(emptyList())
            awaitClose { }
            return@callbackFlow
        }

        // Los avisos del sistema llegan desde otro hilo; todo el estado se toca bajo este cerrojo.
        val lock = Any()
        var found = emptyList<DiscoveredAgent>()
        val pending = ArrayDeque<NsdServiceInfo>()
        var resolving = false

        // En versiones de Android anteriores a la 14 solo se puede resolver un servicio a la
        // vez, así que se resuelven en cola.
        fun resolveNext() {
            val next = synchronized(lock) {
                if (resolving) return
                pending.removeFirstOrNull()?.also { resolving = true }
            } ?: return

            val listener = object : NsdManager.ResolveListener {
                override fun onServiceResolved(info: NsdServiceInfo) {
                    Discovery.fromResolved(info.serviceName.orEmpty(), addressesOf(info), info.port, info.attributes.orEmpty())?.let { agent ->
                        trySend(synchronized(lock) { Discovery.upsert(found, agent).also { found = it } })
                    }
                    synchronized(lock) { resolving = false }
                    resolveNext()
                }

                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                    synchronized(lock) { resolving = false }
                    resolveNext()
                }
            }
            @Suppress("DEPRECATION") // el reemplazo solo existe desde Android 14
            runCatching { manager.resolveService(next, listener) }.onFailure {
                synchronized(lock) { resolving = false }
                resolveNext()
            }
        }

        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                synchronized(lock) { pending.addLast(info) }
                resolveNext()
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                val name = info.serviceName ?: return
                trySend(synchronized(lock) { Discovery.remove(found, name).also { found = it } })
            }

            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }

        trySend(emptyList())
        val started = runCatching {
            manager.discoverServices(Discovery.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        }.isSuccess

        awaitClose {
            if (started) runCatching { manager.stopServiceDiscovery(discoveryListener) }
        }
    }

    private fun addressesOf(info: NsdServiceInfo): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            info.hostAddresses.mapNotNull { it.hostAddress }
        } else {
            @Suppress("DEPRECATION")
            listOfNotNull(info.host?.hostAddress)
        }
}
