package app.smallthingz.reverb

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.io.RandomAccessFile
import java.util.UUID
import java.util.zip.CRC32

/** One small durable authority per save/store, independent of the number of PCM objects. */
internal object DurablePcmBatchReferences {
    private const val MAGIC = 0x52565052
    private const val VERSION = 1
    private const val MAX_BYTES = 8 * 1024 * 1024
    private const val MAX_CACHED_OBJECTS = DurablePcmPins.MAX_OBJECTS * 2
    private val pinName = Regex("pcm-[0-9]{1,5}")
    private data class Cached(val identity: String, val size: Long, val proofs: Map<String, DurablePcmPins.ObjectProof>)
    // Access is serialized by DurablePcmPins.mutationLock. Cache entries never grant deletion authority.
    private val cache = LinkedHashMap<String, Cached>(16, 0.75f, true)
    private var cachedObjects = 0

    fun file(root: File, jobId: String): File {
        requireJobId(jobId)
        return File(root, "batch-$jobId")
    }

    fun isBatchName(name: String): Boolean = name.startsWith("batch-") &&
        runCatching { requireJobId(name.removePrefix("batch-")) }.isSuccess

    /** Existing wire format, with CRC over the backing bytes and one final copy. */
    private class BatchBuffer : ByteArrayOutputStream() {
        override fun write(value: Int) {
            if (size() >= MAX_BYTES - 8) throw IOException("PCM reference batch exceeds limit")
            super.write(value)
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            if (length > MAX_BYTES - 8 - size()) throw IOException("PCM reference batch exceeds limit")
            super.write(bytes, offset, length)
        }

        fun finishWithChecksum(): ByteArray {
            val checksum = CRC32().apply { update(buf, 0, count) }.value
            return ByteArray(count + 8).also { result ->
                System.arraycopy(buf, 0, result, 0, count)
                for (index in 0 until 8) result[count + index] = (checksum ushr ((7 - index) * 8)).toByte()
            }
        }
    }

    fun encode(jobId: String, proofs: List<DurablePcmPins.ObjectProof>): ByteArray {
        requireJobId(jobId)
        if (proofs.isEmpty() || proofs.size > DurablePcmPins.MAX_OBJECTS) throw IOException("Invalid PCM reference count")
        val bytes = BatchBuffer()
        DataOutputStream(bytes).use { output ->
            output.writeInt(MAGIC)
            output.writeInt(VERSION)
            output.writeUTF(jobId)
            output.writeInt(proofs.size)
            val paths = HashSet<String>()
            val names = HashSet<String>()
            proofs.forEach { proof ->
                validate(proof)
                if (!paths.add(proof.sourcePath) || !names.add(proof.name)) throw IOException("Duplicate PCM reference")
                output.writeUTF(proof.name)
                output.writeUTF(proof.identity)
                output.writeLong(proof.payloadOffset)
                output.writeLong(proof.payloadBytes)
                output.writeInt(proof.checksum)
                output.writeUTF(proof.sourcePath)
                output.writeUTF(proof.sourceDirectoryIdentity)
                output.writeUTF(proof.storeDirectoryIdentity)
            }
        }
        return bytes.finishWithChecksum()
    }

    internal fun decode(bytes: ByteArray, jobId: String): Map<String, DurablePcmPins.ObjectProof> {
        requireJobId(jobId)
        if (bytes.size !in 24..MAX_BYTES) throw IOException("Invalid PCM reference batch size")
        val payloadSize = bytes.size - 8
        val checksum = DataInputStream(ByteArrayInputStream(bytes, payloadSize, 8)).readLong()
        if (CRC32().apply { update(bytes, 0, payloadSize) }.value != checksum) throw IOException("PCM reference checksum mismatch")
        return DataInputStream(ByteArrayInputStream(bytes, 0, payloadSize)).use { input ->
            if (input.readInt() != MAGIC || input.readInt() != VERSION || input.readUTF() != jobId) {
                throw IOException("PCM reference batch identity mismatch")
            }
            val count = input.readInt()
            if (count !in 1..DurablePcmPins.MAX_OBJECTS) throw IOException("Invalid PCM reference count")
            val proofs = LinkedHashMap<String, DurablePcmPins.ObjectProof>()
            val names = HashSet<String>()
            repeat(count) {
                val proof = DurablePcmPins.ObjectProof(input.readUTF(), input.readUTF(), input.readLong(),
                    input.readLong(), input.readInt(), input.readUTF(), input.readUTF(), input.readUTF())
                validate(proof)
                if (proofs.put(proof.sourcePath, proof) != null || !names.add(proof.name)) throw IOException("Duplicate PCM reference")
            }
            if (input.read() != -1) throw IOException("Trailing PCM reference bytes")
            proofs
        }
    }

    fun read(file: File, descriptorMatches: (String, FileDescriptor) -> Boolean,
        useCache: Boolean = true): Map<String, DurablePcmPins.ObjectProof> {
        val name = file.name.removeSuffix(".pending")
        if (!isBatchName(name)) throw IOException("Invalid PCM batch name")
        val identity = resolveFileIdentity(file).takeIf(String::isNotBlank)
            ?: throw IOException("PCM reference batch unavailable")
        RandomAccessFile(file, "r").use { input ->
            val size = input.length()
            if (size !in 24L..MAX_BYTES.toLong() || !descriptorMatches(identity, input.fd)) {
                throw IOException("PCM reference batch descriptor changed")
            }
            val cached = cache[file.absolutePath]?.takeIf {
                useCache && identity.startsWith("stat:") && it.identity == identity && it.size == size
            }
            val proofs = cached?.proofs ?: ByteArray(size.toInt()).let { bytes ->
                input.readFully(bytes)
                decode(bytes, name.removePrefix("batch-"))
            }
            if (!fileIdentityMatches(identity, resolveFileIdentity(file)) || !descriptorMatches(identity, input.fd)) {
                throw IOException("PCM reference batch changed during read")
            }
            val sourceRoot = file.parentFile.parentFile
            if (cached == null && proofs.values.any { File(it.sourcePath).parentFile.parentFile != sourceRoot }) {
                throw IOException("PCM reference batch belongs to another store")
            }
            if (cached == null && identity.startsWith("stat:") && useCache) {
                cache.remove(file.absolutePath)?.let { cachedObjects -= it.proofs.size }
                cache[file.absolutePath] = Cached(identity, size, proofs)
                cachedObjects += proofs.size
                while (cachedObjects > MAX_CACHED_OBJECTS || cache.size > DurableSaveJournal.MAX_JOBS) {
                    val oldest = cache.entries.iterator()
                    cachedObjects -= oldest.next().value.proofs.size
                    oldest.remove()
                }
            }
            return proofs
        }
    }

    private fun validate(proof: DurablePcmPins.ObjectProof) {
        DurablePcmPins.checkedSourcePath(proof.sourcePath)
        if (!proof.name.matches(pinName) || !proof.identity.startsWith("stat:") ||
            proof.identity.length > 512 || proof.sourceDirectoryIdentity.isBlank() ||
            proof.storeDirectoryIdentity.isBlank() || proof.sourceDirectoryIdentity.length > 512 ||
            proof.storeDirectoryIdentity.length > 512 || proof.payloadOffset < 0 || proof.payloadBytes <= 0 ||
            proof.payloadOffset > Long.MAX_VALUE - proof.payloadBytes) throw IOException("Invalid PCM batch proof")
    }

    private fun requireJobId(id: String) {
        if (runCatching { UUID.fromString(id).toString() }.getOrNull() != id) throw IOException("Invalid PCM reference owner")
    }
}
