package app.smallthingz.reverb

import android.annotation.SuppressLint
import android.content.Context
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileInputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes
import java.util.Base64
import java.util.UUID

private const val OUTPUT_CLEANUP_RECORD_VERSION_V1 = "v1"
private const val OUTPUT_CLEANUP_RECORD_VERSION_V2 = "v2"
private const val OUTPUT_CLEANUP_RECORD_VERSION = "v3"
private const val VERIFIED_EXPORT_STAGING_VERSION_V1 = "v1"
private const val VERIFIED_EXPORT_STAGING_VERSION = "v2"
private val outputCleanupJournalLock = Any()
private val verifiedExportStagingLock = Any()

internal data class PendingOutputCleanupRecord(
    val storageType: RecordingStorageType,
    val id: String,
    val byteCount: Long,
    val sha256Hex: String,
    val fileKey: String?,
    val providerIdentity: String? = null,
)

internal data class StableOutputFingerprint(
    val digest: CopyDigest,
    val fileKey: String?,
    val providerIdentity: String?,
)

internal data class VerifiedExportStagingRecord(
    val storageType: RecordingStorageType,
    val id: String,
    val byteCount: Long,
    val sha256Hex: String,
    val fileKey: String?,
    val providerIdentity: String?,
)

private enum class OutputCleanupAssetState { PRESENT, MISSING, UNAVAILABLE }

internal fun encodePendingOutputCleanupRecord(record: PendingOutputCleanupRecord): String = buildString {
    append(OUTPUT_CLEANUP_RECORD_VERSION).append('|')
    append(record.storageType.storageCode.toInt()).append('|')
    append(encodeCleanupField(record.id)).append('|')
    append(record.byteCount).append('|')
    append(record.sha256Hex.lowercase()).append('|')
    append(encodeCleanupField(record.fileKey.orEmpty())).append('|')
    append(encodeCleanupField(record.providerIdentity.orEmpty()))
}

internal fun decodePendingOutputCleanupRecord(raw: String): PendingOutputCleanupRecord? {
    val parts = raw.split('|')
    val version = parts.firstOrNull()
    val expectedSize = when (version) {
        OUTPUT_CLEANUP_RECORD_VERSION_V1 -> 6
        OUTPUT_CLEANUP_RECORD_VERSION_V2, OUTPUT_CLEANUP_RECORD_VERSION -> 7
        else -> return null
    }
    if (parts.size != expectedSize) return null
    val storageType = when (version) {
        OUTPUT_CLEANUP_RECORD_VERSION_V1, OUTPUT_CLEANUP_RECORD_VERSION_V2 ->
            RecordingStorageType.fromLegacyName(parts[1])
        OUTPUT_CLEANUP_RECORD_VERSION ->
            parts[1].toIntOrNull()?.let(RecordingStorageType::fromStorageCode)
        else -> null
    } ?: return null
    val id = decodeCleanupField(parts[2])?.takeIf { it.isNotBlank() } ?: return null
    val byteCount = parts[3].toLongOrNull()?.takeIf { it >= 0L } ?: return null
    val sha256Hex = parts[4].lowercase()
    if (sha256Hex.length != 64 || sha256Hex.any { it !in '0'..'9' && it !in 'a'..'f' }) return null
    val fileKey = decodeCleanupField(parts[5])?.takeIf { it.isNotBlank() }
    val providerIdentity = if (parts.size == 7) {
        decodeCleanupField(parts[6])?.takeIf { it.isNotBlank() }
    } else {
        null
    }
    return PendingOutputCleanupRecord(storageType, id, byteCount, sha256Hex, fileKey, providerIdentity)
}

internal fun encodeVerifiedExportStagingRecord(record: VerifiedExportStagingRecord): String = buildString {
    append(VERIFIED_EXPORT_STAGING_VERSION).append('|')
    append(record.storageType.storageCode.toInt()).append('|')
    append(encodeCleanupField(record.id)).append('|')
    append(record.byteCount).append('|')
    append(record.sha256Hex.lowercase()).append('|')
    append(encodeCleanupField(record.fileKey.orEmpty())).append('|')
    append(encodeCleanupField(record.providerIdentity.orEmpty()))
}

