package app.smallthingz.reverb

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineSnapshotDeliveryTest {
    @Test
    fun successfulLiveDeliveryTransfersSnapshotOwnership() {
        var closed = false
        var delivered: Closeable? = null
        val snapshot = Closeable { closed = true }

        deliverTimelineSnapshotAtServiceBoundary(false, snapshot) { delivered = it }

        assertSame(snapshot, delivered)
        assertFalse(closed)
        delivered?.close()
        assertTrue(closed)
    }

    @Test
    fun failedLiveDeliveryClosesSnapshotBeforePropagatingCallbackFailure() {
        var closed = false
        val snapshot = Closeable { closed = true }
        var threw = false

        try {
            deliverTimelineSnapshotAtServiceBoundary(false, snapshot) {
                throw IllegalStateException("callback failed")
            }
        } catch (_: IllegalStateException) {
            threw = true
        }

        assertTrue(threw)
        assertTrue(closed)
    }

    @Test
    fun captureSnapshotCleanup_attemptsEveryCloseAfterEarlierFailure() {
        val events = mutableListOf<String>()
        val first = Closeable {
            events += "first"
            throw IOException("release failed")
        }
        val second = Closeable { events += "second" }

        closeCaptureSnapshotsBestEffort(first, second)

        assertEquals(listOf("first", "second"), events)
    }

    @Test
    fun serviceSnapshotRelease_failureDoesNotEscapeOrSuppressFollowingTerminal() {
        val expected = IOException("release failed")
        var observed: Exception? = null
        var terminalDelivered = false

        releaseTimelineSnapshotBestEffort(
            release = { throw expected },
            onFailure = { observed = it },
        )
        terminalDelivered = true

        assertSame(expected, observed)
        assertTrue(terminalDelivered)
    }

    @Test
    fun serviceSnapshotRelease_failureReporterCannotEscapeCleanupBoundary() {
        var terminalDelivered = false

        releaseTimelineSnapshotBestEffort(
            release = { throw IOException("release failed") },
            onFailure = { throw IllegalStateException("report failed") },
        )
        terminalDelivered = true

        assertTrue(terminalDelivered)
    }

    @Test
    fun teardownDelivery_reportsReleaseFailureAndStillDeliversNull() {
        val expected = IOException("release failed")
        var observed: Exception? = null
        var delivered: Closeable? = Closeable {}

        deliverTimelineSnapshotAtServiceBoundary(
            serviceDestroying = true,
            snapshot = Closeable { throw expected },
            callback = { delivered = it },
            onReleaseFailure = { observed = it },
        )

        assertSame(expected, observed)
        assertEquals(null, delivered)
    }

    @Test
    fun timelineChildRelease_reportsDeferredRetentionFailureWithoutEscaping() {
        val parent = File("build/tmp/timeline-snapshot-tests").apply { mkdirs() }
        val root = Files.createTempDirectory(parent.toPath(), "child-release-").toFile()
        var failChunkDirectorySync = false
        val store = PersistentAudioChunkStore(
            rootDirectory = root,
            overwriteOldest = false,
            directorySync = { directory ->
                if (failChunkDirectorySync && directory.name == BUFFER_CHUNKS_FOLDER_NAME) {
                    throw IOException("Injected child-release retention sync failure")
                }
            },
        )
        try {
            store.configure(
                requestedRetentionMode = RetentionMode.SIZE,
                requestedRetentionValue = 128 * 1024L,
                requestedSampleRate = 8_000,
                requestedChannelCount = 1,
                sampleFormat = PcmSampleFormat.PCM_16,
            )
            val first = ByteArray(8_192) { index -> (index * 17).toByte() }
            val second = ByteArray(8_192) { index -> (index * 29 + 3).toByte() }
            assertEquals(first.size, store.append(first, 0, first.size))
            store.sealActiveChunk()
            assertEquals(second.size, store.append(second, 0, second.size))
            store.sealActiveChunk()

            val parentLease = requireNotNull(store.acquireRange(0.0, store.durationSeconds()))
            var reported: Exception? = null
            val snapshot = ReverbService.TimelineSnapshot(
                lease = parentLease,
                onChildReleaseFailure = { reported = it },
            )
            val child = requireNotNull(snapshot.acquireRange(0.0, snapshot.durationSeconds))

            store.configure(
                requestedRetentionMode = RetentionMode.SIZE,
                requestedRetentionValue = 4_096L,
                requestedSampleRate = 8_000,
                requestedChannelCount = 1,
                sampleFormat = PcmSampleFormat.PCM_16,
            )
            snapshot.close()

            failChunkDirectorySync = true
            snapshot.releaseChildRangeBestEffort(child)
            failChunkDirectorySync = false

            assertTrue(reported?.message?.contains("Injected child-release retention sync failure") == true)
        } finally {
            failChunkDirectorySync = false
            runCatching {
                store.configure(
                    requestedRetentionMode = RetentionMode.SIZE,
                    requestedRetentionValue = 4_096L,
                    requestedSampleRate = 8_000,
                    requestedChannelCount = 1,
                    sampleFormat = PcmSampleFormat.PCM_16,
                )
            }
            runCatching { store.close() }
            root.deleteRecursively()
        }
    }

    @Test
    fun callbackFailure_keepsCallbackPrimaryAndSuppressesReleaseFailure() {
        val callbackFailure = IllegalStateException("callback failed")
        val releaseFailure = IOException("release failed")
        var reported: Exception? = null

        val thrown = try {
            deliverTimelineSnapshotAtServiceBoundary(
                serviceDestroying = false,
                snapshot = Closeable { throw releaseFailure },
                callback = { throw callbackFailure },
                onReleaseFailure = { reported = it },
            )
            null
        } catch (error: Throwable) {
            error
        }

        assertSame(callbackFailure, thrown)
        assertSame(releaseFailure, reported)
        assertTrue(callbackFailure.suppressed.contains(releaseFailure))
    }
}
