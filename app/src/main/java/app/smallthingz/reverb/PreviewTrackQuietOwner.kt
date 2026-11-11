package app.smallthingz.reverb

import java.util.concurrent.RejectedExecutionException

/** One instance per physical AudioTrack; only the caller holds the native track. */
internal class PreviewTrackQuietOwner(private val coordinator: PreviewQuietCoordinator) {
    val observationIdentity = Any()
    private val stateLock = Any()
    // Only audio/cleanup workers may acquire this gate. retire() never touches it.
    private val audioGate = Any()
    private val writeGate = Any()
    private var terminal = false
    private var releaseClaimed = false
    private var ticket: PreviewQuietCoordinator.Ticket? = null
    private var quietAttempted = false
    private var quietSucceeded = false
    private var quietFailure: Throwable? = null
    private var cleanupDispatched = false
    private var silenceDispatched = false
    private var silenceDispatchFailure: Throwable? = null
    @Volatile var nativeReleaseSucceeded = false
        private set
    @Volatile var acceptedWriteBytes = 0L
        private set

    fun claimCleanupDispatch(): Boolean = synchronized(stateLock) {
        if (cleanupDispatched) false else { cleanupDispatched = true; true }
    }

    /** Only enqueue here: the short state lock orders dispatch failure before terminal reporting. */
    fun dispatchSilence(dispatch: () -> Unit) {
        val rejected = synchronized(stateLock) {
            if (cleanupDispatched || releaseClaimed || silenceDispatched) null else {
                silenceDispatched = true
                try {
                    dispatch()
                    null
                } catch (error: RejectedExecutionException) {
                    silenceDispatchFailure = error
                    retire()
                }
            }
        }
        // A waiter may resume inline. Never run its continuation under the native owner's lock.
        // Successful release can win this race; the coordinator ignores failure after QUIET.
        rejected?.let(coordinator::failed)
    }

    /** Pause may interrupt a blocking write; terminal release waits for its return. */
    fun write(action: () -> Int): Int? = synchronized(writeGate) {
        if (synchronized(stateLock) { terminal }) null else action().also { written ->
            if (written > 0) acceptedWriteBytes += written
        }
    }

    /** Native position sampling shares the terminal lifetime fence with writes. */
    fun <T> read(action: () -> T): T? = synchronized(writeGate) {
        if (synchronized(stateLock) { terminal }) null else action()
    }

    /** Called synchronously at UI retirement, before enqueueing native cleanup. */
    fun retire(): PreviewQuietCoordinator.Ticket = synchronized(stateLock) {
        terminal = true
        ticket ?: coordinator.register().also { ticket = it }
    }

    /** Worker-only. A retired owner can never start or restart the native stream. */
    fun play(start: () -> Unit): Boolean = synchronized(audioGate) {
        if (synchronized(stateLock) { terminal }) false
        else { start(); true }
    }

    /**
     * Report a rejected cleanup dispatch without claiming native release. A separately admitted
     * fallback worker may still call release(). Until positive proof, the ticket stays unsafe.
     */
    fun cleanupRejected() { coordinator.failed(retire()) }

    /** The control worker never waits for terminal native release on another track. */
    fun silence(pause: () -> Unit, flush: () -> Unit): Boolean {
        val retiring = retire()
        return synchronized(audioGate) {
            if (!quietAttempted) {
                quietAttempted = true
                fun attempt(block: () -> Unit): Boolean = try {
                    block(); true
                } catch (error: Throwable) {
                    val prior = quietFailure
                    if (prior == null) quietFailure = error else if (prior !== error) prior.addSuppressed(error)
                    false
                }
                val paused = attempt(pause)
                val flushed = attempt(flush)
                quietSucceeded = paused && flushed
                if (quietSucceeded) coordinator.quiet(retiring)
            }
            quietSucceeded
        }
    }

    /**
     * Worker-only; duplicate callers share retirement and only one touches the native resource.
     * AudioTrack.stop is deliberately absent: streaming stop can drain queued samples.
     */
    fun release(pause: () -> Unit, flush: () -> Unit, release: () -> Unit,
                onFailure: (Throwable) -> Unit): Boolean {
        val retiring = retire()
        val claimed = synchronized(stateLock) {
            if (releaseClaimed) false else { releaseClaimed = true; true }
        }
        if (!claimed) return false
        val silenced = silence(pause, flush)
        var failure: Throwable? = synchronized(stateLock) { silenceDispatchFailure }
        synchronized(audioGate) { quietFailure }?.let { error ->
            if (failure == null) failure = error else if (failure !== error) failure?.addSuppressed(error)
        }
        fun attempt(block: () -> Unit): Boolean = try {
            block(); true
        } catch (error: Throwable) {
            val prior = failure
            if (prior == null) failure = error else if (prior !== error) prior.addSuppressed(error)
            false
        }
        val released = synchronized(writeGate) { attempt(release) }
        nativeReleaseSucceeded = released
        if (released) coordinator.quiet(retiring)
        else if (!silenced) coordinator.failed(retiring)
        failure?.let(onFailure)
        return true
    }
}
