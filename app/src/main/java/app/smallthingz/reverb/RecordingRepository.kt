package app.smallthingz.reverb

import android.content.Context
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal enum class CatalogCorruptionRecoveryMode {
    FIRST_PAINT,
    REFRESH,
}

internal class CatalogFirstPaintUnavailableException : IOException(
    "Recording catalog was reset after corruption and requires storage reconciliation",
)

internal class RecordingCatalogIdentityChangedException(message: String) : IOException(message)

internal fun catalogRegistrationFailureAllowsVerifiedSaveSuccess(error: Throwable): Boolean =
    error !is RecordingCatalogIdentityChangedException

internal suspend fun <T> recoverCatalogAfterCorruption(
    mode: CatalogCorruptionRecoveryMode,
    reset: () -> Unit,
    rebuild: suspend () -> T,
): T {
    reset()
    return when (mode) {
        CatalogCorruptionRecoveryMode.FIRST_PAINT -> throw CatalogFirstPaintUnavailableException()
        CatalogCorruptionRecoveryMode.REFRESH -> rebuild()
    }
}

object RecordingRepository {
    private val mutex = Mutex()
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val backgroundDeleteLock = Any()
    private var backgroundDeleteJob: Job? = null

    private fun dao(context: Context): RecordingDao =
        RecordingDatabase.getInstance(context).recordingDao()

    private suspend fun awaitBackgroundDeletes() {
        while (true) {
            val pending = synchronized(backgroundDeleteLock) { backgroundDeleteJob } ?: return
            pending.join()
            if (synchronized(backgroundDeleteLock) { backgroundDeleteJob === pending }) return
        }
    }

    suspend fun refresh(context: Context): List<RecordingEntity> {
        return withContext(Dispatchers.IO) {
            awaitBackgroundDeletes()
            mutex.withLock {
                try {
                    refreshLocked(context)
                } catch (corrupt: SQLiteDatabaseCorruptException) {
                    // The database error handler has already preserved the damaged DB and
                    // removed the active copy. Reopen once and rebuild solely from surviving
                    // storage; destructive operations never use this automatic retry path.
                    recoverCatalogAfterCorruption(
                        mode = CatalogCorruptionRecoveryMode.REFRESH,
                        reset = { RecordingDatabase.resetAfterCorruption() },
                        rebuild = { refreshLocked(context) },
                    )
                }
            }
        }
    }

    private suspend fun refreshLocked(context: Context): List<RecordingEntity> {
        replayPendingDeletionsLocked(context)
        val reconciledDirectoryIds = syncRecoverableDirectories(context).toMutableSet().apply {
            // Preserve the existing fail-closed rule for the configured destination: if its
            // directory enumeration fails, that failure is not evidence that every row vanished.
            add(getConfiguredOutputDirectoryId(context))
        }
        updateMissingStatesLocked(context, skipDirectoryIds = reconciledDirectoryIds)
        val pending = pendingDeletionIds(context)
        return visibleCatalogRecordings(
            dao(context).listAll(),
            pending,
        )
    }

    /**
     * Fast library snapshot for first paint. This intentionally does not touch
     * storage providers or scan directories; refresh() reconciles those in the
     * background after the sheet is already visible.
     */
    suspend fun listKnown(context: Context): List<RecordingEntity> {
        return withContext(Dispatchers.IO) {
            awaitBackgroundDeletes()
            mutex.withLock {
                try {
                    val pending = pendingDeletionIds(context)
                    visibleCatalogRecordings(
                        dao(context).listAll(),
                        pending,
                    )
                } catch (corrupt: SQLiteDatabaseCorruptException) {
                    // The preservation handler froze and copied the corrupt database before
                    // removing its active files. First paint must not crash while waiting for
                    // refresh() to rebuild from authoritative storage.
                    recoverCatalogAfterCorruption<List<RecordingEntity>>(
                        mode = CatalogCorruptionRecoveryMode.FIRST_PAINT,
                        reset = { RecordingDatabase.resetAfterCorruption() },
                        rebuild = { error("FIRST_PAINT never rebuilds providers") },
                    )
                }
            }
        }
    }

    fun deleteInBackground(context: Context, recordings: List<RecordingEntity>) {
        if (recordings.isEmpty()) return
        val appContext = context.applicationContext
        synchronized(backgroundDeleteLock) {
            val previous = backgroundDeleteJob
            backgroundDeleteJob = cleanupScope.launch {
                previous?.join()
                var failed = false
                recordings.forEach { recording ->
                    val deleted = runCatching { delete(appContext, recording) }.getOrDefault(false)
                    if (!deleted) failed = true
                }
                if (failed) {
                    AppFeedbackCenter.post(
                        appContext.getString(R.string.recording_delete_failed),
                        FeedbackTone.ERROR,
                    )
                }
            }
        }
    }

    suspend fun hasMovableKnownRecordings(
        context: Context,
        targetDirectoryId: String = getConfiguredOutputDirectoryId(context),
    ): Boolean {
        return withContext(Dispatchers.IO) {
            awaitBackgroundDeletes()
            mutex.withLock {
                replayPendingDeletionsLocked(context)
                val dao = dao(context)
                val pendingIds = pendingDeletionIds(context)
                val nowMillis = System.currentTimeMillis()
                val updates = mutableListOf<RecordingEntity>()
                var movable = false
                dao.listAll().forEach { recording ->
                    if (!isRecordingEligibleForMove(recording.id, pendingIds)) return@forEach
                    if (recordingMutations.isActive(recording.id)) return@forEach
                    if (recording.directoryId == targetDirectoryId) return@forEach
                    val updated = when (selectedRecordingAssetState(context, recording)) {
                        RecordingAssetState.PRESENT -> {
                            val present = markRecordingPresent(recording, nowMillis)
                            if (recordingDestructiveIdentityMatches(context, present)) movable = true
                            present
                        }
                        RecordingAssetState.MISSING -> markRecordingMissing(recording, nowMillis)
                        RecordingAssetState.UNAVAILABLE -> recording
                    }
                    if (updated != recording) updates += updated
                }
                dao.applyChanges(updates, emptyList())
                movable
            }
        }
    }

