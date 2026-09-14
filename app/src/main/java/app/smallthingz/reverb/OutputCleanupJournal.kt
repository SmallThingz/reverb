package app.smallthingz.reverb

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
private const val OUTPUT_CLEANUP_RECORD_VERSION = "v2"
private val outputCleanupJournalLock = Any()

internal data class PendingOutputCleanupRecord(
    val storageType: RecordingStorageType,
    val id: String,
    val byteCount: Long,
    val sha256Hex: String,
    val fileKey: String?,
    val providerIdentity: String? = null,
)

private data class OutputCleanupFingerprint(
    val digest: CopyDigest,
    val fileKey: String?,
    val providerIdentity: String?,
)

private enum class OutputCleanupAssetState { PRESENT, MISSING, UNAVAILABLE }

internal fun encodePendingOutputCleanupRecord(record: PendingOutputCleanupRecord): String = buildString {
    append(OUTPUT_CLEANUP_RECORD_VERSION).append('|')
    append(record.storageType.name).append('|')
    append(encodeCleanupField(record.id)).append('|')
    append(record.byteCount).append('|')
    append(record.sha256Hex.lowercase()).append('|')
    append(encodeCleanupField(record.fileKey.orEmpty())).append('|')
    append(encodeCleanupField(record.providerIdentity.orEmpty()))
}

internal fun decodePendingOutputCleanupRecord(raw: String): PendingOutputCleanupRecord? {
    val parts = raw.split('|')
    val expectedSize = when (parts.firstOrNull()) {
        OUTPUT_CLEANUP_RECORD_VERSION_V1 -> 6
        OUTPUT_CLEANUP_RECORD_VERSION -> 7
        else -> return null
    }
    if (parts.size != expectedSize) return null
    val storageType = RecordingStorageType.entries.firstOrNull { it.name == parts[1] } ?: return null
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

internal fun pendingOutputCleanupIds(context: Context): Set<String> = synchronized(outputCleanupJournalLock) {
    pendingOutputCleanupEntriesLocked(context).mapNotNullTo(mutableSetOf()) { raw ->
        decodePendingOutputCleanupRecord(raw)?.id
    }
}

internal fun suppressAndDeleteOutputTarget(context: Context, target: RecordingOutputTarget): Boolean {
    val id = target.id
    val existing = pendingOutputCleanupRecord(context, id)
    if (existing != null) {
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
    val fingerprint = readOutputCleanupFingerprint(context, target.storageType, id) ?: return false
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

internal fun retryPendingOutputCleanup(context: Context) {
    val rawEntries = synchronized(outputCleanupJournalLock) { pendingOutputCleanupEntriesLocked(context) }
    for (raw in rawEntries) {
        val record = decodePendingOutputCleanupRecord(raw)
        if (record == null) {
            removePendingOutputCleanupRaw(context, raw)
            continue
        }
        if (record.storageType == RecordingStorageType.FILE && outputCleanupClaimFile(record)?.isFile == true) {
            if (deletePendingOutputAsset(context, record)) removePendingOutputCleanup(context, record.id)
            continue
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
        val fingerprint = readOutputCleanupFingerprint(context, record.storageType, record.id) ?: continue
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
    getRecorderPreferences(context).getStringSet(PrefKey.PENDING_OUTPUT_CLEANUP, emptySet())
        ?.toSet()
        .orEmpty()

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
            decodePendingOutputCleanupRecord(raw)?.id == record.id
        }
        updated += encodePendingOutputCleanupRecord(record)
        getRecorderPreferences(context).edit()
            .putStringSet(PrefKey.PENDING_OUTPUT_CLEANUP, updated)
            .commit()
    }

private fun removePendingOutputCleanup(context: Context, id: String): Boolean = synchronized(outputCleanupJournalLock) {
    val current = pendingOutputCleanupEntriesLocked(context)
    val updated = current.filterNotTo(mutableSetOf()) { raw ->
        decodePendingOutputCleanupRecord(raw)?.id == id
    }
    writePendingOutputCleanupEntriesLocked(context, updated)
}

private fun removePendingOutputCleanupRaw(context: Context, raw: String): Boolean =
    synchronized(outputCleanupJournalLock) {
        writePendingOutputCleanupEntriesLocked(context, pendingOutputCleanupEntriesLocked(context) - raw)
    }

private fun writePendingOutputCleanupEntriesLocked(context: Context, entries: Set<String>): Boolean {
    val editor = getRecorderPreferences(context).edit()
    if (entries.isEmpty()) editor.remove(PrefKey.PENDING_OUTPUT_CLEANUP)
    else editor.putStringSet(PrefKey.PENDING_OUTPUT_CLEANUP, entries)
    return editor.commit()
}

private fun readOutputCleanupFingerprint(
    context: Context,
    storageType: RecordingStorageType,
    id: String,
): OutputCleanupFingerprint? = runCatching {
    when (storageType) {
        RecordingStorageType.FILE -> readStableFileOutputCleanupFingerprint(File(id))
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
            OutputCleanupFingerprint(digest, fileKey = null, providerIdentity = before)
        }
    }
}.getOrNull()

private fun readStableFileOutputCleanupFingerprint(file: File): OutputCleanupFingerprint? = runCatching {
    FileInputStream(file).use { input ->
        val openedIdentity = resolveFileDescriptorIdentity(input.fd)
            .takeIf { it.isNotBlank() } ?: return@runCatching null
        val digest = sha256(input)
        val closedIdentity = resolveFileDescriptorIdentity(input.fd)
        if (openedIdentity != closedIdentity) return@runCatching null
        val pathIdentity = resolveFileIdentity(file).takeIf { it.isNotBlank() } ?: return@runCatching null
        if (!fileDescriptorIdentityMatches(pathIdentity, closedIdentity)) return@runCatching null
        OutputCleanupFingerprint(digest, fileKey = pathIdentity, providerIdentity = null)
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
    val fingerprint = readOutputCleanupFingerprint(context, record.storageType, record.id) ?: return false
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
    val claim = deletionClaimFile(intent)
    val result = if (claim?.isFile == true) {
        replayClaimedFileDeletion(intent, claim)
    } else {
        deleteClaimedFile(intent)
    }
    return when (result) {
        FileDeletionClaimResult.RETRY -> false
        FileDeletionClaimResult.MISMATCH_PRESERVED ->
            confirmFileDirectoryStateDurable(File(record.id))
        FileDeletionClaimResult.DELETED -> {
            val source = File(record.id)
            if (!confirmFileDirectoryStateDurable(source)) return false
            if (!source.exists()) true
            else !fileIdentityMatches(identity, resolveFileIdentity(source))
        }
    }
}

internal fun pendingOutputCleanupFileIntent(record: PendingOutputCleanupRecord): PendingDeletionIntent? {
    if (record.storageType != RecordingStorageType.FILE) return null
    val identity = record.fileKey?.takeIf { it.isNotBlank() } ?: return null
    return PendingDeletionIntent(
        id = record.id,
        byteCount = record.byteCount,
        sha256Hex = record.sha256Hex,
        assetDeleted = false,
        storageType = RecordingStorageType.FILE.name,
        claimToken = outputCleanupClaimToken(record),
        fileIdentity = identity,
    )
}

private fun outputCleanupClaimFile(record: PendingOutputCleanupRecord): File? =
    pendingOutputCleanupFileIntent(record)?.let(::deletionClaimFile)

private fun outputCleanupClaimToken(record: PendingOutputCleanupRecord): String {
    val seed = buildString {
        append(record.storageType.name).append('|')
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