internal fun decodeVerifiedExportStagingRecord(raw: String): VerifiedExportStagingRecord? {
    val parts = raw.split('|')
    if (parts.size != 7) return null
    val storageType = when (parts[0]) {
        VERIFIED_EXPORT_STAGING_VERSION_V1 -> RecordingStorageType.fromLegacyName(parts[1])
        VERIFIED_EXPORT_STAGING_VERSION -> parts[1].toIntOrNull()?.let(RecordingStorageType::fromStorageCode)
        else -> null
    } ?: return null
    val id = decodeCleanupField(parts[2])?.takeIf { it.isNotBlank() } ?: return null
    val byteCount = parts[3].toLongOrNull()?.takeIf { it > 0L } ?: return null
    val sha256Hex = parts[4].lowercase()
    if (sha256Hex.length != 64 || sha256Hex.any { it !in '0'..'9' && it !in 'a'..'f' }) return null
    val fileKey = decodeCleanupField(parts[5])?.takeIf { it.isNotBlank() }
    val providerIdentity = decodeCleanupField(parts[6])?.takeIf { it.isNotBlank() }
    return VerifiedExportStagingRecord(storageType, id, byteCount, sha256Hex, fileKey, providerIdentity)
}

internal enum class PendingOutputCleanupMatch {
    EXACT,
    REPLACED,
    UNPROVEN,
}

internal fun classifyPendingOutputCleanup(
    record: PendingOutputCleanupRecord,
    byteCount: Long,
    sha256Hex: String,
    fileKey: String?,
    providerIdentity: String? = null,
): PendingOutputCleanupMatch {
    if (record.byteCount != byteCount || !record.sha256Hex.equals(sha256Hex, ignoreCase = true)) {
        return PendingOutputCleanupMatch.REPLACED
    }
    return when (record.storageType) {
        RecordingStorageType.FILE -> {
            val expected = record.fileKey ?: return PendingOutputCleanupMatch.UNPROVEN
            val current = fileKey ?: return PendingOutputCleanupMatch.UNPROVEN
            if (fileIdentityMatches(expected, current)) PendingOutputCleanupMatch.EXACT
            else PendingOutputCleanupMatch.REPLACED
        }
        RecordingStorageType.DOCUMENT,
        RecordingStorageType.MEDIASTORE,
        -> {
            val expected = record.providerIdentity ?: return PendingOutputCleanupMatch.UNPROVEN
            val current = providerIdentity ?: return PendingOutputCleanupMatch.UNPROVEN
            if (providerRecordingIdentityMatches(expected, current)) PendingOutputCleanupMatch.EXACT
            else PendingOutputCleanupMatch.REPLACED
        }
    }
}

internal fun pendingOutputCleanupMatches(
    record: PendingOutputCleanupRecord,
    byteCount: Long,
    sha256Hex: String,
    fileKey: String?,
    providerIdentity: String? = null,
): Boolean = classifyPendingOutputCleanup(
    record, byteCount, sha256Hex, fileKey, providerIdentity,
) == PendingOutputCleanupMatch.EXACT

internal fun pendingOutputCleanupSuppressedId(raw: String): String? {
    decodePendingOutputCleanupRecord(raw)?.let { return it.id }
    val parts = raw.split('|')
    if (parts.size < 3) return null
    if (parts[0] !in setOf(
            OUTPUT_CLEANUP_RECORD_VERSION_V1,
            OUTPUT_CLEANUP_RECORD_VERSION_V2,
            OUTPUT_CLEANUP_RECORD_VERSION,
        )
    ) {
        return null
    }
    // Suppression needs only the target ID, not deletion authority. Recover it even when
    // another field was torn/corrupted so uncertain output never becomes visible merely
    // because the cleanup journal can no longer authorize a physical delete.
    return decodeCleanupField(parts[2])?.takeIf { it.isNotBlank() }
}

internal fun pendingOutputCleanupIds(context: Context): Set<String> = synchronized(outputCleanupJournalLock) {
    pendingOutputCleanupEntriesLocked(context).mapNotNullTo(mutableSetOf(), ::pendingOutputCleanupSuppressedId)
}

