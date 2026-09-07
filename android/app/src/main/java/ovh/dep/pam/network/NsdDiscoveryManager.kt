/*
 * Copyright 2026 Nikita Denissov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


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

    private var currentDiscoveryListener: NsdManager.DiscoveryListener? = null

    private fun createDiscoveryListener() = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) {
            Log.d(TAG, "Discovery started for $serviceType")
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
        }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.e(TAG, "Start discovery failed: error $errorCode")
            // If the current listener failed, clear it so we can try again
            if (currentDiscoveryListener == this) {
                currentDiscoveryListener = null
            }
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

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** Starts mDNS discovery. Safe to call multiple times. */
    fun startDiscovery() {
        mainHandler.post {
            if (currentDiscoveryListener != null) return@post
            
            val listener = createDiscoveryListener()
            currentDiscoveryListener = listener
            try {
                nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start discovery", e)
                currentDiscoveryListener = null
            }
        }
    }

    /** Stops mDNS discovery. */
    fun stopDiscovery() {
        mainHandler.post {
            val listener = currentDiscoveryListener ?: return@post
            currentDiscoveryListener = null
            try {
                nsdManager.stopServiceDiscovery(listener)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop discovery", e)
            }
        }
    }

    /** Restarts discovery (e.g. after Wi-Fi change or stale connection). */
    fun restartDiscovery() {
        stopDiscovery()
        _services.value = emptyMap()
        startDiscovery()
    }
}
