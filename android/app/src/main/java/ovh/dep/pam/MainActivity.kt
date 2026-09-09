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
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
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
import javax.crypto.spec.GCMParameterSpec
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
import ovh.dep.pam.ui.theme.LinuxBiopamTheme

class MainActivity : AppCompatActivity() {

    companion object {
        private const val AUTH_KEY_ALIAS = "pam_bio_auth_key_v2"
        private const val AUTH_PREFS = "pam_bio_auth_prefs_v2"
        private const val AUTH_IV = "auth_iv"
        private const val AUTH_CT = "auth_ct"
        private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = keyStore.getKey(AUTH_KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val specBuilder = KeyGenParameterSpec.Builder(
            AUTH_KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setUserAuthenticationRequired(true)
            
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            specBuilder.setUserAuthenticationParameters(
                0,
                KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL
            )
        }
        
        keyGenerator.init(specBuilder.build())
        return keyGenerator.generateKey()
    }

    private fun getCipher(): Cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)

    fun getEncryptedProbe(): Pair<ByteArray, ByteArray>? {
        val prefs = getSharedPreferences(AUTH_PREFS, MODE_PRIVATE)
        val ivB64 = prefs.getString(AUTH_IV, null)
        val ctB64 = prefs.getString(AUTH_CT, null)

        if (ivB64 != null && ctB64 != null) {
            return Base64.decode(ivB64, Base64.DEFAULT) to Base64.decode(ctB64, Base64.DEFAULT)
        }
        return null
    }

    fun deleteKeyAndProbe() {
        try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            keyStore.deleteEntry(AUTH_KEY_ALIAS)
        } catch (e: Exception) {}
        getSharedPreferences(AUTH_PREFS, MODE_PRIVATE).edit().clear().apply()
    }

    fun buildEncryptCipher(): Cipher {
        val cipher = getCipher()
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        return cipher
    }

    fun saveEncryptedProbe(iv: ByteArray, ciphertext: ByteArray) {
        getSharedPreferences(AUTH_PREFS, MODE_PRIVATE).edit()
            .putString(AUTH_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
            .putString(AUTH_CT, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .apply()
    }

    fun buildDecryptCipher(iv: ByteArray): Cipher {
        val cipher = getCipher()
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), GCMParameterSpec(128, iv))
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

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
                    val prefs = remember { AppPrefs(this@MainActivity) }
                    val requireBiometric by prefs.getRequireBiometricOnStartFlow().collectAsState(initial = prefs.requireBiometricOnStart)
                    val disableScreenshots by prefs.getDisableScreenshotsFlow().collectAsState(initial = prefs.disableScreenshots)
                    
                    LaunchedEffect(disableScreenshots) {
                        if (disableScreenshots) {
                            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
                        } else {
                            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
                        }
                    }

                    val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
                    val canAuthResult = BiometricManager.from(this@MainActivity).canAuthenticate(authenticators)
                    
                    if (canAuthResult != BiometricManager.BIOMETRIC_SUCCESS) {
                        SecurityRequiredScreen()
                    } else {
                        var isAuthenticated by remember { mutableStateOf(!requireAuth || !requireBiometric) }
                        
                        if (isAuthenticated) {
                            PamBioNavigation()
                        } else {
                            UnlockScreen(onUnlockSuccess = { isAuthenticated = true })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PamBioNavigation() {
    val navController = rememberNavController()

    // Permission checks happen here — after biometric unlock
    PostAuthPermissionChecks()

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

/**
 * Handles post-authentication permission checks:
 * 1. Notification permission (Android 13+) — required for foreground service
 * 2. Full-screen intent permission (Android 14+) — required for lock-screen auth prompts
 */
@Composable
private fun PostAuthPermissionChecks() {
    val context = LocalContext.current

    // ── Notification permission ────────────────────────────────
    var showNotificationDeniedDialog by remember { mutableStateOf(false) }
    var notificationPermissionChecked by remember { mutableStateOf(false) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        notificationPermissionChecked = true
        if (!isGranted) {
            showNotificationDeniedDialog = true
        }
    }

    // ── Full-screen intent permission ──────────────────────────
    var showFullScreenDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        // 1. Check notification permission (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                notificationPermissionChecked = true
            }
        } else {
            notificationPermissionChecked = true
        }
    }

    // 2. Check full-screen intent permission after notification permission is resolved
    LaunchedEffect(notificationPermissionChecked) {
        if (!notificationPermissionChecked) return@LaunchedEffect
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val nm = context.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            if (!nm.canUseFullScreenIntent()) {
                showFullScreenDialog = true
            }
        }
    }

    // ── Notification denied dialog ─────────────────────────────
    if (showNotificationDeniedDialog) {
        AlertDialog(
            onDismissRequest = { showNotificationDeniedDialog = false },
            icon = {
                Icon(
                    Icons.Filled.Notifications,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(32.dp)
                )
            },
            title = { Text(stringResource(R.string.notifications_denied_title)) },
            text = {
                Text(
                    stringResource(R.string.notifications_denied_text),
                    textAlign = TextAlign.Start
                )
            },
            confirmButton = {
                Button(onClick = {
                    showNotificationDeniedDialog = false
                    val intent = Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                        putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                    }
                    context.startActivity(intent)
                }) {
                    Text(stringResource(R.string.open_settings))
                }
            },
            dismissButton = {
                TextButton(onClick = { showNotificationDeniedDialog = false }) {
                    Text(stringResource(R.string.later))
                }
            }
        )
    }

    // ── Full-screen intent dialog ──────────────────────────────
    if (showFullScreenDialog) {
        AlertDialog(
            onDismissRequest = { showFullScreenDialog = false },
            title = { Text(stringResource(R.string.fullscreen_permission_title)) },
            text = {
                Text(
                    stringResource(R.string.fullscreen_permission_text),
                    textAlign = TextAlign.Start
                )
            },
            confirmButton = {
                Button(onClick = {
                    showFullScreenDialog = false
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        val intent = Intent(android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                            data = android.net.Uri.parse("package:${context.packageName}")
                        }
                        context.startActivity(intent)
                    }
                }) {
                    Text(stringResource(R.string.allow))
                }
            },
            dismissButton = {
                TextButton(onClick = { showFullScreenDialog = false }) {
                    Text(stringResource(R.string.later))
                }
            }
        )
    }
}

