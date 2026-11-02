package dev.phonepal.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import dev.phonepal.api.ReminderScheduler

/**
 * Schedules reminders with [AlarmManager.setAndAllowWhileIdle]: the alarm also fires in Doze but is
 * inexact, so no exact-alarm permission is required. Reboot clears the alarms (no persistence yet).
 */
class AlarmReminderScheduler(private val context: Context) : ReminderScheduler {
    private val alarms = context.getSystemService(AlarmManager::class.java)

    override fun schedule(text: String, atMillis: Long) {
        val id = reminderId(text, atMillis)
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            putExtra(ReminderReceiver.EXTRA_ID, id)
            putExtra(ReminderReceiver.EXTRA_TEXT, text)
        }
        val pending = PendingIntent.getBroadcast(
            context,
            id,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending)
    }

    /** Stable per (time, text): identical reminders collapse, but two different texts at the same minute don't. */
    private fun reminderId(text: String, atMillis: Long): Int =
        (atMillis.toInt() xor text.hashCode()) and Int.MAX_VALUE
}
