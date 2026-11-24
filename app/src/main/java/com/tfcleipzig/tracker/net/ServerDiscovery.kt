package com.tfcleipzig.tracker.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.HttpUrl
import java.net.Inet4Address

/**
 * Finds the TFCStreamServer (linux app) via mDNS `_http._tcp.` and exposes its base URL.
 * Simplified version of the tablet app's NsdHelper: no health checks yet.
 */
class ServerDiscovery(
    private val context: Context,
) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val _server = MutableStateFlow<HttpUrl?>(null)
    val server: StateFlow<HttpUrl?> = _server.asStateFlow()

    private var listener: NsdManager.DiscoveryListener? = null

    /** Follows the found server's address and port; null while none is found. */
    private var watch: NsdManager.ServiceInfoCallback? = null

    fun start() {
        if (listener != null) return
        val l =
            object : NsdManager.DiscoveryListener {
                override fun onServiceFound(info: NsdServiceInfo) {
                    if (info.serviceName.equals(SERVICE_NAME, ignoreCase = true)) follow(info)
                }

                override fun onServiceLost(info: NsdServiceInfo) = Unit

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
        unfollow()
    }

    /** Discovery calls back on its own thread; the callback below on the main thread. */
    @Synchronized
    private fun follow(info: NsdServiceInfo) {
        if (watch != null) return
        val cb =
            object : NsdManager.ServiceInfoCallback {
                override fun onServiceUpdated(updated: NsdServiceInfo) {
                    val addresses = updated.hostAddresses
                    val host = (addresses.firstOrNull { it is Inet4Address } ?: addresses.firstOrNull())?.hostAddress ?: return
                    val url =
                        HttpUrl
                            .Builder()
                            .scheme("http")
                            .host(host)
                            .port(updated.port)
                            .build()
                    if (url != _server.value) Log.i(TAG, "server at $url")
                    _server.value = url
                }

                override fun onServiceLost() {
                    Log.i(TAG, "server lost")
                    _server.value = null
                    unfollow()
                }

                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                    Log.w(TAG, "resolve failed: $errorCode")
                    synchronized(this@ServerDiscovery) { if (watch === this) watch = null }
                }

                override fun onServiceInfoCallbackUnregistered() = Unit
            }
        watch = cb
        nsd.registerServiceInfoCallback(info, context.mainExecutor, cb)
    }

    @Synchronized
    private fun unfollow() {
        watch?.let { runCatching { nsd.unregisterServiceInfoCallback(it) } }
        watch = null
    }

    private companion object {
        const val TAG = "ServerDiscovery"
        const val SERVICE_TYPE = "_http._tcp."
        const val SERVICE_NAME = "TFCStreamServer"
    }
}
