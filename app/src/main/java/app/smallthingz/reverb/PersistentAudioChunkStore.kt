package app.smallthingz.reverb

import android.content.Context
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.ArrayDeque
import java.util.zip.CRC32
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToLong

internal fun oneShotWritableBytes(
    retentionMode: RetentionMode,
    retentionValue: Long,
    retainedPayloadBytes: Long,
    retainedDurationSeconds: Double,
    sampleRate: Int,
    frameBytes: Int,
): Long {
    if (retentionValue <= 0L || sampleRate <= 0 || frameBytes <= 0) return 0L
    val remaining = when (retentionMode) {
        RetentionMode.SIZE -> (retentionValue - retainedPayloadBytes).coerceAtLeast(0L)
        RetentionMode.TIME -> {
            val remainingSeconds = (retentionValue.toDouble() - retainedDurationSeconds).coerceAtLeast(0.0)
            val remainingFrames = floor(remainingSeconds * sampleRate.toDouble()).toLong()
            if (remainingFrames > Long.MAX_VALUE / frameBytes.toLong()) Long.MAX_VALUE
            else remainingFrames * frameBytes.toLong()
        }
    }
    return remaining - remaining % frameBytes.toLong()
}

/**
 * Disk-backed append-only PCM timeline.
 *
 * Completed chunks are immutable. The only mutable audio file is the newest ACTIVE chunk.
 * Chronology lives in [chunks], not in numeric filename ordering, so UInt32 wrap is harmless.
 */
