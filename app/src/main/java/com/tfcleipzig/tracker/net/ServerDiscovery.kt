package com.tfcleipzig.tracker.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.HttpUrl

/**
 * Finds the TFCStreamServer (linux app) via mDNS `_http._tcp.` and exposes its base URL.
 * Simplified version of the tablet app's NsdHelper: no health checks yet.
 */
class ServerDiscovery(
    context: Context,
) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val _server = MutableStateFlow<HttpUrl?>(null)
    val server: StateFlow<HttpUrl?> = _server.asStateFlow()

    private var listener: NsdManager.DiscoveryListener? = null

    fun start() {
        if (listener != null) return
        val l =
            object : NsdManager.DiscoveryListener {
                override fun onServiceFound(info: NsdServiceInfo) {
                    if (info.serviceName.equals(SERVICE_NAME, ignoreCase = true)) resolve(info)
                }

                override fun onServiceLost(info: NsdServiceInfo) {
                    if (info.serviceName.equals(SERVICE_NAME, ignoreCase = true)) _server.value = null
                }

                override fun onDiscoveryStarted(serviceType: String) = Unit

                override fun onDiscoveryStopped(serviceType: String) = Unit

                override fun onStartDiscoveryFailed(
                    serviceType: String,
                    errorCode: Int,
                ) {
                    Log.w(TAG, "discovery failed: $errorCode")
                    listener = null
                }

                override fun onStopDiscoveryFailed(
                    serviceType: String,
                    errorCode: Int,
                ) = Unit
            }
        listener = l
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l)
    }

    fun stop() {
        listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        listener = null
    }

    @Suppress("DEPRECATION") // registerServiceInfoCallback is API 34+, minSdk is 33.
    private fun resolve(info: NsdServiceInfo) {
        nsd.resolveService(
            info,
            object : NsdManager.ResolveListener {
                override fun onServiceResolved(resolved: NsdServiceInfo) {
                    val host = resolved.host?.hostAddress ?: return
                    _server.value =
                        HttpUrl
                            .Builder()
                            .scheme("http")
                            .host(host)
                            .port(resolved.port)
                            .build()
                    Log.i(TAG, "server at ${_server.value}")
                }

                override fun onResolveFailed(
                    info: NsdServiceInfo,
                    errorCode: Int,
                ) {
                    Log.w(TAG, "resolve failed: $errorCode")
                }
            },
        )
    }

    private companion object {
        const val TAG = "ServerDiscovery"
        const val SERVICE_TYPE = "_http._tcp."
        const val SERVICE_NAME = "TFCStreamServer"
    }
}