    suspend fun register(context: Context, recording: RecordingEntity): RecordingEntity {
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                val stableIdentityAvailable = recording.fileIdentity.isNotBlank()
                if (!recordingCatalogIdentityIsCurrent(
                        stableIdentityAvailable = stableIdentityAvailable,
                        currentIdentityMatches = stableIdentityAvailable &&
                            recordingContentIdentityMatches(context, recording),
                    )
                ) {
                    throw RecordingCatalogIdentityChangedException("Recording changed before catalog registration")
                }
                val dao = dao(context)
                val existing = dao.findById(recording.id)
                val presentRecording = mergeObservedRecording(
                    existing = existing, observed = recording,
                    nowMillis = System.currentTimeMillis(),
                )
                dao.upsert(presentRecording)
                presentRecording
            }
        }
    }

    suspend fun cacheWaveform(
        context: Context,
        recording: RecordingEntity,
        waveformData: String,
        waveformRevision: String,
    ): Boolean {
        if (!isValidRecordingWaveformCache(recording, waveformData, waveformRevision)) return false
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                if (!recordingContentIdentityMatches(context, recording)) return@withLock false
                dao(context).updateWaveformCache(
                    recording = recording,
                    waveformData = waveformData,
                    waveformRevision = waveformRevision,
                )
            }
        }
    }

    suspend fun delete(context: Context, recording: RecordingEntity): Boolean {
        val operation = recordingMutations.tryBegin(recording.id) ?: return false
        try {
            return withContext(Dispatchers.IO) {
                mutex.withLock {
                    replayPendingDeletionsLocked(context)
                    val dao = dao(context)
                    val tracked = dao.findById(recording.id) ?: return@withLock true
                    if (!sameRecordingActionTarget(recording, tracked)) return@withLock false
                    if (tracked.storageType == RecordingStorageType.FILE &&
                        !recordingFileIdentityMatches(tracked)
                    ) {
                        return@withLock false
                    }

                    val intent = createPendingDeletionIntent(context, tracked) ?: return@withLock false
                    if (!putPendingDeletionLocked(context, intent)) return@withLock false

                    val deleted = when (tracked.storageType) {
                        RecordingStorageType.FILE -> when (deleteClaimedFile(intent)) {
                            FileDeletionClaimResult.DELETED -> true
                            FileDeletionClaimResult.MISMATCH_PRESERVED -> {
                                removePendingDeletionLocked(context, tracked.id)
                                false
                            }
                            FileDeletionClaimResult.RETRY -> false
                        }
                        else -> {
                            if (!pendingDeletionMatchesCurrentAsset(context, tracked, intent)) {
                                removePendingDeletionLocked(context, tracked.id)
                                false
                            } else {
                                deleteVerifiedRecordingAsset(context, tracked)
                            }
                        }
                    }
                    if (!deleted) return@withLock false

                    // Persist the destructive phase boundary before touching catalog metadata.
                    // If this commit fails, keep the existing planned intent and catalog row; the
                    // pending-id filter hides it and replay can finish cleanup once absence is known.
                    if (!putPendingDeletionLocked(context, intent.copy(assetDeleted = true))) {
                        return@withLock true
                    }
                    // This row describes the object the user selected, not any later object that
                    // may reuse the same path. Retiring metadata cannot delete replacement bytes;
                    // directory reconciliation will import a replacement as a fresh observation.
                    dao.deleteById(tracked.id)
                    removePendingDeletionLocked(context, tracked.id)
                    true
                }
            }
        } finally {
            recordingMutations.finish(operation)
        }
    }

    private suspend fun replayPendingDeletionsLocked(context: Context) {
        val rawEntries = pendingDeletionEntries(context)
        if (rawEntries.isEmpty()) return
        val dao = dao(context)
        val byId = dao.listAll().associateBy { it.id }
        for (raw in rawEntries) {
            val intent = decodePendingDeletionIntent(raw)
            if (intent == null) {
                // Old ID-only and malformed entries do not contain enough identity to
                // authorize a destructive retry. Drop the intent, never the asset.
                removePendingDeletionRawLocked(context, raw)
                continue
            }
            val recording = byId[intent.id]
            val claim = deletionClaimFile(intent)
            if (claim != null) {
                val claimObservation = observeStoragePath(claim)
                when (claimedFileReplayAction(claimObservation)) {
                    ClaimedFileReplayAction.WAIT -> continue
                    ClaimedFileReplayAction.NO_CLAIM -> Unit
                    ClaimedFileReplayAction.REPLAY -> when (
                        replayClaimedFileDeletion(intent, claim) {
                            pendingDeletionMoveTargetMatchesCurrentAsset(context, intent)
                        }
                    ) {
                        FileDeletionClaimResult.RETRY -> continue
                        FileDeletionClaimResult.MISMATCH_PRESERVED,
                        FileDeletionClaimResult.DELETED,
                        -> {
                            // Rename/delete/recovery changes must be durable before the journal
                            // that explains them can disappear. A current source-path occupant,
                            // if any, is a different object and reconciliation will import it.
                            if (!confirmFileDirectoryStateDurable(File(intent.id))) continue
                            if (recording != null) dao.deleteById(intent.id)
                            removePendingDeletionLocked(context, intent.id)
                            continue
                        }
                    }
                }
            }
            if (intent.storageType == RecordingStorageType.FILE && intent.fileIdentity != null) {
                val source = File(intent.id)
                when (pendingDeletionReplayAction(intent, fileRecordingAssetState(source))) {
                    PendingDeletionReplayAction.WAIT -> continue
                    PendingDeletionReplayAction.ABANDON_INTENT -> {
                        // A persisted intent alone never authorizes a second physical delete
                        // after process loss. Only a claim file created before the crash may
                        // finish deletion above. A still-present source is preserved.
                        removePendingDeletionLocked(context, intent.id)
                        continue
                    }
                    PendingDeletionReplayAction.CLEAN_CATALOG -> {
                        if (!confirmMissingFileRecordingDurable(source)) continue
                        if (recording != null) dao.deleteById(intent.id)
                        removePendingDeletionLocked(context, intent.id)
                        continue
                    }
                }
            }
            if (recording == null) {
                removePendingDeletionLocked(context, intent.id)
                continue
            }
            when (pendingDeletionReplayAction(intent, recordingAssetState(context, recording))) {
                PendingDeletionReplayAction.WAIT -> continue
                PendingDeletionReplayAction.ABANDON_INTENT -> {
                    removePendingDeletionLocked(context, intent.id)
                    continue
                }
                PendingDeletionReplayAction.CLEAN_CATALOG -> {
                    // Physical deletion is never replayed. This only removes metadata after
                    // confirmed deletion or a positive observation that the asset is absent.
                    dao.deleteById(intent.id)
                    removePendingDeletionLocked(context, intent.id)
                }
            }
        }
    }

    private fun pendingDeletionEntries(context: Context): Set<String> =
        getRecorderPreferences(context).requireDurableStringSet(PrefKey.PENDING_RECORDING_DELETIONS)

    private fun pendingDeletionIds(context: Context): Set<String> =
        pendingDeletionEntries(context).mapNotNullTo(mutableSetOf(), ::pendingDeletionSuppressedId)

    private fun createPendingDeletionIntent(
        context: Context,
        recording: RecordingEntity,
    ): PendingDeletionIntent? = runCatching {
        if (!recordingDeletionIdentityMatches(context, recording)) return@runCatching null
        val digest = sha256StableRecording(context, recording) ?: return@runCatching null
        if (digest.byteCount <= 0L) return@runCatching null
        val isFile = recording.storageType == RecordingStorageType.FILE
        PendingDeletionIntent(
            id = recording.id,
            byteCount = digest.byteCount,
            sha256Hex = digest.sha256.toHexString(),
            assetDeleted = false,
            storageType = RecordingStorageType.FILE.takeIf { isFile },
            claimToken = UUID.randomUUID().toString().takeIf { isFile },
            fileIdentity = recording.fileIdentity.takeIf { isFile && it.isNotBlank() },
        )
    }.getOrNull()

    private fun createMovePendingDeletionIntent(
        context: Context,
        source: RecordingEntity,
        target: RecordingEntity,
    ): PendingDeletionIntent? = runCatching {
        val intent = createPendingDeletionIntent(context, source) ?: return@runCatching null
        val targetIdentity = target.fileIdentity.takeIf { it.isNotBlank() } ?: return@runCatching null
        val targetDigest = sha256StableRecording(context, target) ?: return@runCatching null
        if (!pendingDeletionMatchesDigest(intent, targetDigest.byteCount, targetDigest.sha256.toHexString())) {
            return@runCatching null
        }
        intent.copy(
            storageType = source.storageType,
            moveTargetStorageType = target.storageType,
            moveTargetId = target.id,
            moveTargetIdentity = targetIdentity,
        )
    }.getOrNull()

    private fun pendingDeletionMoveTargetMatchesCurrentAsset(
        context: Context,
        intent: PendingDeletionIntent,
    ): Boolean = runCatching {
        val storageType = intent.moveTargetStorageType
            ?: return@runCatching !pendingDeletionHasAnyMoveTargetField(intent)
        val id = intent.moveTargetId?.takeIf { it.isNotBlank() } ?: return@runCatching false
        val identity = intent.moveTargetIdentity?.takeIf { it.isNotBlank() } ?: return@runCatching false
        val target = RecordingEntity(
            id = id,
            displayName = "",
            mimeType = "",
            startedAtMillis = 0L,
            durationMillis = 0L,
            sizeBytes = intent.byteCount,
            codecSummary = "",
            storageType = storageType,
            directoryId = "",
            fileIdentity = identity,
        )
        val digest = sha256StableRecording(context, target) ?: return@runCatching false
        pendingDeletionMatchesDigest(intent, digest.byteCount, digest.sha256.toHexString())
    }.getOrDefault(false)

    private fun pendingDeletionMatchesCurrentAsset(
        context: Context,
        recording: RecordingEntity,
        intent: PendingDeletionIntent,
    ): Boolean = runCatching {
        val digest = sha256StableRecording(context, recording) ?: return@runCatching false
        pendingDeletionMatchesDigest(intent, digest.byteCount, digest.sha256.toHexString())
    }.getOrDefault(false)

    private fun putPendingDeletionLocked(context: Context, intent: PendingDeletionIntent): Boolean {
        val encoded = encodePendingDeletionIntent(intent).takeIf { it.isNotBlank() } ?: return false
        val current = pendingDeletionEntries(context)
        val updated = current.filterNotTo(mutableSetOf()) { raw ->
            pendingDeletionSuppressedId(raw) == intent.id
        }
        updated += encoded
        return getRecorderPreferences(context).edit()
            .putStringSet(PrefKey.PENDING_RECORDING_DELETIONS, updated)
            .commit()
    }

    private fun removePendingDeletionLocked(context: Context, id: String): Boolean {
        val current = pendingDeletionEntries(context)
        val updated = current.filterNotTo(mutableSetOf()) { raw ->
            pendingDeletionSuppressedId(raw) == id
        }
        return writePendingDeletionEntries(context, updated)
    }

    private fun removePendingDeletionRawLocked(context: Context, raw: String): Boolean =
        writePendingDeletionEntries(context, pendingDeletionEntries(context) - raw)

    private fun writePendingDeletionEntries(context: Context, entries: Set<String>): Boolean {
        val editor = getRecorderPreferences(context).edit()
        if (entries.isEmpty()) editor.remove(PrefKey.PENDING_RECORDING_DELETIONS)
        else editor.putStringSet(PrefKey.PENDING_RECORDING_DELETIONS, entries)
        return editor.commit()
    }

    suspend fun rename(
        context: Context,
        recording: RecordingEntity,
        requestedBaseName: String,
    ): RecordingEntity? {
        val operation = recordingMutations.tryBegin(recording.id) ?: return null
        try {
            return withContext(Dispatchers.IO) {
                mutex.withLock {
                    val dao = dao(context)
                    val tracked = dao.findById(recording.id) ?: return@withLock null
                    if (!sameRecordingActionTarget(recording, tracked)) {
                        throw IOException("Recording changed before rename")
                    }
                    if (!recordingContentIdentityMatches(context, tracked)) {
                        throw IOException("Recording changed before rename")
                    }
                    val renamed = renameRecordingAsset(context, tracked, requestedBaseName) ?: return@withLock null
                    if (renamed == tracked) return@withLock tracked
                    try {
                        // The storage-specific rename verified its result, but external writers are
                        // outside Reverb's mutation lock. Recheck the exact renamed object at the
                        // catalog boundary before carrying metadata/cache state into SQLite.
                        val stableRenamedIdentityAvailable = renamed.fileIdentity.isNotBlank()
                        if (!recordingCatalogIdentityIsCurrent(
                                stableIdentityAvailable = stableRenamedIdentityAvailable,
                                currentIdentityMatches = stableRenamedIdentityAvailable &&
                                    recordingContentIdentityMatches(context, renamed),
                            )
                        ) {
                            throw IOException("Recording changed before rename catalog commit")
                        }
                        dao.applyChanges(
                            upserts = listOf(renamed),
                            deleteIds = if (renamed.id == tracked.id) emptyList() else listOf(tracked.id),
                        )
                    } catch (error: Exception) {
                        // Prefer restoring the original physical identity. If that is no longer
                        // possible (for example the old path was reused concurrently), never keep
                        // a stale catalog row that could now address unrelated bytes. A refresh can
                        // rediscover both the renamed recording and any replacement independently.
                        val rolledBack = runCatching {
                            renameRecordingAsset(context, renamed, tracked.displayName)
                        }.getOrNull()
                        if (rolledBack?.id != tracked.id) {
                            runCatching { dao.deleteById(tracked.id) }
                        }
                        throw error
                    }
                    renamed
                }
            }
        } finally {
            recordingMutations.finish(operation)
        }
    }

    suspend fun moveAllToDirectory(
        context: Context,
        targetTreeUri: Uri?,
    ): MoveResult {
        return withContext(Dispatchers.IO) {
            awaitBackgroundDeletes()
            mutex.withLock {
                replayPendingDeletionsLocked(context)
                val dao = dao(context)
                val current = dao.listAll()
                val pendingIds = pendingDeletionIds(context)
                if (current.isEmpty()) {
                    return@withLock MoveResult()
                }

                // The caller pins one destination before this IO transaction begins. Later
                // Settings saves must not retarget or split an already-submitted move batch.
                val targetDirectoryId = getOutputDirectoryId(context, targetTreeUri)
                val stateUpdates = mutableListOf<RecordingEntity>()
                val moveCandidates = mutableListOf<RecordingEntity>()
                var skipped = 0
                val nowMillis = System.currentTimeMillis()

                current.forEach { recording ->
                    if (!isRecordingEligibleForMove(recording.id, pendingIds)) {
                        skipped++
                        return@forEach
                    }
                    val present = when (selectedRecordingAssetState(context, recording)) {
                        RecordingAssetState.PRESENT -> markRecordingPresent(recording, nowMillis)
                        RecordingAssetState.MISSING -> {
                            val missing = markRecordingMissing(recording, nowMillis)
                            if (missing != recording) stateUpdates += missing
                            skipped++
                            return@forEach
                        }
                        RecordingAssetState.UNAVAILABLE -> {
                            skipped++
                            return@forEach
                        }
                    }
                    if (!recordingDestructiveIdentityMatches(context, present)) {
                        // A move is copy + delete. If source identity cannot be proven before
                        // copying, a later cleanup can never safely retire that source and
                        // repeated move attempts would accumulate duplicate targets.
                        skipped++
                        return@forEach
                    }

                    if (present.directoryId == targetDirectoryId) {
                        if (present != recording) stateUpdates += present
                        skipped++
                        return@forEach
                    }

                    moveCandidates += present
                }

                // Commit non-move reconciliation before copying. Moves themselves are
                // committed one at a time so peak duplicate disk usage is bounded by a
                // single recording rather than the entire library.
                dao.applyChanges(stateUpdates, emptyList())

                var moved = 0
                var failed = 0
                var cleanupFailed = 0
                moveCandidates.forEach { source ->
                    val operation = recordingMutations.tryBegin(source.id)
                    if (operation == null) {
                        skipped++
                        return@forEach
                    }
                    try {
                        // Equal bytes/name are not proof that an existing target belongs to this
                        // move. Without a durable source→target transaction marker, preserve any
                        // existing target and make a fresh verified copy before touching the source.
                        val target = copyRecordingToDirectory(
                            context = context,
                            recording = source,
                            targetTreeUri = targetTreeUri,
                        )
                        if (target == null) {
                            failed++
                            return@forEach
                        }
                        when (commitVerifiedMoveLocked(
                            context = context,
                            dao = dao,
                            source = source,
                            target = target,
                        )) {
                            VerifiedMoveCommitResult.MOVED -> moved++
                            VerifiedMoveCommitResult.SOURCE_CLEANUP_FAILED -> {
                                moved++
                                cleanupFailed++
                            }
                            VerifiedMoveCommitResult.TARGET_CHANGED -> failed++
                        }
                    } finally {
                        recordingMutations.finish(operation)
                    }
                }

                MoveResult(
                    moved = moved,
                    skipped = skipped,
                    failed = failed,
                    cleanupFailed = cleanupFailed,
                )
            }
        }
    }

    private suspend fun commitVerifiedMoveLocked(
        context: Context,
        dao: RecordingDao,
        source: RecordingEntity,
        target: RecordingEntity,
    ): VerifiedMoveCommitResult {
        // copyRecordingToDirectory() verified and pinned this target, but external writers are
        // outside Reverb's repository mutex. Recheck the exact target immediately before its
        // catalog commit; a replacement must never become the recoverable authority for deleting
        // the selected source.
        val stableTargetIdentityAvailable = target.fileIdentity.isNotBlank()
        val targetIdentityCurrent = recordingCatalogIdentityIsCurrent(
            stableIdentityAvailable = stableTargetIdentityAvailable,
            currentIdentityMatches = stableTargetIdentityAvailable &&
                recordingContentIdentityMatches(context, target),
        )
        if (!targetIdentityCurrent) {
            Log.w("RecordingRepository", "Verified move target changed before catalog commit: ${target.id}")
            return verifiedMoveCommitResult(targetIdentityCurrent = false, sourceCleanupComplete = false)
        }

        // Once the exact verified target is cataloged, make it recoverable before touching the
        // source. Never delete that target merely because later source cleanup is uncertain: the
        // source path/URI may have disappeared or been reused for different bytes.
        dao.upsert(target)

        val cleanupResult = cleanupMovedSourceAfterVerifiedCopy(context, source, target)
        if (cleanupResult != VerifiedMoveCommitResult.MOVED) return cleanupResult

        // Physical source cleanup completed (or the source was already positively absent).
        // Metadata retirement is last; if it fails, a FILE cleanup journal can finish it
        // without ever targeting a reused original path.
        dao.deleteById(source.id)
        removePendingDeletionLocked(context, source.id)
        return verifiedMoveCommitResult(targetIdentityCurrent = true, sourceCleanupComplete = true)
    }

    private suspend fun syncRecoverableDirectories(context: Context): Set<String> {
        // Cleanup replay can hash/provider-query large failed outputs. Run it once for the whole
        // refresh, not once per default/persisted/legacy directory scan. Replay itself is best
        // effort: one cleanup failure must not make unrelated directories unavailable. Each scan
        // still reads the latest suppression IDs and therefore independently fails closed when
        // suppression state itself cannot be read.
        runCatching { retryPendingOutputCleanup(context) }
            .onFailure { error -> Log.w("RecordingRepository", "Unable to replay pending output cleanup", error) }
        val reconciledDirectoryIds = LinkedHashSet<String>()
        val directoryUris = LinkedHashMap<String, Uri?>()
        directoryUris[getOutputDirectoryId(context, null)] = null
        getConfiguredExportTreeUri(context)?.let { uri ->
            directoryUris[getOutputDirectoryId(context, uri)] = uri
        }
        runCatching { context.contentResolver.persistedUriPermissions }
            .getOrDefault(emptyList())
            .asSequence()
            .filter { it.isReadPermission }
            .forEach { permission ->
                directoryUris.putIfAbsent(permission.uri.toString(), permission.uri)
            }

        for ((directoryId, treeUri) in directoryUris) {
            if (syncRecoverableDirectory(context, treeUri)) reconciledDirectoryIds += directoryId
        }

        // Pre-MediaStore builds saved into app-specific external/internal storage. Keep
        // scanning it forever so upgrading never strands a recording in the old location.
        val legacyDirectoryId = getSavedRecordingsDirectory(context).absolutePath
        if (legacyDirectoryId !in directoryUris.keys) {
            if (syncObservedDirectory(context, legacyDirectoryId) { known ->
                    listLegacyAppStorageRecordings(context, known)
                }
            ) {
                reconciledDirectoryIds += legacyDirectoryId
            }
        }

        // Older builds used app-specific external storage, which Android removes on
        // uninstall. Snapshot migration admission once immediately before the batch. A Settings
        // change after this point cannot split the decision from the default target pinned below.
        val migrationTargetTreeUri = getConfiguredExportTreeUri(context)
        val migrationTargetDirectoryId = getOutputDirectoryId(context, migrationTargetTreeUri)
        if (shouldMigrateLegacyAppStorage(
                configuredTreeSelected = migrationTargetTreeUri != null,
                legacyDirectoryId = legacyDirectoryId,
                targetDirectoryId = migrationTargetDirectoryId,
            )
        ) {
            migrateLegacyAppStorageLocked(context, legacyDirectoryId)
        }
        return reconciledDirectoryIds
    }

    private suspend fun migrateLegacyAppStorageLocked(context: Context, legacyDirectoryId: String) {
        val dao = dao(context)
        val pendingIds = pendingDeletionIds(context)
        val legacy = dao.listByDirectory(legacyDirectoryId)

        for (source in legacy) {
            if (!isRecordingEligibleForMove(source.id, pendingIds)) continue
            if (selectedRecordingAssetState(context, source) != RecordingAssetState.PRESENT) continue
            if (!recordingDestructiveIdentityMatches(context, source)) continue
            val operation = recordingMutations.tryBegin(source.id) ?: continue
            try {
                // Do not infer interrupted-move ownership from equal bytes or metadata. A fresh
                // verified copy preserves intentionally duplicated recordings; only an explicit
                // future source→target transaction marker may authorize target reuse.
                // This migration was admitted only while the configured destination was the
                // default shared location. Keep that target pinned even if Settings changes later.
                val target = copyRecordingToDirectory(
                    context = context,
                    recording = source,
                    targetTreeUri = null,
                ) ?: continue

                when (commitVerifiedMoveLocked(
                    context = context,
                    dao = dao,
                    source = source,
                    target = target,
                )) {
                    VerifiedMoveCommitResult.MOVED -> Unit
                    VerifiedMoveCommitResult.SOURCE_CLEANUP_FAILED -> {
                        Log.w(
                            "RecordingRepository",
                            "Verified legacy target kept, but source cleanup was unsafe: ${source.id}",
                        )
                    }
                    VerifiedMoveCommitResult.TARGET_CHANGED -> {
                        Log.w(
                            "RecordingRepository",
                            "Legacy move target changed before move completion; source retained: ${source.id}",
                        )
                    }
                }
            } finally {
                recordingMutations.finish(operation)
            }
        }
    }

    private suspend fun syncRecoverableDirectory(
        context: Context,
        treeUri: Uri?,
    ): Boolean {
        val directoryId = getOutputDirectoryId(context, treeUri)
        return syncObservedDirectory(context, directoryId) { known ->
            listOutputDirectoryRecordings(context, treeUri, known)
        }
    }

    private suspend fun syncObservedDirectory(
        context: Context,
        directoryId: String,
        scan: (Map<String, RecordingEntity>) -> List<RecordingEntity>,
    ): Boolean {
        val dao = dao(context)
        val existing = dao.listByDirectory(directoryId)
        val existingById = HashMap<String, RecordingEntity>(existing.size)
        existing.associateByTo(existingById) { it.id }
        val imported = try {
            scan(existingById)
        } catch (error: Exception) {
            // A failed directory enumeration is not evidence that every known recording
            // disappeared. Leave this directory's catalog state untouched and continue
            // reconciling other recoverable locations.
            Log.w("RecordingRepository", "Unable to enumerate recording directory $directoryId", error)
            return false
        }
        val nowMillis = System.currentTimeMillis()
        val importedIds = HashSet<String>(imported.size)
        val importedUpdates = ArrayList<RecordingEntity>()
        imported.forEach { observed ->
            val merged = mergeObservedRecording(existingById[observed.id], observed, nowMillis)
            importedIds += merged.id
            if (existingById[merged.id] != merged) importedUpdates += merged
        }
        val updates = mutableListOf<RecordingEntity>()

        existing.asSequence()
            .filter { it.id !in importedIds }
            .forEach { recording ->
                // Never convert a scan gap into catalog deletion. Provider visibility,
                // removable storage, and persisted permissions can all recover later.
                val updated = when (selectedRecordingAssetState(context, recording)) {
                    RecordingAssetState.PRESENT -> markRecordingPresent(recording, nowMillis)
                    RecordingAssetState.MISSING -> markRecordingMissing(recording, nowMillis)
                    RecordingAssetState.UNAVAILABLE -> recording
                }
                if (updated != recording) updates += updated
            }

        dao.applyChanges(importedUpdates + updates, emptyList())
        return true
    }

    private suspend fun updateMissingStatesLocked(
        context: Context,
        skipDirectoryIds: Set<String> = emptySet(),
    ) {
        val dao = dao(context)
        val all = dao.listAll()
        val nowMillis = System.currentTimeMillis()
        val updates = mutableListOf<RecordingEntity>()
        all.forEach { recording ->
            if (!recordingNeedsFallbackAssetProbe(recording.directoryId, skipDirectoryIds)) return@forEach
            val updated = when (selectedRecordingAssetState(context, recording)) {
                RecordingAssetState.PRESENT -> markRecordingPresent(recording, nowMillis)
                RecordingAssetState.MISSING -> markRecordingMissing(recording, nowMillis)
                RecordingAssetState.UNAVAILABLE -> recording
            }
            if (updated != recording) updates += updated
        }
        dao.applyChanges(updates, emptyList())
    }

    private fun cleanupMovedSourceAfterVerifiedCopy(
        context: Context,
        source: RecordingEntity,
        target: RecordingEntity,
    ): VerifiedMoveCommitResult {
        fun failedCleanupResult(): VerifiedMoveCommitResult = verifiedMoveCommitResult(
            targetIdentityCurrent = recordingContentIdentityMatches(context, target),
            sourceCleanupComplete = false,
        )

        if (!recordingContentIdentityMatches(context, target)) {
            return VerifiedMoveCommitResult.TARGET_CHANGED
        }
        val state = recordingAssetState(context, source)
        val sameContent = state == RecordingAssetState.PRESENT && recordingsHaveSameContent(context, source, target)
        return when (moveSourceCleanupAction(state, sameContent)) {
            MoveSourceCleanupAction.COMPLETE -> {
                if (recordingContentIdentityMatches(context, target)) VerifiedMoveCommitResult.MOVED
                else VerifiedMoveCommitResult.TARGET_CHANGED
            }
            MoveSourceCleanupAction.KEEP_SOURCE -> failedCleanupResult()
            MoveSourceCleanupAction.DELETE_SOURCE -> {
                val intent = createMovePendingDeletionIntent(context, source, target)
                    ?: return failedCleanupResult()
                if (!putPendingDeletionLocked(context, intent)) return failedCleanupResult()
                if (source.storageType != RecordingStorageType.FILE) {
                    val sourceCurrent = pendingDeletionMatchesCurrentAsset(context, source, intent)
                    val targetCurrent = pendingDeletionMoveTargetMatchesCurrentAsset(context, intent)
                    if (!sourceCurrent || !targetCurrent) {
                        removePendingDeletionLocked(context, source.id)
                        return if (!targetCurrent) VerifiedMoveCommitResult.TARGET_CHANGED
                        else VerifiedMoveCommitResult.SOURCE_CLEANUP_FAILED
                    }
                    if (!deleteVerifiedRecordingAsset(context, source)) return failedCleanupResult()
                    // Physical deletion is never replayed for provider assets. The phase marker
                    // lets restart cleanup retire metadata immediately when it can be persisted.
                    putPendingDeletionLocked(context, intent.copy(assetDeleted = true))
                    VerifiedMoveCommitResult.MOVED
                } else {
                    when (
                        deleteClaimedFile(intent) {
                            pendingDeletionMoveTargetMatchesCurrentAsset(context, intent)
                        }
                    ) {
                        FileDeletionClaimResult.DELETED -> {
                            // Best effort phase marker. The planned intent is still safe if this
                            // write fails because replay understands missing/reused paths.
                            putPendingDeletionLocked(context, intent.copy(assetDeleted = true))
                            VerifiedMoveCommitResult.MOVED
                        }
                        FileDeletionClaimResult.MISMATCH_PRESERVED -> {
                            removePendingDeletionLocked(context, source.id)
                            failedCleanupResult()
                        }
                        FileDeletionClaimResult.RETRY -> failedCleanupResult()
                    }
                }
            }
        }
    }

    data class MoveResult(
        val moved: Int = 0,
        val skipped: Int = 0,
        val failed: Int = 0,
        val cleanupFailed: Int = 0,
    ) {
        val hasFailures: Boolean
            get() = failed > 0 || cleanupFailed > 0
    }
}

