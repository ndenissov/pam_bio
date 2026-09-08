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


package ovh.dep.pam

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import android.content.Intent
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.IvParameterSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import ovh.dep.pam.data.AppPrefs
import ovh.dep.pam.data.PairedDeviceRepository
import ovh.dep.pam.service.PamBioForegroundService
import ovh.dep.pam.ui.about.AboutScreen
import ovh.dep.pam.ui.home.HomeScreen
import ovh.dep.pam.ui.history.HistoryScreen
import ovh.dep.pam.ui.settings.SettingsScreen
import ovh.dep.pam.ui.scan.QrScanScreen
import ovh.dep.pam.ui.scan.QrScanScreen
import ovh.dep.pam.ui.theme.LinuxBiopamTheme

class MainActivity : AppCompatActivity() {

    companion object {
        private const val AUTH_KEY_ALIAS = "pam_bio_auth_key"
        private const val AUTH_PREFS = "pam_bio_auth_prefs"
        private const val AUTH_IV = "auth_iv"
        private const val AUTH_CT = "auth_ct"
        private const val CIPHER_TRANSFORMATION = "AES/CBC/PKCS7Padding"
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = keyStore.getKey(AUTH_KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val spec = KeyGenParameterSpec.Builder(
            AUTH_KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_CBC)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_PKCS7)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    private fun getCipher(): Cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)

    fun getOrCreateEncryptedProbe(): Pair<ByteArray, ByteArray> {
        val prefs = getSharedPreferences(AUTH_PREFS, MODE_PRIVATE)
        val ivB64 = prefs.getString(AUTH_IV, null)
        val ctB64 = prefs.getString(AUTH_CT, null)

        if (ivB64 != null && ctB64 != null) {
            return Base64.decode(ivB64, Base64.DEFAULT) to Base64.decode(ctB64, Base64.DEFAULT)
        }

        val cipher = getCipher()
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val ciphertext = cipher.doFinal("pam-auth-probe".toByteArray(Charsets.UTF_8))
        val iv = cipher.iv

        prefs.edit()
            .putString(AUTH_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
            .putString(AUTH_CT, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .apply()

        return iv to ciphertext
    }

    fun buildDecryptCipher(iv: ByteArray): Cipher {
        val cipher = getCipher()
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), IvParameterSpec(iv))
        return cipher
    }

    override fun onResume() {
        super.onResume()
        val prefs = AppPrefs(this)
        if (prefs.serviceEnabled && !PamBioForegroundService.isRunning.value) {
            val intent = Intent(this, PamBioForegroundService::class.java).apply {
                action = PamBioForegroundService.ACTION_START
            }
            startForegroundService(intent)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val denied = results.filterValues { !it }.keys
        if (denied.isNotEmpty()) {
            Toast.makeText(this, getString(R.string.permissions_denied, denied), Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestPermissions()
        checkFullScreenIntentPermission()

        lifecycleScope.launch {
            // Sync authCount from history to fix initial state
            val historyRepo = ovh.dep.pam.data.history.AuthHistoryRepository(this@MainActivity)
            val prefs = ovh.dep.pam.data.AppPrefs(this@MainActivity)
            val historySize = historyRepo.getHistory().first().size
            if (prefs.authCount < historySize) {
                prefs.authCount = historySize
            }

            val pairedDevices = PairedDeviceRepository(this@MainActivity).getAll()
            if (pairedDevices.isNotEmpty()) {
                val serviceIntent = android.content.Intent(this@MainActivity, PamBioForegroundService::class.java)
                ContextCompat.startForegroundService(this@MainActivity, serviceIntent)
                setAppContent(requireAuth = true)
            } else {
                setAppContent(requireAuth = false)
            }
        }
    }

    private fun setAppContent(requireAuth: Boolean) {
        setContent {
            LinuxBiopamTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var isAuthenticated by remember { mutableStateOf(!requireAuth) }
                    
                    if (isAuthenticated) {
                        PamBioNavigation()
                    } else {
                        UnlockScreen(onUnlockSuccess = { isAuthenticated = true })
                    }
                }
            }
        }
    }

    private fun checkFullScreenIntentPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val notificationManager = getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            if (!notificationManager.canUseFullScreenIntent()) {
                val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                    data = android.net.Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
        }
    }


    private fun requestPermissions() {
        val needed = mutableListOf<String>()

        // Camera
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            needed += Manifest.permission.CAMERA
        }

        // Notifications (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                needed += Manifest.permission.POST_NOTIFICATIONS
            }
        }

        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }
}

@Composable
private fun PamBioNavigation() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = "home") {
        composable("home") {
            HomeScreen(
                onNavigateToScan = { navController.navigate("scan") },
                onNavigateToHistory = { navController.navigate("history") },
                onNavigateToSettings = { navController.navigate("settings") }
            )
        }
        composable("scan") {
            QrScanScreen(
                onNavigateBack = { navController.popBackStack() },
                onPairingComplete = { serviceName ->
                    navController.popBackStack()
                }
            )
        }
        composable("history") {
            HistoryScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable("about") {
            AboutScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable("settings") {
            SettingsScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToAbout = { navController.navigate("about") }
            )
        }
    }
}

@Composable
private fun UnlockScreen(onUnlockSuccess: () -> Unit) {
    val context = LocalContext.current as AppCompatActivity
    
    val showAuth = {
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        val canAuth = BiometricManager.from(context).canAuthenticate(authenticators)

        if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) {
            onUnlockSuccess()
        } else {
            val activity = context as MainActivity
            val (iv, ciphertext) = activity.getOrCreateEncryptedProbe()
            val decryptCipher = activity.buildDecryptCipher(iv)

            val executor = ContextCompat.getMainExecutor(context)
            val prompt = BiometricPrompt(context, executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        val cipher = result.cryptoObject?.cipher ?: return
                        try {
                            cipher.doFinal(ciphertext)
                            onUnlockSuccess()
                        } catch (_: Exception) {
                            // Authentication did not unlock the keystore-backed key operation.
                        }
                    }
                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        // Just let the user retry by clicking the button
                    }
                })
            val promptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle(context.getString(R.string.unlock_pambio))
                .setSubtitle(context.getString(R.string.unlock_subtitle))
                .setAllowedAuthenticators(authenticators)
                .build()
            prompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(decryptCipher))
        }
    }

    LaunchedEffect(Unit) {
        showAuth()
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = { showAuth() }) {
                Text(stringResource(R.string.unlock_pambio))
            }
        }
    }
}