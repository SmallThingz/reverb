package app.smallthingz.reverb

import android.content.Context
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.ArrayDeque
import java.util.zip.CRC32
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * Disk-backed append-only PCM timeline.
 *
 * Completed chunks are immutable. The only mutable audio file is the newest ACTIVE chunk.
 * Chronology lives in [chunks], not in numeric filename ordering, so UInt32 wrap is harmless.
 */
internal class PersistentAudioChunkStore(
    context: Context,
) : Closeable {
    private val rootDirectory = File(context.noBackupFilesDir, ReverbConfig.BUFFER_CACHE_FOLDER_NAME)
    private val chunksDirectory = File(rootDirectory, ReverbConfig.BUFFER_CHUNKS_FOLDER_NAME)
    private val indexA = File(rootDirectory, ReverbConfig.BUFFER_INDEX_A_FILE_NAME)
    private val indexB = File(rootDirectory, ReverbConfig.BUFFER_INDEX_B_FILE_NAME)
    private val legacyDirectory = File(context.noBackupFilesDir, ReverbConfig.LEGACY_BUFFER_CACHE_FOLDER_NAME)

    private val chunks = ArrayDeque<ChunkRecord>()
    private val retiredById = HashMap<UInt, ChunkRecord>()

    private var loaded = false
    private var closed = false
    private var indexGeneration = 0L
    private var nextChunkId = 0u
    private var finalizedSinceIndex = 0

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
        val validFormat = requestedSampleRate > 0 && requestedChannelCount > 0
        val formatChanged =
            configuredSampleRate != requestedSampleRate ||
                configuredChannelCount != requestedChannelCount ||
                configuredSampleFormat != sampleFormat

        if (formatChanged) {
            finalizeActiveLocked(forceToDisk = true)
        }

        retentionMode = requestedRetentionMode
        retentionValue = normalizedRetention
        configuredSampleRate = if (validFormat) requestedSampleRate else 0
        configuredChannelCount = if (validFormat) requestedChannelCount else 0
        configuredSampleFormat = sampleFormat

        if (activeRecord != null && retentionExceededLocked()) {
            finalizeActiveLocked(forceToDisk = true)
        }
        val cleaned = cleanupRetentionLocked()
        if (cleaned || formatChanged) {
            writeIndexLocked(forceToDisk = true)
        }
    }

    @Synchronized
    fun append(
        array: ByteArray,
        offset: Int,
        count: Int,
    ) {
        require(offset >= 0 && count >= 0 && offset <= array.size - count) {
            "Invalid PCM range offset=$offset count=$count size=${array.size}"
        }
        if (count == 0) return
        ensureLoadedLocked()

        val frameBytes = configuredFrameBytesLocked()
        if (frameBytes <= 0 || retentionValue <= 0L) return
        require(count % frameBytes == 0) {
            "PCM append must be frame aligned: count=$count frameBytes=$frameBytes"
        }

        var sourceOffset = offset
        var remaining = count
        while (remaining > 0) {
            var record = activeRecord
            if (record == null) {
                record = createActiveChunkLocked()
                if (record == null) return
            }

            val limit = chunkPayloadLimitLocked(frameBytes)
            if (limit <= 0L) return
            val available = limit - record.payloadBytes
            if (available <= 0L) {
                finalizeActiveLocked(forceToDisk = true)
                cleanupRetentionLocked()
                continue
            }

            val writeCount = minOf(remaining.toLong(), available).toInt()
            val alignedWriteCount = writeCount - writeCount % frameBytes
            if (alignedWriteCount <= 0) {
                finalizeActiveLocked(forceToDisk = true)
                cleanupRetentionLocked()
                continue
            }

            val access = requireNotNull(activeAccess)
            access.write(array, sourceOffset, alignedWriteCount)
            activePayloadCrc.update(array, sourceOffset, alignedWriteCount)

            record.payloadBytes += alignedWriteCount.toLong()
            record.sampleFrames += alignedWriteCount.toLong() / frameBytes
            record.payloadChecksum = activePayloadCrc.value.toInt()
            sourceOffset += alignedWriteCount
            remaining -= alignedWriteCount
            lastWriteAtMillis = System.currentTimeMillis()

            if (record.payloadBytes >= limit) {
                finalizeActiveLocked(forceToDisk = true)
                val cleaned = cleanupRetentionLocked()
                if (cleaned || finalizedSinceIndex >= INDEX_CHUNK_INTERVAL) {
                    writeIndexLocked(forceToDisk = true)
                }
            }
        }
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
        return chunks.any { it.sampleFrames > 0L }
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
        val totalDuration = totalDurationSecondsLocked()
        if (totalDuration <= 0.0) return null

        val start = startOffsetSeconds.coerceIn(0.0, totalDuration)
        val end = endOffsetSeconds.coerceIn(start, totalDuration)
        if (end <= start) return null

        val segments = ArrayList<Segment>()
        var cursor = 0.0
        var startedAtMillis = 0L
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
                payloadBytesAtAcquire = record.payloadBytes,
                payloadChecksumAtAcquire = record.payloadChecksum,
            )
            if (startedAtMillis == 0L) {
                startedAtMillis = record.createdAtMillis +
                    (startFrame * 1000L / record.sampleRate.coerceAtLeast(1))
            }
            leaseDuration += frameCount.toDouble() / record.sampleRate.toDouble()
        }

        if (segments.isEmpty()) return null
        return RangeLease(
            store = this,
            segments = segments,
            startedAtMillis = startedAtMillis,
            durationSeconds = leaseDuration,
        )
    }

    @Synchronized
    fun checkpoint() {
        ensureLoadedLocked()
        writeActiveHeaderLocked(forceToDisk = true)
        writeIndexLocked(forceToDisk = true)
    }

    @Synchronized
    fun clear() {
        ensureLoadedLocked()
        closeActiveAccessLocked()
        activeRecord = null
        activePayloadCrc = CRC32()

        while (chunks.isNotEmpty()) {
            retireRecordLocked(chunks.removeFirst())
        }
        lastWriteAtMillis = 0L
        writeIndexLocked(forceToDisk = true)
    }

    @Synchronized
    override fun close() {
        if (closed) return
        if (loaded) {
            runCatching { writeActiveHeaderLocked(forceToDisk = true) }
            runCatching { writeIndexLocked(forceToDisk = true) }
            closeActiveAccessLocked()
        }
        closed = true
    }

    internal inner class RangeLease internal constructor(
        private val store: PersistentAudioChunkStore,
        private val segments: List<Segment>,
        val startedAtMillis: Long,
        val durationSeconds: Double,
    ) : Closeable {
        private var closedLease = false

        fun readNormalized(
            targetSampleRate: Int,
            targetChannelCount: Int,
            targetSampleFormat: PcmSampleFormat,
            consumer: Consumer,
        ): ReadResult {
            check(!closedLease) { "RangeLease is closed" }
            require(targetSampleRate > 0 && targetChannelCount > 0)

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
        val createdAtMillis: Long,
        val payloadBytes: Long,
        val sampleFrames: Long,
        val sampleRate: Int,
        val channelCount: Int,
        val sampleFormat: PcmSampleFormat,
        val payloadChecksum: Int,
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

        // Alpha migration policy: the old circular store is intentionally discarded once.
        if (legacyDirectory != rootDirectory && legacyDirectory.exists()) {
            runCatching { legacyDirectory.deleteRecursively() }
        }

        val firstIndex = readIndex(indexA)
        val secondIndex = readIndex(indexB)
        val restoredIndex = listOfNotNull(firstIndex, secondIndex).maxByOrNull { it.generation }
        val scanned = scanChunkFilesLocked()

        if (restoredIndex != null) {
            restoreFromIndexLocked(restoredIndex, scanned)
        } else {
            restoreWithoutIndexLocked(scanned.values.toList())
        }

        activeRecord = chunks.lastOrNull()?.takeIf { it.state == ChunkState.ACTIVE }
        if (activeRecord != null) {
            // Startup always seals the recovered tail. The next append starts a fresh chunk.
            recoverAndFinalizeRecordLocked(requireNotNull(activeRecord))
            activeRecord = null
        }
        closeActiveAccessLocked()
        lastWriteAtMillis = chunks.lastOrNull()?.let { newest ->
            newest.createdAtMillis + (newest.durationSeconds * 1000.0).toLong()
        } ?: 0L
        loaded = true
        writeIndexLocked(forceToDisk = true)
    }

    private fun scanChunkFilesLocked(): MutableMap<UInt, ChunkRecord> {
        val result = LinkedHashMap<UInt, ChunkRecord>()
        val files = chunksDirectory.listFiles().orEmpty()
        for (file in files) {
            if (!file.isFile) continue
            val id = file.name.toUIntOrNull()
            if (id == null) {
                runCatching { file.delete() }
                continue
            }
            val record = runCatching { readChunkRecord(file, id) }.getOrNull()
            if (record == null || result.put(id, record) != null) {
                runCatching { file.delete() }
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
            val record = scanned.remove(indexed.id) ?: continue
            chunks.addLast(record)
        }

        var expected = nextChunkId
        while (true) {
            val orphan = scanned.remove(expected) ?: break
            chunks.addLast(orphan)
            expected += 1u
        }
        nextChunkId = expected

        // Anything not reachable from the committed end-cap is stale cleanup from a prior crash.
        for (stale in scanned.values) {
            runCatching { stale.file.delete() }
        }
    }

    private fun restoreWithoutIndexLocked(records: List<ChunkRecord>) {
        indexGeneration = 0L
        if (records.isEmpty()) {
            nextChunkId = 0u
            return
        }
        val ordered = orderModuloUInt32(records)
        for (record in ordered) chunks.addLast(record)
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
        val frameBytes = header.channelCount * header.sampleFormat.bytesPerSample
        if (header.sampleRate <= 0 || header.channelCount <= 0 || frameBytes <= 0) return null

        val actualPayload = (file.length() - CHUNK_HEADER_BYTES).coerceAtLeast(0L)
        val alignedActualPayload = actualPayload - actualPayload % frameBytes.toLong()
        if (alignedActualPayload <= 0L) {
            runCatching { file.delete() }
            return null
        }

        val headerConsistent =
            header.state == ChunkState.FINALIZED &&
                header.payloadBytes == alignedActualPayload &&
                header.sampleFrames == alignedActualPayload / frameBytes

        if (headerConsistent) {
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
            )
        }

        // Only an ACTIVE or structurally inconsistent tail needs a payload scan on startup.
        RandomAccessFile(file, "rw").use { access ->
            access.setLength(CHUNK_HEADER_BYTES.toLong() + alignedActualPayload)
        }
        val checksum = crc32FilePayload(file, alignedActualPayload)
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
        )
        writeChunkHeader(recovered, forceToDisk = true)
        return recovered
    }

    private fun createActiveChunkLocked(): ChunkRecord? {
        val frameBytes = configuredFrameBytesLocked()
        if (frameBytes <= 0 || chunkPayloadLimitLocked(frameBytes) <= 0L) return null

        val id = nextChunkId
        val file = File(chunksDirectory, id.toString())
        val liveCollision = chunks.any { it.id == id } || retiredById.containsKey(id)
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
        )
        nextChunkId += 1u
        chunks.addLast(record)
        activeRecord = record
        activePayloadCrc = CRC32()
        activeAccess = RandomAccessFile(file, "rw")
        writeChunkHeader(record, access = requireNotNull(activeAccess), forceToDisk = false)
        return record
    }

    private fun finalizeActiveLocked(forceToDisk: Boolean) {
        val record = activeRecord ?: return
        if (record.payloadBytes <= 0L) {
            closeActiveAccessLocked()
            chunks.remove(record)
            runCatching { record.file.delete() }
            activeRecord = null
            activePayloadCrc = CRC32()
            return
        }

        record.state = ChunkState.FINALIZED
        record.payloadChecksum = activePayloadCrc.value.toInt()
        writeChunkHeader(record, access = activeAccess, forceToDisk = forceToDisk)
        closeActiveAccessLocked()
        activeRecord = null
        activePayloadCrc = CRC32()
        finalizedSinceIndex++
    }

    private fun recoverAndFinalizeRecordLocked(record: ChunkRecord) {
        val recovered = readChunkRecord(record.file, record.id) ?: run {
            chunks.remove(record)
            return
        }
        val replacement = recovered.copy(state = ChunkState.FINALIZED)
        val ordered = ArrayList(chunks)
        val index = ordered.indexOfFirst { it === record }
        if (index >= 0) {
            ordered[index] = replacement
            chunks.clear()
            ordered.forEach(chunks::addLast)
        }
    }

    private fun writeActiveHeaderLocked(forceToDisk: Boolean) {
        val record = activeRecord ?: return
        record.payloadChecksum = activePayloadCrc.value.toInt()
        writeChunkHeader(record, access = activeAccess, forceToDisk = forceToDisk)
    }

    private fun closeActiveAccessLocked() {
        runCatching { activeAccess?.close() }
        activeAccess = null
    }

    private fun cleanupRetentionLocked(): Boolean {
        var changed = false
        if (retentionValue <= 0L) {
            while (chunks.isNotEmpty()) {
                val record = chunks.removeFirst()
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
                var total = totalPayloadBytesLocked()
                while (total > retentionValue && chunks.isNotEmpty()) {
                    val oldest = chunks.first()
                    if (oldest === activeRecord) break
                    chunks.removeFirst()
                    total -= oldest.payloadBytes
                    retireRecordLocked(oldest)
                    changed = true
                }
            }

            RetentionMode.TIME -> {
                var total = totalDurationSecondsLocked()
                while (total > retentionValue.toDouble() && chunks.isNotEmpty()) {
                    val oldest = chunks.first()
                    if (oldest === activeRecord) break
                    chunks.removeFirst()
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

    private fun retireRecordLocked(record: ChunkRecord) {
        record.pendingDelete = true
        if (record.refCount <= 0) {
            runCatching { record.file.delete() }
        } else {
            retiredById[record.id] = record
        }
    }

    private fun releaseRecordLocked(record: ChunkRecord) {
        check(record.refCount > 0) { "Chunk refCount underflow for ${record.id}" }
        record.refCount--
        if (record.refCount == 0 && record.pendingDelete) {
            retiredById.remove(record.id)
            runCatching { record.file.delete() }
        }
    }

    private fun configuredFrameBytesLocked(): Int {
        if (configuredSampleRate <= 0 || configuredChannelCount <= 0) return 0
        return configuredChannelCount * configuredSampleFormat.bytesPerSample
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
        val rawLimit = minOf(CHUNK_PAYLOAD_BYTES.toLong(), retentionBound)
        return (rawLimit / frameBytes.toLong()) * frameBytes.toLong()
    }

    private fun totalPayloadBytesLocked(): Long {
        var total = 0L
        for (record in chunks) {
            total = safeAdd(total, record.payloadBytes)
        }
        return total
    }

    private fun totalDurationSecondsLocked(): Double {
        var total = 0.0
        for (record in chunks) total += record.durationSeconds
        return total
    }

    private fun writeChunkHeader(
        record: ChunkRecord,
        access: RandomAccessFile? = null,
        forceToDisk: Boolean,
    ) {
        val bytes = ByteArray(CHUNK_HEADER_BYTES)
        writeIntLE(bytes, 0, CHUNK_MAGIC)
        writeIntLE(bytes, 4, CHUNK_VERSION)
        writeIntLE(bytes, 8, record.id.toInt())
        writeIntLE(bytes, 12, record.state.code)
        writeLongLE(bytes, 16, record.createdAtMillis)
        writeLongLE(bytes, 24, record.payloadBytes)
        writeLongLE(bytes, 32, record.sampleFrames)
        writeIntLE(bytes, 40, record.sampleRate)
        writeIntLE(bytes, 44, record.channelCount)
        writeIntLE(bytes, 48, sampleFormatCode(record.sampleFormat))
        writeIntLE(bytes, 52, record.payloadChecksum)
        writeIntLE(bytes, 56, crc32(bytes, 0, 56))
        writeIntLE(bytes, 60, 0)

        if (access != null) {
            access.seek(0L)
            access.write(bytes)
            access.seek(CHUNK_HEADER_BYTES.toLong() + record.payloadBytes)
            if (forceToDisk) access.fd.sync()
        } else {
            RandomAccessFile(record.file, "rw").use { opened ->
                opened.seek(0L)
                opened.write(bytes)
                if (forceToDisk) opened.fd.sync()
            }
        }
    }

    private fun readChunkHeader(file: File): ParsedHeader? {
        if (file.length() < CHUNK_HEADER_BYTES) return null
        val bytes = ByteArray(CHUNK_HEADER_BYTES)
        RandomAccessFile(file, "r").use { access ->
            access.readFully(bytes)
        }
        if (readIntLE(bytes, 0) != CHUNK_MAGIC || readIntLE(bytes, 4) != CHUNK_VERSION) return null
        if (readIntLE(bytes, 56) != crc32(bytes, 0, 56)) return null
        val state = ChunkState.fromCode(readIntLE(bytes, 12)) ?: return null
        val sampleFormat = sampleFormatFromCode(readIntLE(bytes, 48)) ?: return null
        return ParsedHeader(
            id = readIntLE(bytes, 8).toUInt(),
            state = state,
            createdAtMillis = readLongLE(bytes, 16),
            payloadBytes = readLongLE(bytes, 24),
            sampleFrames = readLongLE(bytes, 32),
            sampleRate = readIntLE(bytes, 40),
            channelCount = readIntLE(bytes, 44),
            sampleFormat = sampleFormat,
            payloadChecksum = readIntLE(bytes, 52),
        )
    }

    private fun writeIndexLocked(forceToDisk: Boolean) {
        if (!loaded && chunksDirectory.exists().not()) return
        indexGeneration++
        val recordCount = chunks.size
        val bytes = ByteArray(INDEX_HEADER_BYTES + recordCount * INDEX_RECORD_BYTES + INDEX_CRC_BYTES)
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
        FileOutputStream(temp).use { output ->
            output.write(bytes)
            output.flush()
            if (forceToDisk) output.fd.sync()
        }
        try {
            Files.move(
                temp.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: Exception) {
            if (target.exists() && !target.delete()) {
                throw IOException("Unable to replace ${target.absolutePath}")
            }
            if (!temp.renameTo(target)) {
                throw IOException("Unable to commit ${target.absolutePath}")
            }
        }
        finalizedSinceIndex = 0
    }

    private fun readIndex(file: File): LoadedIndex? {
        if (!file.isFile || file.length() < INDEX_HEADER_BYTES + INDEX_CRC_BYTES) return null
        val bytes = runCatching { FileInputStream(file).use { it.readBytes() } }.getOrNull() ?: return null
        if (bytes.size < INDEX_HEADER_BYTES + INDEX_CRC_BYTES) return null
        if (readIntLE(bytes, 0) != INDEX_MAGIC || readIntLE(bytes, 4) != INDEX_VERSION) return null
        val count = readIntLE(bytes, 20)
        if (count < 0) return null
        val expectedSize = INDEX_HEADER_BYTES.toLong() + count.toLong() * INDEX_RECORD_BYTES + INDEX_CRC_BYTES
        if (expectedSize != bytes.size.toLong()) return null
        if (readIntLE(bytes, bytes.size - INDEX_CRC_BYTES) != crc32(bytes, 0, bytes.size - INDEX_CRC_BYTES)) return null

        val records = ArrayList<IndexRecord>(count)
        var offset = INDEX_HEADER_BYTES
        repeat(count) {
            val state = ChunkState.fromCode(readIntLE(bytes, offset + 4)) ?: return null
            val sampleFormat = sampleFormatFromCode(readIntLE(bytes, offset + 40)) ?: return null
            records += IndexRecord(
                id = readIntLE(bytes, offset).toUInt(),
                state = state,
                createdAtMillis = readLongLE(bytes, offset + 8),
                payloadBytes = readLongLE(bytes, offset + 16),
                sampleFrames = readLongLE(bytes, offset + 24),
                sampleRate = readIntLE(bytes, offset + 32),
                channelCount = readIntLE(bytes, offset + 36),
                sampleFormat = sampleFormat,
                payloadChecksum = readIntLE(bytes, offset + 44),
            )
            offset += INDEX_RECORD_BYTES
        }
        return LoadedIndex(
            generation = readLongLE(bytes, 8),
            nextChunkId = readIntLE(bytes, 16).toUInt(),
            records = records,
        )
    }

    private fun crc32FilePayload(file: File, payloadBytes: Long): Int {
        val crc = CRC32()
        RandomAccessFile(file, "r").use { input ->
            input.seek(CHUNK_HEADER_BYTES.toLong())
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
        const val CHUNK_VERSION = 1
        const val CHUNK_HEADER_BYTES = 64
        const val CHUNK_PAYLOAD_BYTES = 1024 * 1024

        const val INDEX_MAGIC = 0x52564958 // RVIX
        const val INDEX_VERSION = 1
        const val INDEX_HEADER_BYTES = 24
        const val INDEX_RECORD_BYTES = 48
        const val INDEX_CRC_BYTES = 4
        const val INDEX_CHUNK_INTERVAL = 16

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
