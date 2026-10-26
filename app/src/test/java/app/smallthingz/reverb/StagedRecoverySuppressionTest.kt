package app.smallthingz.reverb

import android.content.Context
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Actual FILE scanner fast paths. Attempted publication requires the separate Android fixture. */
class StagedRecoverySuppressionTest {
    @Test
    fun ordinaryScanKeepsKnownRowsWithoutDecodingJournalTwice() = withFixture { fixture ->
        val actual = fixture.scan()
        assertEquals(2, actual.size)
        assertSame(fixture.first, actual[0])
        assertSame(fixture.second, actual[1])
        assertEquals(1, fixture.journalReads)
    }

    @Test
    fun ineligibleStagingDoesNotTriggerPublicationOrSecondJournalRead() = withFixture { fixture ->
        val stagingName = stagingOutputName(
            "unfinished-copy.wav", "host-copy", sessionId = "previous-host-session", kind = StagingOutputKind.COPY,
        )
        val staging = fixture.wav(stagingName)
        val before = staging.readBytes()
        fixture.entries = arrayOf(staging, fixture.firstFile, fixture.secondFile)
        assertEquals(listOf(fixture.first.id, fixture.second.id), fixture.scan().map { it.id })
        assertEquals(1, fixture.journalReads)
        assertTrue(staging.readBytes().contentEquals(before))
        assertTrue(!File(fixture.root, "unfinished-copy.wav").exists())
    }

    @Test
    fun initialSuppressionStillExcludesKnownRowsWithoutPublication() = withFixture { fixture ->
        fixture.journal = setOf(encodePendingOutputCleanupRecord(
            requireNotNull(suppressionOnlyFileOutputRecord(
                fixture.second.id, CopyDigest(fixture.second.sizeBytes, ByteArray(32)),
            )),
        ))
        val actual = fixture.scan()
        assertEquals(1, actual.size)
        assertSame(fixture.first, actual.single())
        assertEquals(1, fixture.journalReads)
    }

    private class Fixture(val root: File) {
        var journal: Set<String> = emptySet()
        var journalReads = 0
        val context = RecordingScanTestContext {
            journalReads++
            journal
        }
        val firstFile = wav("first.wav")
        val secondFile = wav("second.wav")
        val first = knownRecording(firstFile)
        val second = knownRecording(secondFile)
        var entries = arrayOf(firstFile, secondFile)

        fun scan(): List<RecordingEntity> {
            val initial = pendingOutputCleanupIds(context)
            val directory = object : File(root.path) {
                override fun listFiles(): Array<File> = entries
            }
            return try {
                @Suppress("UNCHECKED_CAST")
                (Class.forName("app.smallthingz.reverb.RecordingFilesKt")
                    .getDeclaredMethod("listFileDirectoryRecordings", Context::class.java, File::class.java,
                        Map::class.java, Set::class.java)
                    .apply { isAccessible = true }
                    .invoke(null, context, directory, mapOf(first.id to first, second.id to second), initial)
                    as List<RecordingEntity>)
            } catch (error: InvocationTargetException) {
                throw error.targetException
            }
        }

        fun wav(name: String): File = File(root, name).apply {
            val pcm = ByteArray(160)
            writeBytes(buildWavHeaderBytes(8_000, 1, PcmSampleFormat.PCM_16, pcm.size.toLong()) + pcm)
        }

        private fun knownRecording(file: File): RecordingEntity {
            val identity = resolveFileIdentity(file)
            assertTrue("Host identity must be available before testing the cached-row path", identity.isNotBlank())
            return RecordingEntity(
                id = file.absolutePath, displayName = file.name, mimeType = "audio/wav",
                startedAtMillis = 1L, durationMillis = 10L, sizeBytes = file.length(), codecSummary = "PCM",
                storageType = RecordingStorageType.FILE, directoryId = root.absolutePath, fileIdentity = identity,
            )
        }
    }

    private inline fun withFixture(block: (Fixture) -> Unit) {
        val root = Files.createTempDirectory("staged-suppression-").toFile()
        try {
            block(Fixture(root))
        } finally {
            root.deleteRecursively()
        }
    }
}
