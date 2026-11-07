package app.smallthingz.reverb

/** One instance per physical AudioTrack; only the caller holds the native track. */
internal class PreviewTrackQuietOwner(private val coordinator: PreviewQuietCoordinator) {
    private val stateLock = Any()
    // Only audio/cleanup workers may acquire this gate. retire() never touches it.
    private val audioGate = Any()
    private var terminal = false
    private var releaseClaimed = false
    private var ticket: PreviewQuietCoordinator.Ticket? = null

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
        var failure: Throwable? = null
        fun attempt(block: () -> Unit): Boolean = try {
            block(); true
        } catch (error: Throwable) {
            val prior = failure
            if (prior == null) failure = error else if (prior !== error) prior.addSuppressed(error)
            false
        }
        val silenced = synchronized(audioGate) {
            val paused = attempt(pause)
            val flushed = attempt(flush)
            paused && flushed
        }
        // Once paused and flushed, expensive native release need not delay new playback.
        if (silenced) coordinator.quiet(retiring)
        val released = attempt(release)
        if (released) coordinator.quiet(retiring)
        else if (!silenced) coordinator.failed(retiring)
        failure?.let(onFailure)
        return true
    }
}
