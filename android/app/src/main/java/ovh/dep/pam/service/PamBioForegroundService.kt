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

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import ovh.dep.pam.R
import ovh.dep.pam.crypto.KeyManager
import ovh.dep.pam.data.PairedDeviceRepository
import ovh.dep.pam.network.AuthRequestMessage
import ovh.dep.pam.network.NsdDiscoveryManager
import ovh.dep.pam.network.TcpClient
import ovh.dep.pam.ui.BiometricAuthActivity

/**
 * Foreground service that:
 * 1. Runs mDNS discovery for paired PCs
 * 2. Maintains TCP connections
 * 3. Shows full-screen biometric prompts on auth requests
 */
class PamBioForegroundService : Service() {

    companion object {
        private const val TAG = "PamBioService"
        private const val CHANNEL_SERVICE_ID = "pambio_service_status"
        private const val CHANNEL_AUTH_ID = "pambio_auth_requests"
        private const val NOTIFICATION_ID = 1
        const val ACTION_START = "ovh.dep.pam.START_SERVICE"
        const val ACTION_STOP = "ovh.dep.pam.STOP_SERVICE"

        val isRunning = kotlinx.coroutines.flow.MutableStateFlow(false)
        val connectedDevices = kotlinx.coroutines.flow.MutableStateFlow<Set<String>>(emptySet())
        
        var pendingAuthRequest: AuthRequestMessage? = null
        const val ACTION_CLEAR_AUTH = "ovh.dep.pam.CLEAR_AUTH"
    }

    private lateinit var nsdManager: NsdDiscoveryManager
    private lateinit var keyManager: KeyManager
    private lateinit var deviceRepo: PairedDeviceRepository
    private var tcpClient: TcpClient? = null
    private var currentNotificationText: String? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var connectionJob: Job? = null



    override fun onCreate() {
        super.onCreate()
        keyManager = KeyManager(this)
        deviceRepo = PairedDeviceRepository(this)
        nsdManager = NsdDiscoveryManager(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_CLEAR_AUTH -> {
                pendingAuthRequest = null
                updateNotification(currentNotificationText ?: "")
            }
        }

        isRunning.value = true
        val remaining = connectedDevices.value
        val initialText = if (remaining.isNotEmpty()) {
            getString(R.string.connected_to, remaining.first())
        } else {
            getString(R.string.waiting_connection)
        }
        currentNotificationText = initialText
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(initialText),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
            } else {
                startForeground(NOTIFICATION_ID, buildNotification(initialText))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground", e)
        }
        nsdManager.startDiscovery()



        // Watch for discovered services and connect
        connectionJob = scope.launch { watchAndConnect() }

        return START_STICKY
    }

    override fun onDestroy() {
        isRunning.value = false
        connectionJob?.cancel()
        scope.cancel()
        nsdManager.stopDiscovery()
        tcpClient?.disconnect()

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Connection management ──────────────────────────────

    private val connectionJobs = mutableMapOf<String, Job>()

    private suspend fun watchAndConnect() {
        while (isRunning.value) {
            val services = nsdManager.services.value
            val pairedDevices = deviceRepo.getAll()

            for (device in pairedDevices) {
                val resolved = services[device.serviceName] ?: continue

                // Check if there is already an active job for this device
                if (connectionJobs[device.serviceName]?.isActive == true) continue

                connectionJobs[device.serviceName] = scope.launch {
                    Log.i(TAG, "Found paired service: ${device.serviceName} → ${resolved.host}:${resolved.port}")
                    connectToService(resolved.host, resolved.port, device.serviceName)
                }
            }
            delay(3000)
        }
    }

    private suspend fun connectToService(host: String, port: Int, serviceName: String) {
        val client = TcpClient(keyManager)
        client.onAuthRequest = { authReq -> handleAuthRequest(authReq) }

        try {
            try {
                client.connect(host, port)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to connect to $host:$port: ${e.message}")
                // mDNS record is likely stale. Restart discovery to resolve fresh IP/port.
                nsdManager.restartDiscovery()
                delay(5000)
                return
            }

            // Identify ourselves
            if (!client.sendIdentify()) {
                Log.e(TAG, "Identify rejected by $host:$port")
                val repo = PairedDeviceRepository(this@PamBioForegroundService)
                repo.removeDevice(serviceName)
                return
            }

            client.isStillPaired = {
                val repo = PairedDeviceRepository(this@PamBioForegroundService)
                repo.getAll().any { it.serviceName == serviceName }
            }

            tcpClient = client
            BiometricAuthActivity.activeTcpClient = client
            connectedDevices.value = connectedDevices.value + serviceName
            updateNotification(getString(R.string.connected_to, serviceName))
            Log.i(TAG, "Connected and identified to $host:$port")

            // Listen for auth requests (blocks until disconnected)
            client.listenForMessages()
        } finally {
            client.disconnect()
            if (tcpClient == client) {
                tcpClient = null
            }
            if (BiometricAuthActivity.activeTcpClient == client) {
                BiometricAuthActivity.activeTcpClient = null
            }
            connectedDevices.value = connectedDevices.value - serviceName
            
            val remaining = connectedDevices.value
            if (remaining.isEmpty()) {
                updateNotification(getString(R.string.disconnected))
            } else {
                updateNotification(getString(R.string.connected_to, remaining.first()))
            }
            Log.i(TAG, "Disconnected from $host:$port")
        }
    }

    // ── Auth request handling ──────────────────────────────

    private fun handleAuthRequest(authReq: AuthRequestMessage) {
        Log.i(TAG, "Auth request: user=${authReq.user} service=${authReq.service}")

        // Launch BiometricAuthActivity as a full-screen intent
        val intent = Intent(this, BiometricAuthActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(BiometricAuthActivity.EXTRA_NONCE, authReq.nonce)
            putExtra(BiometricAuthActivity.EXTRA_USER, authReq.user)
            putExtra(BiometricAuthActivity.EXTRA_SERVICE, authReq.service)
            putExtra(BiometricAuthActivity.EXTRA_TIMESTAMP, authReq.timestamp)
        }

        // Full-screen notification for lock screen
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
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

        // Also update the persistent notification to show the "Approve" button
        pendingAuthRequest = authReq
        updateNotification(currentNotificationText ?: "")
    }

    // ── Notification ───────────────────────────────────────

    private fun createNotificationChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Low importance channel for service status (no sound/vibration)
        val serviceChannel = NotificationChannel(
            CHANNEL_SERVICE_ID,
            getString(R.string.channel_service_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.channel_service_desc)
            setShowBadge(false)
        }
        nm.createNotificationChannel(serviceChannel)

        // High importance channel for auth requests (sound/vibration)
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
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val mainIntent = Intent().apply {
            setClass(this@PamBioForegroundService, ovh.dep.pam.MainActivity::class.java)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val mainPending = PendingIntent.getActivity(
            this, 0, mainIntent,
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
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(BiometricAuthActivity.EXTRA_NONCE, authReq.nonce)
                putExtra(BiometricAuthActivity.EXTRA_USER, authReq.user)
                putExtra(BiometricAuthActivity.EXTRA_SERVICE, authReq.service)
                putExtra(BiometricAuthActivity.EXTRA_TIMESTAMP, authReq.timestamp)
            }
            val authPending = PendingIntent.getActivity(
                this, 1, authIntent,
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

    private fun updateNotification(text: String) {
        if (currentNotificationText == text) return
        currentNotificationText = text
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }
}
