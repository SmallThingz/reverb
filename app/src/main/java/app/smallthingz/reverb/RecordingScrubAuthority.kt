package app.smallthingz.reverb

internal data class RecordingScrubKey(
    val generation: Long,
    val transportIntentRevision: Long,
    val retainedIdle: Boolean = false,
)

/** Implementations contain native quiet ownership only, never a row, controller or UI callback. */
internal interface RecordingScrubQuietHandle {
    fun retire()
    fun requestQuiet()
}

/** One ordinary gesture; its exact native registration may change during a bounded restart. */
internal class RecordingScrubAuthority internal constructor(
    val key: RecordingScrubKey,
    /** Playback intent at admission; gesture-induced suspension must not overwrite it. */
    val resumePlayback: Boolean = false,
) {
    private val lock = Any()
    private var handle: RecordingScrubQuietHandle? = null
    @Volatile var active = true
        private set
    @Volatile var playbackSuspended = false
        private set

    /** The matching service paused playback or suppressed an accepted seek resume for this gesture. */
    fun acknowledgePlaybackSuspended() = synchronized(lock) {
        if (active) playbackSuspended = true
    }

    fun attach(next: RecordingScrubQuietHandle): Boolean {
        val accepted = synchronized(lock) {
            if (active && (handle == null || handle === next)) {
                handle = next
                true
            } else {
                next.retire()
                false
            }
        }
        if (!accepted) next.requestQuiet()
        return accepted
    }

    fun detach(previous: RecordingScrubQuietHandle) = synchronized(lock) {
        if (handle === previous) handle = null
    }

    fun revoke() {
        val retiring = synchronized(lock) {
            if (!active) return
            active = false
            handle.also { it?.retire(); handle = null }
        }
        // No native call or audioGate acquisition occurs on the revoking thread.
        retiring?.requestQuiet()
    }
}

/** Main-owned registry. Worker-visible authority and registration synchronization live in the lease. */
internal class RecordingScrubAuthorities {
    private var current: RecordingScrubAuthority? = null

    fun acquire(key: RecordingScrubKey, resumePlayback: Boolean = false): RecordingScrubAuthority {
        revoke()
        return RecordingScrubAuthority(key, resumePlayback).also { current = it }
    }

    fun published(key: RecordingScrubKey?) {
        if (current?.key != key) revoke()
    }

    fun owns(authority: RecordingScrubAuthority): Boolean = current === authority && authority.active

    fun finish(authority: RecordingScrubAuthority) {
        if (current === authority) current = null
        authority.revoke()
    }

    /** Old service destruction must not revoke an independent retained-cursor reader. */
    fun retireServiceOwner() {
        if (current?.key?.retainedIdle != true) revoke()
    }

    fun revoke() {
        val retiring = current
        current = null
        retiring?.revoke()
    }
}
