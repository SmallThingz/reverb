package app.smallthingz.reverb

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

object RecordingRepository {
    internal const val MISSING_RECORDING_TTL_MILLIS = 24L * 60L * 60L * 1000L
    private val mutex = Mutex()

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
            RecordingDatabase.getInstance(context).recordingDao().hasMovableRecordings(targetDirectoryId)
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
                }
                deleted
            }
        }
    }

    suspend fun forget(context: Context, recordingId: String) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                RecordingDatabase.getInstance(context).recordingDao().deleteById(recordingId)
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
                RecordingDatabase.getInstance(context).recordingDao().deleteById(recording.id)
                RecordingDatabase.getInstance(context).recordingDao().upsert(renamed)
                renamed
            }
        }
    }

    suspend fun pruneMissing(context: Context): Int {
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                pruneMissingLocked(context)
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
                val copied = mutableListOf<Pair<RecordingEntity, RecordingEntity>>()
                var skipped = 0

                current.forEach { recording ->
                    if (!recordingExists(context, recording)) {
                        missingDeletes += recording.id
                        return@forEach
                    }

                    if (recording.directoryId == targetDirectoryId) {
                        skipped++
                        return@forEach
                    }

                    val movedRecording = copyRecordingToConfiguredDirectory(context, recording)
                    if (movedRecording != null) {
                        copied += recording to movedRecording
                    }
                }

                val updates = mutableListOf<RecordingEntity>()
                val movedDeletes = mutableListOf<String>()
                copied.forEach { (source, target) ->
                    if (deleteRecordingAsset(context, source)) {
                        updates += target
                        movedDeletes += source.id
                    } else {
                        deleteRecordingAsset(context, target)
                    }
                }
                val deletes = missingDeletes + movedDeletes
                if (updates.isNotEmpty()) {
                    dao.upsertAll(updates)
                }
                if (deletes.isNotEmpty()) {
                    dao.deleteByIds(deletes)
                }

                MoveResult(moved = movedDeletes.size, skipped = skipped, removedMissing = missingDeletes.size)
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
        val importedPresent = imported.map { mergeObservedRecording(existingById[it.id], it, nowMillis) }
        val importedIds = HashSet<String>(importedPresent.size)
        importedPresent.mapTo(importedIds) { it.id }
        val importedUpdates = importedPresent.filter { existingById[it.id] != it }
        val updates = mutableListOf<RecordingEntity>()
        val staleIds = mutableListOf<String>()

        existing.asSequence()
            .filter { it.id !in importedIds }
            .forEach { recording ->
                // Keep DB rows for files that still exist even if the directory scan
                // did not rediscover them yet (for example, provider lag or format-
                // specific scan gaps right after export).
                val updated =
                    if (recordingExists(context, recording)) {
                        markRecordingPresent(recording, nowMillis)
                    } else {
                        markRecordingMissing(recording, nowMillis)
                    }
                when {
                    isMissingRecordingExpired(updated, nowMillis) -> staleIds += recording.id
                    updated != recording -> updates += updated
                }
            }

        if (importedUpdates.isNotEmpty()) {
            dao.upsertAll(importedUpdates)
        }
        if (updates.isNotEmpty()) {
            dao.upsertAll(updates)
        }
        if (staleIds.isNotEmpty()) {
            dao.deleteByIds(staleIds)
        }
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
            val updated =
                if (recordingExists(context, recording)) {
                    markRecordingPresent(recording, nowMillis)
                } else {
                    markRecordingMissing(recording, nowMillis)
                }
            when {
                isMissingRecordingExpired(updated, nowMillis) -> missingIds += recording.id
                updated != recording -> updates += updated
            }
        }
        if (updates.isNotEmpty()) {
            dao.upsertAll(updates)
        }
        if (missingIds.isNotEmpty()) {
            dao.deleteByIds(missingIds)
        }
        return missingIds.size
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
