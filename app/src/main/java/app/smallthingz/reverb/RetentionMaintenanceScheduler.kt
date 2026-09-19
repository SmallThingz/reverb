package app.smallthingz.reverb

internal class RetentionMaintenanceSchedulerState {
    private val lock = Any()
    private var active = false
    private var queuedPassId: Long? = null
    private var nextPassId = 1L

    fun isActive(): Boolean = synchronized(lock) { active }

    fun clear() {
        synchronized(lock) {
            active = false
            queuedPassId = null
        }
    }

    /**
     * Reconciles a fresh store observation with scheduler ownership.
     *
     * Queued/running work and a previously proven backlog both outrank a later negative
     * observation: that observation may have been sampled before the previous pass published
     * needsMore=true. Only an accepted completePass(..., false) or explicit clear() may retire
     * known backlog.
     */
    fun claimObservedNeed(needed: Boolean): Long? = synchronized(lock) {
        if (queuedPassId != null) {
            active = true
            return@synchronized null
        }
        if (active) {
            if (!needed) return@synchronized null
            return@synchronized claimPassLocked()
        }
        if (!needed) return@synchronized null
        active = true
        claimPassLocked()
    }

    /** Claims the next pass for an already-known backlog, such as a lease-blocked retry. */
    fun claimActiveRetry(): Long? = synchronized(lock) {
        if (!active || queuedPassId != null) return@synchronized null
        claimPassLocked()
    }

    /**
     * Releases exactly the pass that was claimed. A clear/new claim invalidates an older worker,
     * so its late completion cannot resurrect backlog or revoke ownership from newer work.
     */
    fun completePass(passId: Long, needsMore: Boolean): Boolean = synchronized(lock) {
        if (queuedPassId != passId) return@synchronized false
        queuedPassId = null
        active = needsMore
        true
    }

    private fun claimPassLocked(): Long {
        val passId = nextPassId++
        queuedPassId = passId
        return passId
    }
}
