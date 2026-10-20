package app.smallthingz.reverb

internal class RetentionMaintenanceSchedulerState {
    private val lock = Any()
    private var active = false
    private var queuedPassId: Long? = null
    private var nextPassId = 1L
    private var activeGeneration = 0L
    private var admissionGeneration = 0L
    private var queuedAdmissionGeneration: Long? = null
    private var failureBlocked = false

    /** Only a completed configuration reopens failed maintenance; cleanup is never recovery. */
    fun rearmAfterConfiguration() = synchronized(lock) {
        admissionGeneration++
        failureBlocked = false
        // A queued/running pass still owns its physical work and foreground lifetime.
    }

    fun observationGeneration(): Long? = synchronized(lock) {
        admissionGeneration.takeUnless { failureBlocked }
    }

    fun failObservation(generation: Long): Boolean = synchronized(lock) {
        if (generation != admissionGeneration || failureBlocked) return@synchronized false
        failureBlocked = true
        // Inspection may overlap a worker. Keep that real owner until its terminal callback.
        if (queuedPassId == null) active = false
        true
    }

    fun isActive(): Boolean = synchronized(lock) { active }

    fun clear() {
        synchronized(lock) {
            retirePassLocked()
            admissionGeneration++
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
    fun claimObservedNeed(needed: Boolean, observedGeneration: Long? = null): Long? = synchronized(lock) {
        if (failureBlocked || (observedGeneration != null && observedGeneration != admissionGeneration)) {
            return@synchronized null
        }
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
        if (failureBlocked || !active || queuedPassId != null) return@synchronized null
        claimPassLocked()
    }

    /**
     * Releases exactly the pass that was claimed. A clear/new claim invalidates an older worker,
     * so its late completion cannot resurrect backlog or revoke ownership from newer work.
     */
    fun completePass(passId: Long, needsMore: Boolean): Boolean = synchronized(lock) {
        if (queuedPassId != passId) return@synchronized false
        queuedPassId = null
        queuedAdmissionGeneration = null
        active = needsMore && !failureBlocked
        true
    }

    fun ownsPass(passId: Long): Boolean = synchronized(lock) { active && queuedPassId == passId }

    fun cancelPass(passId: Long): Boolean = synchronized(lock) {
        if (queuedPassId != passId) return@synchronized false
        retirePassLocked()
        true
    }

    /** A late failure cannot poison a configuration that completed after this pass was claimed. */
    fun failPass(passId: Long): RetentionPassFailure = synchronized(lock) {
        if (queuedPassId != passId) return@synchronized RetentionPassFailure.IGNORED
        val superseded = queuedAdmissionGeneration != admissionGeneration
        retirePassLocked()
        when {
            failureBlocked -> RetentionPassFailure.ALREADY_BLOCKED
            superseded -> {
                // Configuration may have observed backlog while this pass still owned the queue.
                // Keep lifetime until a fresh-generation pass can recheck the configured stores.
                active = true
                RetentionPassFailure.SUPERSEDED
            }
            else -> {
                failureBlocked = true
                RetentionPassFailure.BLOCKED
            }
        }
    }

    fun currentGeneration(): Long? = synchronized(lock) { activeGeneration.takeIf { active } }

    fun clearGeneration(generation: Long): Boolean = synchronized(lock) {
        if (!active || activeGeneration != generation) return@synchronized false
        retirePassLocked()
        true
    }

    private fun retirePassLocked() {
        active = false
        queuedPassId = null
        queuedAdmissionGeneration = null
    }

    private fun claimPassLocked(): Long {
        val passId = nextPassId++
        queuedPassId = passId
        queuedAdmissionGeneration = admissionGeneration
        return passId
    }
}

internal enum class RetentionPassFailure { BLOCKED, ALREADY_BLOCKED, SUPERSEDED, IGNORED }

/** Store observations cannot cross a successful configuration or a latched failure boundary. */
internal fun observeRetentionMaintenancePass(
    state: RetentionMaintenanceSchedulerState,
    observe: () -> Boolean,
    onFailure: (Exception) -> Unit,
): Long? {
    val generation = state.observationGeneration() ?: return null
    val needed = try {
        observe()
    } catch (error: Exception) {
        if (state.failObservation(generation)) onFailure(error)
        return null
    }
    return state.claimObservedNeed(needed, generation)
}

/** Latch before failure cleanup: a successful seal must not retry the same failed maintenance. */
internal fun settleFailedRetentionPass(
    state: RetentionMaintenanceSchedulerState,
    passId: Long,
    onFailure: () -> Unit,
    onSuperseded: () -> Unit,
    onAlreadyBlocked: () -> Unit,
) {
    when (state.failPass(passId)) {
        RetentionPassFailure.BLOCKED -> onFailure()
        RetentionPassFailure.SUPERSEDED -> onSuperseded()
        RetentionPassFailure.ALREADY_BLOCKED -> onAlreadyBlocked()
        RetentionPassFailure.IGNORED -> Unit
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
