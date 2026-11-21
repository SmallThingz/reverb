package app.smallthingz.reverb

import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

internal enum class BootResumeFailure { TIMEOUT, MICROPHONE_PERMISSION, OVERLAY_PERMISSION, UNEXPECTED, AUDIO_INPUT, NO_WRITABLE_BUFFER }

/** One boot session. Timers and unlock callbacks enter through the same serial worker. */
internal class BootResumeRetry(
    private val elapsedMillis: () -> Long,
    private val enqueue: (() -> Unit) -> Unit,
    private val schedule: (delayMillis: Long, run: () -> Unit) -> Closeable,
    private val watchWindow: (changed: (screenTurnedOff: Boolean) -> Unit) -> Closeable,
    private val windowReady: () -> Boolean,
    private val allowed: () -> Boolean,
    private val durableIntentEnabled: () -> Boolean,
    private val launch: (deadline: Long, allowed: () -> Boolean, cancel: () -> Unit) -> Unit,
    private val report: (Exception) -> Unit,
    private val permissionFailure: () -> BootResumeFailure? = { null },
    private val attempting: (deadline: Long) -> Unit = {},
    private val ended: (BootResumeFailure?) -> Unit = {},
) : Closeable {
    private val closed = AtomicBoolean(false)
    @Volatile private var deadline: Long? = null
    private var windowWatch: Closeable? = null
    private var timer: Closeable? = null
    private var timerRevision = 0L
    private var delayMillis = FIRST_DELAY_MILLIS
    private var waitingForWindow = true

    fun start() {
        if (windowWatch != null) return
        try {
            if (retireIfNecessary()) return
            windowWatch = watchWindow { screenTurnedOff -> enqueue { windowChanged(screenTurnedOff) } }
            attempt()
        } catch (error: Exception) {
            end(BootResumeFailure.UNEXPECTED)
            report(error)
        }
    }

    private fun failure(): BootResumeFailure? = permissionFailure() ?:
        if (deadline?.let { elapsedMillis() >= it } == true) BootResumeFailure.TIMEOUT else null

    private fun current(): Boolean = !closed.get() && allowed() && failure() == null

    private fun retireIfNecessary(): Boolean {
        if (closed.get()) return true
        if (!allowed()) {
            close()
            return true
        }
        val failure = failure() ?: return false
        end(failure)
        return true
    }

    private fun windowChanged(screenTurnedOff: Boolean) {
        try {
            if (retireIfNecessary()) return
            if (screenTurnedOff || !windowReady()) waitingForWindow = true
            else if (waitingForWindow) attempt()
        } catch (error: Exception) {
            end(BootResumeFailure.UNEXPECTED)
            report(error)
        }
    }

    private fun attempt() {
        retireTimer()
        try {
            if (retireIfNecessary()) return
            if (!durableIntentEnabled()) return close()
            waitingForWindow = !windowReady()
            val firstAttempt = deadline == null
            val end = deadline ?: if (waitingForWindow) return else (elapsedMillis() + WINDOW_MILLIS).also { deadline = it }
            if (retireIfNecessary()) return
            if (!waitingForWindow) launch(end, ::current, ::close)
            if (firstAttempt && current()) attempting(end)
            val revision = timerRevision
            timer = schedule(minOf(delayMillis, (end - elapsedMillis()).coerceAtLeast(0))) {
                // Cancellation can race a timer whose callback has already reached the worker queue.
                if (timerRevision == revision) attempt()
            }
            if (!waitingForWindow) delayMillis = (delayMillis * 2).coerceAtMost(MAX_DELAY_MILLIS)
        } catch (error: Exception) {
            end(BootResumeFailure.UNEXPECTED)
            report(error)
        }
    }

    override fun close() { end(null) }

    private fun end(failure: BootResumeFailure?) {
        if (!closed.compareAndSet(false, true)) return
        enqueue {
            retireTimer()
            try { windowWatch?.close() } catch (error: Exception) { report(error) }
            windowWatch = null
            ended(failure)
        }
    }

    private fun retireTimer() {
        timerRevision++
        timer?.close()
        timer = null
    }

    private companion object {
        const val WINDOW_MILLIS = 60_000L
        const val FIRST_DELAY_MILLIS = 1_000L
        const val MAX_DELAY_MILLIS = 15_000L
    }
}
