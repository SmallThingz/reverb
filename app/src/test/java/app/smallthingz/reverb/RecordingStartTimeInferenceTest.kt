package app.smallthingz.reverb

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingStartTimeInferenceTest {
    private val modifiedAtMillis = 1_700_000_000_000L

    private fun fileWithModifiedTime(name: String, modifiedAt: Long): File =
        object : File(name) {
            override fun lastModified(): Long = modifiedAt
        }

    @Test
    fun negativeNumericNames_useModificationTimeForBothResolvers() {
        for (name in listOf("-1.wav", "-1 (1).wav", "${Long.MIN_VALUE}.wav", "${Long.MIN_VALUE} (3).flac")) {
            assertEquals(name, modifiedAtMillis, resolveRecordingStartTimeMillis(name, modifiedAtMillis))
            assertEquals(
                name,
                modifiedAtMillis,
                resolveRecordingStartTimeMillis(fileWithModifiedTime(name, modifiedAtMillis)),
            )
        }
    }

    @Test
    fun nonnegativeNumericNames_keepTimestampAndCollisionSuffixCompatibility() {
        val names = mapOf(
            "0.wav" to 0L,
            "0 (1).wav" to 0L,
            "1700000000123.wav" to 1_700_000_000_123L,
            "1700000000123 (12).flac" to 1_700_000_000_123L,
            "${Long.MAX_VALUE}.wav" to Long.MAX_VALUE,
        )
        for ((name, expected) in names) {
            assertEquals(name, expected, resolveRecordingStartTimeMillis(name, modifiedAtMillis))
            assertEquals(
                name,
                expected,
                resolveRecordingStartTimeMillis(fileWithModifiedTime(name, modifiedAtMillis)),
            )
        }
    }

    @Test
    fun unrecognizedNames_keepModificationTimeFallback() {
        for (name in listOf("recording.wav", "123 (x).wav", "9223372036854775808.wav")) {
            assertEquals(name, modifiedAtMillis, resolveRecordingStartTimeMillis(name, modifiedAtMillis))
            assertEquals(
                name,
                modifiedAtMillis,
                resolveRecordingStartTimeMillis(fileWithModifiedTime(name, modifiedAtMillis)),
            )
        }
    }

    @Test
    fun negativeNumericNames_withoutModificationTimeUseCurrentTime() {
        for (modifiedAt in listOf(0L, -1L)) {
            val before = System.currentTimeMillis()
            val fromName = resolveRecordingStartTimeMillis("-1.wav", modifiedAt)
            val fromFile = resolveRecordingStartTimeMillis(fileWithModifiedTime("-1 (1).wav", modifiedAt))
            val after = System.currentTimeMillis()
            assertTrue(fromName in before..after)
            assertTrue(fromFile in before..after)
        }
    }

    @Test
    fun negativeNumericName_resolvesToCatalogAdmissibleTimestamp() {
        val displayName = "-1.wav"
        val startedAt = resolveRecordingStartTimeMillis(displayName, modifiedAtMillis)
        assertTrue(
            recordingCatalogCoreFieldsAreValid(
                id = "/recordings/$displayName",
                displayName = displayName,
                directoryId = "/recordings",
                startedAtMillis = startedAt,
                durationMillis = 1_000L,
                sizeBytes = 2_000L,
                createdAtMillis = modifiedAtMillis,
                lastSeenAtMillis = modifiedAtMillis,
                missingSinceMillis = null,
            ),
        )
    }
}
