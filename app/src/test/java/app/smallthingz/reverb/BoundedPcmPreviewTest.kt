package app.smallthingz.reverb

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class BoundedPcmPreviewTest {
    private class Superseded : RuntimeException()

    @Test fun expandingSelectedBoundsPreservesQueuedSupportAcrossRepeatedUpdates() {
        for (direction in listOf(-1, 1)) {
            var admission = ShuttlePlaybackAdmission(5.0, 6.0, direction)
            repeat(30) {
                val expanded = admission.updated(admission.startSeconds - 0.01, admission.endSeconds + 0.01,
                    direction.toFloat())
                assertFalse("Expansion must retain track, epoch and cached source", expanded.requiresRetirementOf(admission))
                admission = expanded
            }
            assertFalse(admission.updated(admission.startSeconds, admission.endSeconds, 0f)
                .requiresRetirementOf(admission))
        }
    }

    @Test fun eitherExcludedEdgeStillRequiresRetirementEvenWhenTheOtherEdgeExpands() {
        val selected = ShuttlePlaybackAdmission(5.0, 6.0, 1)
        for ((start, end) in listOf(5.1 to 6.0, 5.0 to 5.9, 5.1 to 6.1, 4.9 to 5.9)) {
            assertTrue(selected.updated(start, end, 1f).requiresRetirementOf(selected))
        }
        assertFalse(selected.updated(4.9, 6.0, 1f).requiresRetirementOf(selected))
        assertFalse(selected.updated(5.0, 6.1, 1f).requiresRetirementOf(selected))
    }

    @Test fun directionReversalThroughZeroRetiresPreviouslyQueuedDirection() {
        for (direction in listOf(-1, 1)) {
            val moving = ShuttlePlaybackAdmission(5.0, 6.0, direction)
            val zero = moving.updated(4.9, 6.1, 0f)
            val anotherZero = zero.updated(4.8, 6.2, -0f)
            assertEquals(direction, anotherZero.direction)
            assertFalse(zero.requiresRetirementOf(moving))
            assertFalse(anotherZero.requiresRetirementOf(zero))
            assertTrue(anotherZero.updated(4.7, 6.3, -direction.toFloat())
                .requiresRetirementOf(anotherZero))
            assertFalse(anotherZero.updated(4.7, 6.3, direction.toFloat())
                .requiresRetirementOf(anotherZero))
        }
        val neverPlayed = ShuttlePlaybackAdmission(5.0, 6.0, 0)
        assertFalse(neverPlayed.updated(5.0, 6.0, -1f).requiresRetirementOf(neverPlayed))
    }

    @Test fun workerEndAllowsRestartBeforeDelayedUiCompletionAndOldEndCannotStopNewRun() {
        val runs = PreviewPlaybackRuns()
        runs.start(7L)
        assertTrue(runs.active(7L))
        // The audio drain finishes while Main has not handled its completion publication.
        runs.finish(7L)
        assertFalse(runs.active(7L))
        runs.start(8L)
        runs.finish(7L)
        assertTrue(runs.active(8L))
        assertFalse(runs.active(7L))
    }

    @Test fun pauseRevokesPendingRunAndItsLateFinishCannotRetireRestart() {
        val runs = PreviewPlaybackRuns()
        runs.start(1L)
        runs.cancel()
        assertFalse(runs.active(1L))
        runs.start(3L)
        runs.finish(1L)
        assertTrue(runs.active(3L))
    }

    @Test fun boundedSourceCacheAmortizesIdentityReadsWithoutChangingOutputAdmission() {
        val rate = 48_000
        val file = Files.createTempFile("bounded-source-cache-", ".pcm").toFile()
        file.writeBytes(ByteArray(rate * 2))
        var validations = 0
        try {
            val input = RandomAccessFile(file, "r")
            RecordingPcm16Reader(input.channel, { validations++; true }, input::close,
                WavPcmLayout(rate, 1, PcmSampleFormat.PCM_16, 0, rate * 2L)).use { reader ->
                var largestRead = 0
                var writtenFrames = 0L
                streamBoundedPcmFrames(PcmPreviewFrameRange(0, rate.toLong()), 2, rate / 4, {},
                    { first, count -> largestRead = maxOf(largestRead, count); reader.readFramesPcm16(first, count) },
                    { _, _, count -> val accepted = minOf(count, rate / 20 * 2); writtenFrames += accepted / 2; accepted })
                assertEquals(rate.toLong(), writtenFrames)
                assertEquals(rate / 4, largestRead)
                assertEquals("Four bounded source reads each validate before and after", 8, validations)
                validations = 0
                streamBoundedPcmFrames(PcmPreviewFrameRange(0, rate.toLong()), 2, rate / 20, {},
                    reader::readFramesPcm16, { _, _, count -> count })
                assertEquals("50ms source reads would resolve identity40 times per second", 40, validations)
            }
        } finally { file.delete() }
    }

    @Test fun sourceIdentityChangeAtReadCompletionPreventsEveryCachedFrameWrite() {
        val file = Files.createTempFile("bounded-source-identity-", ".pcm").toFile()
        file.writeBytes(ByteArray(200))
        var validations = 0
        var writes = 0
        try {
            val input = RandomAccessFile(file, "r")
            RecordingPcm16Reader(input.channel, { ++validations == 1 }, input::close,
                WavPcmLayout(1000, 1, PcmSampleFormat.PCM_16, 0, 200)).use { reader ->
                assertThrows(java.io.IOException::class.java) {
                    streamBoundedPcmFrames(PcmPreviewFrameRange(0, 100), 2, 100, {},
                        reader::readFramesPcm16, { _, _, count -> writes++; count })
                }
                assertEquals(2, validations)
                assertEquals(0, writes)
            }
        } finally { file.delete() }
    }

    @Test fun delayedUiHandoffKeepsPrimedShuttleSilentUntilNormalPlayerPaused() {
        val posted = CountDownLatch(1)
        val callback = AtomicReference<(() -> Unit)?>(null)
        val normalAudible = AtomicBoolean(true)
        val shuttleAudible = AtomicBoolean(false)
        val worker = Executors.newSingleThreadExecutor()
        try {
            val running = worker.submit {
                awaitPreviewPlaybackHandoff({}, { action -> callback.set(action); posted.countDown(); true },
                    { normalAudible.set(false) })
                assertFalse(normalAudible.get())
                shuttleAudible.set(true)
            }
            assertTrue(posted.await(5, TimeUnit.SECONDS))
            assertTrue(normalAudible.get())
            assertFalse(shuttleAudible.get())
            assertFalse(running.isDone)
            callback.get()!!.invoke()
            running.get(5, TimeUnit.SECONDS)
            assertTrue(shuttleAudible.get())
        } finally { worker.shutdownNow() }
    }

    @Test fun delayedOldHandoffCannotPauseNewTransportOrStartRetiredTrack() {
        val current = AtomicBoolean(true)
        val posted = CountDownLatch(1)
        val callback = AtomicReference<(() -> Unit)?>(null)
        val worker = Executors.newSingleThreadExecutor()
        try {
            val running = worker.submit {
                assertThrows(Superseded::class.java) {
                    awaitPreviewPlaybackHandoff({ if (!current.get()) throw Superseded() },
                        { action -> callback.set(action); posted.countDown(); true },
                        { fail("Old handoff paused a newer transport") })
                    fail("Cancelled handoff returned playback authority")
                }
            }
            assertTrue(posted.await(5, TimeUnit.SECONDS))
            current.set(false)
            callback.get()!!.invoke()
            running.get(5, TimeUnit.SECONDS)
        } finally { worker.shutdownNow() }
    }

    @Test fun oneSecondSelectionAtFileEndEmitsExactlySelectedFramesWithPartialWrites() {
        for (rate in listOf(8_000, 44_100, 48_000, 96_000)) {
            val range = previewFrameRange(9_000, 10_000, rate, rate * 10L)
            val output = ByteArrayOutputStream()
            val reads = ArrayList<Pair<Long, Int>>()
            val emitted = streamBoundedPcmFrames(range, 2, 317, {}, { first, count ->
                reads += first to count
                ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
                    repeat(count) { putShort(((first + it) % 30_000).toShort()) }
                }.array()
            }, { bytes, offset, count ->
                val accepted = minOf(count, 34)
                output.write(bytes, offset, accepted)
                accepted
            })
            assertEquals(rate.toLong(), emitted)
            assertEquals(rate * 2, output.size())
            val decoded = ByteBuffer.wrap(output.toByteArray()).order(ByteOrder.LITTLE_ENDIAN)
            repeat(rate) { assertEquals(((range.first + it) % 30_000).toShort(), decoded.short) }
            assertEquals(range.first, reads.first().first)
            assertEquals(range.endExclusive, reads.last().first + reads.last().second)
        }
    }

    @Test fun selectionAtEndHasNoReadOrWriteAndCannotReplayPriorBuffer() {
        for (rounding in RecordingFrameRounding.entries) {
            val end = previewFrameRange(10_000, 10_000, 48_000, 480_000, rounding)
            assertEquals(0L, streamBoundedPcmFrames(end, 2, 2400, {},
                { _, _ -> error("End position read source") }, { _, _, _ -> error("End position emitted audio") }))
            assertEquals(PcmPreviewFrameRange(400, 400), previewFrameRange(9, 9, 48_000, 400, rounding))
            val exactEnd = 400.0 / 48_000
            assertEquals(PcmPreviewFrameRange(400, 400),
                recordingShuttleFrameRange(exactEnd, exactEnd, 48_000, 400, rounding))
            assertEquals(PcmPreviewFrameRange(400, 400),
                recordingShuttleFrameRange(.009, .010, 48_000, 400, rounding))
            assertEquals(0L, recordingShuttleFrameRange(.006, .006, 44_100, 44_100, rounding).frameCount)
        }
    }

    @Test fun replacingSessionDuringSlowReadRejectsItsFirstWrite() {
        var current = true
        var writes = 0
        assertThrows(Superseded::class.java) {
            streamBoundedPcmFrames(PcmPreviewFrameRange(0, 100), 2, 100,
                { if (!current) throw Superseded() },
                { _, frames -> current = false; ByteArray(frames * 2) },
                { _, _, count -> writes++; count })
        }
        assertEquals(0, writes)
    }

    @Test fun replacementBetweenPartialWritesRejectsRemainingOldFrames() {
        var current = true
        var written = 0
        assertThrows(Superseded::class.java) {
            streamBoundedPcmFrames(PcmPreviewFrameRange(700, 900), 2, 200,
                { if (!current) throw Superseded() }, { _, frames -> ByteArray(frames * 2) },
                { _, _, _ -> current = false; written += 2; 2 })
        }
        assertEquals(2, written)
    }

    @Test fun sourceFrameArithmeticPreservesConversionFloorAndDoesNotOverflowAtLargeMillis() {
        assertEquals(PcmPreviewFrameRange(44, 88), previewFrameRange(1, 2, 44_100, 1000))
        val expected = Int.MAX_VALUE.toLong() * 96_000 / 1000
        assertEquals(PcmPreviewFrameRange(expected, expected),
            previewFrameRange(Int.MAX_VALUE, Int.MAX_VALUE, 96_000, Long.MAX_VALUE))
    }

    @Test fun savedTrimPreviewMatchesDirectWavAndEveryConversionRouteAtFractionalFrames() {
        val layout = WavPcmLayout(44_100, 1, PcmSampleFormat.PCM_16, 0L, 88_200L)
        for (format in ExportFormat.entries) for (name in listOf("source.wav", "source.FLAC")) {
            for (detectedFlac in listOf(false, true)) {
                val rounding = recordingTrimFrameRounding(format, name, detectedFlac)
                val preview = previewFrameRange(6, 66, layout.sampleRate, layout.frameCount, rounding)
                val nearest = format == ExportFormat.WAV && name == "source.wav" && !detectedFlac
                val expected = if (nearest) PcmPreviewFrameRange(265, 2_911) else PcmPreviewFrameRange(264, 2_910)
                assertEquals("$format/$name/flac=$detectedFlac", expected, preview)
                if (!nearest) {
                    withAdmittedRecordingConversion(layout, format, 6L, 66L) { saved ->
                        assertEquals(PcmPreviewFrameRange(saved.firstFrame, saved.firstFrame + saved.frameCount), preview)
                    }
                }
                val output = ByteArrayOutputStream()
                streamBoundedPcmFrames(preview, 2, 333, {}, { first, count ->
                    ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
                        repeat(count) { putShort((first + it).toShort()) }
                    }.array()
                }, { bytes, offset, count -> output.write(bytes, offset, count); count })
                val pcm = ByteBuffer.wrap(output.toByteArray()).order(ByteOrder.LITTLE_ENDIAN)
                assertEquals(expected.frameCount.toInt() * 2, pcm.remaining())
                assertEquals(expected.first.toShort(), pcm.getShort(0))
                assertEquals((expected.endExclusive - 1L).toShort(), pcm.getShort(pcm.limit() - 2))
            }
        }
    }

    @Test fun wholeRecordingShuttleRetainsFractionalMillisecondTailAndSingleFrameSources() {
        for (rounding in RecordingFrameRounding.entries) {
            for (rate in listOf(44_100, 48_000, 96_000)) for (frames in listOf(1L, 400L, 48_001L)) {
                val bounds = recordingShuttleFrameRange(0.0, frames.toDouble() / rate, rate, frames, rounding)
                assertEquals(PcmPreviewFrameRange(0L, frames), bounds)
            }
        }
        val file = Files.createTempFile("whole-shuttle-tail-", ".pcm").toFile()
        try {
            val bytes = ByteBuffer.allocate(800).order(ByteOrder.LITTLE_ENDIAN).apply {
                repeat(400) { putShort((if (it >= 384) 16_000 else 0).toShort()) }
            }.array()
            file.writeBytes(bytes)
            RandomAccessFile(file, "r").use { input ->
                val bounds = recordingShuttleFrameRange(0.0, 400.0 / 48_000, 48_000, 400L, RecordingFrameRounding.NEAREST)
                val output = readWavPcm16Range(input.channel,
                    WavPcmLayout(48_000, 1, PcmSampleFormat.PCM_16, 0L, bytes.size.toLong()),
                    0.0, 400.0 / 48_000, 48_000, bounds)
                assertEquals(800, output.size)
                assertTrue(ByteBuffer.wrap(output).order(ByteOrder.LITTLE_ENDIAN).getShort(798) > 15_000)
            }
        } finally { file.delete() }
    }

    @Test fun trimShuttleUsesTheSameSavePolicyAsPlaybackWithoutWideningToWholeSource() {
        for (format in ExportFormat.entries) {
            val rounding = recordingTrimFrameRounding(format, "source.wav", false)
            assertEquals(previewFrameRange(6, 66, 44_100, 44_100L, rounding),
                recordingShuttleFrameRange(.006, .066, 44_100, 44_100L, rounding))
        }
        assertEquals(RecordingFrameRounding.NEAREST, recordingTrimFrameRounding(ExportFormat.WAV, "source.wav", false))
        assertEquals(RecordingFrameRounding.FLOOR, recordingTrimFrameRounding(ExportFormat.FLAC, "source.wav", false))
        assertEquals(PcmPreviewFrameRange(48, 400),
            recordingShuttleFrameRange(.001, 400.0 / 48_000, 48_000, 400,
                RecordingFrameRounding.NEAREST))
    }

    @Test fun wavShuttleFilterCannotMixExcludedPcmAcrossEitherSelectedBoundary() {
        val file = Files.createTempFile("bounded-shuttle-", ".pcm").toFile()
        try {
            val source = ByteBuffer.allocate(400 * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
                repeat(400) { putShort((if (it in 100 until 300) 0 else 30_000).toShort()) }
            }.array()
            file.writeBytes(source)
            RandomAccessFile(file, "r").use { input ->
                val layout = WavPcmLayout(48_000, 1, PcmSampleFormat.PCM_16, 0, source.size.toLong())
                for (targetRate in listOf(24_000, 96_000)) {
                    val bounded = readWavPcm16Range(input.channel, layout, 100.0 / 48_000, 300.0 / 48_000,
                        targetRate, PcmPreviewFrameRange(100, 300))
                    assertTrue(bounded.isNotEmpty())
                    assertTrue("Excluded neighboring source influenced selected PCM", bounded.all { it == 0.toByte() })
                }
            }
        } finally { file.delete() }
    }

    @Test fun timelinePreviewHardBoundaryPreservesDefaultExportContext() {
        val root = Files.createTempDirectory("bounded-timeline-").toFile()
        try {
            PersistentAudioChunkStore(root).use { store ->
                store.configure(RetentionMode.SIZE, 4096, 12_000, 1, PcmSampleFormat.PCM_FLOAT)
                val source = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
                    .putFloat(0f).putFloat(0f).putFloat(1f).putFloat(1f).array()
                assertEquals(source.size, store.append(source, 0, source.size))
                requireNotNull(store.acquireRange(0.0, 2.0 / 12_000)).use { selection ->
                    fun read(context: Boolean): ByteArray {
                        val output = ByteArrayOutputStream()
                        selection.readNormalized(24_000, 1, PcmSampleFormat.PCM_FLOAT, Long.MAX_VALUE, 8,
                            allowOutsideRangeContext = context) { bytes, offset, count ->
                            output.write(bytes, offset, count); count
                        }
                        return output.toByteArray()
                    }
                    assertTrue(read(false).all { it == 0.toByte() })
                    assertFalse("Durable export's existing edge context changed", read(true).all { it == 0.toByte() })
                }
            }
        } finally { root.deleteRecursively() }
    }
}
