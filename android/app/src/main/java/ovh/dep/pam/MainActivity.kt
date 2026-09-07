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
import ovh.dep.pam.data.PairedDeviceRepository
import ovh.dep.pam.service.PamBioForegroundService
import ovh.dep.pam.ui.home.HomeScreen
import ovh.dep.pam.ui.history.HistoryScreen
import ovh.dep.pam.ui.scan.QrScanScreen
import ovh.dep.pam.ui.theme.LinuxBiopamTheme

class MainActivity : AppCompatActivity() {

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
                onNavigateToHistory = { navController.navigate("history") }
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
            val executor = ContextCompat.getMainExecutor(context)
            val prompt = BiometricPrompt(context, executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        onUnlockSuccess()
                    }
                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        // Just let the user retry by clicking the button
                    }
                })
            val promptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle(context.getString(R.string.app_name))
                .setSubtitle("Unlock to access PamBio")
                .setAllowedAuthenticators(authenticators)
                .build()
            prompt.authenticate(promptInfo)
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
                Text("Unlock PamBio")
            }
        }
    }
}