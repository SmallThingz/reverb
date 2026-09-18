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

private data class PendingOutputCleanupEntry(
    val raw: String,
    val record: PendingOutputCleanupRecord,
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

internal enum class OutputCleanupAssetState { PRESENT, MISSING, UNAVAILABLE }

internal fun providerOutputCleanupCompleted(observedState: OutputCleanupAssetState): Boolean =
    observedState == OutputCleanupAssetState.MISSING

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

internal fun pendingFileOutputCleanupRequiresClaimReplay(record: PendingOutputCleanupRecord): Boolean =
    record.storageType == RecordingStorageType.FILE && !record.fileKey.isNullOrBlank()

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

internal fun suppressionOnlyFileOutputRecord(
    id: String,
    digest: CopyDigest,
): PendingOutputCleanupRecord? {
    if (id.isBlank() || digest.byteCount <= 0L) return null
    return PendingOutputCleanupRecord(
        storageType = RecordingStorageType.FILE,
        id = id,
        byteCount = digest.byteCount,
        sha256Hex = digest.sha256.toHexString(),
        fileKey = null,
        providerIdentity = null,
    )
}

internal fun suppressFileOutputWithoutDeletion(
    context: Context,
    id: String,
    digest: CopyDigest,
): Boolean = runOutputCleanupFailClosed {
    val record = suppressionOnlyFileOutputRecord(id, digest)
        ?: return@runOutputCleanupFailClosed false
    // The current bytes are enough to keep this failed publication hidden, but the
    // unexpected object identity was never verified and therefore gains no delete authority.
    putPendingOutputCleanup(context, record)
}

internal fun suppressionOnlyProviderOutputRecord(
    storageType: RecordingStorageType,
    id: String,
    digest: CopyDigest,
): PendingOutputCleanupRecord? {
    if (storageType == RecordingStorageType.FILE || id.isBlank() || digest.byteCount <= 0L) return null
    return PendingOutputCleanupRecord(
        storageType = storageType,
        id = id,
        byteCount = digest.byteCount,
        sha256Hex = digest.sha256.toHexString(),
        fileKey = null,
        providerIdentity = null,
    )
}

internal fun suppressProviderOutputWithoutDeletion(
    context: Context,
    storageType: RecordingStorageType,
    id: String,
    digest: CopyDigest,
): Boolean = runOutputCleanupFailClosed {
    val record = suppressionOnlyProviderOutputRecord(storageType, id, digest) ?: return@runOutputCleanupFailClosed false
    // Deliberately omit provider identity. The cleanup replay classifier therefore remains
    // UNPROVEN while these exact bytes occupy the URI: enough to suppress Library import,
    // never enough to authorize physical deletion of an uncertain provider object.
    putPendingOutputCleanup(context, record)
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

internal fun verifiedProviderPublicationMatches(
    expected: StableOutputFingerprint,
    published: StableOutputFingerprint,
): Boolean = copyDigestMatches(expected.digest, published.digest) &&
    sameProviderObjectAcrossMutation(expected.providerIdentity, published.providerIdentity)

internal fun outputCleanupFingerprintForTarget(
    target: RecordingOutputTarget,
    verifiedFingerprint: StableOutputFingerprint,
): StableOutputFingerprint {
    val publishedIdentity = target.publishedIdentity.takeIf { it.isNotBlank() }
        ?: return verifiedFingerprint
    return when (target.storageType) {
        RecordingStorageType.FILE -> verifiedFingerprint.copy(
            fileKey = publishedIdentity,
            providerIdentity = null,
        )
        RecordingStorageType.DOCUMENT,
        RecordingStorageType.MEDIASTORE,
        -> verifiedFingerprint.copy(
            fileKey = null,
            providerIdentity = publishedIdentity,
        )
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
        writeVerifiedExportStagingEntriesLocked(
            context,
            verifiedExportStagingEntriesAfterAppend(current, encodeVerifiedExportStagingRecord(record)),
        )
    }
}

internal fun verifiedExportStagingEntriesAfterAppend(
    entries: Set<String>,
    raw: String,
): Set<String> = entries + raw

internal fun verifiedExportStagingEntriesAfterFingerprintRemoval(
    entries: Set<String>,
    storageType: RecordingStorageType,
    id: String,
    expectedFingerprint: StableOutputFingerprint,
): Set<String> = entries.filterNotTo(mutableSetOf()) { raw ->
    decodeVerifiedExportStagingRecord(raw)?.let { record ->
        record.storageType == storageType &&
            record.id == id &&
            verifiedExportStagingRecordMatches(record, expectedFingerprint)
    } == true
}

internal fun removeVerifiedExportStaging(
    context: Context,
    storageType: RecordingStorageType,
    id: String,
    expectedFingerprint: StableOutputFingerprint,
): Boolean = synchronized(verifiedExportStagingLock) {
    val current = verifiedExportStagingEntriesLocked(context)
    val updated = verifiedExportStagingEntriesAfterFingerprintRemoval(
        entries = current,
        storageType = storageType,
        id = id,
        expectedFingerprint = expectedFingerprint,
    )
    if (updated.size == current.size) return@synchronized true
    writeVerifiedExportStagingEntriesLocked(context, updated)
}

private fun writeVerifiedExportStagingEntriesLocked(context: Context, entries: Set<String>): Boolean {
    val preferences = getRecorderPreferences(context)
    val current = preferences.requireDurableStringSet(PrefKey.VERIFIED_EXPORT_STAGING)
    if (current == entries) return true
    return preferences.commitDurableStringSetReplacement(
        key = PrefKey.VERIFIED_EXPORT_STAGING,
        previousEntries = current,
        updatedEntries = entries,
    )
}

internal fun verifiedExportStagingFingerprint(
    context: Context,
    storageType: RecordingStorageType,
    id: String,
): StableOutputFingerprint? {
    val rawEntries = synchronized(verifiedExportStagingLock) { verifiedExportStagingEntriesLocked(context) }
    val fingerprint = readStableOutputFingerprint(context, storageType, id) ?: return null
    return fingerprint.takeIf {
        verifiedExportStagingHasFingerprint(rawEntries, storageType, id, fingerprint)
    }
}

internal fun verifiedExportStagingHasFingerprint(
    entries: Set<String>,
    storageType: RecordingStorageType,
    id: String,
    fingerprint: StableOutputFingerprint,
): Boolean = entries.any { raw ->
    decodeVerifiedExportStagingRecord(raw)?.let { record ->
        record.storageType == storageType &&
            record.id == id &&
            verifiedExportStagingRecordMatches(record, fingerprint)
    } == true
}

private fun verifiedExportStagingEntriesLocked(context: Context): Set<String> =
    getRecorderPreferences(context).requireDurableStringSet(PrefKey.VERIFIED_EXPORT_STAGING)

internal inline fun runOutputCleanupFailClosed(block: () -> Boolean): Boolean =
    try {
        block()
    } catch (_: Exception) {
        false
    }

internal fun suppressAndDeleteOutputTarget(
    context: Context,
    target: RecordingOutputTarget,
    expectedFingerprint: StableOutputFingerprint,
): Boolean = runOutputCleanupFailClosed {
    val id = target.id
    val existingRawEntries = pendingOutputCleanupRawEntries(context, id)
    var exactEntry = pendingOutputCleanupRawMatchingFingerprint(
        entries = existingRawEntries,
        id = id,
        storageType = target.storageType,
        expectedFingerprint = expectedFingerprint,
    )?.let { raw -> PendingOutputCleanupEntry(raw, requireNotNull(decodePendingOutputCleanupRecord(raw))) }
    when (outputCleanupAssetState(context, target.storageType, id)) {
        OutputCleanupAssetState.MISSING -> {
            if (target.storageType == RecordingStorageType.FILE &&
                !confirmMissingFileRecordingDurable(File(id))
            ) {
                return false
            }
            // Positive absence needs no destructive authority. Revoke any recovery/suppression
            // metadata only after that absence is proven durable.
            if (!removeVerifiedExportStaging(context, target.storageType, id, expectedFingerprint)) return false
            // Positive durable absence invalidates only the same-ID cleanup entries observed at
            // the start of this operation. A newer concurrent failure may already have appended
            // a fresh record for a newly reused path/URI, and must remain suppressed.
            if (!removePendingOutputCleanupEntries(context, existingRawEntries)) return false
            return true
        }
        OutputCleanupAssetState.UNAVAILABLE -> return false
        OutputCleanupAssetState.PRESENT -> Unit
    }

    val current = readStableOutputFingerprint(context, target.storageType, id) ?: return false
    if (!stableOutputFingerprintMatches(target.storageType, expectedFingerprint, current)) return false

    if (exactEntry == null) {
        val newRecord = PendingOutputCleanupRecord(
            storageType = target.storageType,
            id = id,
            byteCount = current.digest.byteCount,
            sha256Hex = current.digest.sha256.toHexString(),
            fileKey = current.fileKey,
            providerIdentity = current.providerIdentity,
        )
        // Install this exact authority alongside any older same-ID records. Replacing them here
        // lets a stale producer erase a newer failure record; replay removes obsolete records
        // independently once current content/identity proves they are stale.
        if (!putPendingOutputCleanup(context, newRecord)) return false
        exactEntry = PendingOutputCleanupEntry(encodePendingOutputCleanupRecord(newRecord), newRecord)
    }
    val entry = requireNotNull(exactEntry)
    val record = entry.record
    // The cleanup journal now owns the exact verified object. Recovery authority may be revoked;
    // if that commit fails, leave both records in place and keep the bytes.
    if (!removeVerifiedExportStaging(context, target.storageType, id, expectedFingerprint)) return false

    val cleaned = deletePendingOutputAsset(context, record)
    cleaned && removePendingOutputCleanupEntry(context, entry.raw)
}

internal fun copyDigestMatches(expected: CopyDigest, actual: CopyDigest): Boolean =
    expected.byteCount == actual.byteCount && expected.sha256.contentEquals(actual.sha256)

internal fun pendingOutputCleanupRecordMatchesFingerprint(
    record: PendingOutputCleanupRecord,
    storageType: RecordingStorageType,
    fingerprint: StableOutputFingerprint,
): Boolean = record.storageType == storageType && pendingOutputCleanupMatches(
    record = record,
    byteCount = fingerprint.digest.byteCount,
    sha256Hex = fingerprint.digest.sha256.toHexString(),
    fileKey = fingerprint.fileKey,
    providerIdentity = fingerprint.providerIdentity,
)

internal fun retryPendingOutputCleanup(context: Context) {
    val rawEntries = synchronized(outputCleanupJournalLock) { pendingOutputCleanupEntriesLocked(context) }
    for (raw in rawEntries) {
        val record = decodePendingOutputCleanupRecord(raw)
        if (record == null) {
            // Malformed cleanup metadata has no destructive authority, but discarding it can
            // expose a cancelled/failed final-name output. Keep it suppression-only.
            continue
        }
        if (pendingFileOutputCleanupRequiresClaimReplay(record)) {
            when (outputCleanupClaimState(record)) {
                OutputCleanupClaimState.WAIT -> continue
                OutputCleanupClaimState.NONE -> Unit
                is OutputCleanupClaimState.REPLAY -> {
                    if (deletePendingOutputAsset(context, record)) {
                        removePendingOutputCleanupEntry(context, raw)
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
                removePendingOutputCleanupEntry(context, raw)
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
                removePendingOutputCleanupEntry(context, raw)
                continue
            }
            PendingOutputCleanupMatch.EXACT -> Unit
        }
        if (deletePendingOutputAsset(context, record)) {
            removePendingOutputCleanupEntry(context, raw)
        }
    }
}

private fun pendingOutputCleanupEntriesLocked(context: Context): Set<String> =
    getRecorderPreferences(context).requireDurableStringSet(PrefKey.PENDING_OUTPUT_CLEANUP)

internal fun pendingOutputCleanupRawEntriesForId(
    entries: Set<String>,
    id: String,
): Set<String> = entries.filterTo(mutableSetOf()) { raw ->
    pendingOutputCleanupSuppressedId(raw) == id
}

private fun pendingOutputCleanupRawEntries(context: Context, id: String): Set<String> =
    synchronized(outputCleanupJournalLock) {
        pendingOutputCleanupRawEntriesForId(pendingOutputCleanupEntriesLocked(context), id)
    }

internal fun pendingOutputCleanupRawMatchingFingerprint(
    entries: Collection<String>,
    id: String,
    storageType: RecordingStorageType,
    expectedFingerprint: StableOutputFingerprint,
): String? = entries.firstOrNull { raw ->
    decodePendingOutputCleanupRecord(raw)?.let { record ->
        record.id == id && pendingOutputCleanupRecordMatchesFingerprint(
            record, storageType, expectedFingerprint,
        )
    } == true
}

internal fun pendingOutputCleanupEntriesAfterAppend(
    entries: Set<String>,
    raw: String,
): Set<String> = entries + raw

private fun putPendingOutputCleanup(context: Context, record: PendingOutputCleanupRecord): Boolean =
    synchronized(outputCleanupJournalLock) {
        val current = pendingOutputCleanupEntriesLocked(context)
        val raw = encodePendingOutputCleanupRecord(record)
        getRecorderPreferences(context).commitDurableStringSetReplacement(
            key = PrefKey.PENDING_OUTPUT_CLEANUP,
            previousEntries = current,
            updatedEntries = pendingOutputCleanupEntriesAfterAppend(current, raw),
        )
    }

// Cleanup replay snapshots journal entries before doing slow filesystem/provider work. A newer
// failure may replace the same target ID while that work is in flight, so terminal cleanup may
// remove only the exact raw entry it started from, never "whatever currently owns this ID".
internal fun pendingOutputCleanupEntriesAfterExactRemovals(
    entries: Set<String>,
    expectedRaws: Set<String>,
): Set<String> = if (expectedRaws.none { it in entries }) entries else entries - expectedRaws

internal fun pendingOutputCleanupEntriesAfterExactRemoval(
    entries: Set<String>,
    expectedRaw: String,
): Set<String> = pendingOutputCleanupEntriesAfterExactRemovals(entries, setOf(expectedRaw))

private fun removePendingOutputCleanupEntries(
    context: Context,
    expectedRaws: Set<String>,
): Boolean = synchronized(outputCleanupJournalLock) {
    if (expectedRaws.isEmpty()) return@synchronized true
    val current = pendingOutputCleanupEntriesLocked(context)
    val updated = pendingOutputCleanupEntriesAfterExactRemovals(current, expectedRaws)
    if (updated.size == current.size) return@synchronized true
    writePendingOutputCleanupEntriesLocked(context, updated)
}

private fun removePendingOutputCleanupEntry(context: Context, expectedRaw: String): Boolean =
    removePendingOutputCleanupEntries(context, setOf(expectedRaw))

private fun writePendingOutputCleanupEntriesLocked(context: Context, entries: Set<String>): Boolean {
    val preferences = getRecorderPreferences(context)
    val current = preferences.requireDurableStringSet(PrefKey.PENDING_OUTPUT_CLEANUP)
    if (current == entries) return true
    return preferences.commitDurableStringSetReplacement(
        key = PrefKey.PENDING_OUTPUT_CLEANUP,
        previousEntries = current,
        updatedEntries = entries,
    )
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
        document.delete()
        providerOutputCleanupCompleted(outputCleanupAssetState(context, record.storageType, record.id))
    }.getOrDefault(false)
    RecordingStorageType.MEDIASTORE -> runCatching {
        if (!pendingProviderOutputCleanupStillMatches(context, record)) return@runCatching false
        context.contentResolver.delete(record.id.toUri(), null, null)
        providerOutputCleanupCompleted(outputCleanupAssetState(context, record.storageType, record.id))
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

internal fun pendingOutputCleanupOwnsClaimPath(context: Context, claimPath: String): Boolean =
    synchronized(outputCleanupJournalLock) {
        pendingOutputCleanupEntriesLocked(context).any { raw ->
            val record = decodePendingOutputCleanupRecord(raw) ?: return@any false
            if (record.storageType != RecordingStorageType.FILE) return@any false
            outputCleanupClaimTokens(record).any { token ->
                pendingOutputCleanupFileIntent(record, token)?.let(::deletionClaimFile)
                    ?.absolutePath == claimPath
            }
        }
    }

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
