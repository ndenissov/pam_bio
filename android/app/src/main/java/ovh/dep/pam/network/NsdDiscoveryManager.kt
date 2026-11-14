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
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.Executor

/**
 * Resolved mDNS service info for a PamBio PC.
 */
data class ResolvedService(
    val serviceName: String,
    val host: String,
    val port: Int,
    val hosts: List<String> = listOf(host),
    val pkPrefix: String? = null
)

/**
 * Discovers PamBio services (`_pambio._tcp`) on the local network using
 * Android's NsdManager.
 *
 * Fixes Android NsdManager pitfalls:
 * - Serializes stop/start transitions so `discoverServices` never races with
 *   asynchronous `stopServiceDiscovery`.
 * - Uses `ServiceInfoCallback` on Android 14+ (API 34+) to collect ALL host
 *   addresses (preferring IPv4 over link-local IPv6 `fe80::`) and get live
 *   updates.
 * - Uses a sequential resolve queue on API < 34 so concurrent `resolveService`
 *   calls never drop with `FAILURE_ALREADY_ACTIVE`.
 */
class NsdDiscoveryManager(context: Context) {

    companion object {
        private const val TAG = "NsdDiscovery"
        private const val SERVICE_TYPE = "_pambio._tcp."
        private const val STOP_WATCHDOG_MS = 1500L
    }

    private enum class State {
        STOPPED,
        STARTING,
        STARTED,
        STOPPING
    }

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mainExecutor = Executor { command -> mainHandler.post(command) }

    private val _services = MutableStateFlow<Map<String, ResolvedService>>(emptyMap())

    /** Currently discovered services, keyed by service name. */
    val services: StateFlow<Map<String, ResolvedService>> = _services

    private val _serviceUpdates = MutableSharedFlow<ResolvedService>(extraBufferCapacity = 32)
    val serviceUpdates: SharedFlow<ResolvedService> = _serviceUpdates.asSharedFlow()

    private var state = State.STOPPED
    private var wantRunning = false
    private var currentDiscoveryListener: NsdManager.DiscoveryListener? = null

    // API < 34 sequential resolve queue
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var isResolving = false

    // API 34+ active ServiceInfoCallbacks keyed by normalized service name
    private val serviceInfoCallbacks = mutableMapOf<String, NsdManager.ServiceInfoCallback>()

    private val stopWatchdog = Runnable {
        if (state == State.STOPPING) {
            NetLog.w(TAG, "stopServiceDiscovery callback timed out; resetting state")
            state = State.STOPPED
            currentDiscoveryListener = null
            if (wantRunning) {
                beginDiscoveryLocked()
            }
        }
    }

    /** Starts mDNS discovery. Safe to call multiple times. */
    fun startDiscovery() {
        mainHandler.post {
            wantRunning = true
            if (state == State.STOPPED) {
                beginDiscoveryLocked()
            }
        }
    }

    /** Stops mDNS discovery. */
    fun stopDiscovery() {
        mainHandler.post {
            wantRunning = false
            clearResolveCallbacksLocked()
            if (state == State.STARTED || state == State.STARTING) {
                beginStopLocked()
            }
        }
    }

    /** Restarts discovery (e.g. after Wi-Fi change or stale connection). */
    fun restartDiscovery() {
        mainHandler.post {
            wantRunning = true
            clearResolveCallbacksLocked()
            _services.value = emptyMap()
            when (state) {
                State.STARTED, State.STARTING -> beginStopLocked()
                State.STOPPING -> { /* onDiscoveryStopped will restart since wantRunning == true */ }
                State.STOPPED -> beginDiscoveryLocked()
            }
        }
    }

