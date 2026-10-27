package app.smallthingz.reverb

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistentAudioSubRangeTest {
    @Test
    fun mixedSnapshotKeepsStereoWhileMonoChildrenRetainTheirOwnChannelLayout() {
        val parent = File(System.getProperty("reverb.channelFixtureRoot", "build/tmp/subrange-channel-tests"))
            .apply { check(mkdirs() || isDirectory) }
        val root = Files.createTempDirectory(parent.toPath(), "channel-layout-").toFile()
        fun pcm(frames: Int, channels: Int, left: Int, right: Int = left): ByteArray =
            ByteArray(frames * channels * 2).also { output ->
                for (frame in 0 until frames) for (channel in 0 until channels) {
                    val value = if (channel == 0) left else right
                    val offset = (frame * channels + channel) * 2
                    output[offset] = value.toByte()
                    output[offset + 1] = (value shr 8).toByte()
                }
            }
        fun read(lease: PersistentAudioChunkStore.RangeLease): ByteArray {
            val output = ByteArrayOutputStream()
            val result = lease.readNormalized(8000, lease.maximumChannelCount(), PcmSampleFormat.PCM_16) {
                bytes, offset, count ->
                output.write(bytes, offset, count)
                count
            }
            assertEquals(output.size().toLong(), result.sampleBytes)
            return output.toByteArray()
        }
        try {
            PersistentAudioChunkStore(root, overwriteOldest = false, directorySync = {}).use { store ->
                store.configure(RetentionMode.SIZE, 1_048_576, 8000, 1, PcmSampleFormat.PCM_16)
                val mono = pcm(8000, 1, 2000)
                assertEquals(mono.size, store.append(mono, 0, mono.size))
                requireNotNull(store.acquireRange(0.0, 1.0)).use { frozenMono ->
                    store.configure(RetentionMode.SIZE, 1_048_576, 8000, 2, PcmSampleFormat.PCM_16)
                    val stereo = pcm(8000, 2, 12000, -12000)
                    assertEquals(stereo.size, store.append(stereo, 0, stereo.size))
                    assertEquals(1, frozenMono.maximumChannelCount())
                    assertArrayEquals(mono, read(frozenMono))
                    requireNotNull(store.acquireRange(0.0, 2.0)).use { snapshot ->
                        assertEquals(2, snapshot.maximumChannelCount())
                        requireNotNull(snapshot.acquireSubRange(0.25, 0.75)).use { child ->
                            assertEquals(1, child.maximumChannelCount())
                            assertArrayEquals(pcm(4000, 1, 2000), read(child))
                        }
                        requireNotNull(snapshot.acquireSubRange(1.25, 1.75)).use { child ->
                            assertEquals(2, child.maximumChannelCount())
                            assertArrayEquals(pcm(4000, 2, 12000, -12000), read(child))
                        }
                        requireNotNull(snapshot.acquireSubRange(0.5, 1.5)).use { child ->
                            assertEquals(2, child.maximumChannelCount())
                            assertArrayEquals(pcm(4000, 2, 2000) + pcm(4000, 2, 12000, -12000), read(child))
                        }
                    }
                }
            }
        } finally {
            // Keep synthetic backing chunks for diagnosis even when an assertion fails.
            println("CHANNEL_FIXTURE_RETAINED ${root.canonicalPath}")
        }
    }

    @Test
    fun channelLayoutMetadataIsCachedButStillRejectsClosedLeases() {
        val source = metadataSegments(4096)
        val segments = CountingSegments(source)
        PersistentAudioChunkStore(File("build/tmp/subrange-unused"), directorySync = {}).use { store ->
            val lease = newLease(store, segments)
            try {
                segments.inspections = 0
                assertEquals(1, lease.maximumChannelCount())
                assertTrue(segments.inspections >= source.size)
                segments.inspections = 0
                repeat(8) { assertEquals(1, lease.maximumChannelCount()) }
                assertEquals(0, segments.inspections)
                lease.close()
                assertThrows(IllegalStateException::class.java) { lease.maximumChannelCount() }
            } finally {
                lease.close()
            }
            assertTrue(source.all { it.record.refCount == 0 })
        }
    }

    @Test
    fun storedMixedRateRangesPreserveFrameRoundingAtBoundariesAndInsideNestedRanges() = withRoot { root ->
        PersistentAudioChunkStore(root, overwriteOldest = false, directorySync = {}).use { store ->
            repeat(12) { index ->
                val rate = intArrayOf(8_000, 11_025, 44_100, 96_000)[index % 4]
                val format = if (index % 2 == 0) PcmSampleFormat.PCM_16 else PcmSampleFormat.PCM_FLOAT
                store.configure(RetentionMode.SIZE, 1_048_576, rate, 1, format)
                val bytes = ByteArray((41 + index) * format.bytesPerSample)
                assertEquals(bytes.size, store.append(bytes, 0, bytes.size))
                store.sealActiveChunk()
            }
            requireNotNull(store.acquireRange(0.3 / 8_000, store.durationSeconds() - 0.3 / 96_000)).use { parent ->
                assertBoundaryWindows(parent)
                requireNotNull(parent.acquireSubRange(
                    parent.durationSeconds * 0.17,
                    parent.durationSeconds * 0.91,
                )).use { child ->
                    assertBoundaryWindows(child)
                    requireNotNull(child.acquireSubRange(
                        child.durationSeconds * 0.11,
                        child.durationSeconds * 0.83,
                    )).use(::assertBoundaryWindows)
                }
            }
        }
    }

    @Test
    fun randomizedPartialAndNestedRangesMatchOriginalLinearArithmeticExactly() {
        val random = Random(0x512A)
        val segments = metadataSegments(73)
        PersistentAudioChunkStore(File("build/tmp/subrange-unused"), directorySync = {}).use { store ->
            newLease(store, segments).use { parent ->
                repeat(400) {
                    val start = (random.nextDouble() * 1.2 - 0.1) * parent.durationSeconds
                    val end = (random.nextDouble() * 1.2 - 0.1) * parent.durationSeconds
                    assertLegacySelection(parent, start, end)
                }
                repeat(30) {
                    val start = random.nextDouble() * parent.durationSeconds * 0.5
                    val end = (0.5 + random.nextDouble() * 0.5) * parent.durationSeconds
                    requireNotNull(parent.acquireSubRange(start, end)).use { child ->
                        assertBoundaryWindows(child)
                    }
                }
            }
            assertTrue(segments.all { it.record.refCount == 0 })
        }
    }

    @Test
    fun lateSmallRangeInspectsLogarithmicPrefixInsteadOfWholeHistory() {
        val source = metadataSegments(16_384)
        val segments = CountingSegments(source)
        PersistentAudioChunkStore(File("build/tmp/subrange-unused"), directorySync = {}).use { store ->
            newLease(store, segments).use { parent ->
                val last = source.last()
                val start = last.timelineStartSeconds + 0.2 * (last.timelineEndSeconds - last.timelineStartSeconds)
                val end = last.timelineStartSeconds + 0.8 * (last.timelineEndSeconds - last.timelineStartSeconds)
                val expected = legacySelection(segments, parent.durationSeconds, start, end)
                assertTrue("Legacy lookup must traverse the complete prefix", segments.inspections >= source.size)
                segments.inspections = 0
                requireNotNull(parent.acquireSubRange(start, end)).use { child ->
                    assertEquals(expected!!.segments, child.segmentsForTest())
                    assertTrue("Only binary lookup and one overlapping segment are needed: ${segments.inspections}",
                        segments.inspections <= 32)
                }
                assertTrue(source.all { it.record.refCount == 1 })
            }
            assertTrue(source.all { it.record.refCount == 0 })
        }
    }

    @Test
    fun childPinsOnlyItsSelectedRecordsAfterParentCloses() {
        val source = metadataSegments(9)
        PersistentAudioChunkStore(File("build/tmp/subrange-unused"), directorySync = {}).use { store ->
            val parent = newLease(store, source)
            val child = requireNotNull(parent.acquireSubRange(source[7].timelineStartSeconds, parent.durationSeconds))
            try {
                assertEquals(listOf(source[7].record.id, source[8].record.id), child.segmentsForTest().map { it.record.id })
                parent.close()
                assertEquals(List(7) { 0 } + listOf(1, 1), source.map { it.record.refCount })
                assertLegacySelection(child, 0.0, child.durationSeconds)
            } finally {
                child.close()
                parent.close()
            }
            assertTrue(source.all { it.record.refCount == 0 })
        }
    }

    @Test
    fun invalidAndEmptyWindowsDoNotAcquireOwnership() {
        val source = metadataSegments(2)
        PersistentAudioChunkStore(File("build/tmp/subrange-unused"), directorySync = {}).use { store ->
            newLease(store, source).use { parent ->
                listOf(
                    Double.NaN to 1.0,
                    0.0 to Double.POSITIVE_INFINITY,
                    0.0 to 0.0,
                    -2.0 to -1.0,
                    parent.durationSeconds to parent.durationSeconds + 1.0,
                    1.0 to 0.0,
                ).forEach { (start, end) ->
                    assertNull(parent.acquireSubRange(start, end))
                    assertTrue(source.all { it.record.refCount == 1 })
                }
            }
            // The internal constructor previously allowed an empty lease even with positive duration.
            store.RangeLease(store, emptyList(), 0, 0, 1.0).use {
                assertNull(it.acquireSubRange(0.0, 0.5))
            }
            assertTrue(source.all { it.record.refCount == 0 })
        }
    }

    private fun assertBoundaryWindows(parent: PersistentAudioChunkStore.RangeLease) {
        assertLegacySelection(parent, -1.0, parent.durationSeconds + 1.0)
        val segments = parent.segmentsForTest()
        for (segment in segments) {
            val boundary = segment.timelineStartSeconds
            val oneFrame = 1.0 / segment.record.sampleRate
            for (start in listOf(Math.nextDown(boundary), boundary, Math.nextUp(boundary))) {
                assertLegacySelection(parent, start, boundary + oneFrame * 0.7)
                assertLegacySelection(parent, boundary - oneFrame * 0.7, start)
            }
        }
        val end = parent.durationSeconds
        assertLegacySelection(parent, Math.nextDown(end), end)
        assertLegacySelection(parent, end, Math.nextUp(end))
    }

    private fun assertLegacySelection(
        parent: PersistentAudioChunkStore.RangeLease,
        start: Double,
        end: Double,
    ) {
        val source = parent.segmentsForTest()
        val refsBefore = source.map { it.record.refCount }
        val expected = legacySelection(source, parent.durationSeconds, start, end)
        val actual = parent.acquireSubRange(start, end)
        if (expected == null) {
            assertNull(actual)
        } else {
            requireNotNull(actual).use {
                assertEquals(expected.segments, it.segmentsForTest())
                assertEquals(expected.startedAtMillis, it.startedAtMillis)
                assertEquals(expected.endedAtMillis, it.endedAtMillis)
                assertEquals(expected.durationSeconds, it.durationSeconds, 0.0)
                for (segment in expected.segments) {
                    val sourceIndex = source.indexOfFirst { original -> original.record === segment.record }
                    assertEquals(refsBefore[sourceIndex] + 1, segment.record.refCount)
                }
            }
        }
        assertEquals(refsBefore, source.map { it.record.refCount })
    }

    private data class Selection(
        val segments: List<PersistentAudioChunkStore.Segment>,
        val startedAtMillis: Long,
        val endedAtMillis: Long,
        val durationSeconds: Double,
    )

    // Keep the pre-optimization cursor walk as an independent rounding/ownership oracle.
    private fun legacySelection(
        segments: List<PersistentAudioChunkStore.Segment>,
        duration: Double,
        startOffset: Double,
        endOffset: Double,
    ): Selection? {
        if (!startOffset.isFinite() || !endOffset.isFinite()) return null
        val start = startOffset.coerceIn(0.0, duration)
        val end = endOffset.coerceIn(start, duration)
        if (end <= start) return null
        val selected = ArrayList<PersistentAudioChunkStore.Segment>()
        var cursor = 0.0
        var startedAtMillis = 0L
        var endedAtMillis = 0L
        var selectedDuration = 0.0
        for (segment in segments) {
            val rate = segment.record.sampleRate
            if (rate <= 0 || segment.frameCount <= 0L) continue
            val segmentStart = cursor
            val segmentEnd = cursor + segment.frameCount.toDouble() / rate.toDouble()
            cursor = segmentEnd
            if (end <= segmentStart) break
            if (start >= segmentEnd) continue
            val localStart = maxOf(start, segmentStart) - segmentStart
            val localEnd = minOf(end, segmentEnd) - segmentStart
            val firstFrame = floor(localStart * rate).toLong().coerceIn(0, segment.frameCount)
            val lastFrame = ceil(localEnd * rate).toLong().coerceIn(firstFrame, segment.frameCount)
            val childFrames = lastFrame - firstFrame
            if (childFrames <= 0) continue
            val absoluteStart = segment.startFrame + firstFrame
            val childDuration = childFrames.toDouble() / rate.toDouble()
            selected += segment.copy(
                startFrame = absoluteStart,
                frameCount = childFrames,
                timelineStartSeconds = selectedDuration,
                timelineEndSeconds = selectedDuration + childDuration,
            )
            if (startedAtMillis == 0L) {
                startedAtMillis = segment.record.createdAtMillis + absoluteStart * 1000L / rate
            }
            endedAtMillis = segment.record.createdAtMillis + (absoluteStart + childFrames) * 1000L / rate
            selectedDuration += childDuration
        }
        return if (selected.isEmpty()) null else Selection(selected, startedAtMillis, endedAtMillis, selectedDuration)
    }

    private fun metadataSegments(count: Int): List<PersistentAudioChunkStore.Segment> {
        var cursor = 0.0
        return List(count) { index ->
            val rate = intArrayOf(8_000, 11_025, 44_100, 96_000)[index % 4]
            val startFrame = (index % 7).toLong()
            val frames = 17L + index % 101
            val format = if (index % 2 == 0) PcmSampleFormat.PCM_16 else PcmSampleFormat.PCM_FLOAT
            val record = PersistentAudioChunkStore.ChunkRecord(
                id = index.toUInt(),
                file = File("unused-$index"),
                state = PersistentAudioChunkStore.ChunkState.FINALIZED,
                createdAtMillis = 1_700_000_000_000L + index * 1000L,
                payloadBytes = (frames + startFrame) * format.bytesPerSample,
                sampleFrames = frames + startFrame,
                sampleRate = rate,
                channelCount = 1,
                sampleFormat = format,
                payloadChecksum = index,
                headerGeneration = 1,
                payloadOffsetBytes = 128,
                refCount = 1,
            )
            val end = cursor + frames.toDouble() / rate.toDouble()
            PersistentAudioChunkStore.Segment(
                record, startFrame, frames, 128, record.payloadBytes, record.payloadChecksum, cursor, end,
            ).also { cursor = end }
        }
    }

    private fun newLease(
        store: PersistentAudioChunkStore,
        segments: List<PersistentAudioChunkStore.Segment>,
    ): PersistentAudioChunkStore.RangeLease = store.RangeLease(
        store, segments, segments.first().record.createdAtMillis,
        segments.last().record.createdAtMillis, segments.last().timelineEndSeconds,
    )

    private class CountingSegments(
        private val source: List<PersistentAudioChunkStore.Segment>,
    ) : AbstractList<PersistentAudioChunkStore.Segment>() {
        var inspections = 0
        override val size: Int get() = source.size
        override fun get(index: Int): PersistentAudioChunkStore.Segment {
            inspections++
            return source[index]
        }
    }

    private fun PersistentAudioChunkStore.RangeLease.segmentsForTest(): List<PersistentAudioChunkStore.Segment> {
        val field = javaClass.getDeclaredField("segments").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        return field.get(this) as List<PersistentAudioChunkStore.Segment>
    }

    private fun withRoot(block: (File) -> Unit) {
        val parent = File("build/tmp/subrange-tests").apply { mkdirs() }
        val root = Files.createTempDirectory(parent.toPath(), "store-").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }
}
