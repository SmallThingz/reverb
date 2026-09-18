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
     * Queued/running work and a previously proven backlog both outrank a later negative
     * observation: that observation may have been sampled before the previous pass published
     * needsMore=true. Only completePass(false) or explicit clear() may retire known backlog.
     */
    fun claimObservedNeed(needed: Boolean): Boolean = synchronized(lock) {
        if (passQueued) {
            active = true
            return@synchronized false
        }
        if (active) {
            if (!needed) return@synchronized false
            passQueued = true
            return@synchronized true
        }
        if (!needed) return@synchronized false
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