internal fun recordingNeedsFallbackAssetProbe(
    directoryId: String,
    reconciledOrProtectedDirectoryIds: Set<String>,
): Boolean = directoryId !in reconciledOrProtectedDirectoryIds

internal fun shouldMigrateLegacyAppStorage(
    configuredTreeSelected: Boolean,
    legacyDirectoryId: String,
    targetDirectoryId: String,
): Boolean = !configuredTreeSelected && legacyDirectoryId != targetDirectoryId

internal enum class MoveSourceCleanupAction { DELETE_SOURCE, COMPLETE, KEEP_SOURCE }

internal fun moveSourceCleanupAction(
    assetState: RecordingAssetState,
    sameContentAsVerifiedTarget: Boolean,
): MoveSourceCleanupAction = when (assetState) {
    RecordingAssetState.MISSING -> MoveSourceCleanupAction.COMPLETE
    RecordingAssetState.UNAVAILABLE -> MoveSourceCleanupAction.KEEP_SOURCE
    RecordingAssetState.PRESENT -> if (sameContentAsVerifiedTarget) {
        MoveSourceCleanupAction.DELETE_SOURCE
    } else {
        MoveSourceCleanupAction.KEEP_SOURCE
    }
}

private const val PENDING_DELETION_V1_PREFIX = "v1|"
private const val PENDING_DELETION_V2_PREFIX = "v2|"
private const val PENDING_DELETION_V3_PREFIX = "v3|"
private const val PENDING_DELETION_V4_PREFIX = "v4|"
private const val DELETION_CLAIM_PREFIX = ".reverb-delete-"
private const val DELETION_CLAIM_SUFFIX = ".pending"

