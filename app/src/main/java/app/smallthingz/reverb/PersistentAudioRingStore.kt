package app.smallthingz.reverb

import android.content.Context
import android.os.SystemClock
import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.zip.CRC32

/**
 * Disk-backed circular PCM history.
 *
 * Audio bytes are the source of truth. Only a small metadata record is kept in memory, so
 * retention is not constrained by the Java heap or by MappedByteBuffer's Int-sized offsets.
 */
internal class PersistentAudioRingStore(
    context: Context,
) : Closeable {
    private val directory = File(
        context.noBackupFilesDir,
        ReverbConfig.BUFFER_CACHE_FOLDER_NAME,
    ).also { directory ->
        if (!directory.exists() && !directory.mkdirs() && !directory.exists()) {
            throw IllegalStateException("Unable to create buffer cache directory: ${directory.absolutePath}")
        }
    }
    private val metaFile = File(directory, ReverbConfig.BUFFER_META_FILE_NAME)
    private val dataFile = File(directory, ReverbConfig.BUFFER_PCM_FILE_NAME)

    private var metaAccess: RandomAccessFile? = null
    private var dataAccess: RandomAccessFile? = null
    private var loaded = false
    private var closed = false

    private var metadataGeneration = 0L
    private var storageGeneration = 0L
    private var capacityBytes = 0L
    private var totalWrittenBytes = 0L
    private var filledBytes = 0L
    private var lastWriteAtMillis = 0L
    private var sampleRate = 0
    private var channelCount = 0
    private var bytesPerSample = 0
    private var lastMetadataWriteAtUptimeMillis = 0L

    data class Snapshot(
        val capacityBytes: Long,
        val sampleRate: Int,
        val channelCount: Int,
        val bytesPerSample: Int,
        val filledBytes: Long,
        val lastWriteAtMillis: Long,
    )

    fun interface Consumer {
        fun consume(array: ByteArray, offset: Int, count: Int): Int
    }

    @Synchronized
    fun configure(
        requestedCapacityBytes: Long,
        requestedSampleRate: Int,
        requestedChannelCount: Int,
        sampleFormat: PcmSampleFormat = PcmSampleFormat.PCM_16,
    ) {
        ensureLoadedLocked()
        val frameBytes = requestedChannelCount.toLong() * sampleFormat.bytesPerSample.toLong()
        val normalizedCapacity = if (frameBytes > 0L) {
            (requestedCapacityBytes.coerceAtLeast(0L) / frameBytes) * frameBytes
        } else {
            0L
        }
        if (normalizedCapacity <= 0L || requestedSampleRate <= 0 || requestedChannelCount <= 0) {
            resetLocked(
                capacity = 0L,
                rate = 0,
                channels = 0,
                sampleBytes = 0,
                forceToDisk = true,
            )
            return
        }

        val unchanged =
            capacityBytes == normalizedCapacity &&
                sampleRate == requestedSampleRate &&
                channelCount == requestedChannelCount &&
                bytesPerSample == sampleFormat.bytesPerSample
        if (unchanged) return

        // Alpha storage format: incompatible sizing or PCM settings intentionally reset history.
        resetLocked(
            capacity = normalizedCapacity,
            rate = requestedSampleRate,
            channels = requestedChannelCount,
            sampleBytes = sampleFormat.bytesPerSample,
            forceToDisk = true,
        )
    }

    @Synchronized
    fun append(
        array: ByteArray,
        offset: Int,
        count: Int,
        requestedCapacityBytes: Long,
        requestedSampleRate: Int,
        requestedChannelCount: Int,
        sampleFormat: PcmSampleFormat = PcmSampleFormat.PCM_16,
    ) {
        require(offset >= 0 && count >= 0 && offset <= array.size - count) {
            "Invalid PCM range offset=$offset count=$count size=${array.size}"
        }
        if (count == 0) return

        configure(
            requestedCapacityBytes,
            requestedSampleRate,
            requestedChannelCount,
            sampleFormat,
        )
        if (capacityBytes <= 0L) return

        val access = dataAccessLocked()
        var sourceOffset = offset
        var remaining = count

        while (remaining > 0) {
            val writePosition = totalWrittenBytes % capacityBytes
            val chunk = minOf(remaining.toLong(), capacityBytes - writePosition).toInt()
            access.seek(writePosition)
            access.write(array, sourceOffset, chunk)
            sourceOffset += chunk
            remaining -= chunk
            totalWrittenBytes += chunk.toLong()
        }
        filledBytes = minOf(capacityBytes, filledBytes + count.toLong())
        lastWriteAtMillis = System.currentTimeMillis()
        maybeCheckpointLocked()
    }

    /**
     * Streams one logical range from oldest to newest without pinning the store lock while the
     * consumer writes. Returns false if live capture overwrites any unread part of the range.
     */
    fun read(
        skipBytes: Long,
        maxBytes: Long,
        consumer: Consumer,
    ): Boolean {
        val normalizedSkip = skipBytes.coerceAtLeast(0L)
        val normalizedMax = maxBytes.coerceAtLeast(0L)
        if (normalizedMax <= 0L) return false

        val plan = synchronized(this) {
            ensureLoadedLocked()
            if (capacityBytes <= 0L || filledBytes <= 0L) return false
            if (normalizedSkip > filledBytes || normalizedMax > filledBytes - normalizedSkip) return false
            val oldestAbsolute = totalWrittenBytes - filledBytes
            ReadPlan(
                storageGeneration = storageGeneration,
                capacityBytes = capacityBytes,
                startAbsolute = oldestAbsolute + normalizedSkip,
                endAbsolute = oldestAbsolute + normalizedSkip + normalizedMax,
            )
        }

        val scratch = ByteArray(IO_CHUNK_SIZE)
        var nextAbsolute = plan.startAbsolute
        while (nextAbsolute < plan.endAbsolute) {
            val count = synchronized(this) {
                ensureLoadedLocked()
                if (storageGeneration != plan.storageGeneration || capacityBytes != plan.capacityBytes) {
                    return false
                }
                val currentOldest = totalWrittenBytes - filledBytes
                if (nextAbsolute < currentOldest) {
                    return false
                }
                val physicalOffset = nextAbsolute % plan.capacityBytes
                val wanted = minOf(
                    scratch.size.toLong(),
                    plan.endAbsolute - nextAbsolute,
                    plan.capacityBytes - physicalOffset,
                ).toInt()
                val access = dataAccessLocked()
                try {
                    access.seek(physicalOffset)
                    access.readFully(scratch, 0, wanted)
                } catch (_: EOFException) {
                    return false
                }
                wanted
            }
            if (consumer.consume(scratch, 0, count) != count) return false
            nextAbsolute += count.toLong()
        }
        return true
    }

    @Synchronized
    fun peekSnapshot(): Snapshot? {
        ensureLoadedLocked()
        if (capacityBytes <= 0L || filledBytes <= 0L || sampleRate <= 0 || channelCount <= 0) {
            return null
        }
        return Snapshot(
            capacityBytes = capacityBytes,
            sampleRate = sampleRate,
            channelCount = channelCount,
            bytesPerSample = bytesPerSample,
            filledBytes = filledBytes,
            lastWriteAtMillis = lastWriteAtMillis,
        )
    }

    @Synchronized
    fun hasData(): Boolean {
        ensureLoadedLocked()
        return capacityBytes > 0L && filledBytes > 0L && sampleRate > 0 && channelCount > 0
    }

    @Synchronized
    fun countFilledBytes(): Long {
        ensureLoadedLocked()
        return filledBytes.coerceIn(0L, capacityBytes.coerceAtLeast(0L))
    }

    @Synchronized
    fun checkpoint() {
        ensureLoadedLocked()
        checkpointLocked(forceToDisk = true)
    }

    @Synchronized
    fun clear() {
        ensureLoadedLocked()
        if (capacityBytes <= 0L && filledBytes == 0L) return
        totalWrittenBytes = 0L
        filledBytes = 0L
        lastWriteAtMillis = 0L
        storageGeneration++
        runCatching { dataAccessLocked().setLength(0L) }
        checkpointLocked(forceToDisk = true)
    }

    @Synchronized
    override fun close() {
        if (closed) return
        if (loaded) {
            runCatching { checkpointLocked(forceToDisk = true) }
            runCatching { dataAccess?.close() }
            runCatching { metaAccess?.close() }
            dataAccess = null
            metaAccess = null
            loaded = false
        }
        closed = true
    }

    @Synchronized
    internal fun testStorageFiles(): Pair<File, File> = metaFile to dataFile

    private fun ensureLoadedLocked() {
        check(!closed) { "PersistentAudioRingStore is closed" }
        if (loaded) return
        val openedMeta = RandomAccessFile(metaFile, "rw")
        val openedData = try {
            RandomAccessFile(dataFile, "rw")
        } catch (error: Throwable) {
            runCatching { openedMeta.close() }
            throw error
        }
        metaAccess = openedMeta
        dataAccess = openedData
        loaded = true

        val first = readMetadataSlotLocked(0)
        val second = readMetadataSlotLocked(1)
        val restored = listOfNotNull(first, second).maxByOrNull { it.generation }
        if (restored == null) {
            metadataGeneration = 0L
            return
        }
        metadataGeneration = restored.generation
        capacityBytes = restored.capacityBytes.coerceAtLeast(0L)
        totalWrittenBytes = restored.totalWrittenBytes.coerceAtLeast(0L)
        filledBytes = restored.filledBytes.coerceIn(0L, capacityBytes)
        lastWriteAtMillis = restored.lastWriteAtMillis.coerceAtLeast(0L)
        sampleRate = restored.sampleRate.coerceAtLeast(0)
        channelCount = restored.channelCount.coerceAtLeast(0)
        bytesPerSample = restored.bytesPerSample.coerceAtLeast(0)

        if (capacityBytes <= 0L || sampleRate <= 0 || channelCount <= 0 || bytesPerSample <= 0) {
            capacityBytes = 0L
            totalWrittenBytes = 0L
            filledBytes = 0L
            lastWriteAtMillis = 0L
            sampleRate = 0
            channelCount = 0
            bytesPerSample = 0
        } else if (totalWrittenBytes < filledBytes) {
            totalWrittenBytes = 0L
            filledBytes = 0L
            lastWriteAtMillis = 0L
        } else {
            val expectedStoredBytes = minOf(totalWrittenBytes, capacityBytes)
            val actualStoredBytes = runCatching { dataAccessLocked().length() }.getOrDefault(0L)
            if (actualStoredBytes < expectedStoredBytes) {
                totalWrittenBytes = 0L
                filledBytes = 0L
                lastWriteAtMillis = 0L
            }
        }
    }

    private fun resetLocked(
        capacity: Long,
        rate: Int,
        channels: Int,
        sampleBytes: Int,
        forceToDisk: Boolean,
    ) {
        capacityBytes = capacity
        totalWrittenBytes = 0L
        filledBytes = 0L
        lastWriteAtMillis = 0L
        sampleRate = rate
        channelCount = channels
        bytesPerSample = sampleBytes
        storageGeneration++
        runCatching { dataAccessLocked().setLength(0L) }
        checkpointLocked(forceToDisk)
    }

    private fun maybeCheckpointLocked() {
        val now = SystemClock.uptimeMillis()
        if (now - lastMetadataWriteAtUptimeMillis >= METADATA_INTERVAL_MS) {
            checkpointLocked(forceToDisk = false)
            lastMetadataWriteAtUptimeMillis = now
        }
    }

    private fun checkpointLocked(forceToDisk: Boolean) {
        metadataGeneration++
        val metadata = Metadata(
            generation = metadataGeneration,
            capacityBytes = capacityBytes,
            totalWrittenBytes = totalWrittenBytes,
            filledBytes = filledBytes,
            lastWriteAtMillis = lastWriteAtMillis,
            sampleRate = sampleRate,
            channelCount = channelCount,
            bytesPerSample = bytesPerSample,
        )
        writeMetadataSlotLocked((metadataGeneration and 1L).toInt(), metadata)
        if (forceToDisk) {
            dataAccess?.fd?.sync()
            metaAccess?.fd?.sync()
        }
    }

    private fun readMetadataSlotLocked(slot: Int): Metadata? {
        val access = metaAccess ?: return null
        val offset = slot.toLong() * META_SLOT_BYTES
        if (access.length() < offset + META_SLOT_BYTES) return null
        val bytes = ByteArray(META_SLOT_BYTES)
        return try {
            access.seek(offset)
            access.readFully(bytes)
            val storedCrc = readInt(bytes, META_CRC_OFFSET)
            val computed = crc32(bytes, 0, META_CRC_OFFSET)
            if (storedCrc != computed) return null
            if (readInt(bytes, 0) != META_MAGIC || readInt(bytes, 4) != META_VERSION) return null
            Metadata(
                generation = readLong(bytes, 8),
                capacityBytes = readLong(bytes, 16),
                totalWrittenBytes = readLong(bytes, 24),
                filledBytes = readLong(bytes, 32),
                lastWriteAtMillis = readLong(bytes, 40),
                sampleRate = readInt(bytes, 48),
                channelCount = readInt(bytes, 52),
                bytesPerSample = readInt(bytes, 56),
            )
        } catch (_: IOException) {
            null
        }
    }

    private fun writeMetadataSlotLocked(slot: Int, metadata: Metadata) {
        val access = metaAccess ?: return
        val bytes = ByteArray(META_SLOT_BYTES)
        writeInt(bytes, 0, META_MAGIC)
        writeInt(bytes, 4, META_VERSION)
        writeLong(bytes, 8, metadata.generation)
        writeLong(bytes, 16, metadata.capacityBytes)
        writeLong(bytes, 24, metadata.totalWrittenBytes)
        writeLong(bytes, 32, metadata.filledBytes)
        writeLong(bytes, 40, metadata.lastWriteAtMillis)
        writeInt(bytes, 48, metadata.sampleRate)
        writeInt(bytes, 52, metadata.channelCount)
        writeInt(bytes, 56, metadata.bytesPerSample)
        writeInt(bytes, META_CRC_OFFSET, crc32(bytes, 0, META_CRC_OFFSET))
        access.seek(slot.toLong() * META_SLOT_BYTES)
        access.write(bytes)
    }

    private fun dataAccessLocked(): RandomAccessFile {
        return dataAccess ?: RandomAccessFile(dataFile, "rw").also { dataAccess = it }
    }

    private data class Metadata(
        val generation: Long,
        val capacityBytes: Long,
        val totalWrittenBytes: Long,
        val filledBytes: Long,
        val lastWriteAtMillis: Long,
        val sampleRate: Int,
        val channelCount: Int,
        val bytesPerSample: Int,
    )

    private data class ReadPlan(
        val storageGeneration: Long,
        val capacityBytes: Long,
        val startAbsolute: Long,
        val endAbsolute: Long,
    )

    companion object {
        private const val META_MAGIC = 0x54545242 // TTRB
        private const val META_VERSION = 2
        private const val META_SLOT_BYTES = 64
        private const val META_CRC_OFFSET = 60
        private const val IO_CHUNK_SIZE = 64 * 1024
        private const val METADATA_INTERVAL_MS = 500L

        private fun crc32(bytes: ByteArray, offset: Int, count: Int): Int {
            val crc = CRC32()
            crc.update(bytes, offset, count)
            return crc.value.toInt()
        }

        private fun readInt(bytes: ByteArray, offset: Int): Int {
            return (bytes[offset].toInt() and 0xff) or
                ((bytes[offset + 1].toInt() and 0xff) shl 8) or
                ((bytes[offset + 2].toInt() and 0xff) shl 16) or
                ((bytes[offset + 3].toInt() and 0xff) shl 24)
        }

        private fun writeInt(bytes: ByteArray, offset: Int, value: Int) {
            bytes[offset] = value.toByte()
            bytes[offset + 1] = (value ushr 8).toByte()
            bytes[offset + 2] = (value ushr 16).toByte()
            bytes[offset + 3] = (value ushr 24).toByte()
        }

        private fun readLong(bytes: ByteArray, offset: Int): Long {
            var value = 0L
            for (index in 0 until 8) {
                value = value or ((bytes[offset + index].toLong() and 0xffL) shl (index * 8))
            }
            return value
        }

        private fun writeLong(bytes: ByteArray, offset: Int, value: Long) {
            for (index in 0 until 8) {
                bytes[offset + index] = (value ushr (index * 8)).toByte()
            }
        }
    }
}
