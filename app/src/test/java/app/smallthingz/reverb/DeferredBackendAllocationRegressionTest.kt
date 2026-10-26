package app.smallthingz.reverb

import android.os.Build
import android.provider.OpenableColumns
import java.io.File
import java.nio.file.Files
import java.util.LinkedList
import org.junit.Assert.*
import org.junit.Test

class DeferredBackendAllocationRegressionTest {
    @Test
    fun feedbackOverflowKeepsHeadAndNewestTailAcrossSmallAndOversizedQueues() {
        val events = (1L..20L).map { FeedbackEvent(it, "event-$it", FeedbackTone.INFO) }
        val incoming = FeedbackEvent(99L, "newest", FeedbackTone.ERROR)
        val cases = listOf(
            Triple(1, 1, listOf(1L)),
            Triple(1, 20, listOf(1L)),
            Triple(2, 8, listOf(1L, 99L)),
            Triple(4, 8, listOf(1L, 7L, 8L, 99L)),
            Triple(16, 20, listOf(1L) + (7L..20L).toList() + 99L),
            Triple(4, 2, listOf(1L, 2L, 99L)),
        )
        for ((capacity, count, expectedIds) in cases) {
            for (linked in listOf(false, true)) {
                val pending: MutableList<FeedbackEvent> = if (linked) LinkedList(events.take(count))
                    else ArrayList(events.take(count))
                val result = enqueueFeedbackEvent(pending, incoming, capacity)
                assertEquals("$capacity/$count/$linked", expectedIds, result.map { it.id })
                assertSame(events.first(), result.first())
                if (capacity > 1) assertSame(incoming, result.last())
                pending.clear()
                assertEquals(expectedIds, result.map { it.id })
            }
        }
        val duplicate = enqueueFeedbackEvent(listOf(events[0], events[0], events[1]), events[0], 3)
        assertEquals(listOf(events[0], events[1], events[0]), duplicate)
        assertEquals(listOf(incoming), enqueueFeedbackEvent(emptyList(), incoming, 1))
    }

    @Test
    fun sharedOptionListsCannotBeMutatedAndProjectionKeepsOrderAndDuplicates() {
        val columns = verifiedFileProviderQueryColumns(null)
        assertThrows(UnsupportedOperationException::class.java) {
            (columns as MutableList<String>)[0] = "replaced"
        }
        assertEquals(listOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            verifiedFileProviderQueryColumns(null))
        val projection = arrayOf(OpenableColumns.SIZE, "ignored", OpenableColumns.SIZE, OpenableColumns.DISPLAY_NAME)
        val filtered = verifiedFileProviderQueryColumns(projection)
        projection[0] = "changed"
        assertEquals(listOf(OpenableColumns.SIZE, OpenableColumns.SIZE, OpenableColumns.DISPLAY_NAME), filtered)
        assertTrue(verifiedFileProviderQueryColumns(emptyArray()).isEmpty())

        val modes = AudioSourceMode.availableModes()
        assertThrows(UnsupportedOperationException::class.java) {
            (modes as MutableList<AudioSourceMode>).clear()
        }
        val expected = mutableListOf(AudioSourceMode.VOICE_RECOGNITION, AudioSourceMode.VOICE_COMMUNICATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) expected += AudioSourceMode.VOICE_PERFORMANCE
        expected += listOf(AudioSourceMode.CAMCORDER, AudioSourceMode.DEFAULT, AudioSourceMode.MIC, AudioSourceMode.UNPROCESSED)
        assertEquals(expected, AudioSourceMode.availableModes())
    }

    @Test
    fun reusedObservationValuesStillReadEveryFilesystemTransitionWithoutFollowingLinks() {
        val parent = File("build/tmp/deferred-backend-allocations").apply { mkdirs() }
        val root = Files.createTempDirectory(parent.toPath(), "fresh-state-").toFile()
        val path = File(root, "changing")
        try {
            assertEquals(StoragePathObservation(StoragePathState.MISSING), observeStoragePath(path))
            assertEquals(RecordingAssetState.MISSING, fileRecordingAssetState(path))
            path.writeBytes(byteArrayOf(1, 2, 3))
            assertEquals(StoragePathObservation(StoragePathState.PRESENT, true), observeStoragePath(path))
            assertEquals(RecordingAssetState.PRESENT, fileRecordingAssetState(path))
            assertTrue(path.delete())
            assertTrue(path.mkdir())
            assertEquals(StoragePathObservation(StoragePathState.PRESENT, false), observeStoragePath(path))
            assertEquals(RecordingAssetState.MISSING, fileRecordingAssetState(path))
            assertTrue(path.delete())
            Files.createSymbolicLink(path.toPath(), File(root, "missing-target").toPath().toAbsolutePath())
            assertEquals(StoragePathObservation(StoragePathState.PRESENT, false), observeStoragePath(path))
            assertEquals(RecordingAssetState.MISSING, fileRecordingAssetState(path))
            Files.delete(path.toPath())
            assertEquals(StoragePathObservation(StoragePathState.MISSING), observeStoragePath(path))
        } finally {
            root.deleteRecursively()
        }
    }
}
