package app.smallthingz.reverb

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

object RecordingRepository {
    internal const val MISSING_RECORDING_TTL_MILLIS = 24L * 60L * 60L * 1000L
    private val mutex = Mutex()
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pendingDirectoryIds = mutableSetOf<String>()

    suspend fun refresh(context: Context): List<RecordingEntity> {
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                syncConfiguredDirectory(context)
                pruneMissingLocked(context, skipDirectoryId = getConfiguredOutputDirectoryId(context))
                RecordingDatabase.getInstance(context).recordingDao().listAll()
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
            RecordingDatabase.getInstance(context).recordingDao().listAll()
        }
    }

    suspend fun hasMovableKnownRecordings(
        context: Context,
        targetDirectoryId: String = getConfiguredOutputDirectoryId(context),
    ): Boolean {
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                val dao = RecordingDatabase.getInstance(context).recordingDao()
                val nowMillis = System.currentTimeMillis()
                val updates = mutableListOf<RecordingEntity>()
                val expiredIds = mutableListOf<String>()
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
                    when {
                        isMissingRecordingExpired(updated, nowMillis) -> expiredIds += recording.id
                        updated != recording -> updates += updated
                    }
                }
                dao.applyChanges(updates, expiredIds)
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

    fun schedulePersistedPermissionCleanup(context: Context) {
        val appContext = context.applicationContext
        cleanupScope.launch {
            cleanupPersistedDirectoryPermissions(appContext)
        }
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
                val deleted = deleteRecordingAsset(context, recording)
                if (deleted) {
                    RecordingDatabase.getInstance(context).recordingDao().deleteById(recording.id)
                    schedulePersistedPermissionCleanup(context)
                }
                deleted
            }
        }
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
                        deleteIds = listOf(recording.id),
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
            mutex.withLock {
                val dao = RecordingDatabase.getInstance(context).recordingDao()
                val current = dao.listAll()
                if (current.isEmpty()) {
                    return@withLock MoveResult()
                }

                val targetDirectoryId = getConfiguredOutputDirectoryId(context)
                val missingDeletes = mutableListOf<String>()
                val stateUpdates = mutableListOf<RecordingEntity>()
                val moveCandidates = mutableListOf<RecordingEntity>()
                var skipped = 0
                val nowMillis = System.currentTimeMillis()

                current.forEach { recording ->
                    val present = when (recordingAssetState(context, recording)) {
                        RecordingAssetState.PRESENT -> markRecordingPresent(recording, nowMillis)
                        RecordingAssetState.MISSING -> {
                            val missing = markRecordingMissing(recording, nowMillis)
                            if (isMissingRecordingExpired(missing, nowMillis)) {
                                missingDeletes += recording.id
                            } else if (missing != recording) {
                                stateUpdates += missing
                            }
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
                dao.applyChanges(stateUpdates, missingDeletes)

                var moved = 0
                moveCandidates.forEach { source ->
                    val target = copyRecordingToConfiguredDirectory(context, source)
                    if (target == null) {
                        skipped++
                        return@forEach
                    }
                    try {
                        dao.applyChanges(
                            upserts = listOf(target),
                            deleteIds = listOf(source.id),
                        )
                    } catch (error: Exception) {
                        deleteRecordingAsset(context, target)
                        throw error
                    }

                    // Commit the catalog switch before deleting a source. If source cleanup
                    // fails, the verified target remains authoritative and the worst case is
                    // an unindexed duplicate, never a catalog row pointing at deleted audio.
                    deleteRecordingAsset(context, source)
                    moved++
                }

                MoveResult(moved = moved, skipped = skipped, removedMissing = missingDeletes.size).also {
                    if (moved > 0 || missingDeletes.isNotEmpty()) {
                        schedulePersistedPermissionCleanup(context)
                    }
                }
            }
        }
    }

    private suspend fun syncConfiguredDirectory(context: Context) {
        val dao = RecordingDatabase.getInstance(context).recordingDao()
        val currentDirectoryId = getConfiguredOutputDirectoryId(context)
        val existing = dao.listByDirectory(currentDirectoryId)
        val existingById = HashMap<String, RecordingEntity>(existing.size)
        existing.associateByTo(existingById) { it.id }
        val imported = listCurrentOutputDirectoryRecordings(context, existingById)
        val nowMillis = System.currentTimeMillis()
        val importedIds = HashSet<String>(imported.size)
        val importedUpdates = ArrayList<RecordingEntity>()
        imported.forEach { observed ->
            val merged = mergeObservedRecording(existingById[observed.id], observed, nowMillis)
            importedIds += merged.id
            if (existingById[merged.id] != merged) importedUpdates += merged
        }
        val updates = mutableListOf<RecordingEntity>()
        val staleIds = mutableListOf<String>()

        existing.asSequence()
            .filter { it.id !in importedIds }
            .forEach { recording ->
                // Keep DB rows for files that still exist even if the directory scan
                // did not rediscover them yet (for example, provider lag or format-
                // specific scan gaps right after export).
                val updated = when (recordingAssetState(context, recording)) {
                    RecordingAssetState.PRESENT -> markRecordingPresent(recording, nowMillis)
                    RecordingAssetState.MISSING -> markRecordingMissing(recording, nowMillis)
                    RecordingAssetState.UNAVAILABLE -> recording
                }
                when {
                    isMissingRecordingExpired(updated, nowMillis) -> staleIds += recording.id
                    updated != recording -> updates += updated
                }
            }

        dao.applyChanges(importedUpdates + updates, staleIds)
    }

    private suspend fun pruneMissingLocked(
        context: Context,
        skipDirectoryId: String? = null,
    ): Int {
        val dao = RecordingDatabase.getInstance(context).recordingDao()
        val all = dao.listAll()
        val nowMillis = System.currentTimeMillis()
        val updates = mutableListOf<RecordingEntity>()
        val missingIds = mutableListOf<String>()
        all.forEach { recording ->
            if (recording.directoryId == skipDirectoryId) return@forEach
            val updated = when (recordingAssetState(context, recording)) {
                RecordingAssetState.PRESENT -> markRecordingPresent(recording, nowMillis)
                RecordingAssetState.MISSING -> markRecordingMissing(recording, nowMillis)
                RecordingAssetState.UNAVAILABLE -> recording
            }
            when {
                isMissingRecordingExpired(updated, nowMillis) -> missingIds += recording.id
                updated != recording -> updates += updated
            }
        }
        dao.applyChanges(updates, missingIds)
        return missingIds.size
    }

    private suspend fun cleanupPersistedDirectoryPermissions(context: Context) {
        mutex.withLock {
            val keep = mutableSetOf<String>()
            getConfiguredExportTreeUri(context)?.toString()?.let(keep::add)
            RecordingDatabase.getInstance(context).recordingDao().listAll().asSequence()
                .filter { resolveRecordingStorageType(it) == RecordingStorageType.DOCUMENT }
                .map { it.directoryId }
                .filter { it.isNotBlank() }
                .forEach(keep::add)
            synchronized(pendingDirectoryIds) {
                keep += pendingDirectoryIds
            }

            val permissions = runCatching { context.contentResolver.persistedUriPermissions }
                .getOrElse { return@withLock }
            permissions.forEach { permission ->
                if (permission.uri.toString() in keep) return@forEach
                var flags = 0
                if (permission.isReadPermission) flags = flags or Intent.FLAG_GRANT_READ_URI_PERMISSION
                if (permission.isWritePermission) flags = flags or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                if (flags != 0) {
                    runCatching {
                        context.contentResolver.releasePersistableUriPermission(permission.uri, flags)
                    }
                }
            }
        }
    }

    data class MoveResult(
        val moved: Int = 0,
        val skipped: Int = 0,
        val removedMissing: Int = 0,
    )
}

internal fun mergeObservedRecording(
    existing: RecordingEntity?,
    observed: RecordingEntity,
    nowMillis: Long,
): RecordingEntity {
    return observed.copy(
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

internal fun isMissingRecordingExpired(
    recording: RecordingEntity,
    nowMillis: Long,
): Boolean {
    val missingSinceMillis = recording.missingSinceMillis ?: return false
    return nowMillis - missingSinceMillis >= RecordingRepository.MISSING_RECORDING_TTL_MILLIS
}
