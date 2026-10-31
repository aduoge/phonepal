package dev.phonepal.app

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random

/**
 * Task ids double as trace run ids. The debug bridge [prime]s the next id so `phonepal run
 * --run-id r1` finds its trace under `traces/r1/`; otherwise ids look like `app-20261031-120000-a1b2`.
 */
class RunIds(
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: Random = Random.Default,
) {
    private val primed = AtomicReference<String?>(null)

    /** The next [next] call returns [id]. */
    fun prime(id: String) = primed.set(id)

    fun next(): String = primed.getAndSet(null) ?: generate()

    fun generate(): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(clock()))
        val suffix = (1..4).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")
        return "app-$stamp-$suffix"
    }

    companion object {
        private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"
        private val VALID = Regex("^[A-Za-z0-9._-]{1,80}$")

        /** Same rule as the CLI: ids are folder names under `traces/`. */
        fun isValid(id: String): Boolean = VALID.matches(id) && id != "." && id != ".."
    }
}
