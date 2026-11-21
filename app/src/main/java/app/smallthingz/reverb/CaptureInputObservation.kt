package app.smallthingz.reverb

import java.io.Closeable
import java.io.File
import kotlinx.coroutines.launch

internal interface CapturePolicySubscription : Closeable {
    /** Called only after native start and session admission succeed. */
    fun activate()
}

/** Unknown is not evidence that Android restored the microphone. */
internal class CapturePolicyEpisode {
    private var silenced: Boolean? = null
    fun observe(value: Boolean?): Boolean {
        if (value == null) return false
        val started = value && silenced != true
        silenced = value
        return started
    }
}

internal enum class CaptureInputObservationReason(val description: String) {
    POLICY_SILENCED("Microphone input silenced by Android"),
    INPUT_RESTARTED("Audio input disconnected and was restarted"),
}

/** Retry the identical fact, including after a visible write whose directory barrier failed. */
internal suspend fun persistCaptureInputObservation(
    persist: () -> Unit,
    waitBeforeRetry: suspend (Long) -> Unit,
    onFailure: (Exception) -> Unit,
) {
    for (attempt in 0..2) {
        try { persist(); return } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            if (attempt == 2) { runCatching { onFailure(error) }; return }
        }
        waitBeforeRetry(if (attempt == 0) 100L else 500L)
    }
}

/** Callback facts transfer synchronously; only the initial snapshot uses the worker. */
internal class CapturePolicyWitnesses(
    private val execute: (() -> Unit) -> Unit,
    private val snapshot: () -> Pair<Boolean?, Long>,
    private val deliver: (Long) -> Unit,
    private val failed: (Exception) -> Unit,
    private val deliverUnsilenced: (Long) -> Unit = {},
) : CapturePolicySubscription {
    private val lock = Any()
    private val episode = CapturePolicyEpisode()
    private val pending = java.util.ArrayDeque<Long>()
    private var sequence = 0L
    private var positiveObserved = false
    private var active = false
    private var closed = false
    private var failure: Exception? = null
    private var failureDelivered = false

    fun observe(value: Boolean?, observedAtMillis: Long) {
        var unsilencedHint = false
        val admitted = synchronized(lock) {
            if (closed || failure != null || value == null) return
            sequence++
            // Only explicit callback state can cue another reminder probe. The initial
            // snapshot belongs to incident reporting and has no shared callback order.
            unsilencedHint = active && value == false
            accept(value, observedAtMillis)
        }
        if (admitted) publish(observedAtMillis)
        if (unsilencedHint) {
            try { deliverUnsilenced(observedAtMillis) } catch (error: Exception) { report(error) }
        }
    }

    private fun accept(value: Boolean?, time: Long): Boolean {
        if (!episode.observe(value)) return false
        positiveObserved = true
        if (active) return true
        if (pending.size == 64) failure = IllegalStateException("Microphone policy observation queue exhausted")
        else pending.addLast(time)
        return false
    }

    /** Service holds its owner-admission lock while adopting provisional start observations. */
    override fun activate() {
        val provisional = synchronized(lock) {
            if (closed || active) return
            active = true
            pending.toList().also { pending.clear() }
        }
        provisional.forEach(::publish)
        reportFailure()
        try { execute(::sampleInitial) } catch (error: Exception) { report(error) }
    }

    private fun sampleInitial() {
        val before = synchronized(lock) { if (closed) return; sequence }
        try {
            val (state, time) = snapshot()
            val admitted = synchronized(lock) {
                when {
                    closed || failure != null -> false
                    sequence == before -> accept(state, time)
                    state == true && !positiveObserved -> {
                        // Keep callback state: the two APIs expose no shared observation order.
                        positiveObserved = true
                        true
                    }
                    else -> false
                }
            }
            if (admitted) publish(time)
        } catch (error: Exception) { report(error) }
    }

    private fun publish(time: Long) {
        try { deliver(time) } catch (error: Exception) { report(error) }
    }

    fun report(error: Exception) {
        synchronized(lock) { if (failure == null) failure = error }
        reportFailure()
    }

    private fun reportFailure() {
        val error = synchronized(lock) {
            if (closed || !active || failureDelivered) null else failure?.also { failureDelivered = true }
        }
        if (error != null) runCatching { failed(error) }
    }

    override fun close() {
        synchronized(lock) { closed = true; pending.clear() }
    }
}

/** Bounded observation writes, including retry delays; no fact is allocated on saturation. */
internal class CaptureObservationAdmission(private val maximumInFlight: Int = 64) {
    private val lock = Any()
    private var inFlight = 0
    private var overflowReported = false
    private class Pending(val root: File, val incident: RecordingIncident)
    private val pending = mutableSetOf<Pending>()
    init { require(maximumInFlight > 0) }

    fun incidents(root: File): List<RecordingIncident> {
        val owner = root.absoluteFile
        return synchronized(lock) { pending.mapNotNull { if (it.root == owner) it.incident else null } }
    }

    fun submit(
        scope: kotlinx.coroutines.CoroutineScope,
        root: File,
        create: () -> RecordingIncident,
        persist: suspend (RecordingIncident) -> Unit,
        onFailure: (Exception) -> Unit,
    ): kotlinx.coroutines.Job? {
        fun report(error: Exception) { try { onFailure(error) } catch (_: Exception) {} }
        var reportOverflow = false
        val admitted = synchronized(lock) {
            if (inFlight < maximumInFlight) { inFlight++; true }
            else { reportOverflow = !overflowReported; overflowReported = true; false }
        }
        if (!admitted) {
            if (reportOverflow) report(IllegalStateException(
                "Microphone input observations could not be saved: pending-work limit reached"))
            return null
        }
        var released = false
        var registered: Pending? = null
        fun release() = synchronized(lock) {
            if (!released) {
                released = true
                registered?.let { pending.remove(it) }
                inFlight--
                overflowReported = false
            }
        }
        return try {
            val incident = create()
            val owner = Pending(root.absoluteFile, incident)
            // Register before even an undispatched launch can persist; create never holds this lock.
            synchronized(lock) { registered = owner; pending.add(owner) }
            scope.launch {
                try { persist(incident) } catch (error: Exception) {
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    report(error)
                }
            }.also { job -> job.invokeOnCompletion { release() } }
        } catch (error: Throwable) {
            release()
            if (error !is Exception) throw error
            if (error !is kotlinx.coroutines.CancellationException) report(error)
            null
        }
    }
}
