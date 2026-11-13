package app.smallthingz.reverb

import java.io.Closeable
import java.util.concurrent.atomic.AtomicReference

/** One short-lived visible boot window owns this callback; close revokes queued delivery too. */
internal class BootCaptureConfirmation(
    private val afterNanos: Long,
    callback: () -> Unit,
) : Closeable {
    private val callback = AtomicReference<(() -> Unit)?>(callback)
    var commandGeneration = Long.MIN_VALUE
        private set
    var continuityGeneration = Long.MIN_VALUE
        private set

    val active: Boolean get() = callback.get() != null

    fun arm(command: Long, continuity: Long) {
        commandGeneration = command
        continuityGeneration = continuity
    }

    fun accepts(command: Long, continuity: Long, readEndedAtNanos: Long, byteCount: Int): Boolean =
        active && command == commandGeneration && continuity == continuityGeneration &&
            readEndedAtNanos >= afterNanos && byteCount > 0

    fun deliver(current: Boolean) {
        val receiver = callback.getAndSet(null)
        if (current) receiver?.invoke()
    }

    override fun close() { callback.set(null) }
}
