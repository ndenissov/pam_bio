package ovh.dep.pam.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import ovh.dep.pam.data.AppPrefs

class ScheduleReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_ALARM_START = "ovh.dep.pam.action.ALARM_START"
        const val ACTION_ALARM_STOP = "ovh.dep.pam.action.ALARM_STOP"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val prefs = AppPrefs(context)
        if (!prefs.enableServiceTimer) return

        when (intent.action) {
            ACTION_ALARM_START -> {
                prefs.serviceEnabled = false
                val serviceIntent = Intent(context, PamBioForegroundService::class.java).apply {
                    action = PamBioForegroundService.ACTION_STOP
                }
                context.startService(serviceIntent)
            }
            ACTION_ALARM_STOP -> {
                prefs.serviceEnabled = true
                val serviceIntent = Intent(context, PamBioForegroundService::class.java).apply {
                    action = PamBioForegroundService.ACTION_START
                }
                ContextCompat.startForegroundService(context, serviceIntent)
            }
        }
        
        // Re-schedule alarms to ensure they keep repeating properly (if inexact alarms get cleared on reboot)
        ServiceScheduler.updateSchedule(context)
    }
}
