package dev.xiaoman.ios.runner

import dev.xiaoman.api.ReminderScheduler
import java.io.File
import java.io.PrintStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Reminders for the Mac runner: an in-process timer prints the reminder and shows a macOS
 * notification. They only fire while ios-runner keeps running.
 */
class MacReminders(
    private val out: PrintStream,
    private val clock: () -> Long = System::currentTimeMillis,
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "xiaoman-reminders").apply { isDaemon = true }
    },
    private val notify: (String) -> Unit = ::macNotification,
) : ReminderScheduler {
    override fun schedule(text: String, atMillis: Long) {
        val delay = (atMillis - clock()).coerceAtLeast(0)
        out.println("已安排提醒（${SimpleDateFormat("MM-dd HH:mm").format(Date(atMillis))}）：$text。提醒只在 ios-runner 运行期间有效。")
        executor.schedule({
            out.println("⏰ 提醒：$text")
            runCatching { notify(text) }
        }, delay, TimeUnit.MILLISECONDS)
    }

    companion object {
        private const val OSASCRIPT = "/usr/bin/osascript"

        /** Shows a notification on macOS; the text is passed as an argument, never spliced into a script. */
        fun macNotification(text: String) {
            if (!System.getProperty("os.name").orEmpty().contains("Mac") || !File(OSASCRIPT).canExecute()) return
            ProcessBuilder(OSASCRIPT, "-e", "on run argv", "-e", "display notification (item 1 of argv) with title \"小满\"",
                "-e", "end run", text)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start()
                .waitFor(10, TimeUnit.SECONDS)
        }
    }
}