internal fun verifiedExportStagingRecordMatches(
    record: VerifiedExportStagingRecord,
    fingerprint: StableOutputFingerprint,
): Boolean {
    if (!record.sha256Hex.equals(fingerprint.digest.sha256.toHexString(), ignoreCase = true) ||
        record.byteCount != fingerprint.digest.byteCount
    ) {
        return false
    }
    return when (record.storageType) {
        RecordingStorageType.FILE -> {
            val expected = record.fileKey ?: return false
            fingerprint.fileKey?.let { fileIdentityMatches(expected, it) } == true
        }
        RecordingStorageType.DOCUMENT,
        RecordingStorageType.MEDIASTORE,
        -> {
            val expected = record.providerIdentity ?: return false
            fingerprint.providerIdentity?.let { providerRecordingIdentityMatches(expected, it) } == true
        }
    }
}

internal fun stableOutputFingerprintMatches(
    storageType: RecordingStorageType,
    expected: StableOutputFingerprint,
    actual: StableOutputFingerprint,
): Boolean {
    if (!copyDigestMatches(expected.digest, actual.digest)) return false
    return when (storageType) {
        RecordingStorageType.FILE -> {
            val expectedIdentity = expected.fileKey ?: return false
            actual.fileKey?.let { fileIdentityMatches(expectedIdentity, it) } == true
        }
        RecordingStorageType.DOCUMENT,
        RecordingStorageType.MEDIASTORE,
        -> {
            val expectedIdentity = expected.providerIdentity ?: return false
            actual.providerIdentity?.let { providerRecordingIdentityMatches(expectedIdentity, it) } == true
        }
    }
}

internal fun requireVerifiedOutputRecoveryMarker(
    persisted: Boolean,
    targetId: String = "",
) {
    if (persisted) return
    val suffix = targetId.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
    throw java.io.IOException("Verified output recovery marker could not be persisted$suffix")
}

internal fun putVerifiedExportStaging(
    context: Context,
    target: RecordingOutputTarget,
    fingerprint: StableOutputFingerprint,
): Boolean {
    if (!target.staging || fingerprint.digest.byteCount <= 0L) return false
    when (target.storageType) {
        RecordingStorageType.FILE -> if (fingerprint.fileKey.isNullOrBlank()) return false
        RecordingStorageType.DOCUMENT,
        RecordingStorageType.MEDIASTORE,
        -> if (fingerprint.providerIdentity.isNullOrBlank()) return false
    }
    val record = VerifiedExportStagingRecord(
        storageType = target.storageType,
        id = target.id,
        byteCount = fingerprint.digest.byteCount,
        sha256Hex = fingerprint.digest.sha256.toHexString(),
        fileKey = fingerprint.fileKey,
        providerIdentity = fingerprint.providerIdentity,
    )
    return synchronized(verifiedExportStagingLock) {
        val current = verifiedExportStagingEntriesLocked(context)
        val updated = current.filterNotTo(mutableSetOf()) { raw ->
            decodeVerifiedExportStagingRecord(raw)?.let { record ->
                record.storageType == target.storageType && record.id == target.id
            } == true
        }
        updated += encodeVerifiedExportStagingRecord(record)
        writeVerifiedExportStagingEntriesLocked(context, updated)
    }
}

internal fun removeVerifiedExportStaging(
    context: Context,
    storageType: RecordingStorageType,
    id: String,
): Boolean = synchronized(verifiedExportStagingLock) {
        val current = verifiedExportStagingEntriesLocked(context)
        val updated = current.filterNotTo(mutableSetOf()) { raw ->
            decodeVerifiedExportStagingRecord(raw)?.let { record ->
                record.storageType == storageType && record.id == id
            } == true
        }
        if (updated.size == current.size) return@synchronized true
        writeVerifiedExportStagingEntriesLocked(context, updated)
    }

@SuppressLint("UseKtx") // The commit() Boolean is part of the fail-closed durability contract.
private fun writeVerifiedExportStagingEntriesLocked(context: Context, entries: Set<String>): Boolean {
    val editor = getRecorderPreferences(context).edit()
    if (entries.isEmpty()) editor.remove(PrefKey.VERIFIED_EXPORT_STAGING)
    else editor.putStringSet(PrefKey.VERIFIED_EXPORT_STAGING, entries)
    return editor.commit()
}

