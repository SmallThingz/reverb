package app.smallthingz.reverb

import android.content.Context
import android.util.Log
import java.io.File

/** A stale snapshot keeps this small retired owner, never a process-global token tombstone. */
internal class FileCopyRecoveryIncidentOwner {
    private var reported = false
    private var retired = false

    @Synchronized fun claim(): Boolean {
        if (reported || retired) return false
        reported = true
        return true
    }

    @Synchronized fun retire(withIncident: Boolean): Boolean {
        val report = withIncident && !reported && !retired
        retired = true
        return report
    }
}

/** Accessed under the FILE-copy journal mutex; members exist only for live journal tokens. */
internal class FileCopyRecoveryIncidentOwners {
    private val owners = HashMap<String, FileCopyRecoveryIncidentOwner>()
    val size: Int get() = owners.size

    fun bind(record: FileCopyPublication): FileCopyPublication = record.also {
        it.recoveryIncidentOwner = owners.getOrPut(it.token) { FileCopyRecoveryIncidentOwner() }
    }

    fun retire(record: FileCopyPublication, withIncident: Boolean): Boolean {
        val owner = owners.remove(record.token) ?: record.recoveryIncidentOwner ?: FileCopyRecoveryIncidentOwner()
        record.recoveryIncidentOwner = owner
        return owner.retire(withIncident)
    }
}

private val unavailableJournalIncident = FileCopyRecoveryIncidentOwner()

internal fun enqueueFileCopyRecoveryIncident(context: Context, failure: Exception) {
    RecordingIncidentStore.recordUnexpectedErrorInBackground(context,
        "File publication preserved for recovery", failure)
}

internal fun reportFileCopyJournalUnavailableIncident(context: Context, failure: Exception) {
    if (unavailableJournalIncident.claim()) enqueueFileCopyRecoveryIncident(context, failure)
}

internal fun reportFileCopyRecoveryIncident(context: Context, record: FileCopyPublication, failure: Exception) {
    // Token ownership also covers repeated recovery attempts whose incident was already queued.
    if (failure is FileCopyIncidentException) failure.incidentOwnedByRecovery = true
    if (checkNotNull(record.recoveryIncidentOwner).claim()) enqueueFileCopyRecoveryIncident(context, failure)
}

internal fun recoverPendingFileCopyPublications(context: Context) {
    for (record in FileCopyPublications.snapshot(context).records) {
        if (FileCopyPublications.sourceIsActive(record.sourceId)) continue
        if (record.phase >= FileCopyPhase.VERIFIED && record.purpose != FileCopyPurpose.CLAIM_RESTORE) continue
        try {
            FileCopyPublications.withSource(File(record.sourceId)) {
                if (record.purpose == FileCopyPurpose.CLAIM_RESTORE) {
                    // Already under the source owner: do not recursively acquire it through the move adapter.
                    resumeDeletionClaimCopyOwned(ContextFileCopyPublicationStore(context), DefaultFileCopyPublicationIo,
                        record, ::retireCopiedDeletionClaim)
                } else recoverCopiedFileRecord(context, File(record.sourceId), record.requestedName, record.sourceFingerprint())
            }
        } catch (failure: Exception) {
            reportFileCopyRecoveryIncident(context, record, failure)
            Log.w("FileCopyPublication", "Copied publication remains preserved: ${record.token}", failure)
        }
    }
}

internal fun recoverCopiedFileTarget(
    context: Context,
    target: RecordingOutputTarget,
    expected: StableOutputFingerprint,
): RecordingOutputTarget? {
    val record = recoverCopiedFileRecord(context, requireNotNull(target.file), target.displayName, expected) ?: return null
    return target.copy(id = record.targetId, file = File(record.targetId), displayName = File(record.targetId).name,
        staging = false, publishedIdentity = record.targetIdentity)
}

internal fun recoverCopiedFileRecord(
    context: Context,
    source: File,
    requestedName: String,
    expected: StableOutputFingerprint,
): FileCopyPublication? = try {
    recoverFileCopyPublication(ContextFileCopyPublicationStore(context), DefaultFileCopyPublicationIo,
        source, requestedName, expected)
} catch (failure: FileCopyRecoveryRequired) {
    val records = FileCopyPublications.snapshot(context).records.filter { it.matchesSource(source, expected) }
    records.forEach { record ->
        reportFileCopyRecoveryIncident(context, record, failure)
    }
    throw failure
}
