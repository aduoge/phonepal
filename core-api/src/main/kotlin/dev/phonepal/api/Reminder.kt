package dev.phonepal.api

/**
 * Schedules a local reminder notification. The app module implements it on top of AlarmManager;
 * core-agent depends only on this interface and therefore stays Android-free.
 */
fun interface ReminderScheduler {
    /** Shows [text] as a notification at [atMillis] (epoch milliseconds in the device timezone). */
    fun schedule(text: String, atMillis: Long)
}