internal fun verifiedExportStagingFingerprint(
    context: Context,
    storageType: RecordingStorageType,
    id: String,
): StableOutputFingerprint? {
    val record = synchronized(verifiedExportStagingLock) {
        verifiedExportStagingEntriesLocked(context).firstNotNullOfOrNull { raw ->
            decodeVerifiedExportStagingRecord(raw)?.takeIf { it.id == id && it.storageType == storageType }
        }
    } ?: return null
    val fingerprint = readStableOutputFingerprint(context, storageType, id) ?: return null
    return fingerprint.takeIf { verifiedExportStagingRecordMatches(record, it) }
}

private fun verifiedExportStagingEntriesLocked(context: Context): Set<String> =
    getRecorderPreferences(context).requireDurableStringSet(PrefKey.VERIFIED_EXPORT_STAGING)

internal fun suppressAndDeleteOutputTarget(
    context: Context,
    target: RecordingOutputTarget,
    expectedDigest: CopyDigest,
): Boolean {
    val id = target.id
    // Cleanup revokes crash-recovery authority before any destructive attempt. If that
    // synchronous preference update cannot be made durable, fail closed and keep the bytes.
    if (!removeVerifiedExportStaging(context, target.storageType, id)) return false
    val existing = pendingOutputCleanupRecord(context, id)
    if (existing != null) {
        if (!pendingOutputCleanupRecordMatchesDigest(existing, expectedDigest)) return false
        val cleaned = deletePendingOutputAsset(context, existing)
        if (cleaned) removePendingOutputCleanup(context, id)
        return cleaned
    }
    when (outputCleanupAssetState(context, target.storageType, id)) {
        OutputCleanupAssetState.MISSING -> {
            if (target.storageType == RecordingStorageType.FILE &&
                !confirmMissingFileRecordingDurable(File(id))
            ) {
                return false
            }
            removePendingOutputCleanup(context, id)
            return true
        }
        OutputCleanupAssetState.UNAVAILABLE -> return false
        OutputCleanupAssetState.PRESENT -> Unit
    }
    val fingerprint = readStableOutputFingerprint(context, target.storageType, id) ?: return false
    if (!copyDigestMatches(expectedDigest, fingerprint.digest)) return false
    val record = PendingOutputCleanupRecord(
        storageType = target.storageType,
        id = id,
        byteCount = fingerprint.digest.byteCount,
        sha256Hex = fingerprint.digest.sha256.toHexString(),
        fileKey = fingerprint.fileKey,
        providerIdentity = fingerprint.providerIdentity,
    )
    if (!putPendingOutputCleanup(context, record)) return false
    val cleaned = deletePendingOutputAsset(context, record)
    if (cleaned) removePendingOutputCleanup(context, id)
    return cleaned
}

internal fun copyDigestMatches(expected: CopyDigest, actual: CopyDigest): Boolean =
    expected.byteCount == actual.byteCount && expected.sha256.contentEquals(actual.sha256)

internal fun pendingOutputCleanupRecordMatchesDigest(
    record: PendingOutputCleanupRecord,
    digest: CopyDigest,
): Boolean = record.byteCount == digest.byteCount &&
    record.sha256Hex.equals(digest.sha256.toHexString(), ignoreCase = true)

