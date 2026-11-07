package app.smallthingz.reverb

import java.io.File
import java.io.IOException
import java.util.Random
import org.junit.Assert.*
import org.junit.Test

class DurablePcmPinTraversalTest {
    @Test
    fun orderedTraversalMatchesPreviousExpressionAcrossDuplicatesAndContext() {
        val random = Random(98321L)
        repeat(64) {
            val records = List(17) { record(it) }
            fun segments() = List(random.nextInt(50)) { segment(records[random.nextInt(records.size)]) }
            val selected = listOf(segment(records[4])) + segments()
            val range = FrozenPcmRange(selected, segments(), segments(), 1, 2, 1.0)
            val previous = (range.segments + range.leadingContext + range.trailingContext)
                .map { it.record }.distinctBy { it.file.absolutePath }
            val current = DurablePcmPins.sourceRecords(range)
            assertEquals(previous.size, current.size)
            previous.indices.forEach { index -> assertSame(previous[index], current[index]) }
        }
    }

    @Test
    fun duplicatePathsKeepFirstRecordEvenWhenMetadataAndFileSpellingDiffer() {
        val first = record(1)
        val duplicate = first.copy(file = first.file.absoluteFile, payloadChecksum = 99)
        val other = record(2)
        val range = FrozenPcmRange(listOf(segment(first)), listOf(segment(duplicate), segment(other)),
            listOf(segment(first), segment(other.copy(payloadChecksum = 100))), 1, 2, 1.0)
        val result = DurablePcmPins.sourceRecords(range)
        assertEquals(2, result.size)
        assertSame(first, result[0])
        assertSame(other, result[1])
    }

    @Test
    fun capCountsUniqueObjectsAndRejectsTheFirstExcessWithoutFilesystemWork() {
        val records = List(DurablePcmPins.MAX_OBJECTS) { record(it) }
        val selected = records.map(::segment)
        val duplicateContext = listOf(segment(records.first()), segment(records.last()))
        val range = FrozenPcmRange(selected, duplicateContext, duplicateContext, 1, 2, 1.0)
        assertEquals(DurablePcmPins.MAX_OBJECTS, DurablePcmPins.sourceRecords(range).size)
        assertThrows(IOException::class.java) {
            DurablePcmPins.sourceRecords(range.copy(trailingContext = duplicateContext + segment(record(records.size))))
        }
        assertThrows(IOException::class.java) {
            DurablePcmPins.sourceRecords(range.copy(segments = emptyList(), leadingContext = emptyList(), trailingContext = emptyList()))
        }
    }

    private fun record(id: Int) = PersistentAudioChunkStore.ChunkRecord(id.toUInt(),
        File("build/tmp/traversal/source/chunks/$id"), PersistentAudioChunkStore.ChunkState.FINALIZED,
        1L, 2L, 1L, 16_000, 1, PcmSampleFormat.PCM_16, 0, 1L, 16L)
    private fun segment(record: PersistentAudioChunkStore.ChunkRecord) = PersistentAudioChunkStore.Segment(
        record, 0L, 1L, 16L, 2L, 0, 0.0, 1.0 / 16_000, timelineStartFrame = 0L)
}