internal data class PendingDeletionIntent(
    val id: String,
    val byteCount: Long,
    val sha256Hex: String,
    val assetDeleted: Boolean,
    val storageType: RecordingStorageType? = null,
    val claimToken: String? = null,
    val fileIdentity: String? = null,
    val moveTargetStorageType: RecordingStorageType? = null,
    val moveTargetId: String? = null,
    val moveTargetIdentity: String? = null,
)

internal fun encodePendingDeletionIntent(intent: PendingDeletionIntent): String {
    val id = encodePendingDeletionField(intent.id)
    if (pendingDeletionHasAnyMoveTargetField(intent)) {
        val sourceStorage = intent.storageType ?: return ""
        val targetStorage = intent.moveTargetStorageType ?: return ""
        val targetId = intent.moveTargetId?.takeIf { it.isNotBlank() } ?: return ""
        val targetIdentity = intent.moveTargetIdentity?.takeIf { it.isNotBlank() } ?: return ""
        if (sourceStorage == RecordingStorageType.FILE &&
            (intent.claimToken.isNullOrBlank() || intent.fileIdentity.isNullOrBlank())
        ) return ""
        if (sourceStorage != RecordingStorageType.FILE &&
            (!intent.claimToken.isNullOrBlank() || !intent.fileIdentity.isNullOrBlank())
        ) return ""
        return buildString {
            append(PENDING_DELETION_V4_PREFIX)
            append(id).append('|')
            append(intent.byteCount).append('|')
            append(intent.sha256Hex.lowercase()).append('|')
            append(if (intent.assetDeleted) '1' else '0').append('|')
            append(sourceStorage.storageCode.toInt()).append('|')
            append(intent.claimToken.orEmpty()).append('|')
            append(intent.fileIdentity?.let(::encodePendingDeletionField).orEmpty()).append('|')
            append(targetStorage.storageCode.toInt()).append('|')
            append(encodePendingDeletionField(targetId)).append('|')
            append(encodePendingDeletionField(targetIdentity))
        }
    }
    if (intent.storageType == null && intent.claimToken == null && intent.fileIdentity == null) {
        return buildString {
            append(PENDING_DELETION_V1_PREFIX)
            append(id).append('|')
            append(intent.byteCount).append('|')
            append(intent.sha256Hex.lowercase()).append('|')
            append(if (intent.assetDeleted) '1' else '0')
        }
    }
    val storage = intent.storageType ?: return ""
    val token = intent.claimToken.orEmpty()
    return buildString {
        append(PENDING_DELETION_V3_PREFIX)
        append(id).append('|')
        append(intent.byteCount).append('|')
        append(intent.sha256Hex.lowercase()).append('|')
        append(if (intent.assetDeleted) '1' else '0').append('|')
        append(storage.storageCode.toInt()).append('|')
        append(token).append('|')
        append(intent.fileIdentity?.let(::encodePendingDeletionField).orEmpty())
    }
}

