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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import ovh.dep.pam.data.AppPrefs
import ovh.dep.pam.data.PairedDeviceRepository
import java.util.Calendar

/**
 * Starts [PamBioForegroundService] and restores scheduled alarms after device
 * boot or app update when autostart is enabled and at least one PC is paired.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "PamBioBoot"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            action != "android.intent.action.QUICKBOOT_POWERON" &&
            action != "com.htc.intent.action.QUICKBOOT_POWERON"
        ) {
            return
        }

        val pendingResult = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val prefs = AppPrefs(appContext)
                if (prefs.enableServiceTimer) {
                    ServiceScheduler.updateSchedule(appContext)
                }

                val devices = PairedDeviceRepository(appContext).getAll()
                val now = Calendar.getInstance()
                val shouldStart = BootStartPolicy.shouldStartOnBoot(
                    autoStartOnBoot = prefs.autoStartOnBoot,
                    serviceEnabled = prefs.serviceEnabled,
                    hasPairedDevices = devices.isNotEmpty(),
                    timerEnabled = prefs.enableServiceTimer,
                    nowHour = now.get(Calendar.HOUR_OF_DAY),
                    nowMinute = now.get(Calendar.MINUTE),
                    offHour = prefs.timerStartHour,
                    offMinute = prefs.timerStartMinute,
                    onHour = prefs.timerStopHour,
                    onMinute = prefs.timerStopMinute
                )

                if (!shouldStart) {
                    Log.i(TAG, "Skipping autostart on $action (autoStart=${prefs.autoStartOnBoot}, enabled=${prefs.serviceEnabled}, devices=${devices.size})")
                    return@launch
                }

                if (prefs.enableServiceTimer && !prefs.serviceEnabled) {
                    prefs.serviceEnabled = true
                }

                val serviceIntent = Intent(appContext, PamBioForegroundService::class.java).apply {
                    this.action = PamBioForegroundService.ACTION_START
                    setPackage(appContext.packageName)
                }
                try {
                    ContextCompat.startForegroundService(appContext, serviceIntent)
                    Log.i(TAG, "Started foreground service on $action")
                } catch (e: Exception) {
                    // On some OEMs or restricted battery states Android 12+/15+ may block FGS start from background.
                    Log.w(TAG, "System blocked foreground service start on $action: ${e.message}", e)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}

