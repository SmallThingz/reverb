package app.smallthingz.reverb

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

object RecordingRepository {
    private val mutex = Mutex()
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pendingDirectoryIds = mutableSetOf<String>()
    private val backgroundDeleteLock = Any()
    private var backgroundDeleteJob: Job? = null

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
                replayPendingDeletionsLocked(context)
                syncRecoverableDirectories(context)
                updateMissingStatesLocked(context, skipDirectoryId = getConfiguredOutputDirectoryId(context))
                val pending = pendingDeletionIds(context)
                visibleCatalogRecordings(
                    RecordingDatabase.getInstance(context).recordingDao().listAll(),
                    pending,
                )
            }
        }
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
                val pending = pendingDeletionIds(context)
                visibleCatalogRecordings(
                    RecordingDatabase.getInstance(context).recordingDao().listAll(),
                    pending,
                )
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
                val dao = RecordingDatabase.getInstance(context).recordingDao()
                val nowMillis = System.currentTimeMillis()
                val updates = mutableListOf<RecordingEntity>()
                var movable = false
                dao.listAll().forEach { recording ->
                    if (recording.directoryId == targetDirectoryId) return@forEach
                    val updated = when (recordingAssetState(context, recording)) {
                        RecordingAssetState.PRESENT -> {
                            movable = true
                            markRecordingPresent(recording, nowMillis)
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

    fun retainPendingDirectory(uri: Uri) {
        synchronized(pendingDirectoryIds) {
            pendingDirectoryIds += uri.toString()
        }
    }

    fun releasePendingDirectoryAndCleanup(context: Context, uri: Uri?) {
        if (uri != null) {
            synchronized(pendingDirectoryIds) {
                pendingDirectoryIds -= uri.toString()
            }
        }
        schedulePersistedPermissionCleanup(context)
    }

    fun schedulePersistedPermissionCleanup(@Suppress("UNUSED_PARAMETER") context: Context) {
        // Persisted SAF grants are part of the recovery path for recordings outside the
        // currently selected directory. Provider scans can transiently report an empty
        // directory, so automatically releasing a grant can make the only surviving audio
        // unreachable. Keep grants until the user clears app data or an explicit, verified
        // destructive workflow is introduced.
    }

    suspend fun register(context: Context, recording: RecordingEntity): RecordingEntity {
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                val presentRecording = mergeObservedRecording(
                    existing = null, observed = recording,
                    nowMillis = System.currentTimeMillis(),
                )
                RecordingDatabase.getInstance(context).recordingDao().upsert(presentRecording)
                presentRecording
            }
        }
    }

    suspend fun delete(context: Context, recording: RecordingEntity): Boolean {
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                if (!addPendingDeletionLocked(context, recording.id)) return@withLock false
                if (!deleteRecordingAsset(context, recording)) return@withLock false
                RecordingDatabase.getInstance(context).recordingDao().deleteById(recording.id)
                removePendingDeletionLocked(context, recording.id)
                schedulePersistedPermissionCleanup(context)
                true
            }
        }
    }

    private suspend fun replayPendingDeletionsLocked(context: Context) {
        val pendingIds = pendingDeletionIds(context)
        if (pendingIds.isEmpty()) return
        val dao = RecordingDatabase.getInstance(context).recordingDao()
        val byId = dao.listAll().associateBy { it.id }
        for (id in pendingIds) {
            val recording = byId[id] ?: continue
            if (!deleteRecordingAsset(context, recording)) continue
            dao.deleteById(id)
            removePendingDeletionLocked(context, id)
        }
    }

    private fun pendingDeletionIds(context: Context): Set<String> =
        getRecorderPreferences(context).getStringSet(PrefKey.PENDING_RECORDING_DELETIONS, emptySet())
            ?.toSet()
            .orEmpty()

    private fun addPendingDeletionLocked(context: Context, id: String): Boolean {
        val updated = pendingDeletionIds(context) + id
        return getRecorderPreferences(context).edit()
            .putStringSet(PrefKey.PENDING_RECORDING_DELETIONS, updated)
            .commit()
    }

    private fun removePendingDeletionLocked(context: Context, id: String): Boolean {
        val updated = pendingDeletionIds(context) - id
        val editor = getRecorderPreferences(context).edit()
        if (updated.isEmpty()) editor.remove(PrefKey.PENDING_RECORDING_DELETIONS)
        else editor.putStringSet(PrefKey.PENDING_RECORDING_DELETIONS, updated)
        return editor.commit()
    }

    suspend fun rename(
        context: Context,
        recording: RecordingEntity,
        requestedBaseName: String,
    ): RecordingEntity? {
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                val renamed = renameRecordingAsset(context, recording, requestedBaseName) ?: return@withLock null
                if (renamed == recording) return@withLock recording
                try {
                    RecordingDatabase.getInstance(context).recordingDao().applyChanges(
                        upserts = listOf(renamed),
                        deleteIds = if (renamed.id == recording.id) emptyList() else listOf(recording.id),
                    )
                } catch (error: Exception) {
                    // Keep the catalog and physical asset on the same name when the
                    // database commit fails. Recovery can still rediscover the renamed
                    // asset if a provider refuses the rollback.
                    runCatching {
                        renameRecordingAsset(context, renamed, recording.displayName)
                    }
                    throw error
                }
                renamed
            }
        }
    }

    suspend fun moveAllToConfiguredDirectory(context: Context): MoveResult {
        return withContext(Dispatchers.IO) {
            awaitBackgroundDeletes()
            mutex.withLock {
                replayPendingDeletionsLocked(context)
                val dao = RecordingDatabase.getInstance(context).recordingDao()
                val current = dao.listAll()
                if (current.isEmpty()) {
                    return@withLock MoveResult()
                }

                val targetDirectoryId = getConfiguredOutputDirectoryId(context)
                val stateUpdates = mutableListOf<RecordingEntity>()
                val moveCandidates = mutableListOf<RecordingEntity>()
                var skipped = 0
                val nowMillis = System.currentTimeMillis()

                current.forEach { recording ->
                    val present = when (recordingAssetState(context, recording)) {
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
                val durableTargets = current.filter { it.directoryId == targetDirectoryId }.toMutableList()
                moveCandidates.forEach { source ->
                    val recoveredTarget = durableTargets.firstOrNull { candidate ->
                        candidate.displayName == source.displayName &&
                            recordingsHaveSameContent(context, source, candidate)
                    }
                    val target = recoveredTarget ?: copyRecordingToConfiguredDirectory(context, source)
                    if (target == null) {
                        skipped++
                        return@forEach
                    }
                    if (recoveredTarget == null) durableTargets += target
                    try {
                        dao.applyChanges(
                            upserts = listOf(target),
                            deleteIds = listOf(source.id),
                        )
                    } catch (error: Exception) {
                        if (recoveredTarget == null) deleteRecordingAsset(context, target)
                        throw error
                    }

                    // Commit the catalog switch before deleting a source. If source cleanup
                    // fails, the verified target remains authoritative and the worst case is
                    // an extra recoverable copy, never a catalog row pointing at deleted audio.
                    deleteRecordingAsset(context, source)
                    moved++
                }

                MoveResult(moved = moved, skipped = skipped).also {
                    if (moved > 0) schedulePersistedPermissionCleanup(context)
                }
            }
        }
    }

    private suspend fun syncRecoverableDirectories(context: Context) {
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

        for (treeUri in directoryUris.values) {
            syncRecoverableDirectory(context, treeUri)
        }

        // Pre-MediaStore builds saved into app-specific external/internal storage. Keep
        // scanning it forever so upgrading never strands a recording in the old location.
        val legacyDirectoryId = getSavedRecordingsDirectory(context).absolutePath
        if (legacyDirectoryId !in directoryUris.keys) {
            syncObservedDirectory(context, legacyDirectoryId) { known ->
                listLegacyAppStorageRecordings(context, known)
            }
        }

        // Older builds used app-specific external storage, which Android removes on
        // uninstall. When the user is on the default destination, migrate those recordings
        // to the durable shared Music/Reverb destination using verified copy-before-delete.
        if (getConfiguredExportTreeUri(context) == null && legacyDirectoryId != getConfiguredOutputDirectoryId(context)) {
            migrateLegacyAppStorageLocked(context, legacyDirectoryId)
        }
    }

    private suspend fun migrateLegacyAppStorageLocked(context: Context, legacyDirectoryId: String) {
        val dao = RecordingDatabase.getInstance(context).recordingDao()
        val targetDirectoryId = getConfiguredOutputDirectoryId(context)
        val durableTargets = dao.listByDirectory(targetDirectoryId).toMutableList()
        val legacy = dao.listByDirectory(legacyDirectoryId)

        for (source in legacy) {
            if (recordingAssetState(context, source) != RecordingAssetState.PRESENT) continue

            // If a previous process died after publishing the target but before committing
            // the catalog switch, reuse that byte-identical target instead of duplicating it.
            val recoveredTarget = durableTargets.firstOrNull { candidate ->
                candidate.displayName == source.displayName && recordingsHaveSameContent(context, source, candidate)
            }
            val target = recoveredTarget ?: copyRecordingToConfiguredDirectory(context, source) ?: continue

            if (recoveredTarget == null) durableTargets += target
            try {
                dao.applyChanges(
                    upserts = listOf(target),
                    deleteIds = listOf(source.id),
                )
            } catch (error: Exception) {
                if (recoveredTarget == null) deleteRecordingAsset(context, target)
                throw error
            }

            // Deleting the source is strictly last. Failure leaves an extra copy which will
            // be rediscovered and deduplicated on a later refresh; it never loses audio.
            deleteRecordingAsset(context, source)
        }
    }

    private suspend fun syncRecoverableDirectory(
        context: Context,
        treeUri: Uri?,
    ) {
        val directoryId = getOutputDirectoryId(context, treeUri)
        syncObservedDirectory(context, directoryId) { known ->
            listOutputDirectoryRecordings(context, treeUri, known)
        }
    }

    private suspend fun syncObservedDirectory(
        context: Context,
        directoryId: String,
        scan: (Map<String, RecordingEntity>) -> List<RecordingEntity>,
    ) {
        val dao = RecordingDatabase.getInstance(context).recordingDao()
        val existing = dao.listByDirectory(directoryId)
        val existingById = HashMap<String, RecordingEntity>(existing.size)
        existing.associateByTo(existingById) { it.id }
        val imported = scan(existingById)
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
                val updated = when (recordingAssetState(context, recording)) {
                    RecordingAssetState.PRESENT -> markRecordingPresent(recording, nowMillis)
                    RecordingAssetState.MISSING -> markRecordingMissing(recording, nowMillis)
                    RecordingAssetState.UNAVAILABLE -> recording
                }
                if (updated != recording) updates += updated
            }

        dao.applyChanges(importedUpdates + updates, emptyList())
    }

    private suspend fun updateMissingStatesLocked(
        context: Context,
        skipDirectoryId: String? = null,
    ): Int {
        val dao = RecordingDatabase.getInstance(context).recordingDao()
        val all = dao.listAll()
        val nowMillis = System.currentTimeMillis()
        val updates = mutableListOf<RecordingEntity>()
        all.forEach { recording ->
            if (recording.directoryId == skipDirectoryId) return@forEach
            val updated = when (recordingAssetState(context, recording)) {
                RecordingAssetState.PRESENT -> markRecordingPresent(recording, nowMillis)
                RecordingAssetState.MISSING -> markRecordingMissing(recording, nowMillis)
                RecordingAssetState.UNAVAILABLE -> recording
            }
            if (updated != recording) updates += updated
        }
        dao.applyChanges(updates, emptyList())
        return 0
    }

    data class MoveResult(
        val moved: Int = 0,
        val skipped: Int = 0,
        val removedMissing: Int = 0,
    )
}