private fun encodePendingDeletionField(value: String): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(StandardCharsets.UTF_8))

private fun decodePendingDeletionField(value: String): String? =
    runCatching { String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8) }
        .getOrNull()?.takeIf { it.isNotBlank() }

internal fun pendingDeletionHasAnyMoveTargetField(intent: PendingDeletionIntent): Boolean =
    intent.moveTargetStorageType != null || intent.moveTargetId != null || intent.moveTargetIdentity != null


internal fun decodePendingDeletionIntent(raw: String): PendingDeletionIntent? {
    return when {
        raw.startsWith(PENDING_DELETION_V1_PREFIX) -> decodePendingDeletionV1(raw)
        raw.startsWith(PENDING_DELETION_V2_PREFIX) -> decodePendingDeletionV2(raw)
        raw.startsWith(PENDING_DELETION_V3_PREFIX) -> decodePendingDeletionV3(raw)
        raw.startsWith(PENDING_DELETION_V4_PREFIX) -> decodePendingDeletionV4(raw)
        else -> null
    }
}

private fun decodePendingDeletionV1(raw: String): PendingDeletionIntent? {
    val parts = raw.split('|')
    if (parts.size != 5 || parts[0] != "v1") return null
    val common = decodePendingDeletionCommon(parts[1], parts[2], parts[3], parts[4]) ?: return null
    return PendingDeletionIntent(common.first, common.second, common.third, common.fourth)
}

