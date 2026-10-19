package app.smallthingz.reverb

internal class RetentionMaintenanceSchedulerState {
    private val lock = Any()
    private var active = false
    private var queuedPassId: Long? = null
    private var nextPassId = 1L
    private var activeGeneration = 0L

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
        activeGeneration++
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

    fun ownsPass(passId: Long): Boolean = synchronized(lock) { active && queuedPassId == passId }

    fun cancelPass(passId: Long): Boolean = synchronized(lock) {
        if (queuedPassId != passId) return@synchronized false
        active = false
        queuedPassId = null
        true
    }

    fun currentGeneration(): Long? = synchronized(lock) { activeGeneration.takeIf { active } }

    fun clearGeneration(generation: Long): Boolean = synchronized(lock) {
        if (!active || activeGeneration != generation) return@synchronized false
        active = false
        queuedPassId = null
        true
    }

    private fun claimPassLocked(): Long {
        val passId = nextPassId++
        queuedPassId = passId
        return passId
    }
}

/** A claimed pass cannot touch storage until its current foreground owner accepts it on main. */
internal fun admitRetentionPassOnMain(
    state: RetentionMaintenanceSchedulerState,
    passId: Long,
    post: (Runnable) -> Boolean,
    mayRun: () -> Boolean,
    protect: () -> Boolean,
    submit: (Long) -> Unit,
    rejected: () -> Unit,
) {
    fun retire() { if (state.cancelPass(passId)) rejected() }
    val task = Runnable {
        if (!state.ownsPass(passId)) return@Runnable
        if (!mayRun()) { retire(); return@Runnable }
        if (!protect()) { retire(); return@Runnable }
        // Platform promotion may invoke controlled callbacks; ownership must still be exact.
        if (!state.ownsPass(passId)) return@Runnable
        if (!mayRun()) { retire(); return@Runnable }
        submit(passId)
    }
    if (!post(task)) retire()
}

/** Called on main after capture, export and Clear have relinquished their foreground owners. */
internal fun settleIdleServiceLifetime(
    retention: RetentionMaintenanceSchedulerState,
    protectRetention: () -> Boolean,
    settingsActive: () -> Boolean,
    stopForeground: () -> Unit,
    stopService: () -> Unit,
) {
    val generation = retention.currentGeneration()
    if (generation != null) {
        if (protectRetention()) return
        retention.clearGeneration(generation)
        if (retention.isActive()) return
    }
    // Settings owns a started lifetime, but cannot remove another owner's data-sync foreground.
    stopForeground()
    if (!settingsActive()) stopService()
}