internal fun recordingDirectoryIdsToRetain(
    recordings: List<RecordingEntity>,
): Set<String> = recordings.asSequence()
    .map { it.directoryId }
    .filter { it.isNotBlank() }
    .toSet()

internal fun visibleCatalogRecordings(
    recordings: List<RecordingEntity>,
    pendingDeletionIds: Set<String> = emptySet(),
): List<RecordingEntity> = recordings.filter { recording ->
    recording.id !in pendingDeletionIds && recording.missingSinceMillis == null
}

internal fun mergeObservedRecording(
    existing: RecordingEntity?,
    observed: RecordingEntity,
    nowMillis: Long,
): RecordingEntity {
    return observed.copy(
        mimeType = observed.mimeType.takeIf { it.isNotBlank() } ?: existing?.mimeType.orEmpty(),
        durationMillis = observed.durationMillis.takeIf { it > 0L } ?: existing?.durationMillis ?: 0L,
        sizeBytes = observed.sizeBytes.takeIf { it > 0L } ?: existing?.sizeBytes ?: 0L,
        codecSummary = observed.codecSummary.takeIf { it.isNotBlank() } ?: existing?.codecSummary.orEmpty(),
        createdAtMillis = existing?.createdAtMillis ?: observed.createdAtMillis,
        lastSeenAtMillis = if (existing == null || existing.missingSinceMillis != null) {
            nowMillis
        } else {
            existing.lastSeenAtMillis
        },
        missingSinceMillis = null,
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
