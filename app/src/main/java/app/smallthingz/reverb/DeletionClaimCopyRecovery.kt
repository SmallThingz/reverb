package app.smallthingz.reverb

import android.content.Context
import java.io.File
import java.io.IOException

/** Relocates only an already-held deletion claim; never acquires a fresh source claim. */
internal fun moveClaimForRecovery(
    context: Context,
    claim: File,
    destination: File,
    nativeMove: (File, File) -> Unit = ::moveFileWithoutOverwrite,
) = FileCopyPublications.withSource(claim) {
    if (!recordingFileStorageIdIsManaged(context, claim.absolutePath) ||
        !recordingFileStorageIdIsManaged(context, destination.absolutePath)) {
        throw IOException("Deletion claim restoration is outside managed storage")
    }
    moveDeletionClaimForRecoveryOwned(ContextFileCopyPublicationStore(context), DefaultFileCopyPublicationIo,
        claim, destination, nativeMove, ::retireCopiedDeletionClaim)
}

internal fun moveDeletionClaimForRecoveryOwned(
    store: FileCopyPublicationStore,
    io: FileCopyPublicationIo,
    claim: File,
    destination: File,
    nativeMove: (File, File) -> Unit,
    retireExistingClaim: (FileCopyPublication) -> Boolean,
) {
    if (!isDeletionClaimFileName(claim.name) || claim.absoluteFile.parentFile != destination.absoluteFile.parentFile ||
        claim.absoluteFile == destination.absoluteFile || !isDirectChildBasename(destination.name) ||
        isDeletionClaimFileName(destination.name)) throw IOException("Invalid deletion-claim restoration paths")

    // Consult durable ownership before any native mutation, including on a now-capable mount.
    val related = store.records().filter { it.sourceId == claim.absolutePath }
    if (related.size > 1 || related.any { it.purpose != FileCopyPurpose.CLAIM_RESTORE }) {
        throw FileCopyRecoveryRequired("Conflicting deletion-claim recovery ownership")
    }
    val existing = related.singleOrNull()
    if (existing != null && resumeDeletionClaimCopyOwned(store, io, existing, retireExistingClaim)) return

    try {
        nativeMove(claim, destination)
        return
    } catch (_: NoReplaceMoveUnsupported) {
        // Only a definite unsupported primitive permits the explicit compatibility copy.
    }
    val expected = io.fingerprint(claim)
        ?: throw FileCopyRecoveryRequired("Deletion claim cannot be stably read; it remains preserved")
    val record = copyFileForPublication(store, io, claim, destination, destination.name, expected,
        FileCopyPurpose.CLAIM_RESTORE)
    finishDeletionClaimCopyOwned(store, io, record, retireExistingClaim)
}

internal fun resumeDeletionClaimCopyOwned(
    store: FileCopyPublicationStore,
    io: FileCopyPublicationIo,
    record: FileCopyPublication,
    retireExistingClaim: (FileCopyPublication) -> Boolean,
): Boolean {
    check(record.purpose == FileCopyPurpose.CLAIM_RESTORE)
    val resumed = recoverFileCopyPublication(store, io, File(record.sourceId), record.requestedName,
        record.sourceFingerprint()) ?: return false
    finishDeletionClaimCopyOwned(store, io, resumed, retireExistingClaim)
    return true
}

private fun finishDeletionClaimCopyOwned(
    store: FileCopyPublicationStore,
    io: FileCopyPublicationIo,
    record: FileCopyPublication,
    retireExistingClaim: (FileCopyPublication) -> Boolean,
) {
    check(record.purpose == FileCopyPurpose.CLAIM_RESTORE && record.phase >= FileCopyPhase.VERIFIED)
    val target = io.verifiedFingerprint(File(record.targetId))
    if (target == null || !record.matchesTarget(target)) {
        throw FileCopyRecoveryRequired("Restored target changed; hidden claim remains preserved")
    }
    val claim = File(record.sourceId)
    when (io.state(claim)) {
        StoragePathState.MISSING -> Unit
        StoragePathState.UNAVAILABLE -> throw FileCopyRecoveryRequired("Restored claim state is unavailable")
        StoragePathState.PRESENT -> if (!retireExistingClaim(record)) {
            throw FileCopyRecoveryRequired("Restored target is durable; existing claim retirement remains pending")
        }
    }
    if (io.state(claim) != StoragePathState.MISSING) {
        throw FileCopyRecoveryRequired("Existing claim absence is not confirmed")
    }
    io.forceDirectory(requireNotNull(claim.parentFile))
    val finalTarget = io.fingerprint(File(record.targetId))
    if (finalTarget == null || !record.matchesTarget(finalTarget)) {
        throw FileCopyRecoveryRequired("Restored target changed before recovery ownership could retire")
    }
    store.replace(record, null)
}

/** The durable copy record owns this exact existing claim, including zero/non-audio bytes. */
internal fun retireCopiedDeletionClaim(record: FileCopyPublication): Boolean {
    if (record.purpose != FileCopyPurpose.CLAIM_RESTORE || record.phase < FileCopyPhase.VERIFIED) return false
    val claim = File(record.sourceId)
    val token = deletionClaimTokenFromFileName(claim.name) ?: return false
    val replay = PendingDeletionIntent(
        id = record.sourceId, byteCount = record.byteCount, sha256Hex = record.sha256Hex,
        assetDeleted = false, storageType = RecordingStorageType.FILE, claimToken = token,
        fileIdentity = record.sourceIdentity, moveTargetStorageType = RecordingStorageType.FILE,
        moveTargetId = record.targetId, moveTargetIdentity = record.targetIdentity,
    )
    return replayClaimedFileDeletion(replay, claim, restoreMismatchedClaim = false,
        moveTargetStillCurrent = { pendingFileDeletionMoveTargetIsCurrent(replay) },
    ) == FileDeletionClaimResult.DELETED
}
