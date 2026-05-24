package com.hyperion.grabber

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ScheduleReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_SCHEDULE_START = "com.hyperion.grabber.SCHEDULE_START"
        const val ACTION_SCHEDULE_STOP  = "com.hyperion.grabber.SCHEDULE_STOP"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences("grabber_prefs", Context.MODE_PRIVATE)

        when (intent.action) {
            ACTION_SCHEDULE_START -> {
                if (ScreenGrabberService.isRunning) {
                    context.startService(
                        Intent(context, ScreenGrabberService::class.java).apply {
                            action = ScreenGrabberService.ACTION_RESUME
                        }
                    )
                }
                // If service is not running (e.g. after reboot), we can't start MediaProjection
                // from a receiver — user needs to open the app once to grant permission.
            }

            ACTION_SCHEDULE_STOP -> {
                if (ScreenGrabberService.isRunning) {
                    context.startService(
                        Intent(context, ScreenGrabberService::class.java).apply {
                            action = ScreenGrabberService.ACTION_PAUSE
                        }
                    )
                }
            }

            Intent.ACTION_BOOT_COMPLETED -> {
                // Re-register alarms — they are cleared on reboot
                val modeName = prefs.getString("scheduleMode", ScheduleMode.OFF.name) ?: ScheduleMode.OFF.name
                val mode = runCatching { ScheduleMode.valueOf(modeName) }.getOrDefault(ScheduleMode.OFF)
                if (mode != ScheduleMode.OFF) {
                    val startHour = prefs.getInt("scheduleStartHour", 19)
                    val endHour   = prefs.getInt("scheduleEndHour", 22)
                    ScheduleManager.apply(context, mode, startHour, endHour)
                }

                // Start-on-boot: launch MainActivity so it can prompt for
                // MediaProjection consent. Can't bypass the dialog — Android 10+
                // forbids silent screen capture.
                if (prefs.getBoolean("startOnBoot", false)) {
                    val host = prefs.getString("host", "") ?: ""
                    if (host.isNotBlank()) {
                        context.startActivity(
                            Intent(context, MainActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                putExtra(MainActivity.EXTRA_AUTO_START, true)
                            }
                        )
                    }
                }
            }
        }
    }
}
