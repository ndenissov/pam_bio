package ovh.dep.pam.ui

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
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
import ovh.dep.pam.network.TcpClient
import ovh.dep.pam.ui.theme.LinuxBiopamTheme

/**
 * Activity shown over the lock screen when the daemon sends an auth request.
 * Displays a BiometricPrompt and sends the result back via the service's TCP
 * connection.
 */
class BiometricAuthActivity : FragmentActivity() {

    companion object {
        private const val TAG = "BiometricAuth"
        const val EXTRA_NONCE = "nonce"
        const val EXTRA_USER = "user"
        const val EXTRA_SERVICE = "service"

        // Shared reference so the service can set the active TcpClient
        @Volatile
        var activeTcpClient: TcpClient? = null
    }

    private var nonce: String = ""
    private var user: String = ""
    private var serviceName: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setShowWhenLocked(true)
        setTurnScreenOn(true)

        nonce = intent.getStringExtra(EXTRA_NONCE) ?: ""
        user = intent.getStringExtra(EXTRA_USER) ?: "unknown"
        serviceName = intent.getStringExtra(EXTRA_SERVICE) ?: "unknown"

        setContent {
            LinuxBiopamTheme {
                AuthScreen(
                    user = user,
                    service = serviceName,
                    onApprove = { showBiometricPrompt() },
                    onDeny = { sendResponse(approved = false) }
                )
            }
        }

        // Auto-trigger biometric prompt
        showBiometricPrompt()
    }

    private fun showBiometricPrompt() {
        val canAuth = BiometricManager.from(this)
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)

        if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) {
            Toast.makeText(this, "Биометрия недоступна", Toast.LENGTH_SHORT).show()
            sendResponse(approved = false)
            return
        }

        val executor = ContextCompat.getMainExecutor(this)

        val title = when (serviceName) {
            "sudo" -> "Запрос sudo"
            "sddm", "login" -> "Разблокировка экрана"
            else -> "Аутентификация ($serviceName)"
        }

        val prompt = BiometricPrompt(this, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    Log.i(TAG, "Biometric success")
                    sendResponse(approved = true)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    Log.w(TAG, "Biometric error $errorCode: $errString")
                    // Do not close the activity on CANCELED (5) or USER_CANCELED (10).
                    // This happens automatically on the lock screen.
                    if (errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                        sendResponse(approved = false)
                    }
                }

                override fun onAuthenticationFailed() {
                    Log.w(TAG, "Biometric failed (wrong finger?)")
                    // Don't close — let user retry
                }
            })

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle("Пользователь: $user")
            .setNegativeButtonText("Отклонить")
            .build()

        prompt.authenticate(promptInfo)
    }

    private fun sendResponse(approved: Boolean) {
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
    onApprove: () -> Unit,
    onDeny: () -> Unit
) {
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
                        "sudo" -> "Запрос sudo"
                        "sddm", "login" -> "Разблокировка экрана"
                        else -> "Аутентификация"
                    },
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )

                Text(
                    text = "Пользователь: $user\nСервис: $service",
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
                    Text("Подтвердить биометрией", fontSize = 16.sp)
                }

                OutlinedButton(
                    onClick = onDeny,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Filled.Close, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Отклонить", fontSize = 16.sp)
                }
            }
        }
    }
}