    private fun beginDiscoveryLocked() {
        if (state != State.STOPPED) return
        val listener = createDiscoveryListener()
        currentDiscoveryListener = listener
        state = State.STARTING
        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            NetLog.e(TAG, "Failed to start discovery", e)
            currentDiscoveryListener = null
            state = State.STOPPED
        }
    }

    private fun beginStopLocked() {
        val listener = currentDiscoveryListener
        if (listener == null) {
            state = State.STOPPED
            if (wantRunning) beginDiscoveryLocked()
            return
        }
        state = State.STOPPING
        mainHandler.removeCallbacks(stopWatchdog)
        mainHandler.postDelayed(stopWatchdog, STOP_WATCHDOG_MS)
        try {
            nsdManager.stopServiceDiscovery(listener)
        } catch (e: Exception) {
            NetLog.e(TAG, "Failed to stop discovery", e)
            mainHandler.removeCallbacks(stopWatchdog)
            currentDiscoveryListener = null
            state = State.STOPPED
            if (wantRunning) beginDiscoveryLocked()
        }
    }

    private fun createDiscoveryListener() = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) {
            mainHandler.post {
                if (currentDiscoveryListener !== this) return@post
                NetLog.d(TAG, "Discovery started for $serviceType")
                state = State.STARTED
                if (!wantRunning) {
                    beginStopLocked()
                }
            }
        }

        override fun onServiceFound(info: NsdServiceInfo) {
            mainHandler.post {
                if (!wantRunning) return@post
                NetLog.d(TAG, "Service found: ${info.serviceName}")
                resolveDiscoveredService(info)
            }
        }

        override fun onServiceLost(info: NsdServiceInfo) {
            mainHandler.post {
                val name = normalizeName(info.serviceName)
                NetLog.d(TAG, "Service lost: $name")
                unregisterServiceInfoCallback(name)
                _services.value = _services.value - name
            }
        }

        override fun onDiscoveryStopped(serviceType: String) {
            mainHandler.post {
                NetLog.d(TAG, "Discovery stopped")
                mainHandler.removeCallbacks(stopWatchdog)
                if (currentDiscoveryListener === this) {
                    currentDiscoveryListener = null
                    state = State.STOPPED
                    if (wantRunning) {
                        beginDiscoveryLocked()
                    }
                }
            }
        }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            mainHandler.post {
                NetLog.e(TAG, "Start discovery failed: error $errorCode")
                if (currentDiscoveryListener === this) {
                    currentDiscoveryListener = null
                    state = State.STOPPED
                }
            }
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            mainHandler.post {
                NetLog.e(TAG, "Stop discovery failed: error $errorCode")
                mainHandler.removeCallbacks(stopWatchdog)
                if (currentDiscoveryListener === this) {
                    currentDiscoveryListener = null
                    state = State.STOPPED
                    if (wantRunning) {
                        beginDiscoveryLocked()
                    }
                }
            }
        }
    }

    private fun resolveDiscoveredService(info: NsdServiceInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val name = normalizeName(info.serviceName)
            if (serviceInfoCallbacks.containsKey(name)) return
            val callback = object : NsdManager.ServiceInfoCallback {
                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                    NetLog.e(TAG, "ServiceInfoCallback reg failed for $name: $errorCode")
                    mainHandler.post { serviceInfoCallbacks.remove(name) }
                }

                override fun onServiceUpdated(serviceInfo: NsdServiceInfo) {
                    val sortedHosts = EndpointSelector.sortInetAddresses(serviceInfo.hostAddresses)
                    val port = serviceInfo.port
                    if (sortedHosts.isEmpty() || port !in 1..65535) return
                    val pkPrefix = serviceInfo.attributes["pk"]?.let { String(it, Charsets.UTF_8) }
                    publishResolved(normalizeName(serviceInfo.serviceName), sortedHosts, port, pkPrefix)
                }

                override fun onServiceLost() {
                    mainHandler.post {
                        _services.value = _services.value - name
                    }
                }

                override fun onServiceInfoCallbackUnregistered() {
                    mainHandler.post {
                        serviceInfoCallbacks.remove(name)
                    }
                }
            }
            serviceInfoCallbacks[name] = callback
            try {
                nsdManager.registerServiceInfoCallback(info, mainExecutor, callback)
            } catch (e: Exception) {
                NetLog.e(TAG, "registerServiceInfoCallback failed for $name", e)
                serviceInfoCallbacks.remove(name)
            }
        } else {
            resolveQueue.addLast(info)
            processNextResolve()
        }
    }

    @Suppress("DEPRECATION")
    private fun processNextResolve() {
        if (isResolving || !wantRunning) return
        val next = resolveQueue.removeFirstOrNull() ?: return
        isResolving = true
        try {
            nsdManager.resolveService(next, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                    NetLog.e(TAG, "Resolve failed for ${info.serviceName}: error $errorCode")
                    mainHandler.post {
                        isResolving = false
                        processNextResolve()
                    }
                }

                override fun onServiceResolved(info: NsdServiceInfo) {
                    val host = info.host?.hostAddress
                    val port = info.port
                    if (!host.isNullOrBlank() && port in 1..65535) {
                        val pkPrefix = info.attributes["pk"]?.let { String(it, Charsets.UTF_8) }
                        val existingHosts = _services.value[normalizeName(info.serviceName)]?.hosts.orEmpty()
                        val merged = EndpointSelector.sortHosts(existingHosts + host)
                        publishResolved(normalizeName(info.serviceName), merged, port, pkPrefix)
                    }
                    mainHandler.post {
                        isResolving = false
                        processNextResolve()
                    }
                }
            })
        } catch (e: Exception) {
            NetLog.e(TAG, "resolveService threw", e)
            isResolving = false
            processNextResolve()
        }
    }

    private fun publishResolved(
        serviceName: String,
        hosts: List<String>,
        port: Int,
        pkPrefix: String?
    ) {
        val primary = hosts.firstOrNull() ?: return
        NetLog.i(TAG, "Resolved: $serviceName -> $hosts:$port")
        val resolved = ResolvedService(
            serviceName = serviceName,
            host = primary,
            port = port,
            hosts = hosts,
            pkPrefix = pkPrefix
        )
        _services.value = _services.value + (serviceName to resolved)
        _serviceUpdates.tryEmit(resolved)
    }

    private fun unregisterServiceInfoCallback(name: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            serviceInfoCallbacks.remove(name)?.let { cb ->
                try {
                    nsdManager.unregisterServiceInfoCallback(cb)
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun clearResolveCallbacksLocked() {
        resolveQueue.clear()
        isResolving = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val callbacks = serviceInfoCallbacks.values.toList()
            serviceInfoCallbacks.clear()
            for (cb in callbacks) {
                try {
                    nsdManager.unregisterServiceInfoCallback(cb)
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun normalizeName(raw: String): String = raw.removePrefix("pambio_")
}
