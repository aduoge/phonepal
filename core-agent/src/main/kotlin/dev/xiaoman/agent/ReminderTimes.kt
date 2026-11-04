package dev.xiaoman.agent

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/** Parsing and formatting of the reminder tool's local times; the device timezone is used throughout. */
internal object ReminderTimes {
    private val display = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm EEEE", Locale.SIMPLIFIED_CHINESE)

    /** Parses an ISO-8601 local time such as "2026-11-03T08:00" to epoch millis, or null when malformed. */
    fun parseAtMillis(raw: String): Long? = try {
        LocalDateTime.parse(raw, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    } catch (e: DateTimeParseException) {
        null
    }

    /** Current device-local time for the chat model's system prompt, e.g. "2026-11-02 07:50 星期一". */
    fun promptNow(nowMillis: Long): String =
        ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMillis), ZoneId.systemDefault()).format(display)
}
