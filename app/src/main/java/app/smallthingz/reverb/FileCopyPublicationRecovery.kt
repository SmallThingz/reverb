package app.smallthingz.reverb

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.ConcurrentHashMap

private val reportedFileCopyRecovery = ConcurrentHashMap.newKeySet<String>()

internal fun reportFileCopyRecoveryIncident(context: Context, token: String, failure: Exception) {
    // Token ownership also covers repeated recovery attempts whose incident was already queued.
    if (failure is FileCopyIncidentException) failure.incidentOwnedByRecovery = true
    if (reportedFileCopyRecovery.add(token)) {
        RecordingIncidentStore.recordUnexpectedErrorInBackground(context,
            "File publication preserved for recovery", failure)
    }
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
            reportFileCopyRecoveryIncident(context, record.token, failure)
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
        reportFileCopyRecoveryIncident(context, record.token, failure)
    }
    throw failure
}