private fun decodePendingDeletionV2(raw: String): PendingDeletionIntent? {
    val parts = raw.split('|')
    if (parts.size != 8 || parts[0] != "v2") return null
    val storage = RecordingStorageType.fromLegacyName(parts[5]) ?: return null
    return decodePendingDeletionClaim(parts, storage)
}

private fun decodePendingDeletionV3(raw: String): PendingDeletionIntent? {
    val parts = raw.split('|')
    if (parts.size != 8 || parts[0] != "v3") return null
    val storage = parts[5].toIntOrNull()?.let(RecordingStorageType::fromStorageCode) ?: return null
    return decodePendingDeletionClaim(parts, storage)
}

private fun decodePendingDeletionV4(raw: String): PendingDeletionIntent? {
    val parts = raw.split('|')
    if (parts.size != 11 || parts[0] != "v4") return null
    val common = decodePendingDeletionCommon(parts[1], parts[2], parts[3], parts[4]) ?: return null
    val sourceStorage = parts[5].toIntOrNull()?.let(RecordingStorageType::fromStorageCode) ?: return null
    val claimToken: String?
    val sourceIdentity: String?
    if (sourceStorage == RecordingStorageType.FILE) {
        claimToken = parts[6].takeIf { it.isNotBlank() }?.let { value ->
            runCatching { UUID.fromString(value).toString() }.getOrNull() ?: return null
        } ?: return null
        sourceIdentity = decodePendingDeletionField(parts[7]) ?: return null
    } else {
        if (parts[6].isNotBlank() || parts[7].isNotBlank()) return null
        claimToken = null
        sourceIdentity = null
    }
    val targetStorage = parts[8].toIntOrNull()?.let(RecordingStorageType::fromStorageCode) ?: return null
    val targetId = decodePendingDeletionField(parts[9]) ?: return null
    val targetIdentity = decodePendingDeletionField(parts[10]) ?: return null
    return PendingDeletionIntent(
        id = common.first,
        byteCount = common.second,
        sha256Hex = common.third,
        assetDeleted = common.fourth,
        storageType = sourceStorage,
        claimToken = claimToken,
        fileIdentity = sourceIdentity,
        moveTargetStorageType = targetStorage,
        moveTargetId = targetId,
        moveTargetIdentity = targetIdentity,
    )
}

private fun decodePendingDeletionClaim(
    parts: List<String>,
    storage: RecordingStorageType,
): PendingDeletionIntent? {
    if (storage != RecordingStorageType.FILE) return null
    val common = decodePendingDeletionCommon(parts[1], parts[2], parts[3], parts[4]) ?: return null
    val token = parts[6].takeIf { it.isNotBlank() }?.let { value ->
        runCatching { UUID.fromString(value).toString() }.getOrNull() ?: return null
    }
    val fileIdentity = parts[7].takeIf { it.isNotBlank() }?.let { encoded ->
        decodePendingDeletionField(encoded) ?: return null
    }
    if (token == null || fileIdentity == null) return null
    return PendingDeletionIntent(
        common.first, common.second, common.third, common.fourth, storage, token, fileIdentity,
    )
}

private data class DecodedPendingDeletionCommon(
    val first: String,
    val second: Long,
    val third: String,
    val fourth: Boolean,
)

