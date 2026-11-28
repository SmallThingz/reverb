package app.smallthingz.reverb

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Pending jobs are not finished recording assets and never grant playback/delete authority. */
internal enum class SavePhase { QUEUED, SAVING, RECOVERING, FAILED }

internal data class PendingSave(
    val id: String,
    val displayName: String,
    val startedAtMillis: Long,
    val durationMillis: Long,
    val phase: SavePhase,
    val progress: Float? = null,
    val error: String? = null,
    val publishedRecordingId: String? = null,
    val codecSummary: String = "",
    val publishedRecordingIdentity: String? = null,
    val terminallyRetired: Boolean = false,
    val startupDiscovered: Boolean = false,
)

internal fun PendingSave.matchesPublishedRecording(recordings: Map<String, String>): Boolean {
    if (!terminallyRetired || phase == SavePhase.FAILED) return false
    return matchesPublishedIdentity(recordings)
}

internal fun PendingSave.matchesPublishedIdentity(recordings: Map<String, String>): Boolean {
    val id = publishedRecordingId ?: return false
    val identity = publishedRecordingIdentity?.takeIf { it.isNotBlank() } ?: return false
    val observed = recordings[id]?.takeIf { it.isNotBlank() } ?: return false
    return fileIdentityMatches(identity, observed)
}

internal fun retryDurableSavesForLibraryRefresh(
    readiness: DurableSaveJobs.Readiness,
    pending: List<PendingSave>,
    userInitiated: Boolean,
): Boolean = !readiness.loaded || readiness.recoveryFailure != null ||
    (userInitiated && pending.any {
        it.phase == SavePhase.FAILED || (it.phase == SavePhase.QUEUED && it.startupDiscovered)
    })

internal fun durableSaveCompletionNeedsCatalogRefresh(
    previous: List<PendingSave>,
    current: List<PendingSave>,
    visibleRecordings: Map<String, String>,
): Boolean {
    val newlyPublished = current.any { job ->
        job.terminallyRetired && job.phase != SavePhase.FAILED && !job.publishedRecordingId.isNullOrBlank() &&
            !job.publishedRecordingIdentity.isNullOrBlank() && !job.matchesPublishedRecording(visibleRecordings) &&
            previous.none { old -> old.id == job.id && old.terminallyRetired && old.phase != SavePhase.FAILED &&
                old.publishedRecordingId == job.publishedRecordingId &&
                old.publishedRecordingIdentity == job.publishedRecordingIdentity }
    }
    return newlyPublished || previous.any { old ->
        current.none { it.id == old.id } && !old.matchesPublishedRecording(visibleRecordings)
    }
}

/** Process-local publication ownership; never grants disk or recording authority. */
internal class DurableSavePublicationReceipt internal constructor(
    internal val id: String,
    internal val fromHydration: Boolean = false,
)

internal class DurableSaveRecoveryScan(val observed: Map<String, DurableSavePublicationReceipt>) {
    val rows = ArrayList<PendingSave>()
    val retiredIds = ArrayList<String>()
    var presentOrOwnedIds: Set<String> = emptySet()
}

internal object DurableSaveJobs {
    data class Readiness(val loaded: Boolean = false, val recoveryFailure: String? = null)
    private val mutableReadiness = MutableStateFlow(Readiness())
    val readiness: StateFlow<Readiness> = mutableReadiness.asStateFlow()
    private val presentation = DurableSavePresentationState()
    val state: StateFlow<List<PendingSave>> = presentation.state
    private var recoveryOwner: Any? = null
    private var recoveryRequest: (() -> Unit)? = null

    internal fun recovered(error: String? = null) {
        mutableReadiness.value = Readiness(loaded = error == null, recoveryFailure = error)
    }

    internal fun publish(job: PendingSave) = presentation.publish(job)
    internal fun recoveryHandoffDropped(scan: DurableSaveRecoveryScan) {
        // Scanning may have cleared an earlier read error before a previously invisible
        // job could be adopted. Keep Library refresh eligible without creating a receipt.
        val visible = state.value
        if (scan.rows.any { row -> row.id !in scan.observed && visible.none { it.id == row.id } }) {
            mutableReadiness.compareAndSet(Readiness(loaded = true), Readiness())
        }
    }

    internal fun beginRecoveryScan() = presentation.beginRecoveryScan()
    internal fun adoptRecovery(scan: DurableSaveRecoveryScan, ownedIds: Set<String>) =
        presentation.adoptRecovery(scan, ownedIds)
    internal fun hydrateIfAbsent(job: PendingSave) = presentation.hydrateIfAbsent(job)
    internal fun failIfCurrent(receipt: DurableSavePublicationReceipt, error: String) = presentation.failIfCurrent(receipt, error)
    internal fun claimFailureDelivery(receipt: DurableSavePublicationReceipt) = presentation.claimFailureDelivery(receipt)
    internal fun remove(id: String) = presentation.remove(id)
    internal fun reconcileAuthoritativeJobs(presentIds: Set<String>) = presentation.reconcileAuthoritativeJobs(presentIds)
    internal fun acknowledgePublished(recordings: Map<String, String>) = presentation.acknowledgePublished(recordings)

    @Synchronized
    internal fun setRecoveryRequest(owner: Any, callback: () -> Unit) {
        recoveryOwner = owner
        recoveryRequest = callback
    }

    @Synchronized
    internal fun clearRecoveryRequest(owner: Any) {
        if (recoveryOwner !== owner) return
        recoveryOwner = null
        recoveryRequest = null
    }

    internal fun retryRecovery() {
        val request = synchronized(this) { recoveryRequest }
        request?.invoke()
    }
}

