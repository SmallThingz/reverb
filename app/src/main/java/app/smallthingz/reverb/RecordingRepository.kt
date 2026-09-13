package app.smallthingz.reverb

import android.content.Context
import android.net.Uri
import android.util.Log
import java.nio.charset.StandardCharsets
import java.util.Base64
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
                val pendingIds = pendingDeletionIds(context)
                val nowMillis = System.currentTimeMillis()
                val updates = mutableListOf<RecordingEntity>()
                var movable = false
                dao.listAll().forEach { recording ->
                    if (!isRecordingEligibleForMove(recording.id, pendingIds)) return@forEach
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
                replayPendingDeletionsLocked(context)
                val dao = RecordingDatabase.getInstance(context).recordingDao()
                val tracked = dao.listAll().firstOrNull { it.id == recording.id }
                    ?: return@withLock true

                val intent = createPendingDeletionIntent(context, tracked) ?: return@withLock false
                if (!putPendingDeletionLocked(context, intent)) return@withLock false
                if (!pendingDeletionMatchesCurrentAsset(context, tracked, intent)) {
                    removePendingDeletionLocked(context, tracked.id)
                    return@withLock false
                }
                if (!deleteRecordingAsset(context, tracked)) return@withLock false

                // Persist the destructive phase boundary before touching catalog metadata.
                // If this commit fails, replay still never repeats physical deletion.
                putPendingDeletionLocked(context, intent.copy(assetDeleted = true))
                dao.deleteById(tracked.id)
                removePendingDeletionLocked(context, tracked.id)
                schedulePersistedPermissionCleanup(context)
                true
            }
        }
    }

    private suspend fun replayPendingDeletionsLocked(context: Context) {
        val rawEntries = pendingDeletionEntries(context)
        if (rawEntries.isEmpty()) return
        val dao = RecordingDatabase.getInstance(context).recordingDao()
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
        getRecorderPreferences(context).getStringSet(PrefKey.PENDING_RECORDING_DELETIONS, emptySet())
            ?.toSet()
            .orEmpty()

    private fun pendingDeletionIds(context: Context): Set<String> =
        pendingDeletionEntries(context).mapNotNullTo(mutableSetOf()) { raw ->
            decodePendingDeletionIntent(raw)?.id ?: raw.takeUnless { it.startsWith(PENDING_DELETION_VERSION_PREFIX) }
        }

    private fun createPendingDeletionIntent(
        context: Context,
        recording: RecordingEntity,
    ): PendingDeletionIntent? = runCatching {
        val digest = openRecordingInputStream(context, recording)?.use(::sha256) ?: return@runCatching null
        if (digest.byteCount <= 0L) return@runCatching null
        PendingDeletionIntent(
            id = recording.id,
            byteCount = digest.byteCount,
            sha256Hex = digest.sha256.toHexString(),
            assetDeleted = false,
        )
    }.getOrNull()

    private fun pendingDeletionMatchesCurrentAsset(
        context: Context,
        recording: RecordingEntity,
        intent: PendingDeletionIntent,
    ): Boolean = runCatching {
        val digest = openRecordingInputStream(context, recording)?.use(::sha256) ?: return@runCatching false
        pendingDeletionMatchesDigest(intent, digest.byteCount, digest.sha256.toHexString())
    }.getOrDefault(false)

    private fun putPendingDeletionLocked(context: Context, intent: PendingDeletionIntent): Boolean {
        val current = pendingDeletionEntries(context)
        val updated = current.filterNotTo(mutableSetOf()) { raw ->
            decodePendingDeletionIntent(raw)?.id == intent.id || raw == intent.id
        }
        updated += encodePendingDeletionIntent(intent)
        return getRecorderPreferences(context).edit()
            .putStringSet(PrefKey.PENDING_RECORDING_DELETIONS, updated)
            .commit()
    }

    private fun removePendingDeletionLocked(context: Context, id: String): Boolean {
        val current = pendingDeletionEntries(context)
        val updated = current.filterNotTo(mutableSetOf()) { raw ->
            decodePendingDeletionIntent(raw)?.id == id || raw == id
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
                    // Prefer restoring the original physical identity. If that is no longer
                    // possible (for example the old path was reused concurrently), never keep
                    // a stale catalog row that could now address unrelated bytes. A refresh can
                    // rediscover both the renamed recording and any replacement independently.
                    val rolledBack = runCatching {
                        renameRecordingAsset(context, renamed, recording.displayName)
                    }.getOrNull()
                    if (!renameRollbackRestoredOriginal(recording.id, rolledBack?.id)) {
                        runCatching { RecordingDatabase.getInstance(context).recordingDao().deleteById(recording.id) }
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
                val pendingIds = pendingDeletionIds(context)
                if (current.isEmpty()) {
                    return@withLock MoveResult()
                }

                val targetDirectoryId = getConfiguredOutputDirectoryId(context)
                val stateUpdates = mutableListOf<RecordingEntity>()
                val moveCandidates = mutableListOf<RecordingEntity>()
                var skipped = 0
                val nowMillis = System.currentTimeMillis()

                current.forEach { recording ->
                    if (!isRecordingEligibleForMove(recording.id, pendingIds)) {
                        skipped++
                        return@forEach
                    }
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
                var failed = 0
                var cleanupFailed = 0
                val durableTargets = current
                    .filter { it.directoryId == targetDirectoryId && isRecordingEligibleForMove(it.id, pendingIds) }
                    .toMutableList()
                moveCandidates.forEach { source ->
                    val recoveredTarget = durableTargets.firstOrNull { candidate ->
                        candidate.displayName == source.displayName &&
                            recordingsHaveSameContent(context, source, candidate)
                    }
                    val target = recoveredTarget ?: copyRecordingToConfiguredDirectory(context, source)
                    if (target == null) {
                        failed++
                        return@forEach
                    }
                    val cleanupComplete = commitVerifiedMoveLocked(
                        context = context,
                        dao = dao,
                        source = source,
                        target = target,
                    )
                    if (recoveredTarget == null) durableTargets += target
                    moved++
                    if (!cleanupComplete) cleanupFailed++
                }

                MoveResult(
                    moved = moved,
                    skipped = skipped,
                    failed = failed,
                    cleanupFailed = cleanupFailed,
                ).also {
                    if (moved > 0) schedulePersistedPermissionCleanup(context)
                }
            }
        }
    }

    private suspend fun commitVerifiedMoveLocked(
        context: Context,
        dao: RecordingDao,
        source: RecordingEntity,
        target: RecordingEntity,
    ): Boolean {
        // Once a verified target exists, make it recoverable before touching the source.
        // Never delete that target merely because later source cleanup is uncertain: the
        // source path/URI may have disappeared or been reused for different bytes.
        dao.upsert(target)

        val cleanupComplete = cleanupMovedSourceAfterVerifiedCopy(context, source, target)
        if (!cleanupComplete) return false

        // Physical source cleanup completed (or the source was already positively absent).
        // Metadata retirement is last; if it fails, refresh hides the missing source while
        // retaining the verified target.
        dao.deleteById(source.id)
        return true
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
        val pendingIds = pendingDeletionIds(context)
        val durableTargets = dao.listByDirectory(targetDirectoryId)
            .filter { isRecordingEligibleForMove(it.id, pendingIds) }
            .toMutableList()
        val legacy = dao.listByDirectory(legacyDirectoryId)

        for (source in legacy) {
            if (!isRecordingEligibleForMove(source.id, pendingIds)) continue
            if (recordingAssetState(context, source) != RecordingAssetState.PRESENT) continue

            // If a previous process died after publishing the target but before committing
            // the catalog switch, reuse that byte-identical target instead of duplicating it.
            val recoveredTarget = durableTargets.firstOrNull { candidate ->
                candidate.displayName == source.displayName && recordingsHaveSameContent(context, source, candidate)
            }
            val target = recoveredTarget ?: copyRecordingToConfiguredDirectory(context, source) ?: continue

            val cleanupComplete = commitVerifiedMoveLocked(
                context = context,
                dao = dao,
                source = source,
                target = target,
            )
            if (recoveredTarget == null) durableTargets += target
            if (!cleanupComplete) {
                Log.w(
                    "RecordingRepository",
                    "Verified legacy target kept, but source cleanup was unsafe: ${source.id}",
                )
            }
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

    private fun cleanupMovedSourceAfterVerifiedCopy(
        context: Context,
        source: RecordingEntity,
        target: RecordingEntity,
    ): Boolean {
        val state = recordingAssetState(context, source)
        val sameContent = state == RecordingAssetState.PRESENT && recordingsHaveSameContent(context, source, target)
        return when (moveSourceCleanupAction(state, sameContent)) {
            MoveSourceCleanupAction.COMPLETE -> true
            MoveSourceCleanupAction.KEEP_SOURCE -> false
            MoveSourceCleanupAction.DELETE_SOURCE -> deleteRecordingAsset(context, source)
        }
    }

    data class MoveResult(
        val moved: Int = 0,
        val skipped: Int = 0,
        val failed: Int = 0,
        val cleanupFailed: Int = 0,
        val removedMissing: Int = 0,
    ) {
        val hasFailures: Boolean
            get() = failed > 0 || cleanupFailed > 0
    }
}

internal fun renameRollbackRestoredOriginal(originalId: String, rolledBackId: String?): Boolean =
    rolledBackId == originalId

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

internal fun recordingDirectoryIdsToRetain(
    recordings: List<RecordingEntity>,
): Set<String> = recordings.asSequence()
    .map { it.directoryId }
    .filter { it.isNotBlank() }
    .toSet()

private const val PENDING_DELETION_VERSION_PREFIX = "v1|"

internal data class PendingDeletionIntent(
    val id: String,
    val byteCount: Long,
    val sha256Hex: String,
    val assetDeleted: Boolean,
)

internal fun encodePendingDeletionIntent(intent: PendingDeletionIntent): String {
    val id = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(intent.id.toByteArray(StandardCharsets.UTF_8))
    return buildString {
        append(PENDING_DELETION_VERSION_PREFIX)
        append(id).append('|')
        append(intent.byteCount).append('|')
        append(intent.sha256Hex.lowercase()).append('|')
        append(if (intent.assetDeleted) '1' else '0')
    }
}

internal fun decodePendingDeletionIntent(raw: String): PendingDeletionIntent? {
    if (!raw.startsWith(PENDING_DELETION_VERSION_PREFIX)) return null
    val parts = raw.split('|')
    if (parts.size != 5 || parts[0] != "v1") return null
    val id = runCatching {
        String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
    val byteCount = parts[2].toLongOrNull()?.takeIf { it > 0L } ?: return null
    val sha256 = parts[3].lowercase()
    if (sha256.length != 64 || sha256.any { it !in '0'..'9' && it !in 'a'..'f' }) return null
    val deleted = when (parts[4]) {
        "0" -> false
        "1" -> true
        else -> return null
    }
    return PendingDeletionIntent(id, byteCount, sha256, deleted)
}

internal fun pendingDeletionMatchesDigest(
    intent: PendingDeletionIntent,
    byteCount: Long,
    sha256Hex: String,
): Boolean = intent.byteCount == byteCount && intent.sha256Hex.equals(sha256Hex, ignoreCase = true)

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
