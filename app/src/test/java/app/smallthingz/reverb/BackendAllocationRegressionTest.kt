package app.smallthingz.reverb

import java.nio.charset.StandardCharsets
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendAllocationRegressionTest {
    @Test
    fun suppressionOnlyParsingRetainsLegacyValidTornAndMalformedRecords() {
        val token = "12345678-1234-1234-1234-123456789abc"
        val base = PendingDeletionIntent("/recordings/音声|clip.wav", 42L, "ab".repeat(32), false)
        val claimed = base.copy(storageType = RecordingStorageType.FILE, claimToken = token, fileIdentity = "stat:1:2:3:4:0")
        val moved = claimed.copy(
            moveTargetStorageType = RecordingStorageType.DOCUMENT,
            moveTargetId = "content://documents/tree/root/document/clip",
            moveTargetIdentity = "provider:target",
        )
        val valid = listOf(
            encodePendingDeletionIntent(base),
            encodePendingDeletionIntent(claimed).replaceFirst("v3|", "v2|")
                .replace("|${RecordingStorageType.FILE.storageCode.toInt()}|$token|", "|FILE|$token|"),
            encodePendingDeletionIntent(claimed),
            encodePendingDeletionIntent(moved),
        )
        for (raw in valid) {
            assertEquals(base.id, requireNotNull(decodePendingDeletionIntent(raw)).id)
            for (end in 0..raw.length) {
                val truncated = raw.substring(0, end)
                assertEquals("prefix length=$end", legacySuppressedId(truncated), pendingDeletionSuppressedId(truncated))
            }
            for (tail in listOf("|", "|extra", "|".repeat(128))) {
                assertEquals(legacySuppressedId(raw + tail), pendingDeletionSuppressedId(raw + tail))
            }
        }
        val encodedIds = listOf("", "IA", "AA", "_w", "wA", "YQ", "YQ==", "YR", "A", "%%%%", "Y Q") +
            (0..255).map { Base64.getUrlEncoder().withoutPadding().encodeToString(byteArrayOf(it.toByte())) }
        for (version in listOf("v1", "v2", "v3", "v4", "v0", "v99", "v01", "v１", "v", "V1")) {
            for (id in encodedIds) {
                for (tail in listOf("", "|", "|invalid|authority|fields")) {
                    val raw = "$version|$id$tail"
                    assertEquals(raw, legacySuppressedId(raw), pendingDeletionSuppressedId(raw))
                }
            }
        }
        for (raw in listOf("", " ", "\n", "content://legacy/id", "v1", "v1x|value", "v-1|value", "name|with|pipes")) {
            assertEquals(raw, legacySuppressedId(raw), pendingDeletionSuppressedId(raw))
        }
        val torn = "v4|${Base64.getUrlEncoder().encodeToString(base.id.toByteArray(StandardCharsets.UTF_8))}|broken"
        assertEquals(base.id, pendingDeletionSuppressedId(torn))
        assertNull(decodePendingDeletionIntent(torn))
    }

    @Test
    fun retentionDigestKeepsCanonicalPersistedVectors() {
        assertEquals(
            "a410c1c70e35b88a1788c20d9dc5964258c3099f5b3a5491e64dfae252e12402",
            retentionConfigurationDigest(RetentionConfiguration(RetentionMode.SIZE, 0, 0, 0, 0)),
        )
        assertEquals(
            "802fc0594442c63ed5a472a3140e0071226b46c9e6d485cd5bd9f3a362153013",
            retentionConfigurationDigest(RetentionConfiguration(RetentionMode.TIME, 123, 456, 789, Long.MAX_VALUE)),
        )
    }

    @Test
    fun callerMutationCannotPoisonLaterCatalogQueries() {
        val first = recordingCatalogProjection()
        val expected = first.copyOf()
        val second = recordingCatalogProjection()
        assertNotSame(first, second)
        assertArrayEquals(expected, second)
        first.fill("NULL AS poisoned")
        second.reverse()
        assertArrayEquals(expected, recordingCatalogProjection())
        assertEquals(
            "CASE WHEN typeof(startedAtMillis) = 'integer' THEN startedAtMillis ELSE -1 END DESC, " +
                "CASE WHEN typeof(createdAtMillis) = 'integer' THEN createdAtMillis ELSE -1 END DESC",
            recordingCatalogOrderBy(),
        )
    }

    @Test
    fun silenceFormatIsPinnedOnlyAfterAcceptedPcmAndChecksEveryComponent() {
        for ((rate, channels, format) in listOf(
            Triple(48_000, 1, PcmSampleFormat.PCM_16),
            Triple(16_000, 2, PcmSampleFormat.PCM_16),
            Triple(16_000, 1, PcmSampleFormat.PCM_FLOAT),
        )) {
            val request = SilenceSampleRequest(startedAtNanos = 0L)
            request.accept(ByteArray(0), 0, 0, 8_000, 2, PcmSampleFormat.PCM_FLOAT, 0L)
            assertNull(request.state.value.error)
            val initial = ByteArray(32)
            request.accept(initial, 0, initial.size, 16_000, 1, PcmSampleFormat.PCM_16, 0L)
            request.accept(initial, 0, initial.size, 16_000, 1, PcmSampleFormat.PCM_16, 0L)
            assertTrue(request.running())
            val changed = ByteArray(channels * format.bytesPerSample)
            request.accept(changed, 0, changed.size, rate, channels, format, 0L)
            assertEquals("Audio input changed. Sample the room again.", request.state.value.error)
            assertFalse(request.state.value.complete)
        }
    }

    // Retained pre-optimization behavior: authority decode, then permissive suppression fallback.
    private fun legacySuppressedId(raw: String): String? {
        decodePendingDeletionIntent(raw)?.let { return it.id }
        if (listOf("v1|", "v2|", "v3|", "v4|").none(raw::startsWith)) {
            val separator = raw.indexOf('|')
            val versioned = raw.startsWith('v') && separator > 1 && raw.substring(1, separator).all(Char::isDigit)
            return raw.takeIf { it.isNotBlank() && !versioned }
        }
        val parts = raw.split('|')
        if (parts.size < 2) return null
        return runCatching { String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8) }
            .getOrNull()?.takeIf { it.isNotBlank() }
    }
}
