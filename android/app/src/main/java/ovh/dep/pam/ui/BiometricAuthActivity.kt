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


package ovh.dep.pam.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.res.stringResource
import ovh.dep.pam.R
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import ovh.dep.pam.crypto.KeyManager
import ovh.dep.pam.data.history.AuthHistoryItem
import ovh.dep.pam.data.history.AuthHistoryRepository
import ovh.dep.pam.network.TcpClient
import ovh.dep.pam.ui.theme.LinuxBiopamTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Activity shown over the lock screen when the daemon sends an auth request.
 * Displays a BiometricPrompt and sends the result back via the service's TCP
 * connection.
 */
class BiometricAuthActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "BiometricAuth"
        const val EXTRA_NONCE = "nonce"
        const val EXTRA_USER = "user"
        const val EXTRA_SERVICE = "service"
        const val EXTRA_TIMESTAMP = "timestamp"

        // Shared reference so the service can set the active TcpClient
        @Volatile
        var activeTcpClient: TcpClient? = null
    }

    private var nonce: String = ""
    private var user: String = ""
    private var serviceName: String = ""
    private var timestamp: Long = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        nonce = intent.getStringExtra(EXTRA_NONCE) ?: ""
        user = intent.getStringExtra(EXTRA_USER) ?: "unknown"
        serviceName = intent.getStringExtra(EXTRA_SERVICE) ?: "unknown"
        timestamp = intent.getLongExtra(EXTRA_TIMESTAMP, System.currentTimeMillis())

        setContent {
            LinuxBiopamTheme {
                AuthScreen(
                    user = user,
                    service = serviceName,
                    timestamp = timestamp,
                    onApprove = { showBiometricPrompt() },
                    onDeny = { sendResponse(approved = false, method = "none") }
                )
            }
        }
        // Auto-trigger biometric prompt
        showBiometricPrompt()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Tell the service to clear the pending request from its notification
        val intent = Intent(this, ovh.dep.pam.service.PamBioForegroundService::class.java).apply {
            action = ovh.dep.pam.service.PamBioForegroundService.ACTION_CLEAR_AUTH
        }
        try {
            startService(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear pending auth notification", e)
        }
    }

    private fun showBiometricPrompt() {
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        val canAuth = BiometricManager.from(this).canAuthenticate(authenticators)

        if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) {
            Toast.makeText(this, getString(R.string.biometric_unavailable), Toast.LENGTH_SHORT).show()
            sendResponse(approved = false, method = "none")
            return
        }

        val executor = ContextCompat.getMainExecutor(this)

        val title = when (serviceName) {
            "sudo" -> getString(R.string.sudo_request)
            "sddm", "login" -> getString(R.string.screen_unlock)
            else -> getString(R.string.auth_default_param, serviceName)
        }

        val prompt = BiometricPrompt(this, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    Log.i(TAG, "Biometric success")
                    val method = if (result.authenticationType == BiometricPrompt.AUTHENTICATION_RESULT_TYPE_DEVICE_CREDENTIAL) "pin" else "biometric"
                    sendResponse(approved = true, method = method)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    Log.w(TAG, "Biometric error $errorCode: $errString")
                    // Do not close the activity on CANCELED (5) or USER_CANCELED (10).
                    // This happens automatically on the lock screen.
                    if (errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON || errorCode == BiometricPrompt.ERROR_USER_CANCELED) {
                        sendResponse(approved = false, method = "none")
                    }
                }

                override fun onAuthenticationFailed() {
                    Log.w(TAG, "Biometric failed (wrong finger?)")
                    // Don't close — let user retry
                }
            })

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(getString(R.string.user_label, user))
            .setAllowedAuthenticators(authenticators)
            .build()

        prompt.authenticate(promptInfo)
    }

    private fun sendResponse(approved: Boolean, method: String) {
        // Save to history
        val repo = AuthHistoryRepository(this)
        CoroutineScope(Dispatchers.IO).launch {
            repo.addHistoryItem(AuthHistoryItem(
                timestamp = timestamp,
                user = user,
                service = serviceName,
                status = if (approved) "approved" else "denied",
                method = method
            ))
        }

        if (approved) {
            val prefs = ovh.dep.pam.data.AppPrefs(this)
            prefs.incrementAuthCount()
        }

        val client = activeTcpClient
        if (client != null) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    client.sendAuthResponse(nonce, approved)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send response", e)
                }
                withContext(Dispatchers.Main) {
                    // Clear the notification from PamBioForegroundService
                    val nm = getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                    nm.cancel(2)
                    finish()
                }
            }
        } else {
            Log.e(TAG, "No active TCP client to send response")
            finish()
        }
    }
}

// ── Compose UI ─────────────────────────────────────────────

@Composable
private fun AuthScreen(
    user: String,
    service: String,
    timestamp: Long,
    onApprove: () -> Unit,
    onDeny: () -> Unit
) {
    val formatter = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()) }
    val timeStr = remember(timestamp) { formatter.format(Date(timestamp)) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .padding(16.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Fingerprint,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.primary
                )

                Text(
                    text = when (service) {
                        "sudo" -> stringResource(R.string.sudo_request)
                        "sddm", "login" -> stringResource(R.string.screen_unlock)
                        else -> stringResource(R.string.auth_default)
                    },
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )

                Text(
                    text = "${stringResource(R.string.user_label, user)}\n${stringResource(R.string.service_label, service)}\n\n${timeStr}",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = onApprove,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Filled.Fingerprint, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.auth_approve), fontSize = 16.sp)
                }

                OutlinedButton(
                    onClick = onDeny,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Filled.Close, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.auth_deny), fontSize = 16.sp)
                }
            }
        }
    }
}
