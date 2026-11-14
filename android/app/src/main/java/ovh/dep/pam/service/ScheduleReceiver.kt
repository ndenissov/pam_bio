package ovh.dep.pam.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import ovh.dep.pam.data.AppPrefs

class ScheduleReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "ScheduleReceiver"
        const val ACTION_ALARM_START = "ovh.dep.pam.action.ALARM_START"
        const val ACTION_ALARM_STOP = "ovh.dep.pam.action.ALARM_STOP"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val prefs = AppPrefs(context)
        if (!prefs.enableServiceTimer) return

        when (intent.action) {
            ACTION_ALARM_START -> {
                prefs.serviceEnabled = false
                if (PamBioForegroundService.isRunning.value) {
                    val serviceIntent = Intent(context, PamBioForegroundService::class.java).apply {
                        action = PamBioForegroundService.ACTION_STOP
                        setPackage(context.packageName)
                    }
                    try {
                        context.startService(serviceIntent)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to stop service on alarm: ${e.message}")
                    }
                }
            }
            ACTION_ALARM_STOP -> {
                prefs.serviceEnabled = true
                val serviceIntent = Intent(context, PamBioForegroundService::class.java).apply {
                    action = PamBioForegroundService.ACTION_START
                    setPackage(context.packageName)
                }
                try {
                    ContextCompat.startForegroundService(context, serviceIntent)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to start service on alarm: ${e.message}")
                }
            }
        }

        // Re-schedule alarms to ensure they keep repeating properly (if inexact alarms get cleared on reboot)
        ServiceScheduler.updateSchedule(context)
    }
}
