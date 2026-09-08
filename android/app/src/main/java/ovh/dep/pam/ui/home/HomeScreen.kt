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


package ovh.dep.pam.ui.home

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import ovh.dep.pam.data.PairedDevice
import ovh.dep.pam.data.PairedDeviceRepository
import ovh.dep.pam.service.PamBioForegroundService
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.compose.ui.res.stringResource
import ovh.dep.pam.R
import ovh.dep.pam.data.AppPrefs
import ovh.dep.pam.network.GithubRelease
import ovh.dep.pam.network.UpdateChecker

/**
 * Home screen — list of paired PCs, service toggle, and "add device" FAB.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToScan: () -> Unit,
    onNavigateToHistory: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val deviceRepo = remember { PairedDeviceRepository(context) }
    val devices by deviceRepo.devicesFlow.collectAsState(initial = emptyList())
    val serviceRunning by PamBioForegroundService.isRunning.collectAsState()
    val connectedDevices by PamBioForegroundService.connectedDevices.collectAsState()

    val prefs = remember { AppPrefs(context) }
    val authCount by prefs.getAuthCountFlow().collectAsState(initial = prefs.authCount)
    val githubStarred by prefs.getGithubStarredFlow().collectAsState(initial = prefs.githubStarred)
    val githubClicked by prefs.getGithubClickedFlow().collectAsState(initial = prefs.githubClicked)
    var showGithubDialog by remember { mutableStateOf(false) }

    var githubRelease by remember { mutableStateOf<GithubRelease?>(null) }

    LaunchedEffect(Unit) {
        val release = UpdateChecker.getLatestRelease()
        if (release != null && UpdateChecker.isNewVersionAvailable(release.tag_name)) {
            if (prefs.skippedUpdateVersion != release.tag_name) {
                githubRelease = release
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                actions = {
                    IconButton(onClick = onNavigateToHistory) {
                        Icon(Icons.Filled.History, contentDescription = "History")
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNavigateToScan,
                icon = { Icon(Icons.Filled.QrCodeScanner, stringResource(R.string.scan)) },
                text = { Text(stringResource(R.string.add_pc)) },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(top = 16.dp, bottom = 96.dp)
        ) {
            // Service toggle card
            item {
                ServiceToggleCard(
                    isRunning = serviceRunning,
                    onToggle = { enabled ->
                        prefs.serviceEnabled = enabled
                        toggleService(context, enabled)
                    },
                    onRestart = {
                        val intent = Intent(context, PamBioForegroundService::class.java).apply {
                            action = PamBioForegroundService.ACTION_RESTART
                        }
                        context.startService(intent)
                    }
                )
            }

            // Paired devices header
            if (devices.isNotEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.paired_devices),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }

            // Device list
            items(devices, key = { it.serviceName }) { device ->
                DeviceCard(
                    device = device,
                    isConnected = connectedDevices.contains(device.serviceName),
                    onRemove = {
                        scope.launch { deviceRepo.removeDevice(device.serviceName) }
                    }
                )
            }

            // Empty state
            if (devices.isEmpty()) {
                item {
                    EmptyState()
                }
            }

            // Footer: auth count + GitHub star + bug report
            item {
                Spacer(Modifier.height(32.dp))
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = stringResource(R.string.auth_count_footer, authCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    if (!githubStarred) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = {
                            prefs.githubClicked = true
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/ndenissov/pam_bio")))
                        }) {
                            Text(stringResource(R.string.star_on_github))
                        }
                        if (githubClicked) {
                            TextButton(onClick = { showGithubDialog = true }) {
                                Text(stringResource(R.string.github_star_already), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/ndenissov/pam_bio/issues/new")))
                    }) {
                        Text(stringResource(R.string.bug_report_btn), style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        text = stringResource(R.string.bug_report_desc),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.padding(horizontal = 32.dp)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.with_love),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }
            }
        }
    }

    // Nag dialog logic: show at thresholds 8, 16, 32, ..., 512, 1024, 2048, 3072, 4096, ...
    var showPreamble by remember { mutableStateOf(false) }

    LaunchedEffect(authCount, githubStarred) {
        if (!githubStarred && authCount >= 8) {
            val count = authCount
            var threshold = 8
            while (threshold * 2 <= count && threshold < 512) {
                threshold *= 2
            }
            if (threshold >= 512) {
                threshold = 1024
                while (threshold + 1024 <= count) {
                    threshold += 1024
                }
            }
            if (prefs.lastPromptedAuthCount < threshold) {
                showPreamble = (count - threshold) >= 10
                showGithubDialog = true
                prefs.lastPromptedAuthCount = threshold
            }
        }
    }

    if (showGithubDialog) {
        val msgs = listOf(
            R.string.github_star_msg_1,
            R.string.github_star_msg_2,
            R.string.github_star_msg_3,
            R.string.github_star_msg_4,
            R.string.github_star_msg_5
        )
        val randomMsg = remember { msgs.random() }
        val dialogText = if (showPreamble) {
            stringResource(R.string.github_star_preamble, authCount) + "\n\n" + stringResource(randomMsg)
        } else {
            stringResource(randomMsg)
        }

        AlertDialog(
            onDismissRequest = { showGithubDialog = false },
            title = { Text(stringResource(R.string.github_star_title)) },
            text = { Text(dialogText) },
            confirmButton = {
                TextButton(onClick = {
                    prefs.githubStarred = true
                    showGithubDialog = false
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/ndenissov/pam_bio")))
                }) { Text(stringResource(R.string.github_star_btn)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    prefs.githubStarred = true
                    showGithubDialog = false
                }) { Text(stringResource(R.string.github_star_already)) }
            }
        )
    }

    if (githubRelease != null) {
        val release = githubRelease!!
        AlertDialog(
            onDismissRequest = { githubRelease = null },
            title = { Text(stringResource(R.string.update_available_title)) },
            text = { Text(stringResource(R.string.update_available_msg, release.tag_name, release.body)) },
            confirmButton = {
                Button(onClick = {
                    githubRelease = null
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.html_url)))
                }) {
                    Text(stringResource(R.string.update_download_btn))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    prefs.skippedUpdateVersion = release.tag_name
                    githubRelease = null
                }) {
                    Text(stringResource(R.string.update_skip_btn))
                }
            }
        )
    }
}

@Composable
private fun ServiceToggleCard(isRunning: Boolean, onToggle: (Boolean) -> Unit, onRestart: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isRunning)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isRunning) stringResource(R.string.service_active) else stringResource(R.string.service_stopped),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (isRunning)
                        stringResource(R.string.waiting_auth)
                    else
                        stringResource(R.string.click_to_start),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (isRunning) {
                IconButton(onClick = onRestart) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = "Restart",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                Spacer(Modifier.width(8.dp))
            }
            IconButton(onClick = { onToggle(!isRunning) }) {
                Icon(
                    imageVector = if (isRunning) Icons.Filled.LinkOff else Icons.Filled.Link,
                    contentDescription = if (isRunning) "Stop service" else "Start service",
                    tint = if (isRunning)
                        MaterialTheme.colorScheme.onPrimaryContainer
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun DeviceCard(device: PairedDevice, isConnected: Boolean, onRemove: () -> Unit) {
    var showDialog by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.Computer,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.serviceName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = if (isConnected) stringResource(R.string.status_connected) else stringResource(R.string.status_disconnected),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = { showDialog = true }) {
                Icon(Icons.Filled.Delete, stringResource(R.string.delete), tint = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(stringResource(R.string.delete_device_title)) },
            text = { Text(stringResource(R.string.delete_device_text, device.serviceName)) },
            confirmButton = {
                TextButton(onClick = {
                    onRemove()
                    showDialog = false
                }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}

@Composable
private fun EmptyState() {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Filled.PhonelinkOff,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.no_paired_devices),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.no_paired_devices_desc),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.empty_state_help),
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable {
                val sendIntent: Intent = Intent().apply {
                    action = Intent.ACTION_SEND
                    putExtra(Intent.EXTRA_TEXT, "https://pam.dep.ovh/")
                    type = "text/plain"
                }
                val shareIntent = Intent.createChooser(sendIntent, null)
                context.startActivity(shareIntent)
            }.padding(8.dp)
        )
    }
}

private fun toggleService(context: Context, enable: Boolean) {
    val intent = Intent(context, PamBioForegroundService::class.java)
    if (enable) {
        intent.action = PamBioForegroundService.ACTION_START
        context.startForegroundService(intent)
    } else {
        intent.action = PamBioForegroundService.ACTION_STOP
        context.startService(intent)
    }
}
