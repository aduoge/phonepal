package dev.phonepal.web

import dev.phonepal.api.ReminderScheduler
import kotlinx.serialization.json.put
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.UUID

class IcsReminders(private val channels: WebChannels, private val timezone: ZoneId,
    private val clock: () -> Long = System::currentTimeMillis) : ReminderScheduler {
    override fun schedule(text: String, atMillis: Long) {
        channels.emit(event("reminder") {
            put("text", text)
            put("at", LocalDateTime.ofInstant(Instant.ofEpochMilli(atMillis), timezone).toString())
            put("ics", calendar(text, atMillis, clock()))
        })
    }
    companion object {
        private val utc = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
        fun calendar(text: String, atMillis: Long, nowMillis: Long): String {
            val summary = text.replace("\\", "\\\\").replace("\r\n", "\n").replace("\r", "\n")
                .replace("\n", "\\n").replace(",", "\\,").replace(";", "\\;")
            val lines = listOf("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//PhonePal//Web//ZH", "CALSCALE:GREGORIAN",
                "BEGIN:VEVENT", "UID:${UUID.randomUUID()}@phonepal", "DTSTAMP:${utc.format(Instant.ofEpochMilli(nowMillis))}",
                "DTSTART:${utc.format(Instant.ofEpochMilli(atMillis))}", "SUMMARY:$summary", "BEGIN:VALARM", "TRIGGER:PT0M",
                "ACTION:DISPLAY", "DESCRIPTION:$summary", "END:VALARM", "END:VEVENT", "END:VCALENDAR")
            // Fold by UTF-8 octets without splitting a code point (RFC 5545).
            return lines.joinToString("\r\n") { line -> buildString {
                var bytes = 0
                line.codePoints().forEach { cp ->
                    val s = String(Character.toChars(cp)); val n = s.toByteArray(Charsets.UTF_8).size
                    if (bytes + n > 75) { append("\r\n "); bytes = 1 }
                    append(s); bytes += n
                }
            } } + "\r\n"
        }
    }
}
