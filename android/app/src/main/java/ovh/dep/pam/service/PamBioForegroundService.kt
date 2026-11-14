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


package ovh.dep.pam.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import ovh.dep.pam.MainActivity
import ovh.dep.pam.R
import ovh.dep.pam.crypto.KeyManager
import ovh.dep.pam.data.PairedDevice
import ovh.dep.pam.data.PairedDeviceRepository
import ovh.dep.pam.network.AuthRequestMessage
import ovh.dep.pam.network.EndpointCandidate
import ovh.dep.pam.network.EndpointSelector
import ovh.dep.pam.network.IdentifyResult
import ovh.dep.pam.network.NsdDiscoveryManager
import ovh.dep.pam.network.TcpClient
import ovh.dep.pam.network.UdpDiscoveryManager
import ovh.dep.pam.ui.BiometricAuthActivity
import java.util.concurrent.ConcurrentHashMap

/**
 * Foreground service that:
 * 1. Runs fast UDP broadcast/unicast discovery + mDNS discovery for paired PCs
 * 2. Maintains persistent encrypted TCP connections per paired PC
 * 3. Reconnects immediately on app open, service restart, network changes, or PC wakeup knocks
 * 4. Shows full-screen biometric prompts on auth requests
 */
class PamBioForegroundService : Service() {

    companion object {
        private const val TAG = "PamBioService"
        private const val CHANNEL_SERVICE_ID = "pambio_service_status"
        private const val CHANNEL_AUTH_ID = "pambio_auth_requests"
        private const val NOTIFICATION_ID = 1
        const val ACTION_START = "ovh.dep.pam.START_SERVICE"
        const val ACTION_STOP = "ovh.dep.pam.STOP_SERVICE"
        const val ACTION_RESTART = "ovh.dep.pam.RESTART_SERVICE"
        const val ACTION_NUDGE = "ovh.dep.pam.NUDGE_SERVICE"
        const val ACTION_CLEAR_AUTH = "ovh.dep.pam.CLEAR_AUTH"

        val isRunning = MutableStateFlow(false)
        val connectedDevices = MutableStateFlow<Set<String>>(emptySet())

        @Volatile
        var pendingAuthRequest: AuthRequestMessage? = null
    }

    private lateinit var nsdManager: NsdDiscoveryManager
    private lateinit var udpDiscovery: UdpDiscoveryManager
    private lateinit var keyManager: KeyManager
    private lateinit var deviceRepo: PairedDeviceRepository
    private lateinit var connectivityManager: ConnectivityManager
    private var multicastLock: WifiManager.MulticastLock? = null

    private var foregroundStarted = false
    private var currentNotificationText: String? = null

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var deviceWatcherJob: Job? = null
    private var discoveryObserverJob: Job? = null

    private val latestDevices = ConcurrentHashMap<String, PairedDevice>()
    private val deviceJobs = ConcurrentHashMap<String, Job>()
    private val activeClients = ConcurrentHashMap<String, TcpClient>()
    private val deviceNudges = ConcurrentHashMap<String, Channel<Unit>>()
    private val deviceBackoffs = ConcurrentHashMap<String, ReconnectBackoff>()
    private val recentNonces = RecentNonceCache()

