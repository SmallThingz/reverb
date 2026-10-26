package app.smallthingz.reverb

import java.nio.charset.StandardCharsets
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class FinalBackendCompatibilityRegressionTest {
    @Test
    fun recordingNameFastPathMatchesOriginalAsciiAndUnicodeLowercaseSemantics() {
        val extensions = ExportFormat.entries.map { it.extension }.toSet()
        fun compare(name: String) {
            val baseline = !isStagingOutputName(name) && name.substringAfterLast('.', "").lowercase() in extensions
            assertEquals("name code units: ${name.map { it.code }}", baseline, isSupportedRecordingName(name))
        }
        for (name in listOf("", "wav", "WAV", "flac", ".", "..", "clip.", ".wav", ".FLAC",
            "clip.wav.", "clip.wav.tmp", "clip.wav\u0000", "reverb-partial-clip.WAV", "REVERB-PARTIAL-clip.WAV")) compare(name)
        val nonAscii = listOf(0x130, 0x131, 0x17f, 0x212a, 0x212b, 0x00df, 0x1e9e, 0x0307,
            0x03a3, 0x03c2, 0xfb00, 0xfb02, 0xff21, 0xd800, 0xdc00, 0xffff)
        for (extension in extensions) {
            for (mask in 0 until (1 shl extension.length)) {
                val mixed = extension.mapIndexed { index, char -> if (mask and (1 shl index) == 0) char else char.uppercaseChar() }.joinToString("")
                for (prefix in listOf("clip.", "東京.", "many.dots.", "reverb-partial-", "reverb-partial-clip.")) compare(prefix + mixed)
            }
            for (offset in extension.indices) for (code in (0..255).toList() + nonAscii) {
                val suffix = extension.toCharArray().also { it[offset] = code.toChar() }.concatToString()
                compare("clip.$suffix")
            }
            for (extra in listOf("\u0130", "\u017f", "\u212a", "\uD801\uDC00", "\uD800", "\u0307")) {
                compare("clip.$extension$extra")
                compare("clip.$extra$extension")
            }
        }
    }

    @Test
    fun defaultCleanupTokenMatchesOriginalSeedBytesAndRetainsLegacyReplayTokens() {
        fun baselineToken(record: PendingOutputCleanupRecord, storageIdentity: String): String {
            val seed = buildString {
                append(storageIdentity).append('|')
                append(record.id).append('|')
                append(record.byteCount).append('|')
                append(record.sha256Hex).append('|')
                append(record.fileKey.orEmpty())
            }.toByteArray(StandardCharsets.UTF_8)
            return UUID.nameUUIDFromBytes(seed).toString()
        }
        for (type in RecordingStorageType.entries) {
            for (id in listOf("/recordings/clip.wav", "/recordings/東京|café.wav", "/recordings/line\nbreak.wav", "/recordings/\uD800.wav")) {
                for (bytes in listOf(0L, 1234L, Long.MAX_VALUE)) for (hash in listOf("ab".repeat(32), "AB".repeat(32))) {
                    val record = PendingOutputCleanupRecord(type, id, bytes, hash, "stat:1:2:100:5:77")
                    // Retained pre-change expression: both encodings are computed, de-duplicated, then first wins.
                    val baseline = listOf(baselineToken(record, type.storageCode.toInt().toString()),
                        baselineToken(record, type.name)).distinct()
                    assertEquals(baseline, outputCleanupClaimTokens(record))
                    if (type == RecordingStorageType.FILE) {
                        assertEquals(baseline.first(), requireNotNull(pendingOutputCleanupFileIntent(record)).claimToken)
                        assertEquals(baseline.last(), requireNotNull(pendingOutputCleanupFileIntent(record, baseline.last())).claimToken)
                    } else assertNull(pendingOutputCleanupFileIntent(record))
                }
            }
        }
    }

    @Test
    fun stagingSinglePassMatchesOriginalCollectionDecisionsForEverySubsetAndOrder() {
        fun baseline(entries: Set<String>, record: VerifiedExportStagingRecord, fingerprint: StableOutputFingerprint): VerifiedExportStagingRecovery? {
            val matching = entries.mapNotNull(::decodeVerifiedExportStagingRecord).filter {
                it.storageType == record.storageType && it.id == record.id && verifiedExportStagingRecordMatches(it, fingerprint)
            }
            if (matching.isEmpty()) return null
            return VerifiedExportStagingRecovery(fingerprint, matching.mapNotNull { it.finalDisplayName }.distinct().singleOrNull())
        }
        for (type in RecordingStorageType.entries) {
            val id = when (type) {
                RecordingStorageType.FILE -> "/recordings/staging-7.wav"
                RecordingStorageType.DOCUMENT -> "content://docs/tree/root/document/7"
                RecordingStorageType.MEDIASTORE -> "content://media/external/audio/media/7"
            }
            val record = VerifiedExportStagingRecord(type, id, 4L, "ab".repeat(32),
                if (type == RecordingStorageType.FILE) "stat:1:2:3:4:5" else null,
                if (type == RecordingStorageType.FILE) null else "provider:${type.storageCode.toInt()}:old:4:1", "東京.wav")
            val fingerprint = StableOutputFingerprint(CopyDigest(4L, ByteArray(32) { 0xab.toByte() }), record.fileKey, record.providerIdentity)
            val named = encodeVerifiedExportStagingRecord(record)
            val legacy = encodeVerifiedExportStagingRecord(record.copy(finalDisplayName = null))
            val v1 = "v1|${type.name}|" + legacy.split('|').drop(2).joinToString("|")
            val entries = listOf(legacy, v1, named, named.replace("|${record.sha256Hex}|", "|${record.sha256Hex.uppercase()}|"),
                encodeVerifiedExportStagingRecord(record.copy(finalDisplayName = "conflict.wav")),
                encodeVerifiedExportStagingRecord(record.copy(id = id.replace('7', '8'))),
                encodeVerifiedExportStagingRecord(record.copy(sha256Hex = "cd".repeat(32))),
                encodeVerifiedExportStagingRecord(record.copy(byteCount = 5L)), "v3|broken")
            for (mask in 0 until (1 shl entries.size)) {
                val subset = entries.filterIndexed { index, _ -> mask and (1 shl index) != 0 }
                for (ordered in listOf(subset, subset.reversed())) {
                    val raw = ordered.toCollection(linkedSetOf())
                    assertEquals("$type/$mask", baseline(raw, record, fingerprint),
                        verifiedExportStagingRecoveryForFingerprint(raw, type, id, fingerprint))
                }
            }
        }
    }
}
