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
        private const val CHANNEL_ID = "pambio_foreground"
        private const val NOTIFICATION_ID = 1
        const val ACTION_START = "ovh.dep.pam.START_SERVICE"
        const val ACTION_STOP = "ovh.dep.pam.STOP_SERVICE"

        val isRunning = kotlinx.coroutines.flow.MutableStateFlow(false)
    }

    private lateinit var nsdManager: NsdDiscoveryManager
    private lateinit var keyManager: KeyManager
    private lateinit var deviceRepo: PairedDeviceRepository
    private var tcpClient: TcpClient? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var connectionJob: Job? = null

    private val wifiReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.i(TAG, "Network change detected, restarting discovery")
            nsdManager.restartDiscovery()
        }
    }

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
        }

        isRunning.value = true
        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.waiting_connection)))
        nsdManager.startDiscovery()

        // Register for network changes
        @Suppress("DEPRECATION")
        registerReceiver(wifiReceiver, IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION))

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
        try { unregisterReceiver(wifiReceiver) } catch (_: Exception) {}
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
            if (!client.connect(host, port)) {
                Log.e(TAG, "Failed to connect to $host:$port")
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
            updateNotification(getString(R.string.disconnected))
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
        }

        // Full-screen notification for lock screen
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.auth_request_title))
            .setContentText(getString(R.string.auth_request_text, authReq.service, authReq.user))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(pendingIntent, true)
            .setAutoCancel(true)
            .build()

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(2, notification)
    }

    // ── Notification ───────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "PamBio Service",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "PamBio foreground service and auth notifications"
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val stopIntent = Intent(this, PamBioForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .addAction(0, getString(R.string.stop), stopPending)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }
}
