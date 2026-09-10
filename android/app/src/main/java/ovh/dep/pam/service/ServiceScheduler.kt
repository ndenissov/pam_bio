package ovh.dep.pam.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import ovh.dep.pam.data.AppPrefs
import java.util.Calendar

object ServiceScheduler {
    fun updateSchedule(context: Context) {
        val prefs = AppPrefs(context)
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        val startIntent = Intent(context, ScheduleReceiver::class.java).apply {
            action = ScheduleReceiver.ACTION_ALARM_START
            setPackage(context.packageName)
        }
        val stopIntent = Intent(context, ScheduleReceiver::class.java).apply {
            action = ScheduleReceiver.ACTION_ALARM_STOP
            setPackage(context.packageName)
        }

        val startPendingIntent = PendingIntent.getBroadcast(
            context,
            1,
            startIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopPendingIntent = PendingIntent.getBroadcast(
            context,
            2,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Cancel existing alarms first
        alarmManager.cancel(startPendingIntent)
        alarmManager.cancel(stopPendingIntent)

        if (!prefs.enableServiceTimer) {
            return
        }

        val now = Calendar.getInstance()

        // Schedule Start Time
        val startCal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, prefs.timerStartHour)
            set(Calendar.MINUTE, prefs.timerStartMinute)
            set(Calendar.SECOND, 0)
        }
        if (startCal.before(now)) {
            startCal.add(Calendar.DAY_OF_YEAR, 1)
        }

        // Schedule Stop Time
        val stopCal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, prefs.timerStopHour)
            set(Calendar.MINUTE, prefs.timerStopMinute)
            set(Calendar.SECOND, 0)
        }
        if (stopCal.before(now)) {
            stopCal.add(Calendar.DAY_OF_YEAR, 1)
        }

        // Use inexact repeating alarms, sufficient for this purpose without requiring exact alarm permissions
        alarmManager.setInexactRepeating(
            AlarmManager.RTC_WAKEUP,
            startCal.timeInMillis,
            AlarmManager.INTERVAL_DAY,
            startPendingIntent
        )

        alarmManager.setInexactRepeating(
            AlarmManager.RTC_WAKEUP,
            stopCal.timeInMillis,
            AlarmManager.INTERVAL_DAY,
            stopPendingIntent
        )
    }
}
