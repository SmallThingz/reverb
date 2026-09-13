package app.smallthingz.reverb

import android.content.Context
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.ArrayDeque
import java.util.zip.CRC32
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToLong

internal fun normalizeRetentionValue(
    retentionMode: RetentionMode,
    requestedRetentionValue: Long,
    frameBytes: Int,
): Long {
    val nonNegative = requestedRetentionValue.coerceAtLeast(0L)
    if (retentionMode != RetentionMode.SIZE) return nonNegative
    if (frameBytes <= 0) return 0L
    return nonNegative - nonNegative % frameBytes.toLong()
}

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
            val targetFrames = retentionValue.toDouble() * sampleRate.toDouble()
            val retainedFrames = retainedDurationSeconds.coerceAtLeast(0.0) * sampleRate.toDouble()
            val rawRemainingFrames = targetFrames - retainedFrames
            // Summing durations from chunks with different sample rates necessarily uses
            // floating point. Recover only the tiny error band around an integer frame;
            // the 0.25-frame cap prevents the tolerance from extending retention.
            val roundingTolerance = minOf(
                0.25,
                8.0 * (Math.ulp(targetFrames) + Math.ulp(retainedFrames)),
            )
            val remainingFrames = floor((rawRemainingFrames + roundingTolerance).coerceAtLeast(0.0)).toLong()
            if (remainingFrames > Long.MAX_VALUE / frameBytes.toLong()) Long.MAX_VALUE
            else remainingFrames * frameBytes.toLong()
        }
    }
    return remaining - remaining % frameBytes.toLong()
}

internal fun oneShotRetainedChunkBytes(
    retentionValue: Long,
    retainedBeforeChunk: Long,
    chunkPayloadBytes: Long,
    frameBytes: Int,
): Long {
    if (retentionValue <= retainedBeforeChunk || chunkPayloadBytes <= 0L || frameBytes <= 0) return 0L
    val available = minOf(retentionValue - retainedBeforeChunk, chunkPayloadBytes)
    return available - available % frameBytes.toLong()
}

internal fun oneShotRetainedChunkBytesForTime(
    retentionSeconds: Long,
    retainedDurationBeforeChunk: Double,
    chunkSampleFrames: Long,
    sampleRate: Int,
    frameBytes: Int,
): Long {
    if (retentionSeconds <= 0L || chunkSampleFrames <= 0L || sampleRate <= 0 || frameBytes <= 0) return 0L
    val targetFrames = retentionSeconds.toDouble() * sampleRate.toDouble()
    val retainedFrames = retainedDurationBeforeChunk.coerceAtLeast(0.0) * sampleRate.toDouble()
    val rawRemainingFrames = targetFrames - retainedFrames
    if (rawRemainingFrames <= 0.0) return 0L
    val roundingTolerance = minOf(
        0.25,
        8.0 * (Math.ulp(targetFrames) + Math.ulp(retainedFrames)),
    )
    val keepFrames = floor(rawRemainingFrames + roundingTolerance).toLong()
        .coerceIn(0L, chunkSampleFrames)
    return keepFrames * frameBytes.toLong()
}

/**
 * Disk-backed append-only PCM timeline.
 *
 * Completed chunks are immutable. The only mutable audio file is the newest ACTIVE chunk.
 * Chronology lives in [chunks], not in numeric filename ordering, so UInt32 wrap is harmless.
 */
