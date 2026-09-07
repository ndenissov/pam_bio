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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
                
                showAppOpenAuth()
            } else {
                setAppContent()
            }
        }
    }

    private fun setAppContent() {
        setContent {
            LinuxBiopamTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    PamBioNavigation()
                }
            }
        }
    }

    private fun showAppOpenAuth() {
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        val canAuth = BiometricManager.from(this).canAuthenticate(authenticators)

        if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) {
            // If no auth method is set up, just let them in (or we could block them, but this is safer)
            setAppContent()
            return
        }

        val executor = ContextCompat.getMainExecutor(this)
        val prompt = BiometricPrompt(this, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    setAppContent()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON || errorCode == BiometricPrompt.ERROR_USER_CANCELED) {
                        finish() // Close app if they cancel
                    }
                }
            })

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.app_name))
            .setSubtitle("Unlock to access PamBio")
            .setAllowedAuthenticators(authenticators)
            .build()

        prompt.authenticate(promptInfo)
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