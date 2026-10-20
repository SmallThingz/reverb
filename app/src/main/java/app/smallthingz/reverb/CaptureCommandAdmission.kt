package app.smallthingz.reverb

import java.util.concurrent.atomic.AtomicReference

/** A settled storage failure can be retried only by a new, explicitly owned command. */
internal fun captureConfigurationRetryMayRun(
    configurationUnavailable: Boolean,
    persistenceFailureBlocked: Boolean,
    storesAdmitted: Boolean,
    initializationPending: Boolean,
    recorderListening: Boolean,
    serviceDestroying: Boolean,
    otherWorkPending: Boolean,
): Boolean = configurationUnavailable && persistenceFailureBlocked && storesAdmitted &&
    !initializationPending && !recorderListening && !serviceDestroying && !otherWorkPending

internal fun captureBlobCommandMaySubmit(
    action: CaptureBlobTapAction,
    configurationRetryAllowed: Boolean,
): Boolean = action != CaptureBlobTapAction.NONE || configurationRetryAllowed

/** Revoke readiness before I/O; a failed attempt never publishes a partially configured store. */
internal inline fun retryCaptureConfiguration(
    resetReadiness: () -> Unit,
    configure: () -> Unit,
    publishReadiness: () -> Unit,
    onFailure: (Exception) -> Unit,
): Boolean {
    resetReadiness()
    return try {
        configure()
        publishReadiness()
        true
    } catch (error: Exception) {
        onFailure(error)
        false
    }
}

/**
 * Short admission bookkeeping only. The supplied queue owns accepted work independently of the
 * screen; storage work must run on that queue, never while this admission monitor is held.
 */
internal class CaptureCommandAdmission {
    private val lock = Any()
    private val pending = HashSet<Any>()
    private var closed = false

    fun submit(
        retainLifetime: () -> Boolean,
        enqueue: (Runnable) -> Boolean,
        onIdle: () -> Unit,
        command: () -> Unit,
    ): Boolean = synchronized(lock) {
        if (closed) return@synchronized false
        val ticket = Any()
        pending += ticket
        var queued = false
        var retained = false
        try {
            retained = retainLifetime()
            if (!retained) return@synchronized false
            queued = enqueue(Runnable {
                try {
                    command()
                } finally {
                    // Work may have posted a Stop seal/release tail to the same FIFO.
                    // Keep lifetime ownership until that tail has passed, without waiting here.
                    val completion = Runnable { if (finish(ticket)) onIdle() }
                    val completionQueued = try {
                        enqueue(completion)
                    } catch (_: RuntimeException) {
                        false
                    }
                    if (!completionQueued) completion.run()
                }
            })
            queued
        } finally {
            if (!queued && finish(ticket) && retained) onIdle()
        }
    }

    /** Rejects new submissions without cancelling work already transferred to the service. */
    fun closeAdmission() = synchronized(lock) { closed = true }

    fun isActive(): Boolean = synchronized(lock) { pending.isNotEmpty() }

    private fun finish(ticket: Any): Boolean = synchronized(lock) {
        pending.remove(ticket) && pending.isEmpty()
    }
}

internal data class CaptureCommandOutcome<T>(val result: T, val errorMessage: String?)

/** Accepted commands own their errors through terminal delivery; ordinary reports keep their slot. */
internal class RecorderErrorState {
    private class CommandError(var message: String? = null)

    private val pending = AtomicReference<String?>(null)
    private val commandError = ThreadLocal<CommandError>()

    fun report(message: String) {
        val command = commandError.get()
        if (command != null) command.message = message else pending.set(message)
    }

    /** Actual terminal capture failures can outlive both the command scope and its Service. */
    fun reportTerminalCaptureFailure(message: String, processFallback: (String) -> Unit) {
        val command = commandError.get()
        if (command != null) command.message = message else processFallback(message)
    }

    fun consumePending(): String? = pending.getAndSet(null)

    fun <T> captureCommand(command: () -> T): CaptureCommandOutcome<T> {
        val previous = commandError.get()
        val current = CommandError()
        commandError.set(current)
        try {
            val result = command()
            return CaptureCommandOutcome(result, current.message)
        } finally {
            if (previous == null) commandError.remove() else commandError.set(previous)
        }
    }

    /** Commands without a UI receiver transfer only their actual failure before lifetime settles. */
    fun <T> captureProcessCommand(processFallback: (String) -> Unit, command: () -> T): T {
        val outcome = captureCommand(command)
        outcome.errorMessage?.let(processFallback)
        return outcome.result
    }
}

/** Only the UI feedback is retired on disposal; an accepted recorder command remains owned. */
internal class CaptureCommandFeedback<T>(callback: (T) -> Boolean) {
    private var callback: ((T) -> Boolean)? = callback
    private var terminal = false

    /** A dead UI or failed post transfers the bound result to its non-UI terminal owner. */
    fun postIfCurrent(
        value: T,
        enqueue: (Runnable) -> Boolean,
        isOwnerCurrent: () -> Boolean,
        onUndelivered: (T) -> Unit = {},
    ): Boolean {
        val queued = try {
            enqueue(Runnable { complete(value, isOwnerCurrent(), onUndelivered) })
        } catch (_: RuntimeException) {
            false
        }
        if (!queued) complete(value, false, onUndelivered)
        return queued
    }

    @Synchronized
    fun deliver(value: T, onUndelivered: (T) -> Unit = {}): Boolean =
        complete(value, true, onUndelivered)

    @Synchronized
    private fun complete(value: T, ownerCurrent: Boolean, onUndelivered: (T) -> Unit): Boolean {
        if (terminal) return false
        terminal = true
        val current = callback
        callback = null
        val delivered = try {
            ownerCurrent && current?.invoke(value) == true
        } catch (error: Throwable) {
            try {
                onUndelivered(value)
            } catch (fallbackError: Throwable) {
                if (fallbackError !== error) error.addSuppressed(fallbackError)
            }
            throw error
        }
        if (!delivered) onUndelivered(value)
        return delivered
    }

    @Synchronized
    fun retire() {
        callback = null
    }
}

/** The production click adapter transfers work immediately and owns only terminal UI feedback. */
internal class CaptureCommandUiOwner<T> {
    private val lock = Any()
    private val pending = HashSet<CaptureCommandFeedback<T>>()
    private var retired = false

    fun submit(
        transfer: (CaptureCommandFeedback<T>) -> Boolean,
        onResult: (T) -> Boolean,
    ): Boolean {
        lateinit var feedback: CaptureCommandFeedback<T>
        feedback = CaptureCommandFeedback(
            callback = { value ->
                forget(feedback)
                onResult(value)
            },
        )
        synchronized(lock) {
            if (retired) return false
            pending += feedback
        }
        var accepted = false
        try {
            accepted = transfer(feedback)
            return accepted
        } finally {
            if (!accepted) {
                forget(feedback)
                feedback.retire()
            }
        }
    }

    private fun forget(feedback: CaptureCommandFeedback<T>) = synchronized(lock) {
        pending.remove(feedback)
    }

    fun retire() {
        val callbacks = synchronized(lock) {
            retired = true
            pending.toList().also { pending.clear() }
        }
        callbacks.forEach { it.retire() }
    }
}
/** A refused old start ID must not remove foreground protection from newer started work. */
internal fun releaseStartedServiceIfCurrent(
    startId: Int,
    stopSelfResult: (Int) -> Boolean,
    stopForeground: () -> Unit,
): Boolean {
    if (!stopSelfResult(startId)) return false
    stopForeground()
    return true
}
