package ovh.dep.pam.ui.settings

import android.content.Intent
import android.provider.Settings
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import ovh.dep.pam.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToAbout: () -> Unit
) {
    val context = LocalContext.current
    var showLangMenu by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        val prefs = remember { ovh.dep.pam.data.AppPrefs(context) }
        var requireBiometric by remember { mutableStateOf(prefs.requireBiometricOnStart) }
        var disableScreenshots by remember { mutableStateOf(prefs.disableScreenshots) }
        var enableTimer by remember { mutableStateOf(prefs.enableServiceTimer) }
        var startHour by remember { mutableStateOf(prefs.timerStartHour) }
        var startMinute by remember { mutableStateOf(prefs.timerStartMinute) }
        var stopHour by remember { mutableStateOf(prefs.timerStopHour) }
        var stopMinute by remember { mutableStateOf(prefs.timerStopMinute) }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            
            SwitchSettingsItem(
                icon = Icons.Filled.Lock,
                title = stringResource(R.string.settings_bio_title),
                subtitle = stringResource(R.string.settings_bio_desc),
                checked = requireBiometric,
                onCheckedChange = { 
                    requireBiometric = it
                    prefs.requireBiometricOnStart = it 
                }
            )

            SwitchSettingsItem(
                icon = Icons.Filled.VisibilityOff,
                title = stringResource(R.string.settings_screenshots_title),
                subtitle = stringResource(R.string.settings_screenshots_desc),
                checked = disableScreenshots,
                onCheckedChange = { 
                    disableScreenshots = it
                    prefs.disableScreenshots = it 
                }
            )

            HorizontalDivider()

            SwitchSettingsItem(
                icon = Icons.Filled.Schedule,
                title = stringResource(R.string.settings_timer_title),
                subtitle = stringResource(R.string.settings_timer_desc),
                checked = enableTimer,
                onCheckedChange = { 
                    enableTimer = it
                    prefs.enableServiceTimer = it
                    ovh.dep.pam.service.ServiceScheduler.updateSchedule(context)
                }
            )

            if (enableTimer) {
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    TimePickerButton(
                        label = stringResource(R.string.settings_timer_start),
                        hour = startHour,
                        minute = startMinute,
                        onTimeSelected = { h, m ->
                            startHour = h
                            startMinute = m
                            prefs.timerStartHour = h
                            prefs.timerStartMinute = m
                            ovh.dep.pam.service.ServiceScheduler.updateSchedule(context)
                        }
                    )
                    TimePickerButton(
                        label = stringResource(R.string.settings_timer_stop),
                        hour = stopHour,
                        minute = stopMinute,
                        onTimeSelected = { h, m ->
                            stopHour = h
                            stopMinute = m
                            prefs.timerStopHour = h
                            prefs.timerStopMinute = m
                            ovh.dep.pam.service.ServiceScheduler.updateSchedule(context)
                        }
                    )
                }
            }

            HorizontalDivider()

            SettingsItem(
                icon = Icons.Filled.Notifications,
                title = stringResource(R.string.settings_notifications_title),
                subtitle = stringResource(R.string.settings_notifications_desc),
                onClick = {
                    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    }
                    context.startActivity(intent)
                }
            )

            HorizontalDivider()

            Box {
                SettingsItem(
                    icon = Icons.Filled.Language,
                    title = stringResource(R.string.settings_language_title),
                    subtitle = stringResource(R.string.settings_language_desc),
                    onClick = { showLangMenu = true }
                )
                
                DropdownMenu(
                    expanded = showLangMenu,
                    onDismissRequest = { showLangMenu = false },
                    modifier = Modifier.padding(horizontal = 16.dp)
                ) {
                    DropdownMenuItem(
                        text = { Text("English") },
                        onClick = {
                            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en"))
                            showLangMenu = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Русский") },
                        onClick = {
                            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("ru"))
                            showLangMenu = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("中文") },
                        onClick = {
                            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("zh"))
                            showLangMenu = false
                        }
                    )
                }
            }

            HorizontalDivider()

            SettingsItem(
                icon = Icons.Filled.Info,
                title = stringResource(R.string.about_title),
                subtitle = stringResource(R.string.settings_about_desc),
                onClick = onNavigateToAbout
            )
        }
    }
}

@Composable
private fun TimePickerButton(label: String, hour: Int, minute: Int, onTimeSelected: (Int, Int) -> Unit) {
    val context = LocalContext.current
    OutlinedButton(onClick = {
        android.app.TimePickerDialog(
            context,
            { _, h, m -> onTimeSelected(h, m) },
            hour,
            minute,
            true // 24-hour format
        ).show()
    }) {
        Text("$label: ${String.format("%02d:%02d", hour, minute)}")
    }
}

@Composable
private fun SwitchSettingsItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun SettingsItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