internal fun retryPendingOutputCleanup(context: Context) {
    val rawEntries = synchronized(outputCleanupJournalLock) { pendingOutputCleanupEntriesLocked(context) }
    for (raw in rawEntries) {
        val record = decodePendingOutputCleanupRecord(raw)
        if (record == null) {
            // Malformed cleanup metadata has no destructive authority, but discarding it can
            // expose a cancelled/failed final-name output. Keep it suppression-only.
            continue
        }
        if (record.storageType == RecordingStorageType.FILE) {
            when (outputCleanupClaimState(record)) {
                OutputCleanupClaimState.WAIT -> continue
                OutputCleanupClaimState.NONE -> Unit
                is OutputCleanupClaimState.REPLAY -> {
                    if (deletePendingOutputAsset(context, record)) {
                        removePendingOutputCleanup(context, record.id)
                    }
                    continue
                }
            }
        }
        when (outputCleanupAssetState(context, record.storageType, record.id)) {
            OutputCleanupAssetState.MISSING -> {
                if (record.storageType == RecordingStorageType.FILE &&
                    !confirmMissingFileRecordingDurable(File(record.id))
                ) {
                    continue
                }
                removePendingOutputCleanup(context, record.id)
                continue
            }
            OutputCleanupAssetState.UNAVAILABLE -> continue
            OutputCleanupAssetState.PRESENT -> Unit
        }
        val fingerprint = readStableOutputFingerprint(context, record.storageType, record.id) ?: continue
        when (classifyPendingOutputCleanup(
            record,
            fingerprint.digest.byteCount,
            fingerprint.digest.sha256.toHexString(),
            fingerprint.fileKey,
            fingerprint.providerIdentity,
        )) {
            PendingOutputCleanupMatch.UNPROVEN -> {
                // Legacy provider cleanup records have content fingerprints but no stable
                // object identity. Keep them suppressed, but never delete on that evidence.
                continue
            }
            PendingOutputCleanupMatch.REPLACED -> {
                // A different object/content now owns the path or URI. Abandon the stale
                // cleanup intent so replacement data is neither deleted nor kept hidden.
                if (record.storageType == RecordingStorageType.FILE &&
                    !confirmFileDirectoryStateDurable(File(record.id))
                ) {
                    continue
                }
                removePendingOutputCleanup(context, record.id)
                continue
            }
            PendingOutputCleanupMatch.EXACT -> Unit
        }
        if (deletePendingOutputAsset(context, record)) {
            removePendingOutputCleanup(context, record.id)
        }
    }
}

private fun pendingOutputCleanupEntriesLocked(context: Context): Set<String> =
    getRecorderPreferences(context).requireDurableStringSet(PrefKey.PENDING_OUTPUT_CLEANUP)

private fun pendingOutputCleanupRecord(context: Context, id: String): PendingOutputCleanupRecord? =
    synchronized(outputCleanupJournalLock) {
        pendingOutputCleanupEntriesLocked(context).firstNotNullOfOrNull { raw ->
            decodePendingOutputCleanupRecord(raw)?.takeIf { it.id == id }
        }
    }

private fun putPendingOutputCleanup(context: Context, record: PendingOutputCleanupRecord): Boolean =
    synchronized(outputCleanupJournalLock) {
        val current = pendingOutputCleanupEntriesLocked(context)
        val updated = current.filterNotTo(mutableSetOf()) { raw ->
            pendingOutputCleanupSuppressedId(raw) == record.id
        }
        updated += encodePendingOutputCleanupRecord(record)
        getRecorderPreferences(context).edit()
            .putStringSet(PrefKey.PENDING_OUTPUT_CLEANUP, updated)
            .commit()
    }

private fun removePendingOutputCleanup(context: Context, id: String): Boolean = synchronized(outputCleanupJournalLock) {
    val current = pendingOutputCleanupEntriesLocked(context)
    val updated = current.filterNotTo(mutableSetOf()) { raw ->
        pendingOutputCleanupSuppressedId(raw) == id
    }
    writePendingOutputCleanupEntriesLocked(context, updated)
}

private fun writePendingOutputCleanupEntriesLocked(context: Context, entries: Set<String>): Boolean {
    val editor = getRecorderPreferences(context).edit()
    if (entries.isEmpty()) editor.remove(PrefKey.PENDING_OUTPUT_CLEANUP)
    else editor.putStringSet(PrefKey.PENDING_OUTPUT_CLEANUP, entries)
    return editor.commit()
}

internal fun readStableOutputFingerprint(
    context: Context,
    storageType: RecordingStorageType,
    id: String,
): StableOutputFingerprint? = runCatching {
    when (storageType) {
        RecordingStorageType.FILE -> readStableFileOutputFingerprint(File(id))
        RecordingStorageType.DOCUMENT,
        RecordingStorageType.MEDIASTORE,
        -> {
            val uri = id.toUri()
            val before = resolveProviderRecordingIdentity(context, storageType, uri)
                .takeIf { it.isNotBlank() } ?: return@runCatching null
            val digest = context.contentResolver.openInputStream(uri)?.use(::sha256)
                ?: return@runCatching null
            val after = resolveProviderRecordingIdentity(context, storageType, uri)
            if (!providerRecordingIdentityMatches(before, after)) return@runCatching null
            StableOutputFingerprint(digest, fileKey = null, providerIdentity = before)
        }
    }
}.getOrNull()

