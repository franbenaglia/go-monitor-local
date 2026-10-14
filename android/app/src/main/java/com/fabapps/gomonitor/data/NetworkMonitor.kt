package com.fabapps.gomonitor.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.fabapps.gomonitor.core.NetworkStatus
import java.net.Inet4Address

/** Lee a qué redes está conectado el celular, para explicar por qué falla una conexión. */
class NetworkMonitor(context: Context) {
    private val manager = context.getSystemService(ConnectivityManager::class.java)

    fun status(): NetworkStatus = runCatching {
        var onWifi = false
        val subnets = mutableListOf<Pair<String, Int>>()
        for (network in manager.allNetworks) {
            val capabilities = manager.getNetworkCapabilities(network) ?: continue
            // Wi-Fi o Ethernet: las redes locales por las que se puede llegar al agente.
            val local = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            if (!local) continue
            onWifi = true
            manager.getLinkProperties(network)?.linkAddresses?.forEach { link ->
                val address = link.address
                if (address is Inet4Address) {
                    address.hostAddress?.let { subnets += it to link.prefixLength }
                }
            }
        }
        NetworkStatus(onWifi = onWifi, subnets = subnets)
    }.getOrDefault(NetworkStatus.UNKNOWN)
}