/** Keep the final ID observable until the catalog has supplied its row, despite StateFlow conflation. */
internal class DurableSavePresentationState {
    private val mutableState = MutableStateFlow<List<PendingSave>>(emptyList())
    val state: StateFlow<List<PendingSave>> = mutableState.asStateFlow()
    private val currentPublications = HashMap<String, DurableSavePublicationReceipt>()
    private val claimedFailureDeliveries = HashSet<DurableSavePublicationReceipt>()
    private val retiredPublishedIds = LinkedHashSet<String>()

    @Synchronized
    fun publish(job: PendingSave, fromHydration: Boolean = false): DurableSavePublicationReceipt? {
        val receipt = replacePublication(job.id, fromHydration)
        // Only remove(), called after durable source/journal retirement, may grant terminal status.
        mutableState.value = (mutableState.value.filterNot { it.id == job.id } + job.copy(terminallyRetired = false))
            .sortedByDescending { it.startedAtMillis }
        return receipt
    }

    @Synchronized
    fun hydrateIfAbsent(job: PendingSave) {
        if (mutableState.value.none { it.id == job.id }) publish(job, fromHydration = true)
    }

    @Synchronized
    fun beginRecoveryScan() = DurableSaveRecoveryScan(currentPublications.toMap())

    /** Apply observations only after the Service accepts the generation on main. */
    @Synchronized
    fun adoptRecovery(scan: DurableSaveRecoveryScan, ownedIds: Set<String>): List<DurableSaveHandle> {
        fun unchanged(id: String): Boolean = id !in ownedIds &&
            currentPublications[id] === scan.observed[id]

        for (id in scan.retiredIds) {
            if (unchanged(id)) remove(id)
        }
        reconcileAuthoritativeJobs(scan.presentOrOwnedIds, ::unchanged)
        val handles = ArrayList<DurableSaveHandle>()
        val updates = LinkedHashMap<String, PendingSave>()
        for (job in scan.rows) {
            if (job.id in ownedIds || job.id in retiredPublishedIds) continue
            val current = currentPublications[job.id]
            val observed = scan.observed[job.id]
            // Application may have hydrated an initially absent row while main was queued.
            if (current !== observed && !(observed == null && current?.fromHydration == true)) continue
            val receipt = replacePublication(job.id, fromHydration = false)
            updates[job.id] = job
            if (job.phase == SavePhase.RECOVERING) handles.add(DurableSaveHandle(job.id, receipt))
        }
        if (updates.isNotEmpty()) {
            mutableState.value = (mutableState.value.filterNot { it.id in updates } + updates.values)
                .sortedByDescending { it.startedAtMillis }
        }
        return handles
    }

    @Synchronized
    fun failIfCurrent(receipt: DurableSavePublicationReceipt, error: String): DurableSavePublicationReceipt? {
        if (currentPublications[receipt.id] !== receipt) return null
        val job = mutableState.value.firstOrNull { it.id == receipt.id } ?: return null
        return publish(job.copy(phase = SavePhase.FAILED, progress = null, error = error))
    }

    /** Linearizes delivery without invoking arbitrary receiver code under this monitor. */
    @Synchronized
    fun claimFailureDelivery(receipt: DurableSavePublicationReceipt): Boolean =
        currentPublications[receipt.id] === receipt &&
            mutableState.value.any { it.id == receipt.id && it.phase == SavePhase.FAILED } &&
            claimedFailureDeliveries.add(receipt)

    private fun replacePublication(id: String, fromHydration: Boolean): DurableSavePublicationReceipt {
        retiredPublishedIds.remove(id)
        invalidatePublication(id)
        return DurableSavePublicationReceipt(id, fromHydration).also { currentPublications[id] = it }
    }

    private fun invalidatePublication(id: String) {
        currentPublications.remove(id)?.let(claimedFailureDeliveries::remove)
    }

    @Synchronized
    fun remove(id: String) {
        invalidatePublication(id)
        val job = mutableState.value.firstOrNull { it.id == id } ?: return
        if (job.publishedRecordingId == null) {
            mutableState.value = mutableState.value.filterNot { it.id == id }
            return
        }
        retiredPublishedIds.add(id)
        mutableState.value = mutableState.value.map { if (it.id == id) it.copy(terminallyRetired = true) else it }
        // Completed outputs are already catalog-owned. Bound their presentation-only handoff;
        // active/failed jobs with retained sources are never evicted by this cache limit.
        while (retiredPublishedIds.size > 128) {
            val oldest = retiredPublishedIds.first()
            retiredPublishedIds.remove(oldest)
            invalidatePublication(oldest)
            mutableState.value = mutableState.value.filterNot { it.id == oldest }
        }
    }

    @Synchronized
    fun acknowledgePublished(recordings: Map<String, String>) {
        val observed = mutableState.value.filter { it.matchesPublishedRecording(recordings) }
        if (observed.isEmpty()) return
        val observedIds = observed.mapTo(HashSet()) { it.id }
        for (id in observedIds) {
            invalidatePublication(id)
            retiredPublishedIds.remove(id)
        }
        mutableState.value = mutableState.value.filterNot { it.id in observedIds }
    }

    /** Call only after a complete journal membership observation crosses its durability barrier. */
    @Synchronized
    fun reconcileAuthoritativeJobs(presentIds: Set<String>, canRemove: (String) -> Boolean = { true }) {
        val absentFailures = mutableState.value.filter {
            it.phase == SavePhase.FAILED && it.id !in presentIds && canRemove(it.id)
        }.mapTo(HashSet()) { it.id }
        if (absentFailures.isEmpty()) return
        absentFailures.forEach(::invalidatePublication)
        retiredPublishedIds.removeAll(absentFailures)
        mutableState.value = mutableState.value.filterNot { it.id in absentFailures }
    }
}