internal fun readStableFileOutputFingerprint(file: File): StableOutputFingerprint? = runCatching {
    FileInputStream(file).use { input ->
        val openedIdentity = resolveFileDescriptorIdentity(input.fd)
            .takeIf { it.isNotBlank() } ?: return@runCatching null
        val digest = sha256(input)
        val closedIdentity = resolveFileDescriptorIdentity(input.fd)
        if (openedIdentity != closedIdentity) return@runCatching null
        val pathIdentity = resolveFileIdentity(file).takeIf { it.isNotBlank() } ?: return@runCatching null
        if (!fileDescriptorIdentityMatches(pathIdentity, closedIdentity)) return@runCatching null
        StableOutputFingerprint(digest, fileKey = pathIdentity, providerIdentity = null)
    }
}.getOrNull()

private fun outputCleanupAssetState(
    context: Context,
    storageType: RecordingStorageType,
    id: String,
): OutputCleanupAssetState = when (storageType) {
    RecordingStorageType.FILE -> try {
        val attrs = Files.readAttributes(File(id).toPath(), BasicFileAttributes::class.java)
        if (attrs.isRegularFile) OutputCleanupAssetState.PRESENT else OutputCleanupAssetState.MISSING
    } catch (_: NoSuchFileException) {
        OutputCleanupAssetState.MISSING
    } catch (_: Exception) {
        OutputCleanupAssetState.UNAVAILABLE
    }
    RecordingStorageType.DOCUMENT -> try {
        context.contentResolver.query(
            id.toUri(),
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) OutputCleanupAssetState.PRESENT else OutputCleanupAssetState.MISSING
        } ?: OutputCleanupAssetState.UNAVAILABLE
    } catch (_: Exception) {
        OutputCleanupAssetState.UNAVAILABLE
    }
    RecordingStorageType.MEDIASTORE -> try {
        context.contentResolver.query(
            id.toUri(),
            arrayOf(MediaStore.MediaColumns._ID),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) OutputCleanupAssetState.PRESENT else OutputCleanupAssetState.MISSING
        } ?: OutputCleanupAssetState.UNAVAILABLE
    } catch (_: Exception) {
        OutputCleanupAssetState.UNAVAILABLE
    }
}

private fun deletePendingOutputAsset(
    context: Context,
    record: PendingOutputCleanupRecord,
): Boolean = when (record.storageType) {
    RecordingStorageType.FILE -> deletePendingFileOutput(record)
    RecordingStorageType.DOCUMENT -> runCatching {
        if (!pendingProviderOutputCleanupStillMatches(context, record)) return@runCatching false
        val document = DocumentFile.fromSingleUri(context, record.id.toUri()) ?: return@runCatching false
        if (document.delete()) true
        else outputCleanupAssetState(context, record.storageType, record.id) == OutputCleanupAssetState.MISSING
    }.getOrDefault(false)
    RecordingStorageType.MEDIASTORE -> runCatching {
        if (!pendingProviderOutputCleanupStillMatches(context, record)) return@runCatching false
        if (context.contentResolver.delete(record.id.toUri(), null, null) > 0) true
        else outputCleanupAssetState(context, record.storageType, record.id) == OutputCleanupAssetState.MISSING
    }.getOrDefault(false)
}

private fun pendingProviderOutputCleanupStillMatches(
    context: Context,
    record: PendingOutputCleanupRecord,
): Boolean {
    if (record.storageType == RecordingStorageType.FILE || record.providerIdentity.isNullOrBlank()) return false
    val fingerprint = readStableOutputFingerprint(context, record.storageType, record.id) ?: return false
    return pendingOutputCleanupMatches(
        record,
        fingerprint.digest.byteCount,
        fingerprint.digest.sha256.toHexString(),
        fingerprint.fileKey,
        fingerprint.providerIdentity,
    )
}

