package com.hyperion.grabber

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.util.Calendar

object ScheduleManager {

    private const val REQ_START = 1001
    private const val REQ_STOP  = 1002

    fun apply(context: Context, mode: ScheduleMode, startHour: Int, endHour: Int) {
        cancel(context)
        if (mode == ScheduleMode.OFF) return

        val actualStartHour = if (mode == ScheduleMode.SUNSET) SunsetCalculator.getSunsetHour() else startHour
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        am.setInexactRepeating(
            AlarmManager.RTC_WAKEUP,
            nextOccurrence(actualStartHour),
            AlarmManager.INTERVAL_DAY,
            buildIntent(context, ScheduleReceiver.ACTION_SCHEDULE_START, REQ_START)
        )
        am.setInexactRepeating(
            AlarmManager.RTC_WAKEUP,
            nextOccurrence(endHour),
            AlarmManager.INTERVAL_DAY,
            buildIntent(context, ScheduleReceiver.ACTION_SCHEDULE_STOP, REQ_STOP)
        )
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(buildIntent(context, ScheduleReceiver.ACTION_SCHEDULE_START, REQ_START))
        am.cancel(buildIntent(context, ScheduleReceiver.ACTION_SCHEDULE_STOP,  REQ_STOP))
    }

    private fun nextOccurrence(hour: Int): Long {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }
        return cal.timeInMillis
    }

    private fun buildIntent(context: Context, action: String, requestCode: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context, requestCode,
            Intent(context, ScheduleReceiver::class.java).apply { this.action = action },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}