internal class PersistentAudioChunkStore(
    context: Context,
    cacheFolderName: String = ReverbConfig.BUFFER_CACHE_FOLDER_NAME,
    legacyCacheFolderName: String? = ReverbConfig.LEGACY_BUFFER_CACHE_FOLDER_NAME,
    private val overwriteOldest: Boolean = true,
) : Closeable {
    private val rootDirectory = File(context.noBackupFilesDir, cacheFolderName)
    private val chunksDirectory = File(rootDirectory, ReverbConfig.BUFFER_CHUNKS_FOLDER_NAME)
    private val indexA = File(rootDirectory, ReverbConfig.BUFFER_INDEX_A_FILE_NAME)
    private val indexB = File(rootDirectory, ReverbConfig.BUFFER_INDEX_B_FILE_NAME)
    private val legacyDirectory = legacyCacheFolderName?.let { File(context.noBackupFilesDir, it) }

    private val chunks = ArrayDeque<ChunkRecord>()
    private val liveChunkIds = HashSet<UInt>()
    private val retiredById = HashMap<UInt, ChunkRecord>()

    private var loaded = false
    private var closed = false
    private var indexGeneration = 0L
    private var nextChunkId = 0u
    private var retainedPayloadBytes = 0L
    private var retainedDurationSeconds = 0.0

    private var retentionMode = RetentionMode.SIZE
    private var retentionValue = Long.MAX_VALUE
    private var configuredSampleRate = 0
    private var configuredChannelCount = 0
    private var configuredSampleFormat = PcmSampleFormat.PCM_16

    private var activeRecord: ChunkRecord? = null
    private var activeAccess: RandomAccessFile? = null
    private var activePayloadCrc = CRC32()
    private var lastWriteAtMillis = 0L

    data class Snapshot(
        val filledBytes: Long,
        val durationSeconds: Double,
        val chunkCount: Int,
        val currentSampleRate: Int,
        val currentChannelCount: Int,
        val currentSampleFormat: PcmSampleFormat,
        val lastWriteAtMillis: Long,
    )

    data class ReadResult(
        val sampleBytes: Long,
        val durationSeconds: Double,
    )

    fun interface Consumer {
        fun consume(array: ByteArray, offset: Int, count: Int): Int
    }

    @Synchronized
    fun configure(
        requestedRetentionMode: RetentionMode,
        requestedRetentionValue: Long,
        requestedSampleRate: Int,
        requestedChannelCount: Int,
        sampleFormat: PcmSampleFormat = PcmSampleFormat.PCM_16,
    ) {
        ensureLoadedLocked()

        val normalizedRetention = requestedRetentionValue.coerceAtLeast(0L)
        val validFormat = requestedSampleRate > 0 && requestedChannelCount in 1..MAX_CHANNEL_COUNT
        val normalizedSampleRate = if (validFormat) requestedSampleRate else 0
        val normalizedChannelCount = if (validFormat) requestedChannelCount else 0
        val formatChanged =
            configuredSampleRate != normalizedSampleRate ||
                configuredChannelCount != normalizedChannelCount ||
                configuredSampleFormat != sampleFormat

        if (formatChanged) {
            finalizeActiveLocked()
        }

        retentionMode = requestedRetentionMode
        retentionValue = normalizedRetention
        configuredSampleRate = normalizedSampleRate
        configuredChannelCount = normalizedChannelCount
        configuredSampleFormat = sampleFormat

        if (overwriteOldest && activeRecord != null && retentionExceededLocked()) {
            finalizeActiveLocked()
        }
        val cleaned = overwriteOldest && cleanupRetentionLocked()
        if (cleaned || formatChanged) {
            writeIndexLocked()
        }
    }

    @Synchronized
    fun append(
        array: ByteArray,
        offset: Int,
        count: Int,
    ): Int {
        require(offset >= 0 && count >= 0 && offset <= array.size - count) {
            "Invalid PCM range offset=$offset count=$count size=${array.size}"
        }
        if (count == 0) return 0
        ensureLoadedLocked()

        val frameBytes = configuredFrameBytesLocked()
        if (frameBytes <= 0 || retentionValue <= 0L) return 0
        require(count % frameBytes == 0) {
            "PCM append must be frame aligned: count=$count frameBytes=$frameBytes"
        }

        var sourceOffset = offset
        var remaining = count
        while (remaining > 0) {
            val storeAvailable = writableBytesLocked(frameBytes)
            if (storeAvailable <= 0L) break

            var record = activeRecord
            if (record == null) {
                record = createActiveChunkLocked()
                if (record == null) break
            }

            val limit = chunkPayloadLimitLocked(frameBytes)
            if (limit <= 0L) break
            val available = limit - record.payloadBytes
            if (available <= 0L) {
                finalizeActiveLocked()
                cleanupRetentionLocked()
                continue
            }

            val writeCount = minOf(remaining.toLong(), available, storeAvailable).toInt()
            val alignedWriteCount = writeCount - writeCount % frameBytes
            if (alignedWriteCount <= 0) {
                finalizeActiveLocked()
                cleanupRetentionLocked()
                continue
            }

            val access = requireNotNull(activeAccess)
            access.write(array, sourceOffset, alignedWriteCount)
            activePayloadCrc.update(array, sourceOffset, alignedWriteCount)

            record.payloadBytes += alignedWriteCount.toLong()
            val writtenFrames = alignedWriteCount.toLong() / frameBytes
            record.sampleFrames += writtenFrames
            record.payloadChecksum = activePayloadCrc.value.toInt()
            retainedPayloadBytes = safeAdd(retainedPayloadBytes, alignedWriteCount.toLong())
            retainedDurationSeconds += writtenFrames.toDouble() / record.sampleRate.toDouble()
            sourceOffset += alignedWriteCount
            remaining -= alignedWriteCount
            lastWriteAtMillis = System.currentTimeMillis()

            // Keep the logical timeline inside its configured retention window as data
            // arrives. This is still O(1) in the common case and only performs a delete
            // when an old chunk actually expires.
            if (overwriteOldest) cleanupRetentionLocked()

            if (record.payloadBytes >= limit) {
                finalizeActiveLocked()
                if (overwriteOldest) cleanupRetentionLocked()
            }
        }
        return count - remaining
    }

    @Synchronized
    fun peekSnapshot(): Snapshot? {
        ensureLoadedLocked()
        if (chunks.isEmpty()) return null
        return Snapshot(
            filledBytes = totalPayloadBytesLocked(),
            durationSeconds = totalDurationSecondsLocked(),
            chunkCount = chunks.size,
            currentSampleRate = configuredSampleRate,
            currentChannelCount = configuredChannelCount,
            currentSampleFormat = configuredSampleFormat,
            lastWriteAtMillis = lastWriteAtMillis,
        )
    }

    @Synchronized
    fun hasData(): Boolean {
        ensureLoadedLocked()
        return retainedPayloadBytes > 0L
    }

    @Synchronized
    fun countFilledBytes(): Long {
        ensureLoadedLocked()
        return totalPayloadBytesLocked()
    }

    @Synchronized
    fun durationSeconds(): Double {
        ensureLoadedLocked()
        return totalDurationSecondsLocked()
    }

    /**
     * Acquires a chronological range where offsets are measured from the oldest retained sample.
     * Referenced chunks cannot be physically deleted until the lease closes.
     */
    @Synchronized
    fun acquireRange(
        startOffsetSeconds: Double,
        endOffsetSeconds: Double,
    ): RangeLease? {
        ensureLoadedLocked()
        if (!startOffsetSeconds.isFinite() || !endOffsetSeconds.isFinite()) return null
        val totalDuration = totalDurationSecondsLocked()
        if (totalDuration <= 0.0) return null

        val start = startOffsetSeconds.coerceIn(0.0, totalDuration)
        val end = endOffsetSeconds.coerceIn(start, totalDuration)
        if (end <= start) return null

        val segments = ArrayList<Segment>()
        var cursor = 0.0
        var startedAtMillis = 0L
        var endedAtMillis = 0L
        var leaseDuration = 0.0

        for (record in chunks) {
            if (record.sampleFrames <= 0L || record.sampleRate <= 0) continue
            val chunkDuration = record.sampleFrames.toDouble() / record.sampleRate.toDouble()
            val chunkStart = cursor
            val chunkEnd = cursor + chunkDuration
            cursor = chunkEnd
            if (end <= chunkStart) break
            if (start >= chunkEnd) continue

            val overlapStart = maxOf(start, chunkStart)
            val overlapEnd = minOf(end, chunkEnd)
            val localStartSeconds = overlapStart - chunkStart
            val localEndSeconds = overlapEnd - chunkStart
            val startFrame = floor(localStartSeconds * record.sampleRate).toLong()
                .coerceIn(0L, record.sampleFrames)
            val endFrame = ceil(localEndSeconds * record.sampleRate).toLong()
                .coerceIn(startFrame, record.sampleFrames)
            val frameCount = endFrame - startFrame
            if (frameCount <= 0L) continue

            record.refCount++
            segments += Segment(
                record = record,
                startFrame = startFrame,
                frameCount = frameCount,
                payloadOffsetBytes = record.payloadOffsetBytes,
                payloadBytesAtAcquire = record.payloadBytes,
                payloadChecksumAtAcquire = record.payloadChecksum,
            )
            if (startedAtMillis == 0L) {
                startedAtMillis = record.createdAtMillis +
                    (startFrame * 1000L / record.sampleRate.coerceAtLeast(1))
            }
            endedAtMillis = record.createdAtMillis +
                (endFrame * 1000L / record.sampleRate.coerceAtLeast(1))
            leaseDuration += frameCount.toDouble() / record.sampleRate.toDouble()
        }

        if (segments.isEmpty()) return null
        return RangeLease(
            store = this,
            segments = segments,
            startedAtMillis = startedAtMillis,
            endedAtMillis = endedAtMillis,
            durationSeconds = leaseDuration,
        )
    }

    @Synchronized
    fun checkpoint() {
        ensureLoadedLocked()
        writeActiveHeaderLocked()
        retryRetiredDeletesLocked()
        writeIndexLocked()
    }

    /**
     * Ends the current capture span without discarding history. The next append creates
     * a fresh chunk with a fresh wall-clock timestamp even when the PCM format is unchanged.
     */
    @Synchronized
    fun sealActiveChunk() {
        ensureLoadedLocked()
        if (activeRecord == null) return
        finalizeActiveLocked()
        cleanupRetentionLocked()
        retryRetiredDeletesLocked()
        writeIndexLocked()
    }

    @Synchronized
    fun clear() {
        ensureLoadedLocked()
        closeActiveAccessLocked()
        activeRecord = null
        activePayloadCrc = CRC32()

        while (chunks.isNotEmpty()) {
            retireRecordLocked(removeFirstChunkLocked())
        }
        lastWriteAtMillis = 0L
        writeIndexLocked()
    }

    @Synchronized
    override fun close() {
        if (closed) return
        if (loaded) {
            runCatching { writeActiveHeaderLocked() }
            runCatching { retryRetiredDeletesLocked() }
            runCatching { writeIndexLocked() }
            closeActiveAccessLocked()
        }
        closed = true
    }

    internal inner class RangeLease internal constructor(
        private val store: PersistentAudioChunkStore,
        private val segments: List<Segment>,
        val startedAtMillis: Long,
        val endedAtMillis: Long,
        val durationSeconds: Double,
    ) : Closeable {
        private var closedLease = false

        @Synchronized
        fun acquireSubRange(
            startOffsetSeconds: Double,
            endOffsetSeconds: Double,
        ): RangeLease? {
            check(!closedLease) { "RangeLease is closed" }
            if (!startOffsetSeconds.isFinite() || !endOffsetSeconds.isFinite()) return null
            val start = startOffsetSeconds.coerceIn(0.0, durationSeconds)
            val end = endOffsetSeconds.coerceIn(start, durationSeconds)
            if (end <= start) return null

            synchronized(store) {
                val selected = ArrayList<Segment>()
                var cursor = 0.0
                var selectedStartedAtMillis = 0L
                var selectedEndedAtMillis = 0L
                var selectedDuration = 0.0
                for (segment in segments) {
                    val rate = segment.record.sampleRate
                    if (rate <= 0 || segment.frameCount <= 0L) continue
                    val segmentDuration = segment.frameCount.toDouble() / rate.toDouble()
                    val segmentStart = cursor
                    val segmentEnd = cursor + segmentDuration
                    cursor = segmentEnd
                    if (end <= segmentStart) break
                    if (start >= segmentEnd) continue

                    val localStart = maxOf(start, segmentStart) - segmentStart
                    val localEnd = minOf(end, segmentEnd) - segmentStart
                    val firstFrame = floor(localStart * rate).toLong().coerceIn(0L, segment.frameCount)
                    val lastFrame = ceil(localEnd * rate).toLong().coerceIn(firstFrame, segment.frameCount)
                    val childFrames = lastFrame - firstFrame
                    if (childFrames <= 0L) continue

                    segment.record.refCount++
                    val absoluteStartFrame = segment.startFrame + firstFrame
                    selected += segment.copy(
                        startFrame = absoluteStartFrame,
                        frameCount = childFrames,
                    )
                    if (selectedStartedAtMillis == 0L) {
                        selectedStartedAtMillis = segment.record.createdAtMillis +
                            (absoluteStartFrame * 1000L / rate)
                    }
                    selectedEndedAtMillis = segment.record.createdAtMillis +
                        ((absoluteStartFrame + childFrames) * 1000L / rate)
                    selectedDuration += childFrames.toDouble() / rate.toDouble()
                }
                if (selected.isEmpty()) return null
                return RangeLease(
                    store = store,
                    segments = selected,
                    startedAtMillis = selectedStartedAtMillis,
                    endedAtMillis = selectedEndedAtMillis,
                    durationSeconds = selectedDuration,
                )
            }
        }

        @Synchronized
        fun readNormalized(
            targetSampleRate: Int,
            targetChannelCount: Int,
            targetSampleFormat: PcmSampleFormat,
            consumer: Consumer,
        ): ReadResult {
            check(!closedLease) { "RangeLease is closed" }
            require(targetSampleRate > 0 && targetChannelCount in 1..MAX_CHANNEL_COUNT)

            var totalOutputBytes = 0L
            var cumulativeDuration = 0.0
            var cumulativeTargetFrames = 0L
            for (segment in segments) {
                val segmentDuration = segment.frameCount.toDouble() / segment.record.sampleRate.toDouble()
                cumulativeDuration += segmentDuration
                val targetFramesAtEnd = (cumulativeDuration * targetSampleRate.toDouble()).roundToLong()
                val targetFrames = (targetFramesAtEnd - cumulativeTargetFrames).coerceAtLeast(0L)
                cumulativeTargetFrames = targetFramesAtEnd
                if (targetFrames <= 0L) continue

                totalOutputBytes += PcmNormalizer.normalizeSegment(
                    file = segment.record.file,
                    payloadDataOffset = segment.payloadOffsetBytes,
                    payloadBytes = segment.payloadBytesAtAcquire,
                    expectedPayloadChecksum = segment.payloadChecksumAtAcquire,
                    payloadByteOffset = segment.startFrame * segment.record.frameBytes.toLong(),
                    sourceFrameCount = segment.frameCount,
                    sourceSampleRate = segment.record.sampleRate,
                    sourceChannelCount = segment.record.channelCount,
                    sourceSampleFormat = segment.record.sampleFormat,
                    targetFrameCount = targetFrames,
                    targetChannelCount = targetChannelCount,
                    targetSampleFormat = targetSampleFormat,
                    consumer = consumer,
                )
            }
            return ReadResult(totalOutputBytes, cumulativeDuration)
        }

        @Synchronized
        override fun close() {
            synchronized(store) {
                if (closedLease) return
                closedLease = true
                for (segment in segments) {
                    store.releaseRecordLocked(segment.record)
                }
            }
        }
    }

    internal data class Segment(
        val record: ChunkRecord,
        val startFrame: Long,
        val frameCount: Long,
        val payloadOffsetBytes: Long,
        val payloadBytesAtAcquire: Long,
        val payloadChecksumAtAcquire: Int,
    )

    internal data class ChunkRecord(
        val id: UInt,
        val file: File,
        var state: ChunkState,
        val createdAtMillis: Long,
        var payloadBytes: Long,
        var sampleFrames: Long,
        val sampleRate: Int,
        val channelCount: Int,
        val sampleFormat: PcmSampleFormat,
        var payloadChecksum: Int,
        var headerGeneration: Long,
        val payloadOffsetBytes: Long,
        var refCount: Int = 0,
        var pendingDelete: Boolean = false,
    ) {
        val frameBytes: Int
            get() = channelCount * sampleFormat.bytesPerSample

        val durationSeconds: Double
            get() = if (sampleRate > 0) sampleFrames.toDouble() / sampleRate.toDouble() else 0.0
    }

    internal enum class ChunkState(val code: Int) {
        ACTIVE(1),
        FINALIZED(2),
        ;

        companion object {
            fun fromCode(code: Int): ChunkState? = entries.firstOrNull { it.code == code }
        }
    }

    private data class LoadedIndex(
        val generation: Long,
        val nextChunkId: UInt,
        val records: List<IndexRecord>,
    )

    private data class IndexRecord(
        val id: UInt,
        val state: ChunkState,
        val createdAtMillis: Long,
        val payloadBytes: Long,
        val sampleFrames: Long,
        val sampleRate: Int,
        val channelCount: Int,
        val sampleFormat: PcmSampleFormat,
        val payloadChecksum: Int,
    )

    private data class ParsedHeader(
        val id: UInt,
        val state: ChunkState,
        val generation: Long,
        val createdAtMillis: Long,
        val payloadBytes: Long,
        val sampleFrames: Long,
        val sampleRate: Int,
        val channelCount: Int,
        val sampleFormat: PcmSampleFormat,
        val payloadChecksum: Int,
        val payloadOffsetBytes: Long,
    )

    private data class ParsedImmutableHeader(
        val id: UInt,
        val createdAtMillis: Long,
        val sampleRate: Int,
        val channelCount: Int,
        val sampleFormat: PcmSampleFormat,
    )

    private data class ParsedMutableSlot(
        val generation: Long,
        val state: ChunkState,
        val payloadChecksum: Int,
        val payloadBytes: Long,
        val sampleFrames: Long,
    )

    private fun ensureLoadedLocked() {
        check(!closed) { "PersistentAudioChunkStore is closed" }
        if (loaded) return

        if (!rootDirectory.exists() && !rootDirectory.mkdirs() && !rootDirectory.exists()) {
            throw IllegalStateException("Unable to create chunk storage: ${rootDirectory.absolutePath}")
        }
        if (!chunksDirectory.exists() && !chunksDirectory.mkdirs() && !chunksDirectory.exists()) {
            throw IllegalStateException("Unable to create chunks directory: ${chunksDirectory.absolutePath}")
        }

        // Alpha builds intentionally discard obsolete private storage instead of migrating it.
        legacyDirectory?.let { legacy ->
            if (legacy != rootDirectory && legacy.exists()) {
                runCatching { legacy.deleteRecursively() }
            }
        }
        runCatching { File(rootDirectory, indexA.name + ".tmp").delete() }
        runCatching { File(rootDirectory, indexB.name + ".tmp").delete() }

        val firstIndex = readIndex(indexA)
        val secondIndex = readIndex(indexB)
        val restoredIndex = listOfNotNull(firstIndex, secondIndex).maxByOrNull { it.generation }
        val scanned = scanChunkFilesLocked()

        if (restoredIndex != null) {
            restoreFromIndexLocked(restoredIndex, scanned)
        } else {
            restoreWithoutIndexLocked(scanned.values.toList())
        }

        lastWriteAtMillis = chunks.lastOrNull()?.let { newest ->
            newest.createdAtMillis + (newest.durationSeconds * 1000.0).toLong()
        } ?: 0L
        // Recovery has produced a complete in-memory timeline at this point. Mark it
        // loaded before checkpointing so a checkpoint failure cannot make a later call
        // reconstruct the same files into an already-populated deque.
        loaded = true
        writeIndexLocked()
    }

    private fun scanChunkFilesLocked(): MutableMap<UInt, ChunkRecord> {
        val result = LinkedHashMap<UInt, ChunkRecord>()
        val files = chunksDirectory.listFiles()
            ?: throw IOException("Unable to list chunks directory: ${chunksDirectory.absolutePath}")
        for (file in files) {
            if (!file.isFile) continue
            val id = file.name.toUIntOrNull()
            if (id == null || file.name != id.toString()) {
                try {
                    Files.deleteIfExists(file.toPath())
                } catch (error: IOException) {
                    throw IOException("Unable to remove invalid chunk artifact ${file.absolutePath}", error)
                }
                continue
            }
            val record = try {
                readChunkRecord(file, id)
            } catch (error: IOException) {
                // A transient filesystem/provider failure is not evidence of corruption.
                // Abort recovery rather than deleting or forgetting audio we could not read.
                throw IOException("Unable to inspect chunk ${file.absolutePath}", error)
            }
            if (record == null) {
                try {
                    Files.deleteIfExists(file.toPath())
                } catch (error: IOException) {
                    throw IOException("Unable to remove corrupt chunk ${file.absolutePath}", error)
                }
                continue
            }
            if (result.put(id, record) != null) {
                throw IOException("Duplicate chunk id on disk: $id")
            }
        }
        return result
    }

    private fun restoreFromIndexLocked(
        index: LoadedIndex,
        scanned: MutableMap<UInt, ChunkRecord>,
    ) {
        indexGeneration = index.generation
        nextChunkId = index.nextChunkId

        for (indexed in index.records) {
            val record = scanned[indexed.id] ?: continue
            if (!indexRecordMatchesChunk(indexed, record)) {
                indexGeneration = 0L
                restoreWithoutIndexLocked(scanned.values.toList())
                return
            }
        }

        for (indexed in index.records) {
            val record = scanned.remove(indexed.id) ?: continue
            addChunkLastLocked(record)
        }

        val orphanTail = scanned.values
            .mapNotNull { record ->
                val distance = unsignedDistance(nextChunkId, record.id)
                if (distance < UINT32_HALF_RANGE) distance to record else null
            }
            .sortedBy { it.first }
        if (orphanTail.isNotEmpty()) {
            for ((_, orphan) in orphanTail) {
                scanned.remove(orphan.id)
                addChunkLastLocked(orphan)
            }
            val furthestDistance = orphanTail.last().first
            nextChunkId += (furthestDistance + 1L).toUInt()
        }

        // Anything not reachable from the committed end-cap is stale cleanup from a prior crash.
        for (stale in scanned.values) {
            retireRecordLocked(stale)
        }
    }

    private fun restoreWithoutIndexLocked(records: List<ChunkRecord>) {
        indexGeneration = 0L
        if (records.isEmpty()) {
            nextChunkId = 0u
            return
        }
        val ordered = orderModuloUInt32(records)
        for (record in ordered) addChunkLastLocked(record)
        nextChunkId = ordered.last().id + 1u
    }

    private fun orderModuloUInt32(records: List<ChunkRecord>): List<ChunkRecord> {
        if (records.size <= 1) return records
        val sorted = records.sortedBy { it.id.toLong() }
        var largestGap = -1L
        var startIndex = 0
        for (index in sorted.indices) {
            val current = sorted[index].id
            val next = sorted[(index + 1) % sorted.size].id
            val gap = unsignedDistance(current, next)
            if (gap > largestGap) {
                largestGap = gap
                startIndex = (index + 1) % sorted.size
            }
        }
        return List(sorted.size) { offset -> sorted[(startIndex + offset) % sorted.size] }
    }

    private fun readChunkRecord(file: File, filenameId: UInt): ChunkRecord? {
        val header = readChunkHeader(file) ?: return null
        if (header.id != filenameId) return null
        if (header.sampleRate <= 0 || header.channelCount !in 1..MAX_CHANNEL_COUNT) return null
        val frameBytesLong = header.channelCount.toLong() * header.sampleFormat.bytesPerSample.toLong()
        if (frameBytesLong <= 0L || frameBytesLong > Int.MAX_VALUE.toLong()) return null
        val frameBytes = frameBytesLong.toInt()

        val actualPayload = (Files.size(file.toPath()) - header.payloadOffsetBytes).coerceAtLeast(0L)
        if (actualPayload > CHUNK_PAYLOAD_BYTES.toLong()) return null
        val alignedActualPayload = actualPayload - actualPayload % frameBytes.toLong()
        if (alignedActualPayload <= 0L) {
            runCatching { file.delete() }
            return null
        }

        if (header.state == ChunkState.FINALIZED) {
            if (
                actualPayload != alignedActualPayload ||
                header.payloadBytes != actualPayload ||
                header.sampleFrames != actualPayload / frameBytes
            ) {
                return null
            }
            return ChunkRecord(
                id = header.id,
                file = file,
                state = header.state,
                createdAtMillis = header.createdAtMillis,
                payloadBytes = header.payloadBytes,
                sampleFrames = header.sampleFrames,
                sampleRate = header.sampleRate,
                channelCount = header.channelCount,
                sampleFormat = header.sampleFormat,
                payloadChecksum = header.payloadChecksum,
                headerGeneration = header.generation,
                payloadOffsetBytes = header.payloadOffsetBytes,
            )
        }

        // ACTIVE metadata can lag the payload after a crash. The immutable prefix is
        // independently checksummed, so payload geometry can be reconstructed safely.
        RandomAccessFile(file, "rw").use { access ->
            access.setLength(header.payloadOffsetBytes + alignedActualPayload)
        }
        val checksum = crc32FilePayload(file, header.payloadOffsetBytes, alignedActualPayload)
        val recovered = ChunkRecord(
            id = header.id,
            file = file,
            state = ChunkState.FINALIZED,
            createdAtMillis = header.createdAtMillis,
            payloadBytes = alignedActualPayload,
            sampleFrames = alignedActualPayload / frameBytes,
            sampleRate = header.sampleRate,
            channelCount = header.channelCount,
            sampleFormat = header.sampleFormat,
            payloadChecksum = checksum,
            headerGeneration = header.generation,
            payloadOffsetBytes = header.payloadOffsetBytes,
        )
        writeMutableChunkSlot(recovered, forceToDisk = true)
        return recovered
    }

    private fun indexRecordMatchesChunk(indexed: IndexRecord, record: ChunkRecord): Boolean {
        return indexed.id == record.id &&
            indexed.createdAtMillis == record.createdAtMillis &&
            indexed.sampleRate == record.sampleRate &&
            indexed.channelCount == record.channelCount &&
            indexed.sampleFormat == record.sampleFormat
    }

    private fun createActiveChunkLocked(): ChunkRecord? {
        val frameBytes = configuredFrameBytesLocked()
        if (frameBytes <= 0 || chunkPayloadLimitLocked(frameBytes) <= 0L) return null

        val id = nextChunkId
        val file = File(chunksDirectory, id.toString())
        val liveCollision = id in liveChunkIds || retiredById.containsKey(id)
        if (liveCollision) {
            throw IOException("Chunk id collision with live data: $id")
        }
        if (file.exists()) {
            throw IOException("Unexpected chunk id collision on disk: ${file.absolutePath}")
        }

        val record = ChunkRecord(
            id = id,
            file = file,
            state = ChunkState.ACTIVE,
            createdAtMillis = System.currentTimeMillis(),
            payloadBytes = 0L,
            sampleFrames = 0L,
            sampleRate = configuredSampleRate,
            channelCount = configuredChannelCount,
            sampleFormat = configuredSampleFormat,
            payloadChecksum = 0,
            headerGeneration = 0L,
            payloadOffsetBytes = CHUNK_HEADER_BYTES.toLong(),
        )
        var openedAccess: RandomAccessFile? = null
        val access = try {
            if (!file.createNewFile()) {
                throw IOException("Unable to create chunk file: ${file.absolutePath}")
            }
            openedAccess = RandomAccessFile(file, "rw")
            writeInitialChunkHeader(record, access = requireNotNull(openedAccess))
            requireNotNull(openedAccess)
        } catch (error: Exception) {
            runCatching { openedAccess?.close() }
            runCatching { file.delete() }
            throw error
        }

        nextChunkId += 1u
        addChunkLastLocked(record)
        activeRecord = record
        activePayloadCrc = CRC32()
        activeAccess = access
        return record
    }

    private fun finalizeActiveLocked() {
        val record = activeRecord ?: return
        if (record.payloadBytes <= 0L) {
            closeActiveAccessLocked()
            removeChunkLocked(record)
            activeRecord = null
            activePayloadCrc = CRC32()
            record.pendingDelete = true
            tryDeleteRetiredRecordLocked(record)
            return
        }

        record.state = ChunkState.FINALIZED
        record.payloadChecksum = activePayloadCrc.value.toInt()
        try {
            writeMutableChunkSlot(record, access = activeAccess, forceToDisk = true)
        } catch (error: Exception) {
            // Once finalization has started, do not append to this file again. The on-disk
            // header may be complete, partial, or merely unsynced depending on the failure.
            closeActiveAccessLocked()
            activeRecord = null
            activePayloadCrc = CRC32()
            throw error
        }
        closeActiveAccessLocked()
        activeRecord = null
        activePayloadCrc = CRC32()
    }

    private fun writeActiveHeaderLocked() {
        val record = activeRecord ?: return
        record.payloadChecksum = activePayloadCrc.value.toInt()
        writeMutableChunkSlot(record, access = activeAccess, forceToDisk = true)
    }

    private fun closeActiveAccessLocked() {
        runCatching { activeAccess?.close() }
        activeAccess = null
    }

    private fun cleanupRetentionLocked(): Boolean {
        if (!overwriteOldest) return false
        var changed = false
        if (retentionValue <= 0L) {
            while (chunks.isNotEmpty()) {
                val record = removeFirstChunkLocked()
                if (record === activeRecord) {
                    closeActiveAccessLocked()
                    activeRecord = null
                    activePayloadCrc = CRC32()
                }
                retireRecordLocked(record)
                changed = true
            }
            return changed
        }

        when (retentionMode) {
            RetentionMode.SIZE -> {
                var total = retainedPayloadBytes
                while (total > retentionValue && chunks.isNotEmpty()) {
                    val oldest = chunks.first()
                    if (oldest === activeRecord) break
                    removeFirstChunkLocked()
                    total -= oldest.payloadBytes
                    retireRecordLocked(oldest)
                    changed = true
                }
            }

            RetentionMode.TIME -> {
                var total = retainedDurationSeconds
                while (total > retentionValue.toDouble() && chunks.isNotEmpty()) {
                    val oldest = chunks.first()
                    if (oldest === activeRecord) break
                    removeFirstChunkLocked()
                    total -= oldest.durationSeconds
                    retireRecordLocked(oldest)
                    changed = true
                }
            }
        }
        return changed
    }

    private fun retentionExceededLocked(): Boolean = when (retentionMode) {
        RetentionMode.SIZE -> totalPayloadBytesLocked() > retentionValue
        RetentionMode.TIME -> totalDurationSecondsLocked() > retentionValue.toDouble()
    }

    private fun writableBytesLocked(frameBytes: Int): Long {
        if (overwriteOldest) return Long.MAX_VALUE
        return oneShotWritableBytes(
            retentionMode = retentionMode,
            retentionValue = retentionValue,
            retainedPayloadBytes = retainedPayloadBytes,
            retainedDurationSeconds = retainedDurationSeconds,
            sampleRate = configuredSampleRate,
            frameBytes = frameBytes,
        )
    }

    private fun retireRecordLocked(record: ChunkRecord) {
        record.pendingDelete = true
        if (record.refCount <= 0) {
            tryDeleteRetiredRecordLocked(record)
        } else {
            retiredById[record.id] = record
        }
    }

    private fun releaseRecordLocked(record: ChunkRecord) {
        check(record.refCount > 0) { "Chunk refCount underflow for ${record.id}" }
        record.refCount--
        if (record.refCount == 0 && record.pendingDelete) {
            tryDeleteRetiredRecordLocked(record)
        }
    }

    private fun tryDeleteRetiredRecordLocked(record: ChunkRecord): Boolean {
        val deleted = runCatching {
            Files.deleteIfExists(record.file.toPath())
            true
        }.getOrDefault(false)
        if (deleted) {
            retiredById.remove(record.id)
        } else {
            retiredById[record.id] = record
        }
        return deleted
    }

    private fun retryRetiredDeletesLocked() {
        val retry = retiredById.values.filter { it.refCount == 0 }
        for (record in retry) {
            tryDeleteRetiredRecordLocked(record)
        }
    }

    private fun addChunkLastLocked(record: ChunkRecord) {
        check(liveChunkIds.add(record.id)) { "Duplicate live chunk id ${record.id}" }
        chunks.addLast(record)
        retainedPayloadBytes = safeAdd(retainedPayloadBytes, record.payloadBytes)
        retainedDurationSeconds += record.durationSeconds
    }

    private fun removeFirstChunkLocked(): ChunkRecord {
        val record = chunks.removeFirst()
        removeChunkTotalsLocked(record)
        return record
    }

    private fun removeChunkLocked(record: ChunkRecord): Boolean {
        if (!chunks.remove(record)) return false
        removeChunkTotalsLocked(record)
        return true
    }

    private fun removeChunkTotalsLocked(record: ChunkRecord) {
        check(liveChunkIds.remove(record.id)) { "Missing live chunk id ${record.id}" }
        retainedPayloadBytes = (retainedPayloadBytes - record.payloadBytes).coerceAtLeast(0L)
        retainedDurationSeconds = (retainedDurationSeconds - record.durationSeconds).coerceAtLeast(0.0)
    }

    private fun configuredFrameBytesLocked(): Int {
        if (configuredSampleRate <= 0 || configuredChannelCount !in 1..MAX_CHANNEL_COUNT) return 0
        val frameBytes = configuredChannelCount.toLong() * configuredSampleFormat.bytesPerSample.toLong()
        return frameBytes.takeIf { it in 1..Int.MAX_VALUE.toLong() }?.toInt() ?: 0
    }

    private fun chunkPayloadLimitLocked(frameBytes: Int): Long {
        if (frameBytes <= 0 || retentionValue <= 0L) return 0L
        val retentionBound = when (retentionMode) {
            RetentionMode.SIZE -> retentionValue
            RetentionMode.TIME -> {
                val bytesPerSecond = configuredSampleRate.toLong() * frameBytes.toLong()
                if (bytesPerSecond <= 0L) 0L
                else if (retentionValue > Long.MAX_VALUE / bytesPerSecond) Long.MAX_VALUE
                else retentionValue * bytesPerSecond
            }
        }
        // Retention evicts whole immutable chunks. If a small retention window were
        // represented by one chunk, the first frame of the next chunk would evict
        // essentially the entire history. Keep several chunks inside small windows so
        // rollover only drops a small fraction of the requested retention at a time.
        val retentionGranularityBound = if (retentionBound == Long.MAX_VALUE) {
            Long.MAX_VALUE
        } else {
            (retentionBound / MIN_CHUNKS_PER_RETENTION).coerceAtLeast(frameBytes.toLong())
        }
        val rawLimit = minOf(CHUNK_PAYLOAD_BYTES.toLong(), retentionGranularityBound)
        return (rawLimit / frameBytes.toLong()) * frameBytes.toLong()
    }

    private fun totalPayloadBytesLocked(): Long {
        return retainedPayloadBytes
    }

    private fun totalDurationSecondsLocked(): Double {
        return retainedDurationSeconds
    }

    private fun writeInitialChunkHeader(
        record: ChunkRecord,
        access: RandomAccessFile,
    ) {
        val bytes = ByteArray(CHUNK_HEADER_BYTES)
        writeIntLE(bytes, 0, CHUNK_MAGIC)
        writeIntLE(bytes, 4, CHUNK_VERSION)
        writeIntLE(bytes, 8, record.id.toInt())
        writeIntLE(bytes, 12, CHUNK_HEADER_BYTES)
        writeLongLE(bytes, 16, record.createdAtMillis)
        writeIntLE(bytes, 24, record.sampleRate)
        writeIntLE(bytes, 28, record.channelCount)
        writeIntLE(bytes, 32, sampleFormatCode(record.sampleFormat))
        writeIntLE(bytes, 36, 0)
        writeIntLE(bytes, 40, crc32(bytes, 0, CHUNK_IMMUTABLE_CRC_OFFSET))
        writeIntLE(bytes, 44, 0)

        // Slot A starts at generation 1. Slot B remains all zeroes until the first
        // checkpoint/finalization. Immutable metadata is never rewritten afterward.
        record.headerGeneration = 1L
        writeMutableSlotBytes(bytes, CHUNK_SLOT_A_OFFSET, record)

        access.seek(0L)
        access.write(bytes)
        access.setLength(CHUNK_HEADER_BYTES.toLong())
        access.fd.sync()
        access.seek(CHUNK_HEADER_BYTES.toLong())
    }

    private fun writeMutableChunkSlot(
        record: ChunkRecord,
        access: RandomAccessFile? = null,
        forceToDisk: Boolean,
    ) {
        val nextGeneration = if (record.headerGeneration == Long.MAX_VALUE) 1L else record.headerGeneration + 1L
        val slotOffset = if ((nextGeneration and 1L) == 0L) CHUNK_SLOT_B_OFFSET else CHUNK_SLOT_A_OFFSET
        val bytes = ByteArray(CHUNK_SLOT_BYTES)
        val previousGeneration = record.headerGeneration
        record.headerGeneration = nextGeneration
        try {
            writeMutableSlotBytes(bytes, 0, record)
            if (access != null) {
                access.seek(slotOffset.toLong())
                access.write(bytes)
                access.seek(record.payloadOffsetBytes + record.payloadBytes)
                if (forceToDisk) access.fd.sync()
            } else {
                RandomAccessFile(record.file, "rw").use { opened ->
                    opened.seek(slotOffset.toLong())
                    opened.write(bytes)
                    if (forceToDisk) opened.fd.sync()
                }
            }
        } catch (error: Exception) {
            record.headerGeneration = previousGeneration
            throw error
        }
    }

    private fun writeMutableSlotBytes(
        bytes: ByteArray,
        offset: Int,
        record: ChunkRecord,
    ) {
        writeLongLE(bytes, offset, record.headerGeneration)
        writeIntLE(bytes, offset + 8, record.state.code)
        writeIntLE(bytes, offset + 12, record.payloadChecksum)
        writeLongLE(bytes, offset + 16, record.payloadBytes)
        writeLongLE(bytes, offset + 24, record.sampleFrames)
        writeIntLE(bytes, offset + 32, crc32(bytes, offset, CHUNK_SLOT_CRC_OFFSET))
        writeIntLE(bytes, offset + 36, 0)
    }

    private fun readChunkHeader(file: File): ParsedHeader? {
        if (Files.size(file.toPath()) < CHUNK_HEADER_BYTES) return null
        val bytes = ByteArray(CHUNK_HEADER_BYTES)
        RandomAccessFile(file, "r").use { access ->
            access.readFully(bytes)
        }
        if (readIntLE(bytes, 0) != CHUNK_MAGIC || readIntLE(bytes, 4) != CHUNK_VERSION) return null
        if (readIntLE(bytes, 12) != CHUNK_HEADER_BYTES) return null
        if (readIntLE(bytes, 40) != crc32(bytes, 0, CHUNK_IMMUTABLE_CRC_OFFSET)) return null

        val sampleFormat = sampleFormatFromCode(readIntLE(bytes, 32)) ?: return null
        val immutable = ParsedImmutableHeader(
            id = readIntLE(bytes, 8).toUInt(),
            createdAtMillis = readLongLE(bytes, 16),
            sampleRate = readIntLE(bytes, 24),
            channelCount = readIntLE(bytes, 28),
            sampleFormat = sampleFormat,
        )
        val first = readMutableSlot(bytes, CHUNK_SLOT_A_OFFSET)
        val second = readMutableSlot(bytes, CHUNK_SLOT_B_OFFSET)
        val mutable = when {
            first == null -> second
            second == null -> first
            first.generation >= second.generation -> first
            else -> second
        } ?: return null

        return ParsedHeader(
            id = immutable.id,
            state = mutable.state,
            generation = mutable.generation,
            createdAtMillis = immutable.createdAtMillis,
            payloadBytes = mutable.payloadBytes,
            sampleFrames = mutable.sampleFrames,
            sampleRate = immutable.sampleRate,
            channelCount = immutable.channelCount,
            sampleFormat = immutable.sampleFormat,
            payloadChecksum = mutable.payloadChecksum,
            payloadOffsetBytes = CHUNK_HEADER_BYTES.toLong(),
        )
    }

    private fun readMutableSlot(bytes: ByteArray, offset: Int): ParsedMutableSlot? {
        if (readIntLE(bytes, offset + 32) != crc32(bytes, offset, CHUNK_SLOT_CRC_OFFSET)) return null
        val generation = readLongLE(bytes, offset)
        if (generation <= 0L) return null
        val state = ChunkState.fromCode(readIntLE(bytes, offset + 8)) ?: return null
        val payloadBytes = readLongLE(bytes, offset + 16)
        val sampleFrames = readLongLE(bytes, offset + 24)
        if (payloadBytes < 0L || sampleFrames < 0L) return null
        return ParsedMutableSlot(
            generation = generation,
            state = state,
            payloadChecksum = readIntLE(bytes, offset + 12),
            payloadBytes = payloadBytes,
            sampleFrames = sampleFrames,
        )
    }

    private fun writeIndexLocked() {
        if (!loaded && chunksDirectory.exists().not()) return
        val recordCount = chunks.size
        val indexBytes =
            INDEX_HEADER_BYTES.toLong() + recordCount.toLong() * INDEX_RECORD_BYTES.toLong() + INDEX_CRC_BYTES
        if (indexBytes > MAX_INDEX_BYTES) {
            throw IOException("Chunk index exceeds supported size: $indexBytes bytes")
        }
        val previousGeneration = indexGeneration
        indexGeneration = if (indexGeneration == Long.MAX_VALUE) 1L else indexGeneration + 1L
        val bytes = ByteArray(indexBytes.toInt())
        writeIntLE(bytes, 0, INDEX_MAGIC)
        writeIntLE(bytes, 4, INDEX_VERSION)
        writeLongLE(bytes, 8, indexGeneration)
        writeIntLE(bytes, 16, nextChunkId.toInt())
        writeIntLE(bytes, 20, recordCount)
        var offset = INDEX_HEADER_BYTES
        for (record in chunks) {
            writeIntLE(bytes, offset, record.id.toInt())
            writeIntLE(bytes, offset + 4, record.state.code)
            writeLongLE(bytes, offset + 8, record.createdAtMillis)
            writeLongLE(bytes, offset + 16, record.payloadBytes)
            writeLongLE(bytes, offset + 24, record.sampleFrames)
            writeIntLE(bytes, offset + 32, record.sampleRate)
            writeIntLE(bytes, offset + 36, record.channelCount)
            writeIntLE(bytes, offset + 40, sampleFormatCode(record.sampleFormat))
            writeIntLE(bytes, offset + 44, record.payloadChecksum)
            offset += INDEX_RECORD_BYTES
        }
        writeIntLE(bytes, bytes.size - INDEX_CRC_BYTES, crc32(bytes, 0, bytes.size - INDEX_CRC_BYTES))

        val target = if ((indexGeneration and 1L) == 0L) indexA else indexB
        val temp = File(rootDirectory, target.name + ".tmp")
        try {
            FileOutputStream(temp).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
            try {
                Files.move(
                    temp.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    temp.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } catch (error: Exception) {
            indexGeneration = previousGeneration
            temp.delete()
            throw error
        }
    }

    private fun readIndex(file: File): LoadedIndex? {
        if (!file.isFile) return null
        val fileLength = runCatching { Files.size(file.toPath()) }.getOrNull() ?: return null
        if (
            fileLength < INDEX_HEADER_BYTES + INDEX_CRC_BYTES ||
            fileLength > MAX_INDEX_BYTES
        ) {
            return null
        }
        val bytes = runCatching {
            val size = fileLength.toInt()
            ByteArray(size).also { buffer ->
                FileInputStream(file).use { input ->
                    var offset = 0
                    while (offset < size) {
                        val count = input.read(buffer, offset, size - offset)
                        if (count < 0) throw IOException("Unexpected EOF reading ${file.name}")
                        offset += count
                    }
                }
            }
        }.getOrNull() ?: return null
        if (bytes.size < INDEX_HEADER_BYTES + INDEX_CRC_BYTES) return null
        if (readIntLE(bytes, 0) != INDEX_MAGIC || readIntLE(bytes, 4) != INDEX_VERSION) return null
        val count = readIntLE(bytes, 20)
        if (count < 0) return null
        val expectedSize = INDEX_HEADER_BYTES.toLong() + count.toLong() * INDEX_RECORD_BYTES + INDEX_CRC_BYTES
        if (expectedSize != bytes.size.toLong()) return null
        if (readIntLE(bytes, bytes.size - INDEX_CRC_BYTES) != crc32(bytes, 0, bytes.size - INDEX_CRC_BYTES)) return null

        val generation = readLongLE(bytes, 8)
        if (generation <= 0L) return null

        val records = ArrayList<IndexRecord>(count)
        val ids = HashSet<UInt>(count)
        var offset = INDEX_HEADER_BYTES
        repeat(count) {
            val id = readIntLE(bytes, offset).toUInt()
            if (!ids.add(id)) return null
            val state = ChunkState.fromCode(readIntLE(bytes, offset + 4)) ?: return null
            val sampleFormat = sampleFormatFromCode(readIntLE(bytes, offset + 40)) ?: return null
            val payloadBytes = readLongLE(bytes, offset + 16)
            val sampleFrames = readLongLE(bytes, offset + 24)
            val sampleRate = readIntLE(bytes, offset + 32)
            val channelCount = readIntLE(bytes, offset + 36)
            if (
                payloadBytes < 0L || payloadBytes > CHUNK_PAYLOAD_BYTES.toLong() ||
                sampleFrames < 0L || sampleRate <= 0 || channelCount !in 1..MAX_CHANNEL_COUNT
            ) {
                return null
            }
            records += IndexRecord(
                id = id,
                state = state,
                createdAtMillis = readLongLE(bytes, offset + 8),
                payloadBytes = payloadBytes,
                sampleFrames = sampleFrames,
                sampleRate = sampleRate,
                channelCount = channelCount,
                sampleFormat = sampleFormat,
                payloadChecksum = readIntLE(bytes, offset + 44),
            )
            offset += INDEX_RECORD_BYTES
        }
        for (index in 1 until records.size) {
            val distance = unsignedDistance(records[index - 1].id, records[index].id)
            if (distance == 0L || distance >= UINT32_HALF_RANGE) return null
        }
        if (records.isNotEmpty()) {
            val distanceToEndCap = unsignedDistance(records.last().id, readIntLE(bytes, 16).toUInt())
            if (distanceToEndCap == 0L || distanceToEndCap >= UINT32_HALF_RANGE) return null
        }
        return LoadedIndex(
            generation = generation,
            nextChunkId = readIntLE(bytes, 16).toUInt(),
            records = records,
        )
    }

    private fun crc32FilePayload(
        file: File,
        payloadOffsetBytes: Long,
        payloadBytes: Long,
    ): Int {
        val crc = CRC32()
        RandomAccessFile(file, "r").use { input ->
            input.seek(payloadOffsetBytes)
            val scratch = ByteArray(64 * 1024)
            var remaining = payloadBytes
            while (remaining > 0L) {
                val count = minOf(scratch.size.toLong(), remaining).toInt()
                input.readFully(scratch, 0, count)
                crc.update(scratch, 0, count)
                remaining -= count.toLong()
            }
        }
        return crc.value.toInt()
    }

    private companion object {
        const val CHUNK_MAGIC = 0x52564348 // RVCH
        const val CHUNK_VERSION = 2
        const val CHUNK_HEADER_BYTES = 128
        const val CHUNK_IMMUTABLE_CRC_OFFSET = 40
        const val CHUNK_SLOT_A_OFFSET = 48
        const val CHUNK_SLOT_B_OFFSET = 88
        const val CHUNK_SLOT_BYTES = 40
        const val CHUNK_SLOT_CRC_OFFSET = 32
        const val CHUNK_PAYLOAD_BYTES = 1024 * 1024
        const val MIN_CHUNKS_PER_RETENTION = 16L
        const val MAX_CHANNEL_COUNT = 2
        const val UINT32_HALF_RANGE = 0x8000_0000L

        const val INDEX_MAGIC = 0x52564958 // RVIX
        const val INDEX_VERSION = 2
        const val INDEX_HEADER_BYTES = 24
        const val INDEX_RECORD_BYTES = 48
        const val INDEX_CRC_BYTES = 4
        const val MAX_INDEX_BYTES = 64L * 1024L * 1024L

        fun sampleFormatCode(format: PcmSampleFormat): Int = when (format) {
            PcmSampleFormat.PCM_8 -> 1
            PcmSampleFormat.PCM_16 -> 2
            PcmSampleFormat.PCM_FLOAT -> 3
        }

        fun sampleFormatFromCode(code: Int): PcmSampleFormat? = when (code) {
            1 -> PcmSampleFormat.PCM_8
            2 -> PcmSampleFormat.PCM_16
            3 -> PcmSampleFormat.PCM_FLOAT
            else -> null
        }

        fun unsignedDistance(from: UInt, to: UInt): Long {
            return (to.toLong() - from.toLong()) and 0xffff_ffffL
        }

        fun safeAdd(a: Long, b: Long): Long {
            return if (b > 0L && a > Long.MAX_VALUE - b) Long.MAX_VALUE else a + b
        }

        fun crc32(bytes: ByteArray, offset: Int, count: Int): Int {
            val crc = CRC32()
            crc.update(bytes, offset, count)
            return crc.value.toInt()
        }

        fun readIntLE(bytes: ByteArray, offset: Int): Int {
            return (bytes[offset].toInt() and 0xff) or
                ((bytes[offset + 1].toInt() and 0xff) shl 8) or
                ((bytes[offset + 2].toInt() and 0xff) shl 16) or
                ((bytes[offset + 3].toInt() and 0xff) shl 24)
        }

        fun writeIntLE(bytes: ByteArray, offset: Int, value: Int) {
            bytes[offset] = value.toByte()
            bytes[offset + 1] = (value ushr 8).toByte()
            bytes[offset + 2] = (value ushr 16).toByte()
            bytes[offset + 3] = (value ushr 24).toByte()
        }

        fun readLongLE(bytes: ByteArray, offset: Int): Long {
            var value = 0L
            for (index in 0 until 8) {
                value = value or ((bytes[offset + index].toLong() and 0xffL) shl (index * 8))
            }
            return value
        }

        fun writeLongLE(bytes: ByteArray, offset: Int, value: Long) {
            for (index in 0 until 8) {
                bytes[offset + index] = (value ushr (index * 8)).toByte()
            }
        }
    }
}