private fun deletePendingFileOutput(record: PendingOutputCleanupRecord): Boolean {
    val intent = pendingOutputCleanupFileIntent(record) ?: return false
    val identity = requireNotNull(intent.fileIdentity)
    val claimState = outputCleanupClaimState(record)
    val result = when (claimState) {
        OutputCleanupClaimState.WAIT -> return false
        OutputCleanupClaimState.NONE -> deleteClaimedFile(intent)
        is OutputCleanupClaimState.REPLAY -> replayClaimedFileDeletion(claimState.intent, claimState.file)
    }
    return when (result) {
        FileDeletionClaimResult.RETRY -> false
        FileDeletionClaimResult.MISMATCH_PRESERVED ->
            confirmFileDirectoryStateDurable(File(record.id))
        FileDeletionClaimResult.DELETED -> {
            val source = File(record.id)
            if (!confirmFileDirectoryStateDurable(source)) return false
            val sourceObservation = observeStoragePath(source)
            when (sourceObservation.state) {
                StoragePathState.MISSING -> true
                StoragePathState.UNAVAILABLE -> false
                StoragePathState.PRESENT -> !sourceObservation.isRegularFile ||
                    !fileIdentityMatches(identity, resolveFileIdentity(source))
            }
        }
    }
}

internal fun pendingOutputCleanupFileIntent(
    record: PendingOutputCleanupRecord,
    claimToken: String = outputCleanupClaimToken(record),
): PendingDeletionIntent? {
    if (record.storageType != RecordingStorageType.FILE) return null
    val identity = record.fileKey?.takeIf { it.isNotBlank() } ?: return null
    return PendingDeletionIntent(
        id = record.id,
        byteCount = record.byteCount,
        sha256Hex = record.sha256Hex,
        assetDeleted = false,
        storageType = RecordingStorageType.FILE,
        claimToken = claimToken,
        fileIdentity = identity,
    )
}

private sealed interface OutputCleanupClaimState {
    data object NONE : OutputCleanupClaimState
    data object WAIT : OutputCleanupClaimState
    data class REPLAY(val intent: PendingDeletionIntent, val file: File) : OutputCleanupClaimState
}

private fun outputCleanupClaimState(record: PendingOutputCleanupRecord): OutputCleanupClaimState {
    var replay: OutputCleanupClaimState.REPLAY? = null
    for (token in outputCleanupClaimTokens(record)) {
        val intent = pendingOutputCleanupFileIntent(record, token) ?: return OutputCleanupClaimState.WAIT
        val claim = deletionClaimFile(intent) ?: return OutputCleanupClaimState.WAIT
        when (claimedFileReplayAction(observeStoragePath(claim))) {
            ClaimedFileReplayAction.NO_CLAIM -> Unit
            ClaimedFileReplayAction.WAIT -> return OutputCleanupClaimState.WAIT
            ClaimedFileReplayAction.REPLAY -> {
                if (replay != null) return OutputCleanupClaimState.WAIT
                replay = OutputCleanupClaimState.REPLAY(intent, claim)
            }
        }
    }
    return replay ?: OutputCleanupClaimState.NONE
}

internal fun outputCleanupClaimTokens(record: PendingOutputCleanupRecord): List<String> =
    listOf(
        outputCleanupClaimToken(record, record.storageType.storageCode.toInt().toString()),
        outputCleanupClaimToken(record, record.storageType.name),
    ).distinct()

private fun outputCleanupClaimToken(record: PendingOutputCleanupRecord): String =
    outputCleanupClaimTokens(record).first()

private fun outputCleanupClaimToken(record: PendingOutputCleanupRecord, storageIdentity: String): String {
    val seed = buildString {
        append(storageIdentity).append('|')
        append(record.id).append('|')
        append(record.byteCount).append('|')
        append(record.sha256Hex).append('|')
        append(record.fileKey.orEmpty())
    }.toByteArray(StandardCharsets.UTF_8)
    return UUID.nameUUIDFromBytes(seed).toString()
}

private fun encodeCleanupField(value: String): String = Base64.getUrlEncoder().withoutPadding()
    .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

private fun decodeCleanupField(value: String): String? = runCatching {
    String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)
}.getOrNull()