internal class PersistentAudioChunkStore internal constructor(
    private val rootDirectory: File,
    private val legacyDirectory: File?,
    private val overwriteOldest: Boolean,
) : Closeable {
    constructor(
        context: Context,
        cacheFolderName: String = ReverbConfig.BUFFER_CACHE_FOLDER_NAME,
        legacyCacheFolderName: String? = ReverbConfig.LEGACY_BUFFER_CACHE_FOLDER_NAME,
        overwriteOldest: Boolean = true,
    ) : this(
        rootDirectory = File(context.noBackupFilesDir, cacheFolderName),
        legacyDirectory = legacyCacheFolderName?.let { File(context.noBackupFilesDir, it) },
        overwriteOldest = overwriteOldest,
    )

    internal constructor(
        rootDirectory: File,
        overwriteOldest: Boolean = true,
    ) : this(rootDirectory, legacyDirectory = null, overwriteOldest = overwriteOldest)

    private val chunksDirectory = File(rootDirectory, ReverbConfig.BUFFER_CHUNKS_FOLDER_NAME)
    private val indexA = File(rootDirectory, ReverbConfig.BUFFER_INDEX_A_FILE_NAME)
    private val indexB = File(rootDirectory, ReverbConfig.BUFFER_INDEX_B_FILE_NAME)
    private val quarantineDirectory = File(rootDirectory, "preserved")

    private val chunks = ArrayDeque<ChunkRecord>()
    private val liveChunkIds = HashSet<UInt>()
    private val retiredById = HashMap<UInt, ChunkRecord>()

    private var loaded = false
    private var closed = false
    private var indexGeneration = 0L
    private var nextChunkId = 0u
    private var retainedPayloadBytes = 0L
    private var retainedDurationSeconds = 0.0
    private var retainedDurationCompensation = 0.0
    private var pendingOneShotRetentionTruncation = false

    private var retentionMode = RetentionMode.SIZE
    private var retentionValue = Long.MAX_VALUE
    private var configuredSampleRate = 0
    private var configuredChannelCount = 0
    private var configuredSampleFormat = PcmSampleFormat.PCM_16

    private var activeRecord: ChunkRecord? = null
    private var activeAccess: RandomAccessFile? = null
    private var activePayloadCrc = CRC32()
    private var activeDurablePayloadBytes = 0L
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

        val validFormat = requestedSampleRate > 0 && requestedChannelCount in 1..MAX_CHANNEL_COUNT
        val normalizedSampleRate = if (validFormat) requestedSampleRate else 0
        val normalizedChannelCount = if (validFormat) requestedChannelCount else 0
        val normalizedFrameBytes = if (validFormat) {
            normalizedChannelCount * sampleFormat.bytesPerSample
        } else {
            0
        }
        val normalizedRetention = normalizeRetentionValue(
            requestedRetentionMode,
            requestedRetentionValue,
            normalizedFrameBytes,
        )
        val formatChanged =
            configuredSampleRate != normalizedSampleRate ||
                configuredChannelCount != normalizedChannelCount ||
                configuredSampleFormat != sampleFormat

        if (formatChanged || normalizedRetention == 0L) {
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
        val cleaned = if (overwriteOldest) {
            normalizedRetention > 0L && cleanupRetentionLocked()
        } else {
            truncateOneShotRetentionLocked()
        }
        if (cleaned || formatChanged || normalizedRetention == 0L) {
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
            addRetainedDurationLocked(writtenFrames.toDouble() / record.sampleRate.toDouble())
            sourceOffset += alignedWriteCount
            remaining -= alignedWriteCount
            lastWriteAtMillis = System.currentTimeMillis()

            // Finalize a full replacement before evicting anything it displaced. For a
            // partial active chunk, force its payload durable before retiring an older
            // chunk so sudden power loss cannot lose both the old and replacement audio.
            if (record.payloadBytes >= limit) {
                finalizeActiveLocked()
            }
            if (overwriteOldest) {
                if (retentionCleanupWillRetireChunkLocked()) syncActivePayloadLocked()
                cleanupRetentionLocked()
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
    fun isFull(): Boolean {
        ensureLoadedLocked()
        if (overwriteOldest) return retentionValue <= 0L
        val frameBytes = configuredFrameBytesLocked()
        if (frameBytes <= 0 || retentionValue <= 0L) return true
        return writableBytesLocked(frameBytes) < frameBytes.toLong()
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
            val selectedSegmentDuration = frameCount.toDouble() / record.sampleRate.toDouble()
            segments += Segment(
                record = record,
                startFrame = startFrame,
                frameCount = frameCount,
                payloadOffsetBytes = record.payloadOffsetBytes,
                payloadBytesAtAcquire = record.payloadBytes,
                payloadChecksumAtAcquire = record.payloadChecksum,
                timelineStartSeconds = leaseDuration,
                timelineEndSeconds = leaseDuration + selectedSegmentDuration,
            )
            if (startedAtMillis == 0L) {
                startedAtMillis = record.createdAtMillis +
                    (startFrame * 1000L / record.sampleRate.coerceAtLeast(1))
            }
            endedAtMillis = record.createdAtMillis +
                (endFrame * 1000L / record.sampleRate.coerceAtLeast(1))
            leaseDuration += selectedSegmentDuration
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

    fun syncActivePayloadToDisk(): Long {
        val snapshot = synchronized(this) {
            if (closed) return 0L
            ensureLoadedLocked()
            val record = activeRecord ?: return 0L
            if (record.payloadBytes <= activeDurablePayloadBytes) return 0L
            ActivePayloadSyncSnapshot(record.id, record.file, record.payloadBytes)
        }

        try {
            // WRITE without CREATE cannot resurrect a chunk that was finalized and
            // retired after the snapshot, while force(true) flushes its file data/metadata.
            FileChannel.open(snapshot.file.toPath(), StandardOpenOption.WRITE).use { channel ->
                channel.force(true)
            }
        } catch (_: NoSuchFileException) {
            // Finalization force-syncs before retirement, so disappearance here means
            // another synchronized store operation already made this snapshot obsolete.
            return 0L
        }

        synchronized(this) {
            val record = activeRecord
            if (record != null && record.id == snapshot.id) {
                activeDurablePayloadBytes = maxOf(
                    activeDurablePayloadBytes,
                    minOf(snapshot.payloadBytes, record.payloadBytes),
                )
            }
        }
        return snapshot.payloadBytes
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
        activeDurablePayloadBytes = 0L

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
                    val childDuration = childFrames.toDouble() / rate.toDouble()
                    selected += segment.copy(
                        startFrame = absoluteStartFrame,
                        frameCount = childFrames,
                        timelineStartSeconds = selectedDuration,
                        timelineEndSeconds = selectedDuration + childDuration,
                    )
                    if (selectedStartedAtMillis == 0L) {
                        selectedStartedAtMillis = segment.record.createdAtMillis +
                            (absoluteStartFrame * 1000L / rate)
                    }
                    selectedEndedAtMillis = segment.record.createdAtMillis +
                        ((absoluteStartFrame + childFrames) * 1000L / rate)
                    selectedDuration += childDuration
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

        /**
         * Samples a fixed PCM budget across the leased timeline, left to right. The amount
         * of audio read depends only on the bucket/probe configuration, not retained duration.
         * Returning false from [onBucket] cancels the remaining construction.
         */
        @Synchronized
        fun sampleWaveformEnvelopeProgressive(
            bucketCount: Int,
            probesPerBucket: Int = 2,
            framesPerProbe: Int = 24,
            onBucket: (bucketIndex: Int, magnitude: Float) -> Boolean,
        ): FloatArray {
            check(!closedLease) { "RangeLease is closed" }
            val buckets = bucketCount.coerceIn(16, 512)
            val probes = probesPerBucket.coerceIn(1, 16)
            val frames = framesPerProbe.coerceIn(1, 64)
            val envelope = FloatArray(buckets)
            if (durationSeconds <= 0.0 || segments.isEmpty()) return envelope

            val scratch = ByteArray(frames * MAX_CHANNEL_COUNT * PcmSampleFormat.PCM_FLOAT.bytesPerSample)
            var currentFile: File? = null
            var currentAccess: RandomAccessFile? = null
            try {
                for (bucket in 0 until buckets) {
                    val bucketStart = durationSeconds * bucket.toDouble() / buckets.toDouble()
                    val bucketEnd = durationSeconds * (bucket + 1).toDouble() / buckets.toDouble()
                    var peak = 0f
                    repeat(probes) { probe ->
                        val timelineSeconds = bucketStart +
                            (bucketEnd - bucketStart) * (probe.toDouble() + 0.5) / probes.toDouble()
                        val segmentIndex = segmentIndexAt(timelineSeconds)
                        val segment = segments[segmentIndex]
                        val record = segment.record
                        if (record.sampleRate <= 0 || record.frameBytes <= 0 || segment.frameCount <= 0L) {
                            return@repeat
                        }
                        val localSeconds = (timelineSeconds - segment.timelineStartSeconds).coerceAtLeast(0.0)
                        val centerFrame = (localSeconds * record.sampleRate.toDouble()).toLong()
                            .coerceIn(0L, segment.frameCount - 1L)
                        val readFrames = minOf(frames.toLong(), segment.frameCount).toInt()
                        val localStartFrame = (centerFrame - readFrames / 2L)
                            .coerceIn(0L, segment.frameCount - readFrames.toLong())
                        val absoluteStartFrame = segment.startFrame + localStartFrame
                        val byteOffset = absoluteStartFrame * record.frameBytes.toLong()
                        val readBytes = readFrames * record.frameBytes
                        if (
                            byteOffset < 0L ||
                            byteOffset + readBytes.toLong() > segment.payloadBytesAtAcquire
                        ) {
                            return@repeat
                        }

                        if (currentFile != record.file) {
                            runCatching { currentAccess?.close() }
                            currentFile = record.file
                            currentAccess = RandomAccessFile(record.file, "r")
                        }
                        val access = currentAccess ?: return@repeat
                        access.seek(segment.payloadOffsetBytes + byteOffset)
                        access.readFully(scratch, 0, readBytes)
                        var offset = 0
                        repeat(readFrames) {
                            repeat(record.channelCount) {
                                peak = maxOf(peak, waveformSampleMagnitude(scratch, offset, record.sampleFormat))
                                offset += record.sampleFormat.bytesPerSample
                            }
                        }
                    }
                    val value = peak.coerceIn(0f, 1f)
                    envelope[bucket] = value
                    if (!onBucket(bucket, value)) break
                }
            } finally {
                runCatching { currentAccess?.close() }
            }
            return envelope
        }

        private fun segmentIndexAt(seconds: Double): Int {
            var low = 0
            var high = segments.lastIndex
            while (low < high) {
                val middle = (low + high) ushr 1
                if (seconds < segments[middle].timelineEndSeconds) high = middle else low = middle + 1
            }
            return low.coerceIn(0, segments.lastIndex)
        }

        private fun waveformSampleMagnitude(
            bytes: ByteArray,
            offset: Int,
            format: PcmSampleFormat,
        ): Float = when (format) {
            PcmSampleFormat.PCM_8 ->
                kotlin.math.abs(((bytes[offset].toInt() and 0xff) - 128) / 128f)
            PcmSampleFormat.PCM_16 -> {
                val value = (
                    (bytes[offset].toInt() and 0xff) or
                        (bytes[offset + 1].toInt() shl 8)
                    ).toShort().toInt()
                kotlin.math.abs(value / 32768f)
            }
            PcmSampleFormat.PCM_FLOAT -> {
                val bits =
                    (bytes[offset].toInt() and 0xff) or
                        ((bytes[offset + 1].toInt() and 0xff) shl 8) or
                        ((bytes[offset + 2].toInt() and 0xff) shl 16) or
                        (bytes[offset + 3].toInt() shl 24)
                val value = Float.fromBits(bits)
                if (value.isFinite()) kotlin.math.abs(value).coerceIn(0f, 1f) else 0f
            }
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
        val timelineStartSeconds: Double,
        val timelineEndSeconds: Double,
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

        val rootExisted = rootDirectory.exists()
        if (!rootExisted && !rootDirectory.mkdirs() && !rootDirectory.exists()) {
            throw IllegalStateException("Unable to create chunk storage: ${rootDirectory.absolutePath}")
        }
        if (!rootExisted) {
            rootDirectory.parentFile?.takeIf { it.isDirectory }?.let(::forceDirectoryDurable)
        }
        val chunksExisted = chunksDirectory.exists()
        if (!chunksExisted && !chunksDirectory.mkdirs() && !chunksDirectory.exists()) {
            throw IllegalStateException("Unable to create chunks directory: ${chunksDirectory.absolutePath}")
        }
        if (!chunksExisted) forceDirectoryDurable(rootDirectory)

        // Never delete an older buffer format automatically. Even if this version cannot
        // decode it, those bytes may be the only surviving copy after a downgrade/upgrade.
        // A future explicit migration can consume it; user data must not be cleanup collateral.
        legacyDirectory?.let { legacy ->
            if (legacy != rootDirectory && legacy.exists()) {
                // Intentionally preserved.
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
                preserveUnrecognizedChunkLocked(file, "unrecognized")
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
                preserveUnrecognizedChunkLocked(file, "corrupt")
                continue
            }
            if (result.put(id, record) != null) {
                throw IOException("Duplicate chunk id on disk: $id")
            }
        }
        return result
    }

    private fun preserveUnrecognizedChunkLocked(file: File, reason: String) {
        ensureQuarantineDirectoryDurableLocked()
        var suffix = 0
        while (true) {
            val suffixText = if (suffix == 0) "" else ".$suffix"
            val target = File(quarantineDirectory, "${file.name}.$reason$suffixText")
            if (target.exists()) {
                suffix++
                continue
            }
            try {
                Files.move(file.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(file.toPath(), target.toPath())
            }
            forceDirectoryDurable(quarantineDirectory)
            forceDirectoryDurable(chunksDirectory)
            return
        }
    }

    private fun preserveFileCopyLocked(file: File, reason: String) {
        ensureQuarantineDirectoryDurableLocked()
        var suffix = 0
        while (true) {
            val suffixText = if (suffix == 0) "" else ".$suffix"
            val target = File(quarantineDirectory, "${file.name}.$reason$suffixText")
            if (target.exists()) {
                suffix++
                continue
            }
            FileInputStream(file).use { input ->
                FileOutputStream(target).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }
            forceDirectoryDurable(quarantineDirectory)
            return
        }
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

        // The index says these chunks are no longer in the live timeline, but a crash can
        // make that metadata newer than the user's last recoverable audio. Preserve rather
        // than delete; intentional retention cleanup only destroys chunks while the store is live.
        for (stale in scanned.values) {
            preserveUnrecognizedChunkLocked(stale.file, "stale-index")
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
            // No complete frame can be recovered, but preserve the bytes for forensic/future
            // recovery instead of silently deleting a crash-torn write.
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
        // independently checksummed, so payload geometry can be reconstructed safely. If
        // the crash tore the final frame, preserve the original bytes before aligning the
        // live copy; even undecodable trailing bytes are never silently destroyed.
        if (actualPayload != alignedActualPayload) {
            preserveFileCopyLocked(file, "partial-frame")
        }
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
            forceDirectoryDurable(chunksDirectory)
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
        activeDurablePayloadBytes = 0L
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
            activeDurablePayloadBytes = 0L
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
            activeDurablePayloadBytes = 0L
            throw error
        }
        closeActiveAccessLocked()
        activeRecord = null
        activePayloadCrc = CRC32()
        activeDurablePayloadBytes = 0L
    }

    private fun syncActivePayloadLocked() {
        val record = activeRecord ?: return
        if (record.payloadBytes <= activeDurablePayloadBytes) return
        requireNotNull(activeAccess).fd.sync()
        activeDurablePayloadBytes = record.payloadBytes
    }

    private fun writeActiveHeaderLocked() {
        val record = activeRecord ?: return
        record.payloadChecksum = activePayloadCrc.value.toInt()
        writeMutableChunkSlot(record, access = activeAccess, forceToDisk = true)
        activeDurablePayloadBytes = record.payloadBytes
    }

    private fun closeActiveAccessLocked() {
        runCatching { activeAccess?.close() }
        activeAccess = null
    }

    private fun truncateOneShotRetentionLocked(): Boolean {
        if (overwriteOldest) {
            pendingOneShotRetentionTruncation = false
            return false
        }
        if (!retentionExceededLocked()) {
            pendingOneShotRetentionTruncation = false
            return false
        }

        finalizeActiveLocked()
        val current = chunks.toList()
        var retainedBytesBeforeChunk = 0L
        var retainedDurationBeforeChunk = 0.0
        var cutoffIndex = current.size
        var partialRecord: ChunkRecord? = null
        var partialPayloadBytes = 0L

        for ((index, record) in current.withIndex()) {
            val keepBytes = when (retentionMode) {
                RetentionMode.SIZE -> oneShotRetainedChunkBytes(
                    retentionValue = retentionValue,
                    retainedBeforeChunk = retainedBytesBeforeChunk,
                    chunkPayloadBytes = record.payloadBytes,
                    frameBytes = record.frameBytes,
                )
                RetentionMode.TIME -> oneShotRetainedChunkBytesForTime(
                    retentionSeconds = retentionValue,
                    retainedDurationBeforeChunk = retainedDurationBeforeChunk,
                    chunkSampleFrames = record.sampleFrames,
                    sampleRate = record.sampleRate,
                    frameBytes = record.frameBytes,
                )
            }
            if (keepBytes == record.payloadBytes) {
                retainedBytesBeforeChunk += record.payloadBytes
                retainedDurationBeforeChunk += record.durationSeconds
                continue
            }
            cutoffIndex = index
            if (keepBytes > 0L) {
                partialRecord = record
                partialPayloadBytes = keepBytes
            }
            break
        }

        if (partialRecord != null && partialRecord.refCount > 0) {
            pendingOneShotRetentionTruncation = true
            return false
        }

        if (partialRecord != null) {
            truncateFinalizedChunkLocked(partialRecord, partialPayloadBytes)
            cutoffIndex++
        }

        for (index in current.lastIndex downTo cutoffIndex) {
            val record = current[index]
            if (removeChunkLocked(record)) retireRecordLocked(record)
        }

        lastWriteAtMillis = chunks.lastOrNull()?.let { newest ->
            newest.createdAtMillis + (newest.durationSeconds * 1000.0).toLong()
        } ?: 0L
        pendingOneShotRetentionTruncation = retentionExceededLocked()
        return true
    }

    private fun truncateFinalizedChunkLocked(record: ChunkRecord, payloadBytes: Long) {
        require(record.refCount == 0) { "Cannot truncate a referenced chunk" }
        require(payloadBytes in 1 until record.payloadBytes) { "Invalid truncated payload size: $payloadBytes" }
        require(payloadBytes % record.frameBytes.toLong() == 0L) { "Truncated payload must be frame aligned" }

        if (record === activeRecord) finalizeActiveLocked()
        val temp = File(chunksDirectory, "${record.id}.truncate.tmp")
        if (temp.exists()) {
            preserveUnrecognizedChunkLocked(temp, "stale-truncation")
        }

        val replacement = ChunkRecord(
            id = record.id,
            file = temp,
            state = ChunkState.ACTIVE,
            createdAtMillis = record.createdAtMillis,
            payloadBytes = 0L,
            sampleFrames = 0L,
            sampleRate = record.sampleRate,
            channelCount = record.channelCount,
            sampleFormat = record.sampleFormat,
            payloadChecksum = 0,
            headerGeneration = 0L,
            payloadOffsetBytes = CHUNK_HEADER_BYTES.toLong(),
        )
        var output: RandomAccessFile? = null
        try {
            if (!temp.createNewFile()) throw IOException("Unable to create truncation artifact: ${temp.absolutePath}")
            output = RandomAccessFile(temp, "rw")
            writeInitialChunkHeader(replacement, output)
            val checksum = CRC32()
            RandomAccessFile(record.file, "r").use { input ->
                input.seek(record.payloadOffsetBytes)
                output.seek(replacement.payloadOffsetBytes)
                val scratch = ByteArray(64 * 1024)
                var remaining = payloadBytes
                while (remaining > 0L) {
                    val count = minOf(scratch.size.toLong(), remaining).toInt()
                    input.readFully(scratch, 0, count)
                    output.write(scratch, 0, count)
                    checksum.update(scratch, 0, count)
                    remaining -= count.toLong()
                }
            }
            replacement.payloadBytes = payloadBytes
            replacement.sampleFrames = payloadBytes / replacement.frameBytes.toLong()
            replacement.payloadChecksum = checksum.value.toInt()
            replacement.state = ChunkState.FINALIZED
            writeMutableChunkSlot(replacement, access = output, forceToDisk = true)
            output.close()
            output = null
            try {
                Files.move(
                    temp.toPath(),
                    record.file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), record.file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (error: Exception) {
            runCatching { output?.close() }
            runCatching { temp.delete() }
            throw error
        }

        val oldDuration = record.durationSeconds
        val removedBytes = record.payloadBytes - replacement.payloadBytes
        record.state = replacement.state
        record.payloadBytes = replacement.payloadBytes
        record.sampleFrames = replacement.sampleFrames
        record.payloadChecksum = replacement.payloadChecksum
        record.headerGeneration = replacement.headerGeneration
        retainedPayloadBytes = (retainedPayloadBytes - removedBytes).coerceAtLeast(0L)
        addRetainedDurationLocked(record.durationSeconds - oldDuration)
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
                while (retainedPayloadBytes > retentionValue && chunks.isNotEmpty()) {
                    val oldest = chunks.first()
                    if (oldest === activeRecord) break
                    removeFirstChunkLocked()
                    retireRecordLocked(oldest)
                    changed = true
                }
            }

            RetentionMode.TIME -> {
                while (retainedDurationSeconds > retentionValue.toDouble() && chunks.isNotEmpty()) {
                    val oldest = chunks.first()
                    if (oldest === activeRecord) break
                    removeFirstChunkLocked()
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

    private fun retentionCleanupWillRetireChunkLocked(): Boolean {
        if (!overwriteOldest || !retentionExceededLocked()) return false
        val oldest = chunks.firstOrNull() ?: return false
        return oldest !== activeRecord && activeRecord?.payloadBytes?.let { it > activeDurablePayloadBytes } == true
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
        if (!closed && pendingOneShotRetentionTruncation && record.refCount == 0) {
            if (truncateOneShotRetentionLocked()) writeIndexLocked()
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
        addRetainedDurationLocked(record.durationSeconds)
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
        addRetainedDurationLocked(-record.durationSeconds)
        if (chunks.isEmpty()) {
            retainedDurationSeconds = 0.0
            retainedDurationCompensation = 0.0
        } else if (retainedDurationSeconds < 0.0) {
            retainedDurationSeconds = 0.0
            retainedDurationCompensation = 0.0
        }
    }

    private fun addRetainedDurationLocked(deltaSeconds: Double) {
        val adjusted = deltaSeconds - retainedDurationCompensation
        val updated = retainedDurationSeconds + adjusted
        retainedDurationCompensation = (updated - retainedDurationSeconds) - adjusted
        retainedDurationSeconds = updated
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

    private fun ensureQuarantineDirectoryDurableLocked() {
        val existed = quarantineDirectory.exists()
        if (!existed && !quarantineDirectory.mkdirs() && !quarantineDirectory.exists()) {
            throw IOException("Unable to create preserved chunk directory: ${quarantineDirectory.absolutePath}")
        }
        if (!existed) forceDirectoryDurable(rootDirectory)
    }

    private fun forceDirectoryDurable(directory: File) {
        FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { channel ->
            channel.force(true)
        }
    }

    private data class ActivePayloadSyncSnapshot(
        val id: UInt,
        val file: File,
        val payloadBytes: Long,
    )

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
