package app.smallthingz.reverb

internal class RetentionMaintenanceSchedulerState {
    private val lock = Any()
    private var active = false
    private var passQueued = false

    fun isActive(): Boolean = synchronized(lock) { active }

    fun clear() {
        synchronized(lock) {
            active = false
            passQueued = false
        }
    }

    /**
     * Reconciles a fresh store observation with scheduler ownership.
     *
     * A queued/running pass always wins over the observation: the observation may have been
     * sampled before that pass crossed its mutation boundary, so it cannot revoke accepted work.
     */
    fun claimObservedNeed(needed: Boolean): Boolean = synchronized(lock) {
        if (passQueued) {
            active = true
            return@synchronized false
        }
        if (!needed) {
            active = false
            return@synchronized false
        }
        active = true
        passQueued = true
        true
    }

    /** Claims the next pass for an already-known backlog, such as a lease-blocked retry. */
    fun claimActiveRetry(): Boolean = synchronized(lock) {
        if (!active || passQueued) return@synchronized false
        passQueued = true
        true
    }

    /** Releases the current pass while preserving whether another pass is still required. */
    fun completePass(needsMore: Boolean) {
        synchronized(lock) {
            passQueued = false
            active = needsMore
        }
    }
}
