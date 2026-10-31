package dev.phonepal.hands

import dev.phonepal.api.StopSignal
import java.util.concurrent.atomic.AtomicBoolean

/** A thread-safe [StopSignal] set by the overlay's Stop button (or the debug bridge). */
class MutableStopSignal : StopSignal {
    private val flag = AtomicBoolean(false)

    override val stopped: Boolean get() = flag.get()

    fun stop() = flag.set(true)

    /** Re-arms the signal for the next task. */
    fun reset() = flag.set(false)
}