    @Volatile
    private var networkCallbackRegistered = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Log.d(TAG, "Network available, nudging discovery & reconnect")
            nudgeAll(restartMdns = true, resetBackoff = true)
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            Log.d(TAG, "Link properties changed, nudging discovery & reconnect")
            nudgeAll(restartMdns = false, resetBackoff = true)
        }

        override fun onLost(network: Network) {
            Log.d(TAG, "Network lost")
        }
    }

    override fun onCreate() {
        super.onCreate()
        keyManager = KeyManager(this)
        deviceRepo = PairedDeviceRepository(this)
        nsdManager = NsdDiscoveryManager(this)
        udpDiscovery = UdpDiscoveryManager()
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        multicastLock = wifiManager?.createMulticastLock("PamBio:MulticastLock")?.apply {
            setReferenceCounted(false)
        }

        createNotificationChannel()
        registerNetworkCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopNetworking()
                isRunning.value = false
                foregroundStarted = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_CLEAR_AUTH -> {
                pendingAuthRequest = null
                if (!isRunning.value) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                updateNotification(currentNotificationText ?: statusNotificationText(), force = true)
                return START_STICKY
            }
            ACTION_RESTART -> {
                ensureForegroundStarted()
                performRestart()
                return START_STICKY
            }
            ACTION_NUDGE -> {
                if (!isRunning.value) {
                    ensureForegroundStarted()
                    startNetworking()
                } else {
                    nudgeAll(restartMdns = disconnectedDeviceCount() > 0, resetBackoff = true)
                }
                return START_STICKY
            }
        }

        // ACTION_START or null (START_STICKY restart by Android)
        ensureForegroundStarted()
        if (isRunning.value && deviceWatcherJob?.isActive == true) {
            nudgeAll(restartMdns = false, resetBackoff = true)
        } else {
            startNetworking()
        }
        return START_STICKY
    }

    private fun ensureForegroundStarted() {
        isRunning.value = true
        val initialText = statusNotificationText()
        if (foregroundStarted) return
        currentNotificationText = initialText
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(initialText),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
            } else {
                startForeground(NOTIFICATION_ID, buildNotification(initialText))
            }
            foregroundStarted = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground", e)
        }
    }

    private fun startNetworking() {
        isRunning.value = true
        nsdManager.startDiscovery()
        udpDiscovery.start()
        updateMulticastLock()

        deviceWatcherJob?.cancel()
        discoveryObserverJob?.cancel()

        deviceWatcherJob = scope.launch {
            deviceRepo.devicesFlow.collect { devices ->
                syncPairedDevices(devices)
            }
        }

        discoveryObserverJob = scope.launch {
            launch {
                udpDiscovery.announceEvents.collect { announced ->
                    for (dev in latestDevices.values) {
                        if (dev.serviceName == announced.serviceName ||
                            (announced.pcPubKey.isNotBlank() && dev.pcPubKey == announced.pcPubKey)
                        ) {
                            nudgeDevice(dev.serviceName, resetBackoff = true)
                        }
                    }
                }
            }
            launch {
                nsdManager.serviceUpdates.collect { resolved ->
                    if (latestDevices.containsKey(resolved.serviceName)) {
                        nudgeDevice(resolved.serviceName, resetBackoff = true)
                    }
                }
            }
        }
    }

    private fun stopNetworking() {
        deviceWatcherJob?.cancel()
        deviceWatcherJob = null
        discoveryObserverJob?.cancel()
        discoveryObserverJob = null

        deviceJobs.values.forEach { it.cancel() }
        deviceJobs.clear()
        latestDevices.clear()
        deviceBackoffs.clear()
        deviceNudges.clear()

        activeClients.values.forEach { client ->
            client.disconnect()
            BiometricAuthActivity.unregisterClient(client)
        }
        activeClients.clear()
        connectedDevices.value = emptySet()

        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        nsdManager.stopDiscovery()
        udpDiscovery.stop()
        releaseMulticastLock()
    }

    private fun performRestart() {
        Log.i(TAG, "Performing in-place restart")
        stopNetworking()
        isRunning.value = true
        nsdManager.restartDiscovery()
        udpDiscovery.start()
        startNetworking()
        updateNotification(getString(R.string.waiting_connection), force = true)
    }

    override fun onDestroy() {
        unregisterNetworkCallback()
        isRunning.value = false
        foregroundStarted = false
        stopNetworking()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Device lifecycle & reconnection ────────────────────

    private fun syncPairedDevices(devices: List<PairedDevice>) {
        val incomingNames = devices.map { it.serviceName }.toSet()

        // Tear down removed devices immediately
        val removed = latestDevices.keys.filter { it !in incomingNames }
        for (name in removed) {
            latestDevices.remove(name)
            deviceJobs.remove(name)?.cancel()
            deviceNudges.remove(name)
            deviceBackoffs.remove(name)
            activeClients.remove(name)?.let { client ->
                client.disconnect()
                BiometricAuthActivity.unregisterClient(client)
            }
            connectedDevices.value = connectedDevices.value - name
        }

        // Register or start loops for active paired devices
        for (device in devices) {
            val prev = latestDevices.put(device.serviceName, device)
            val existingJob = deviceJobs[device.serviceName]
            if (existingJob == null || !existingJob.isActive) {
                deviceNudges.putIfAbsent(device.serviceName, Channel(Channel.CONFLATED))
                deviceBackoffs.putIfAbsent(device.serviceName, ReconnectBackoff())
                deviceJobs[device.serviceName] = scope.launch {
                    manageDeviceConnection(device.serviceName)
                }
            } else if (prev == null) {
                nudgeDevice(device.serviceName, resetBackoff = true)
            }
        }

        updateMulticastLock()
        updateNotification(statusNotificationText())
    }

    private suspend fun manageDeviceConnection(serviceName: String) {
        val nudgeChannel = deviceNudges.getOrPut(serviceName) { Channel(Channel.CONFLATED) }
        val backoff = deviceBackoffs.getOrPut(serviceName) { ReconnectBackoff() }
        var attemptsWithoutDiscovery = 0

        while (scope.isActive && isRunning.value) {
            val device = latestDevices[serviceName] ?: break

            val udpMatch = udpDiscovery.findMatch(device.serviceName, device.pcPubKey)
            val nsdMatch = nsdManager.services.value[device.serviceName]
            val candidates = EndpointSelector.buildCandidates(
                udpService = udpMatch,
                nsdService = nsdMatch,
                lastHost = device.lastHost,
                lastPort = device.lastPort
            )

            // Always emit a fast UDP discover probe when disconnected so if the
            // PC changed IP or just started up it announces back in milliseconds.
            if (udpMatch == null) {
                scope.launch {
                    val targets = listOfNotNull(device.lastHost.takeIf { it.isNotBlank() })
                    udpDiscovery.sendDiscover(targetHosts = targets)
                }
            }

            if (candidates.isEmpty()) {
                attemptsWithoutDiscovery++
                if (attemptsWithoutDiscovery == 2) {
                    nsdManager.restartDiscovery()
                }
                val waitMs = backoff.nextDelayMs().coerceAtLeast(1_500L)
                withTimeoutOrNull(waitMs) { nudgeChannel.receive() }
                continue
            }

            var wasVerifiedRejected = false
            var connectedOnce = false

            for (candidate in candidates) {
                if (!scope.isActive || !isRunning.value || !latestDevices.containsKey(serviceName)) {
                    break
                }

                val outcome = connectAndRunSession(device, candidate)
                when (outcome) {
                    SessionOutcome.CONNECTED_THEN_DROPPED -> {
                        connectedOnce = true
                        attemptsWithoutDiscovery = 0
                        break
                    }
                    SessionOutcome.VERIFIED_UNPAIRED -> {
                        wasVerifiedRejected = true
                        break
                    }
                    SessionOutcome.FAILED -> {
                        // Try next candidate
                    }
                }
            }

            if (wasVerifiedRejected) {
                Log.i(TAG, "Device $serviceName verified unpair from PC; removing locally")
                deviceRepo.removeDevice(serviceName)
                break
            }

            if (connectedOnce) {
                // Connection had succeeded and later dropped (e.g. PC reboot/restart).
                // Probe immediately via UDP discover and wait briefly for wakeup knock.
                backoff.resetAfterDrop()
                scope.launch {
                    val targets = listOfNotNull(latestDevices[serviceName]?.lastHost?.takeIf { it.isNotBlank() })
                    udpDiscovery.sendDiscover(targetHosts = targets)
                }
                withTimeoutOrNull(500L) { nudgeChannel.receive() }
            } else {
                attemptsWithoutDiscovery++
                if (attemptsWithoutDiscovery == 2 && nsdMatch == null) {
                    nsdManager.restartDiscovery()
                }
                val waitMs = backoff.nextDelayMs().coerceAtLeast(1_000L)
                withTimeoutOrNull(waitMs) { nudgeChannel.receive() }
            }
        }
    }

    private enum class SessionOutcome {
        CONNECTED_THEN_DROPPED,
        VERIFIED_UNPAIRED,
        FAILED
    }

    private suspend fun connectAndRunSession(
        device: PairedDevice,
        candidate: EndpointCandidate
    ): SessionOutcome {
        val client = TcpClient(keyManager)
        client.onAuthRequest = { authReq -> handleAuthRequest(authReq, client) }
        client.isStillPaired = { latestDevices.containsKey(device.serviceName) }

        return try {
            try {
                client.connect(candidate.host, candidate.port)
            } catch (e: Exception) {
                Log.d(TAG, "Connect to ${candidate.host}:${candidate.port} (${candidate.source}) failed: ${e.message}")
                return SessionOutcome.FAILED
            }

            val identifyResult = client.sendIdentifyVerified(
                expectedPcPubKey = device.pcPubKey,
                trustUnverifiedReject = (candidate.source == EndpointCandidate.Source.NSD)
            )
            when (identifyResult) {
                IdentifyResult.REJECTED_BY_VERIFIED_SERVER -> {
                    return SessionOutcome.VERIFIED_UNPAIRED
                }
                IdentifyResult.REJECTED_UNVERIFIED -> {
                    Log.w(TAG, "Identify unverified/rejected at ${candidate.host}:${candidate.port}, ignoring")
                    return SessionOutcome.FAILED
                }
                IdentifyResult.ACCEPTED -> {
                    // Proceed below
                }
            }

            deviceBackoffs[device.serviceName]?.reset()
            deviceRepo.updateEndpoint(device.serviceName, candidate.host, candidate.port)

            activeClients[device.serviceName] = client
            BiometricAuthActivity.registerClient(nonce = "", client = client)
            connectedDevices.value = connectedDevices.value + device.serviceName
            updateMulticastLock()
            updateNotification(statusNotificationText())
            Log.i(TAG, "Connected and identified to ${device.serviceName} at ${candidate.host}:${candidate.port} via ${candidate.source}")

            client.listenForMessages()
            SessionOutcome.CONNECTED_THEN_DROPPED
        } catch (e: Exception) {
            Log.w(TAG, "Session error for ${device.serviceName}: ${e.message}")
            SessionOutcome.FAILED
        } finally {
            client.disconnect()
            activeClients.remove(device.serviceName, client)
            BiometricAuthActivity.unregisterClient(client)
            connectedDevices.value = connectedDevices.value - device.serviceName
            updateMulticastLock()
            updateNotification(statusNotificationText())
            Log.i(TAG, "Disconnected from ${device.serviceName} (${candidate.host}:${candidate.port})")
        }
    }

    private fun nudgeDevice(serviceName: String, resetBackoff: Boolean) {
        if (resetBackoff) {
            deviceBackoffs[serviceName]?.reset()
        }
        deviceNudges[serviceName]?.trySend(Unit)
    }

    private fun nudgeAll(restartMdns: Boolean, resetBackoff: Boolean) {
        if (!isRunning.value) return
        if (restartMdns) {
            nsdManager.restartDiscovery()
        } else {
            nsdManager.startDiscovery()
        }
        udpDiscovery.start()
        updateMulticastLock()

        val disconnectedTargets = latestDevices.values
            .filter { !connectedDevices.value.contains(it.serviceName) }
            .mapNotNull { it.lastHost.takeIf(String::isNotBlank) }

        if (disconnectedDeviceCount() > 0) {
            scope.launch {
                udpDiscovery.sendDiscover(targetHosts = disconnectedTargets)
            }
        }

        for (name in latestDevices.keys) {
            if (!connectedDevices.value.contains(name)) {
                nudgeDevice(name, resetBackoff = resetBackoff)
            }
        }
    }

    private fun disconnectedDeviceCount(): Int {
        val connected = connectedDevices.value
        return latestDevices.keys.count { it !in connected }
    }

    private fun updateMulticastLock() {
        val lock = multicastLock ?: return
        try {
            val needLock = isRunning.value && disconnectedDeviceCount() > 0
            if (needLock && !lock.isHeld) {
                lock.acquire()
                Log.d(TAG, "Acquired Wi-Fi multicast lock")
            } else if (!needLock && lock.isHeld) {
                lock.release()
                Log.d(TAG, "Released Wi-Fi multicast lock")
            }
        } catch (e: Exception) {
            Log.w(TAG, "MulticastLock error: ${e.message}")
        }
    }

    private fun releaseMulticastLock() {
        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        } catch (_: Exception) {
        }
    }

    private fun registerNetworkCallback() {
        if (networkCallbackRegistered) return
        try {
            val req = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivityManager.registerNetworkCallback(req, networkCallback)
            networkCallbackRegistered = true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register network callback: ${e.message}")
        }
    }

    private fun unregisterNetworkCallback() {
        if (!networkCallbackRegistered) return
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {
        }
        networkCallbackRegistered = false
    }

    // ── Auth request handling ──────────────────────────────

    private fun handleAuthRequest(authReq: AuthRequestMessage, client: TcpClient) {
        if (!recentNonces.markIfNew(authReq.nonce)) {
            Log.d(TAG, "Ignoring duplicate auth request nonce=${authReq.nonce}")
            return
        }
        Log.i(TAG, "Auth request: user=${authReq.user} service=${authReq.service}")
        BiometricAuthActivity.registerClient(authReq.nonce, client)

        val intent = Intent().apply {
            setClass(this@PamBioForegroundService, BiometricAuthActivity::class.java)
            setPackage(packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(BiometricAuthActivity.EXTRA_NONCE, authReq.nonce)
            putExtra(BiometricAuthActivity.EXTRA_USER, authReq.user)
            putExtra(BiometricAuthActivity.EXTRA_SERVICE, authReq.service)
            putExtra(BiometricAuthActivity.EXTRA_TIMESTAMP, authReq.timestamp)
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_AUTH_ID)
            .setSmallIcon(R.drawable.ic_stat_name)
            .setContentTitle(getString(R.string.auth_request_title))
            .setContentText(getString(R.string.auth_request_text, authReq.service, authReq.user))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(pendingIntent, true)
            .setAutoCancel(true)
            .build()

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(2, notification)

        pendingAuthRequest = authReq
        updateNotification(currentNotificationText ?: statusNotificationText(), force = true)
    }

    // ── Notification ───────────────────────────────────────

    private fun statusNotificationText(): String {
        val remaining = connectedDevices.value
        return if (remaining.isNotEmpty()) {
            getString(R.string.connected_to, remaining.first())
        } else if (latestDevices.isNotEmpty()) {
            getString(R.string.waiting_connection)
        } else {
            getString(R.string.waiting_connection)
        }
    }

    private fun createNotificationChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val serviceChannel = NotificationChannel(
            CHANNEL_SERVICE_ID,
            getString(R.string.channel_service_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.channel_service_desc)
            setShowBadge(false)
        }
        nm.createNotificationChannel(serviceChannel)

        val authChannel = NotificationChannel(
            CHANNEL_AUTH_ID,
            getString(R.string.channel_auth_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = getString(R.string.channel_auth_desc)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        nm.createNotificationChannel(authChannel)
    }

    private fun buildNotification(text: String): Notification {
        val stopIntent = Intent().apply {
            setClass(this@PamBioForegroundService, PamBioForegroundService::class.java)
            setPackage(packageName)
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val mainIntent = Intent().apply {
            setClass(this@PamBioForegroundService, MainActivity::class.java)
            setPackage(packageName)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val mainPending = PendingIntent.getActivity(
            this,
            0,
            mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_SERVICE_ID)
            .setSmallIcon(R.drawable.ic_stat_name)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(mainPending)
            .addAction(0, getString(R.string.stop), stopPending)

        pendingAuthRequest?.let { authReq ->
            val authIntent = Intent().apply {
                setClass(this@PamBioForegroundService, BiometricAuthActivity::class.java)
                setPackage(packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(BiometricAuthActivity.EXTRA_NONCE, authReq.nonce)
                putExtra(BiometricAuthActivity.EXTRA_USER, authReq.user)
                putExtra(BiometricAuthActivity.EXTRA_SERVICE, authReq.service)
                putExtra(BiometricAuthActivity.EXTRA_TIMESTAMP, authReq.timestamp)
            }
            val authPending = PendingIntent.getActivity(
                this,
                1,
                authIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(
                R.drawable.ic_stat_name,
                getString(R.string.auth_approve),
                authPending
            )
        }

        return builder.build()
    }

    private fun updateNotification(text: String, force: Boolean = false) {
        if (!force && currentNotificationText == text) return
        currentNotificationText = text
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }
}