@Composable
private fun SecurityRequiredScreen() {
    val context = LocalContext.current
    Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.Warning, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.error)
            Spacer(modifier = Modifier.height(24.dp))
            Text(stringResource(R.string.security_required_title), style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(16.dp))
            Text(stringResource(R.string.security_required_desc), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            Spacer(modifier = Modifier.height(32.dp))
            Button(onClick = {
                context.startActivity(android.content.Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS))
            }) {
                Text(stringResource(R.string.open_security_settings))
            }
        }
    }
}

@Composable
private fun UnlockScreen(onUnlockSuccess: () -> Unit) {
    val context = LocalContext.current
    val titleStr = stringResource(R.string.unlock_pambio)
    val subtitleStr = stringResource(R.string.unlock_subtitle)
    
    val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL

    val showAuth = {
        val activity = context.findActivity() as? MainActivity
        if (activity != null) {
            var currentProbe = activity.getEncryptedProbe()
            val authCipher = try {
                if (currentProbe == null) {
                    activity.buildEncryptCipher()
                } else {
                    val c = activity.buildEncryptCipher()
                    c.doFinal("pam-auth-probe".toByteArray(Charsets.UTF_8))
                    c
                }
            } catch (e: Exception) {
                activity.deleteKeyAndProbe()
                activity.buildEncryptCipher()
            }

            val executor = ContextCompat.getMainExecutor(context)
            val prompt = BiometricPrompt(activity, executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        val cipher = result.cryptoObject?.cipher ?: return
                        try {
                            if (currentProbe == null) {
                                val ciphertext = cipher.doFinal("pam-auth-probe".toByteArray(Charsets.UTF_8))
                                activity.saveEncryptedProbe(cipher.iv, ciphertext)
                            } else {
                                cipher.doFinal(currentProbe!!.second)
                            }
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
                .setTitle(titleStr)
                .setSubtitle(subtitleStr)
                .setAllowedAuthenticators(authenticators)
                .build()
            prompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(authCipher))
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

fun android.content.Context.findActivity(): AppCompatActivity? = when (this) {
    is AppCompatActivity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}