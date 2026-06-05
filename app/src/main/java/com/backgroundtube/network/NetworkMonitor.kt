package com.backgroundtube.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

class NetworkMonitor(
    context: Context,
    private val onStatusChanged: (Boolean) -> Unit
) {
    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private var callback: ConnectivityManager.NetworkCallback? = null

    fun start() {
        if (callback != null) return

        onStatusChanged(isConnected())

        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                onStatusChanged(isConnected())
            }

            override fun onLost(network: Network) {
                onStatusChanged(isConnected())
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                onStatusChanged(isConnected())
            }
        }

        callback = networkCallback
        connectivityManager.registerDefaultNetworkCallback(networkCallback)
    }

    fun stop() {
        val activeCallback = callback ?: return
        runCatching {
            connectivityManager.unregisterNetworkCallback(activeCallback)
        }
        callback = null
    }

    fun isConnected(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
