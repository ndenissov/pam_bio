package ovh.dep.pam.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Resolved mDNS service info for a PamBio PC.
 */
data class ResolvedService(
    val serviceName: String,
    val host: String,
    val port: Int
)

/**
 * Discovers PamBio services (`_pambio._tcp`) on the local network using
 * Android's NsdManager. Emits discovered/lost events as a StateFlow.
 */
class NsdDiscoveryManager(context: Context) {

    companion object {
        private const val TAG = "NsdDiscovery"
        private const val SERVICE_TYPE = "_pambio._tcp."
    }

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    private val _services = MutableStateFlow<Map<String, ResolvedService>>(emptyMap())
    /** Currently discovered services, keyed by service name. */
    val services: StateFlow<Map<String, ResolvedService>> = _services

    private var isDiscovering = false

    private val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) {
            Log.d(TAG, "Discovery started for $serviceType")
            isDiscovering = true
        }

        override fun onServiceFound(info: NsdServiceInfo) {
            Log.d(TAG, "Service found: ${info.serviceName}")
            // Resolve to get IP and port
            nsdManager.resolveService(info, resolveListener())
        }

        override fun onServiceLost(info: NsdServiceInfo) {
            Log.d(TAG, "Service lost: ${info.serviceName}")
            _services.value = _services.value - info.serviceName
        }

        override fun onDiscoveryStopped(serviceType: String) {
            Log.d(TAG, "Discovery stopped")
            isDiscovering = false
        }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.e(TAG, "Start discovery failed: error $errorCode")
            isDiscovering = false
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.e(TAG, "Stop discovery failed: error $errorCode")
        }
    }

    private fun resolveListener() = object : NsdManager.ResolveListener {
        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
            Log.e(TAG, "Resolve failed for ${info.serviceName}: error $errorCode")
        }

        override fun onServiceResolved(info: NsdServiceInfo) {
            val host = info.host?.hostAddress ?: return
            val port = info.port
            Log.i(TAG, "Resolved: ${info.serviceName} → $host:$port")

            val resolved = ResolvedService(
                serviceName = info.serviceName,
                host = host,
                port = port
            )
            _services.value = _services.value + (info.serviceName to resolved)
        }
    }

    /** Starts mDNS discovery. Safe to call multiple times. */
    fun startDiscovery() {
        if (isDiscovering) return
        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start discovery", e)
        }
    }

    /** Stops mDNS discovery. */
    fun stopDiscovery() {
        if (!isDiscovering) return
        try {
            nsdManager.stopServiceDiscovery(discoveryListener)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop discovery", e)
        }
    }

    /** Restarts discovery (e.g. after Wi-Fi change). */
    fun restartDiscovery() {
        stopDiscovery()
        _services.value = emptyMap()
        startDiscovery()
    }
}