private fun decodePendingDeletionCommon(
    encodedId: String,
    byteCountText: String,
    shaText: String,
    deletedText: String,
): DecodedPendingDeletionCommon? {
    val id = runCatching {
        String(Base64.getUrlDecoder().decode(encodedId), StandardCharsets.UTF_8)
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
    val byteCount = byteCountText.toLongOrNull()?.takeIf { it > 0L } ?: return null
    val sha256 = shaText.lowercase()
    if (sha256.length != 64 || sha256.any { it !in '0'..'9' && it !in 'a'..'f' }) return null
    val deleted = when (deletedText) {
        "0" -> false
        "1" -> true
        else -> return null
    }
    return DecodedPendingDeletionCommon(id, byteCount, sha256, deleted)
}

private fun isEncodedPendingDeletionEntry(raw: String): Boolean =
    raw.startsWith(PENDING_DELETION_V1_PREFIX) ||
    raw.startsWith(PENDING_DELETION_V2_PREFIX) ||
    raw.startsWith(PENDING_DELETION_V3_PREFIX) ||
    raw.startsWith(PENDING_DELETION_V4_PREFIX)

private fun looksLikeVersionedPendingDeletionEntry(raw: String): Boolean {
    if (!raw.startsWith('v')) return false
    val separator = raw.indexOf('|')
    if (separator <= 1) return false
    return raw.substring(1, separator).all(Char::isDigit)
}

internal fun pendingDeletionSuppressedId(raw: String): String? {
    decodePendingDeletionIntent(raw)?.let { return it.id }
    if (!isEncodedPendingDeletionEntry(raw)) {
        return raw.takeIf { it.isNotBlank() && !looksLikeVersionedPendingDeletionEntry(it) }
    }
    val parts = raw.split('|')
    if (parts.size < 2) return null
    // Suppression needs only the selected recording ID, never the digest/identity/token that
    // grants destructive authority. Recover it from a recognized torn record so first paint
    // cannot resurrect a deletion while recovery determines what actually happened on disk.
    return runCatching {
        String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
    }.getOrNull()?.takeIf { it.isNotBlank() }
}

internal fun pendingDeletionMatchesDigest(
    intent: PendingDeletionIntent,
    byteCount: Long,
    sha256Hex: String,
): Boolean = intent.byteCount == byteCount && intent.sha256Hex.equals(sha256Hex, ignoreCase = true)

internal enum class FileDeletionClaimResult { DELETED, MISMATCH_PRESERVED, RETRY }

internal enum class ClaimedFileReplayAction { REPLAY, NO_CLAIM, WAIT }

internal fun claimedFileReplayAction(observation: StoragePathObservation): ClaimedFileReplayAction =
    when (observation.state) {
        StoragePathState.MISSING -> ClaimedFileReplayAction.NO_CLAIM
        StoragePathState.UNAVAILABLE -> ClaimedFileReplayAction.WAIT
        StoragePathState.PRESENT -> if (observation.isRegularFile) {
            ClaimedFileReplayAction.REPLAY
        } else {
            ClaimedFileReplayAction.WAIT
        }
    }

internal fun deletionClaimFile(intent: PendingDeletionIntent): File? {
    if (intent.storageType != RecordingStorageType.FILE) return null
    val token = intent.claimToken ?: return null
    val source = File(intent.id)
    val parent = source.parentFile ?: return null
    return File(parent, "$DELETION_CLAIM_PREFIX$token$DELETION_CLAIM_SUFFIX")
}

internal fun deleteClaimedFile(
    intent: PendingDeletionIntent,
    moveTargetStillCurrent: (() -> Boolean)? = null,
): FileDeletionClaimResult {
    val source = File(intent.id)
    val claim = deletionClaimFile(intent) ?: return FileDeletionClaimResult.RETRY
    try {
        Files.move(source.toPath(), claim.toPath())
        if (!confirmFileDirectoryStateDurable(source)) return FileDeletionClaimResult.RETRY
    } catch (_: NoSuchFileException) {
        return when (claimedFileReplayAction(observeStoragePath(claim))) {
            ClaimedFileReplayAction.REPLAY -> replayClaimedFileDeletion(intent, claim, moveTargetStillCurrent)
            ClaimedFileReplayAction.NO_CLAIM,
            ClaimedFileReplayAction.WAIT,
            -> FileDeletionClaimResult.RETRY
        }
    } catch (_: FileAlreadyExistsException) {
        return when (claimedFileReplayAction(observeStoragePath(claim))) {
            ClaimedFileReplayAction.REPLAY -> replayClaimedFileDeletion(intent, claim, moveTargetStillCurrent)
            ClaimedFileReplayAction.NO_CLAIM,
            ClaimedFileReplayAction.WAIT,
            -> FileDeletionClaimResult.RETRY
        }
    } catch (error: IOException) {
        Log.w("RecordingRepository", "Unable to atomically claim recording for deletion: ${intent.id}", error)
        return FileDeletionClaimResult.RETRY
    } catch (error: SecurityException) {
        Log.w("RecordingRepository", "Unable to atomically claim recording for deletion: ${intent.id}", error)
        return FileDeletionClaimResult.RETRY
    }
    return replayClaimedFileDeletion(intent, claim, moveTargetStillCurrent)
}

internal fun replayClaimedFileDeletion(
    intent: PendingDeletionIntent,
    claim: File? = deletionClaimFile(intent),
    moveTargetStillCurrent: (() -> Boolean)? = null,
): FileDeletionClaimResult {
    val resolvedClaim = claim ?: return FileDeletionClaimResult.RETRY
    val expectedIdentity = intent.fileIdentity ?: return FileDeletionClaimResult.RETRY
    if (!sameFileObjectAcrossRename(expectedIdentity, resolveFileIdentity(resolvedClaim))) {
        val preserved = restoreOrPublishMismatchedClaim(intent, resolvedClaim)
        return if (preserved) FileDeletionClaimResult.MISMATCH_PRESERVED else FileDeletionClaimResult.RETRY
    }
    val digest = runCatching { FileInputStream(resolvedClaim).use(::sha256) }.getOrElse { error ->
        Log.w("RecordingRepository", "Unable to verify claimed deletion file: ${resolvedClaim.absolutePath}", error)
        return FileDeletionClaimResult.RETRY
    }
    if (!pendingDeletionMatchesDigest(intent, digest.byteCount, digest.sha256.toHexString())) {
        val preserved = restoreOrPublishMismatchedClaim(intent, resolvedClaim)
        return if (preserved) FileDeletionClaimResult.MISMATCH_PRESERVED else FileDeletionClaimResult.RETRY
    }
    if (pendingDeletionHasAnyMoveTargetField(intent)) {
        val targetStillCurrent = runCatching { moveTargetStillCurrent?.invoke() == true }.getOrDefault(false)
        if (!targetStillCurrent) {
            val preserved = restoreOrPublishMismatchedClaim(intent, resolvedClaim)
            return if (preserved) FileDeletionClaimResult.MISMATCH_PRESERVED else FileDeletionClaimResult.RETRY
        }
    }
    return try {
        if (!Files.deleteIfExists(resolvedClaim.toPath())) return FileDeletionClaimResult.RETRY
        if (!confirmFileDirectoryStateDurable(resolvedClaim)) return FileDeletionClaimResult.RETRY
        FileDeletionClaimResult.DELETED
    } catch (error: IOException) {
        Log.w("RecordingRepository", "Unable to delete claimed recording: ${resolvedClaim.absolutePath}", error)
        FileDeletionClaimResult.RETRY
    } catch (error: SecurityException) {
        Log.w("RecordingRepository", "Unable to delete claimed recording: ${resolvedClaim.absolutePath}", error)
        FileDeletionClaimResult.RETRY
    }
}

private fun restoreOrPublishMismatchedClaim(intent: PendingDeletionIntent, claim: File): Boolean {
    val original = File(intent.id)
    try {
        Files.move(claim.toPath(), original.toPath())
        return confirmFileDirectoryStateDurable(original)
    } catch (_: FileAlreadyExistsException) {
        // A new object now owns the original path. Preserve the claimed replacement under
        // a visible recovery name rather than overwriting either object.
    } catch (_: IOException) {
        // Fall through to a separate recovery filename.
    } catch (_: SecurityException) {
        // Fall through to a separate recovery filename.
    }

    val parent = original.parentFile ?: claim.parentFile ?: return false
    val extension = original.name.substringAfterLast('.', "").takeIf { it.isNotBlank() }
    val suffix = extension?.let { ".$it" }.orEmpty()
    val base = "recovered-delete-race-${System.currentTimeMillis()}-${intent.claimToken?.take(8).orEmpty()}"
    var index = 0
    while (index < 10_000) {
        val candidateName = if (index == 0) "$base$suffix" else "$base-$index$suffix"
        val candidate = File(parent, candidateName)
        try {
            Files.move(claim.toPath(), candidate.toPath())
            return confirmFileDirectoryStateDurable(candidate)
        } catch (_: FileAlreadyExistsException) {
            index++
        } catch (error: IOException) {
            Log.w("RecordingRepository", "Unable to publish mismatched deletion claim: ${claim.absolutePath}", error)
            return false
        } catch (error: SecurityException) {
            Log.w("RecordingRepository", "Unable to publish mismatched deletion claim: ${claim.absolutePath}", error)
            return false
        }
    }
    return false
}

internal enum class PendingDeletionReplayAction { WAIT, ABANDON_INTENT, CLEAN_CATALOG }

internal fun pendingDeletionReplayAction(
    intent: PendingDeletionIntent,
    assetState: RecordingAssetState,
): PendingDeletionReplayAction = when {
    assetState == RecordingAssetState.UNAVAILABLE -> PendingDeletionReplayAction.WAIT
    assetState == RecordingAssetState.PRESENT -> PendingDeletionReplayAction.ABANDON_INTENT
    intent.assetDeleted -> PendingDeletionReplayAction.CLEAN_CATALOG
    else -> PendingDeletionReplayAction.CLEAN_CATALOG
}

internal fun ByteArray.toHexString(): String = joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

internal fun isRecordingEligibleForMove(id: String, pendingDeletionIds: Set<String>): Boolean =
    id !in pendingDeletionIds

internal fun recordingCatalogIdentityIsCurrent(
    stableIdentityAvailable: Boolean,
    currentIdentityMatches: Boolean,
): Boolean = stableIdentityAvailable && currentIdentityMatches

internal enum class VerifiedMoveCommitResult { MOVED, SOURCE_CLEANUP_FAILED, TARGET_CHANGED }

internal fun verifiedMoveCommitResult(
    targetIdentityCurrent: Boolean,
    sourceCleanupComplete: Boolean,
): VerifiedMoveCommitResult = when {
    !targetIdentityCurrent -> VerifiedMoveCommitResult.TARGET_CHANGED
    !sourceCleanupComplete -> VerifiedMoveCommitResult.SOURCE_CLEANUP_FAILED
    else -> VerifiedMoveCommitResult.MOVED
}

internal fun visibleCatalogRecordings(
    recordings: List<RecordingEntity>,
    pendingDeletionIds: Set<String> = emptySet(),
): List<RecordingEntity> = recordings.filter { recording ->
    recording.id !in pendingDeletionIds && recording.missingSinceMillis == null
}

internal fun sameRecordingActionTarget(
    previous: RecordingEntity,
    current: RecordingEntity,
): Boolean {
    if (previous.id != current.id || previous.storageType != current.storageType) return false
    val previousIdentity = previous.fileIdentity
    val currentIdentity = current.fileIdentity
    if (previousIdentity.isNotBlank() || currentIdentity.isNotBlank()) {
        if (previousIdentity.isBlank() || currentIdentity.isBlank()) return false
        return when (previous.storageType) {
            RecordingStorageType.FILE -> previousIdentity == currentIdentity
            RecordingStorageType.DOCUMENT,
            RecordingStorageType.MEDIASTORE,
            -> providerRecordingIdentityMatches(previousIdentity, currentIdentity)
        }
    }
    // Legacy/provider rows without a stable identity may still be displayed, but never carry a
    // selection across a material metadata change that could indicate ID reuse.
    return previous.sizeBytes == current.sizeBytes &&
        previous.durationMillis == current.durationMillis &&
        previous.startedAtMillis == current.startedAtMillis
}

internal fun observedRecordingIsSameAsset(
    existing: RecordingEntity,
    observed: RecordingEntity,
): Boolean {
    if (existing.id != observed.id || existing.storageType != observed.storageType) return false
    val existingIdentity = existing.fileIdentity
    val observedIdentity = observed.fileIdentity
    return when (observed.storageType) {
        RecordingStorageType.FILE -> {
            // FILE reads/destructive actions independently pin and verify the inode. A blank
            // observation can therefore preserve a known identity across a transient stat failure.
            existingIdentity.isBlank() || observedIdentity.isBlank() || existingIdentity == observedIdentity
        }
        RecordingStorageType.DOCUMENT,
        RecordingStorageType.MEDIASTORE,
        -> when {
            existingIdentity.isBlank() -> true // One-time identity bootstrap / legacy row.
            observedIdentity.isBlank() -> false // Existing provider identity can no longer be proven.
            else -> providerRecordingIdentityMatches(existingIdentity, observedIdentity)
        }
    }
}

internal fun mergeObservedRecording(
    existing: RecordingEntity?,
    observed: RecordingEntity,
    nowMillis: Long,
): RecordingEntity {
    val sameAsset = existing?.let { observedRecordingIsSameAsset(it, observed) } ?: false
    val observedStorageType = observed.storageType
    val providerIdentityUnproven = existing != null &&
        existing.id == observed.id && existing.storageType == observed.storageType &&
        (observedStorageType == RecordingStorageType.DOCUMENT ||
            observedStorageType == RecordingStorageType.MEDIASTORE) &&
        existing.fileIdentity.isNotBlank() && observed.fileIdentity.isBlank()
    val metadataFallback = existing?.takeIf { sameAsset || providerIdentityUnproven }
    val waveformFallback = existing?.takeIf { current -> sameRecordingActionTarget(current, observed) }
    val merged = observed.copy(
        mimeType = observed.mimeType.takeIf { it.isNotBlank() } ?: metadataFallback?.mimeType.orEmpty(),
        durationMillis = observed.durationMillis.takeIf { it > 0L } ?: metadataFallback?.durationMillis ?: 0L,
        sizeBytes = observed.sizeBytes.takeIf { it > 0L } ?: metadataFallback?.sizeBytes ?: 0L,
        codecSummary = observed.codecSummary.takeIf { it.isNotBlank() } ?: metadataFallback?.codecSummary.orEmpty(),
        fileIdentity = when {
            observed.fileIdentity.isNotBlank() -> observed.fileIdentity
            sameAsset || providerIdentityUnproven -> metadataFallback?.fileIdentity.orEmpty()
            else -> ""
        },
        createdAtMillis = metadataFallback?.createdAtMillis ?: observed.createdAtMillis,
        lastSeenAtMillis = if (existing == null || existing.missingSinceMillis != null ||
            (!sameAsset && !providerIdentityUnproven)
        ) {
            nowMillis
        } else {
            existing.lastSeenAtMillis
        },
        missingSinceMillis = null,
    )
    val revision = recordingWaveformRevision(merged)
    val preserveWaveform = revision.isNotBlank() &&
        waveformFallback?.waveformRevision == revision &&
        decodeRecordingWaveform(waveformFallback.waveformData) != null
    return merged.copy(
        waveformData = if (preserveWaveform) waveformFallback.waveformData else "",
        waveformRevision = if (preserveWaveform) revision else "",
    )
}

internal fun markRecordingPresent(
    recording: RecordingEntity,
    nowMillis: Long,
): RecordingEntity {
    return if (recording.missingSinceMillis == null) {
        recording
    } else {
        recording.copy(
            lastSeenAtMillis = nowMillis,
            missingSinceMillis = null,
        )
    }
}

internal fun markRecordingMissing(
    recording: RecordingEntity,
    nowMillis: Long,
): RecordingEntity {
    return if (recording.missingSinceMillis != null) {
        recording
    } else {
        recording.copy(missingSinceMillis = nowMillis)
    }
}
