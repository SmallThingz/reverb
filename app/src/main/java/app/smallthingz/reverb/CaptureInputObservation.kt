package app.smallthingz.reverb

import java.io.Closeable
import java.io.File
import kotlinx.coroutines.launch

internal interface CapturePolicySubscription : Closeable {
    /** Latest explicit callback state for the active subscription, or unknown. */
    val callbackSilenced: Boolean? get() = null
    fun pollDropout(): CapturePolicyDropout? = null
    /** Retire new evidence and end any still-open callback witness at the supplied boundary. */
    fun sealDropouts(endedAtElapsedMillis: () -> Long) = Unit
    /** Called only after native start and session admission succeed. */
    fun activate()
}

internal class CapturePolicyDropout(val wallMillis: Long, val elapsedMillis: Long) {
    // Null is still open. -1 records an unproven boundary, such as a missing configuration.
    @Volatile var endedAtElapsedMillis: Long? = null
}

internal class CaptureObservationDuration(val startedAtElapsedMillis: Long) {
    private val end = kotlinx.coroutines.CompletableDeferred<Long>()
    val isOngoing: Boolean get() = !end.isCompleted
    init { require(startedAtElapsedMillis >= 0L) }

    fun finish(endedAtElapsedMillis: Long) { end.complete(endedAtElapsedMillis) }

    suspend fun historyEndAtMillis(startWallMillis: Long): Long {
        val endedAt = end.await()
        if (endedAt < startedAtElapsedMillis) return -1L
        return startWallMillis + minOf(endedAt - startedAtElapsedMillis, Long.MAX_VALUE - startWallMillis)
    }
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
    private val callbackStateChanged: () -> Unit = {},
    private val dropoutsAvailable: (() -> Unit)? = null,
) : CapturePolicySubscription {
    private val lock = Any()
    private val episode = CapturePolicyEpisode()
    private val pending = java.util.ArrayDeque<CapturePolicyDropout>()
    private var callbackDropout: CapturePolicyDropout? = null
    private var sequence = 0L
    private var positiveObserved = false
    private var active = false
    private var closed = false
    private var dropoutsSealed = false
    private var failure: Exception? = null
    private var failureDelivered = false
    private var provisionalCallbackSilenced: Boolean? = null
    @Volatile override var callbackSilenced: Boolean? = null
        private set

    fun observe(value: Boolean?, observedAtMillis: Long, elapsedMillis: Long = observedAtMillis) {
        var unsilencedHint = false
        val stateChanged: Boolean
        val newEvidence = synchronized(lock) {
            if (closed || dropoutsSealed || failure != null) return
            stateChanged = if (active) setCallbackSilenced(value) else {
                provisionalCallbackSilenced = value
                false
            }
            if (value != null) {
                sequence++
                // Only an explicit false callback may cue a reminder recheck.
                unsilencedHint = active && !value
            }
            val changed = if (observeEpisode(value)) {
                if (pending.size == 64) {
                    failure = IllegalStateException("Microphone policy observation queue exhausted")
                    setCallbackSilenced(null)
                } else {
                    val dropout = CapturePolicyDropout(observedAtMillis, elapsedMillis)
                    callbackDropout = dropout
                    pending.addLast(dropout)
                }
                true
            } else if (value != true) {
                finishDropout(if (value == false) elapsedMillis else -1L)
            } else {
                false
            }
            active && changed
        }
        if (stateChanged) callbackStateChanged()
        if (newEvidence) publishDropouts()
        reportFailure()
        if (unsilencedHint) {
            try { deliverUnsilenced(observedAtMillis) } catch (error: Exception) { report(error) }
        }
    }

    private fun setCallbackSilenced(value: Boolean?): Boolean {
        if (callbackSilenced == value) return false
        callbackSilenced = value
        return true
    }

    private fun observeEpisode(value: Boolean?): Boolean {
        if (!episode.observe(value)) return false
        positiveObserved = true
        return true
    }

    private fun finishDropout(endedAt: Long): Boolean {
        val dropout = callbackDropout ?: return false
        dropout.endedAtElapsedMillis = endedAt
        callbackDropout = null
        return true
    }

    override fun pollDropout(): CapturePolicyDropout? = synchronized(lock) {
        if (active && !closed) pending.pollFirst() else null
    }

    override fun sealDropouts(endedAtElapsedMillis: () -> Long) {
        synchronized(lock) {
            dropoutsSealed = true
            if (callbackDropout != null) finishDropout(endedAtElapsedMillis())
            setCallbackSilenced(null)
        }
    }

    private fun publishDropouts() {
        try {
            if (dropoutsAvailable != null) dropoutsAvailable()
            else while (true) deliver((pollDropout() ?: return).wallMillis)
        } catch (error: Exception) { report(error) }
    }

    /** Service holds its owner-admission lock while adopting provisional start observations. */
    override fun activate() {
        val stateChanged: Boolean
        synchronized(lock) {
            if (closed || active) return
            active = true
            stateChanged = failure == null && setCallbackSilenced(provisionalCallbackSilenced)
            provisionalCallbackSilenced = null
        }
        if (stateChanged) callbackStateChanged()
        publishDropouts()
        reportFailure()
        try { execute(::sampleInitial) } catch (error: Exception) { report(error) }
    }

    private fun sampleInitial() {
        val before = synchronized(lock) { if (closed || dropoutsSealed) return; sequence }
        try {
            val (state, time) = snapshot()
            val admitted = synchronized(lock) {
                when {
                    closed || dropoutsSealed || failure != null -> false
                    sequence == before -> state == true && observeEpisode(state)
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
        val stateChanged = synchronized(lock) {
            if (failure == null) failure = error
            finishDropout(-1L)
            setCallbackSilenced(null)
        }
        if (stateChanged) callbackStateChanged()
        reportFailure()
    }

    private fun reportFailure() {
        val error = synchronized(lock) {
            if (closed || !active || failureDelivered) null else failure?.also { failureDelivered = true }
        }
        if (error != null) runCatching { failed(error) }
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            pending.clear()
            finishDropout(-1L)
            provisionalCallbackSilenced = null
            callbackSilenced = null
        }
    }
}

/** Bounded observation writes, including retry delays; no fact is allocated on saturation. */
internal class CaptureObservationAdmission(private val maximumInFlight: Int = 64) {
    private val lock = Any()
    private var inFlight = 0
    private var overflowReported = false
    private class Pending(val root: File, val incident: RecordingIncident, val duration: CaptureObservationDuration?)
    private val pending = mutableSetOf<Pending>()
    init { require(maximumInFlight > 0) }

    fun incidents(root: File): List<RecordingIncident> {
        val owner = root.absoluteFile
        return synchronized(lock) { pending.mapNotNull { if (it.root == owner) it.incident else null } }
    }

    fun ongoingDuration(root: File, incident: RecordingIncident): CaptureObservationDuration? = synchronized(lock) {
        pending.firstOrNull { it.root == root && recordingIncidentReferenceMatches(it.incident, incident) }
            ?.duration?.takeIf { it.isOngoing }
    }

    fun submit(
        scope: kotlinx.coroutines.CoroutineScope,
        root: File,
        create: () -> RecordingIncident,
        persist: suspend (RecordingIncident) -> Unit,
        onFailure: (Exception) -> Unit,
        duration: CaptureObservationDuration? = null,
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
            val owner = Pending(root.absoluteFile, incident, duration)
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
