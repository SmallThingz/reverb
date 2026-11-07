package app.smallthingz.reverb

import java.io.*
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.zip.CRC32

internal class InvalidDurableSaveManifest(message: String, cause: Throwable? = null) : IOException(message, cause)

internal enum class DurableSaveStage(val code: Int) {
    PINNING(1), QUEUED(2), WRITING(3), VERIFIED(4), PUBLISHING(5), PUBLISHED(6), COMPLETE(7), FAILED(8), CANCELLED(9);
    companion object {
        fun decode(code: Int) = entries.firstOrNull { it.code == code }
            ?: throw IOException("Unknown durable save stage")
    }
}

internal data class DurableSaveRequest(
    val name: String, val directoryId: String, val format: ExportFormat, val codec: ExportCodec,
    val sampleRate: Int, val channelCount: Int, val sampleFormat: PcmSampleFormat, val flacCompression: Int,
)

internal data class DurableSaveRecord(
    val id: String, val stage: DurableSaveStage, val request: DurableSaveRequest,
    val source: DurablePcmPins.Snapshot, val error: String = "",
    val output: RecordingOutputTarget? = null, val fingerprint: StableOutputFingerprint? = null,
    val creationName: String = "",
    val materializedDurationMillis: Long = 0L,
) {
    fun pending(phase: SavePhase, progress: Float? = null) = PendingSave(id, request.name,
        source.range.startedAtMillis, (source.range.durationSeconds * 1000.0).toLong(), phase, progress,
        error.takeIf(String::isNotEmpty), output?.takeIf { !it.staging }?.id,
        publishedRecordingIdentity = output?.takeIf { !it.staging }?.publishedIdentity?.takeIf(String::isNotBlank))
}

/** Bounded wire body with one final copy, including the existing big-endian CRC trailer. */
internal class DurableSaveManifestBuffer : ByteArrayOutputStream() {
    override fun write(value: Int) {
        if (size() >= DurableSaveJournal.MAX_MANIFEST_BYTES - 8) throw IOException("Save manifest too large")
        super.write(value)
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        if (length > DurableSaveJournal.MAX_MANIFEST_BYTES - 8 - size()) throw IOException("Save manifest too large")
        super.write(bytes, offset, length)
    }

    fun finishWithChecksum(): ByteArray {
        if (count > DurableSaveJournal.MAX_MANIFEST_BYTES - 8) throw IOException("Save manifest too large")
        val checksum = CRC32().apply { update(buf, 0, count) }.value
        return ByteArray(count + 8).also { result ->
            System.arraycopy(buf, 0, result, 0, count)
            for (index in 0 until 8) result[count + index] = (checksum ushr ((7 - index) * 8)).toByte()
        }
    }
}

/** One bounded atomic manifest owns PCM links and the associated output transaction. */
internal class DurableSaveJournal(
    private val root: File,
    private val descriptorMatches: (String, FileDescriptor) -> Boolean = { identity, descriptor ->
        fileDescriptorIdentityMatches(identity, resolveFileDescriptorIdentity(descriptor))
    },
    private val directorySync: (File) -> Unit = ::forceRecordingDirectoryDurable,
) {
    companion object {
        const val MAX_JOBS = 128
        const val MAX_MANIFEST_BYTES = 8 * 1024 * 1024
        private const val MAGIC = 0x5256534A
        private const val VERSION = 2
    }

    fun directory(id: String): File {
        if (runCatching { UUID.fromString(id).toString() }.getOrNull() != id) throw IOException("Invalid save identity")
        return File(root, id)
    }

    data class DirectoryReservation(val directory: File, val directoryIdentity: String, val rootIdentity: String)

    fun createDirectory(id: String, onReserved: (DirectoryReservation) -> Unit = {}): File {
        if (storagePathState(root) == StoragePathState.MISSING) {
            if (!root.mkdir()) throw IOException("Cannot create save journal")
            directorySync(root.parentFile ?: throw IOException("Missing journal parent"))
        }
        val rootIdentity = requireDirectory(root)
        directorySync(root.parentFile ?: throw IOException("Missing journal parent"))
        val children = rootEntries()
        if (children.size >= MAX_JOBS) throw IOException("Too many pending saves")
        val directory = directory(id)
        if (!directory.mkdir()) throw IOException("Cannot reserve save identity")
        onReserved(DirectoryReservation(directory, requireDirectory(directory), rootIdentity))
        directorySync(root)
        return directory
    }

    /** Only the current admission's observed exclusive mkdir authorizes this caught-failure cleanup. */
    fun abandonEmptyReservation(reservation: DirectoryReservation) {
        val directory = reservation.directory
        if (directory(directory.name) != directory ||
            !fileIdentityMatches(reservation.rootIdentity, requireDirectory(root))) {
            throw IOException("Save reservation parent changed")
        }
        if (!fileIdentityMatches(reservation.directoryIdentity, requireDirectory(directory))) {
            throw IOException("Save reservation directory changed")
        }
        if (Files.newDirectoryStream(directory.toPath()).use { it.iterator().hasNext() }) {
            throw IOException("Save reservation contains recovery evidence")
        }
        if (!fileIdentityMatches(reservation.directoryIdentity, requireDirectory(directory)) ||
            !fileIdentityMatches(reservation.rootIdentity, requireDirectory(root))) {
            throw IOException("Save reservation changed before retirement")
        }
        Files.delete(directory.toPath())
        directorySync(root)
    }

    fun write(record: DurableSaveRecord) {
        val directory = directory(record.id)
        requireDirectory(directory)
        val payload = encode(record)
        validateEncoded(payload, directory)
        val target = File(directory, "manifest")
        when (storagePathState(target)) {
            StoragePathState.MISSING -> Unit
            StoragePathState.PRESENT -> if (resolveFileIdentity(target).isBlank()) {
                throw IOException("Save manifest backing is not a regular file")
            }
            StoragePathState.UNAVAILABLE -> throw IOException("Save manifest backing is unavailable")
        }
        val temporary = File(directory, "manifest.new")
        if (storagePathState(temporary) != StoragePathState.MISSING) throw IOException("Unresolved save manifest publication")
        Files.createFile(temporary.toPath())
        val createdIdentity = resolveFileIdentity(temporary)
        RandomAccessFile(temporary, "rw").use { output ->
            if (!descriptorMatches(createdIdentity, output.fd) || output.length() != 0L) {
                throw IOException("Save manifest object changed before write")
            }
            output.write(payload)
            output.fd.sync()
            val writtenIdentity = resolveFileIdentity(temporary)
            if (!sameFileObjectAcrossRename(createdIdentity, writtenIdentity) ||
                !descriptorMatches(writtenIdentity, output.fd)) {
                throw IOException("Save manifest object changed during write")
            }
        }
        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        directorySync(directory)
    }

    data class Recovery(val failures: Map<String, String>, val presentIds: Set<String> = emptySet())

    fun recoverEach(onRecord: (DurableSaveRecord) -> Unit): Recovery = recoverEach(onRecord, { _, _ -> })

    /** Ephemeral presentation observer; never persisted and never authoritative for recovery. */
    fun recoverEach(onRecord: (DurableSaveRecord) -> Unit,
        onFailure: (String, Exception) -> Unit): Recovery {
        if (storagePathState(root) == StoragePathState.MISSING) {
            val parent = root.parentFile ?: throw IOException("Missing save journal parent")
            requireDirectory(parent)
            directorySync(parent)
            if (storagePathState(root) != StoragePathState.MISSING) throw IOException("Save journal changed during absence proof")
            return Recovery(emptyMap())
        }
        val rootIdentity = requireDirectory(root)
        directorySync(root)
        val directories = rootEntries()
        if (!fileIdentityMatches(rootIdentity, resolveDirectoryIdentity(root))) throw IOException("Save journal root changed")
        val failures = linkedMapOf<String, String>()
        directories.forEach { directory ->
            try {
            if (directory(directory.name) != directory) throw IOException("Invalid save journal child")
            val directoryIdentity = requireDirectory(directory)
            val empty = Files.newDirectoryStream(directory.toPath()).use { !it.iterator().hasNext() }
            if (empty) {
                if (!fileIdentityMatches(directoryIdentity, resolveDirectoryIdentity(directory))) {
                    throw IOException("Empty save directory changed before retirement")
                }
                Files.delete(directory.toPath())
                directorySync(root)
                return@forEach
            }
            recoverPublication(directory)
            onRecord(read(directory))
            } catch (error: Exception) {
                failures[directory.name] = error.message ?: "Save journal unavailable"
                try { onFailure(directory.name, error) }
                catch (cancelled: java.util.concurrent.CancellationException) { throw cancelled }
                catch (observerFailure: Exception) {
                    if (observerFailure !== error) error.addSuppressed(observerFailure)
                    try { android.util.Log.w("DurableSaveJournal", "Recovery failure presentation failed", error) }
                    catch (_: Exception) { /* An unavailable diagnostic sink cannot change recovery. */ }
                }
            }
        }
        val presentIds = rootEntries().mapTo(linkedSetOf()) { it.name }
        directorySync(root)
        if (!fileIdentityMatches(rootIdentity, resolveDirectoryIdentity(root))) throw IOException("Save journal root changed")
        val initialIds = directories.mapTo(HashSet()) { it.name }
        (presentIds - initialIds).forEach { failures[it] = "Save journal membership changed during recovery" }
        return Recovery(failures.filterKeys { it in presentIds }, presentIds)
    }

    fun read(id: String): DurableSaveRecord {
        val directory = directory(id)
        requireDirectory(directory)
        recoverPublication(directory)
        return read(directory)
    }

    private fun recoverPublication(directory: File) {
        val temporary = File(directory, "manifest.new")
        if (storagePathState(temporary) == StoragePathState.MISSING) return
        try {
            read(directory, "manifest.new")
        } catch (invalid: InvalidDurableSaveManifest) {
            read(directory)
            preserveInvalidCandidate(directory, temporary)
            return
        }
        val target = File(directory, "manifest")
        if (storagePathState(target) != StoragePathState.MISSING) read(directory)
        val preparedIdentity = resolveFileIdentity(temporary)
        RandomAccessFile(temporary, "rw").use {
            if (!descriptorMatches(preparedIdentity, it.fd)) {
                throw IOException("Save manifest changed before recovery barrier")
            }
            it.fd.sync()
            if (!fileIdentityMatches(preparedIdentity, resolveFileIdentity(temporary))) {
                throw IOException("Save manifest changed during recovery barrier")
            }
        }
        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        directorySync(directory)
    }

    private fun preserveInvalidCandidate(directory: File, temporary: File) {
        val sourceIdentity = resolveFileIdentity(temporary).takeIf(String::isNotBlank)
            ?: throw IOException("Invalid save candidate identity unavailable")
        RandomAccessFile(temporary, "rw").use { source ->
            if (!descriptorMatches(sourceIdentity, source.fd)) throw IOException("Invalid save candidate changed before preservation")
            source.fd.sync()
            if (!fileIdentityMatches(sourceIdentity, resolveFileIdentity(temporary)) ||
                !descriptorMatches(sourceIdentity, source.fd)) throw IOException("Invalid save candidate changed during preservation")
        }
        val preserved = File(root.parentFile, "durable-save-preserved")
        if (storagePathState(preserved) == StoragePathState.MISSING && !preserved.mkdir()) {
            throw IOException("Cannot preserve interrupted save manifest")
        }
        requireDirectory(preserved)
        directorySync(requireNotNull(preserved.parentFile))
        val target = File(preserved, "${directory.name}-${UUID.randomUUID()}.manifest")
        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        if (!sameFileObjectAcrossRename(sourceIdentity, resolveFileIdentity(target)) ||
            storagePathState(temporary) != StoragePathState.MISSING) {
            throw IOException("Interrupted save manifest preservation is uncertain")
        }
        directorySync(preserved)
        directorySync(directory)
    }

    private fun read(directory: File, name: String = "manifest"): DurableSaveRecord {
        val file = File(directory, name)
        val identity = resolveFileIdentity(file).takeIf(String::isNotBlank)
            ?: throw IOException("Save manifest unavailable")
        val bytes = FileInputStream(file).use { input ->
            if (!descriptorMatches(identity, input.fd)) {
                throw IOException("Save manifest changed before read")
            }
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer, 0, minOf(buffer.size, MAX_MANIFEST_BYTES + 1 - output.size()))
                if (count < 0) break
                output.write(buffer, 0, count)
                if (output.size() > MAX_MANIFEST_BYTES) throw InvalidDurableSaveManifest("Save manifest too large")
            }
            if (!descriptorMatches(identity, input.fd) ||
                !fileIdentityMatches(identity, resolveFileIdentity(file))) throw IOException("Save manifest changed during read")
            output.toByteArray()
        }
        return decode(bytes, directory)
    }

    fun retire(record: DurableSaveRecord) {
        releasePins(record)
        retireMetadata(record)
    }

    fun releasePins(record: DurableSaveRecord) {
        if (record.stage !in setOf(DurableSaveStage.COMPLETE, DurableSaveStage.CANCELLED)) {
            throw IOException("Save has no terminal cleanup authority")
        }
        val directory = directory(record.id)
        requireTerminalOwnership(record, read(directory))
        DurablePcmPins.release(directory, record.source.objects)
    }

    fun retireMetadata(record: DurableSaveRecord) {
        if (record.stage !in setOf(DurableSaveStage.COMPLETE, DurableSaveStage.CANCELLED)) {
            throw IOException("Save has no terminal cleanup authority")
        }
        val directory = directory(record.id)
        requireTerminalOwnership(record, read(directory))
        if (record.source.objects.any { DurablePcmPins.hasReference(directory, it) }) {
            throw IOException("Save source pins remain owned")
        }
        Files.delete(File(directory, "manifest").toPath())
        directorySync(directory)
        Files.delete(directory.toPath())
        directorySync(root)
    }

    private fun requireTerminalOwnership(expected: DurableSaveRecord, durable: DurableSaveRecord) {
        val first = expected.fingerprint
        val second = durable.fingerprint
        val sameFingerprint = if (first == null || second == null) first == null && second == null else
            copyDigestMatches(first.digest, second.digest) && first.fileKey == second.fileKey &&
                first.providerIdentity == second.providerIdentity
        if (expected.id != durable.id || expected.stage != durable.stage || expected.request != durable.request ||
            expected.source.objects != durable.source.objects || expected.output != durable.output || !sameFingerprint) {
            throw IOException("Save cleanup no longer owns the durable terminal record")
        }
    }

    private fun requireDirectory(directory: File): String = resolveDirectoryIdentity(directory).takeIf(String::isNotBlank)
        ?: throw IOException("Save journal is not a trustworthy directory")

    private fun rootEntries(): List<File> {
        val entries = ArrayList<File>()
        Files.newDirectoryStream(root.toPath()).use { stream ->
            for (entry in stream) {
                if (entries.size >= MAX_JOBS) throw IOException("Save journal exceeds job limit")
                entries += entry.toFile()
            }
        }
        return entries
    }

    internal fun encode(record: DurableSaveRecord): ByteArray {
        val body = DurableSaveManifestBuffer()
        DataOutputStream(body).use { out ->
            out.writeInt(MAGIC); out.writeInt(VERSION); out.text(record.id); out.writeByte(record.stage.code)
            with(record.request) {
                out.text(name); out.text(directoryId); out.writeByte(format.storageCode.toInt())
                out.writeByte(codec.storageCode.toInt()); out.writeInt(sampleRate); out.writeInt(channelCount)
                out.writeByte(sampleFormat.storageCode.toInt()); out.writeInt(flacCompression)
            }
            out.text(record.error)
            out.text(record.creationName)
            out.writeLong(record.materializedDurationMillis)
            val sourceCount = record.source.objects.size
            if (sourceCount !in 1..DurablePcmPins.MAX_OBJECTS) throw IOException("Invalid save pin count")
            val sourceDirectory = directory(record.id)
            val sourceNames = HashMap<String, String>(sourceCount * 4 / 3 + 1)
            for (proof in record.source.objects) {
                sourceNames[DurablePcmPins.sourceFile(sourceDirectory, proof).absolutePath] = proof.name
            }
            with(record.source.range) {
                out.writeLong(startedAtMillis); out.writeLong(endedAtMillis); out.writeDouble(durationSeconds)
                out.segments(segments, sourceNames); out.segments(leadingContext, sourceNames); out.segments(trailingContext, sourceNames)
            }
            out.writeInt(sourceCount)
            record.source.objects.forEach { proof ->
                out.text(proof.name); out.text(proof.identity); out.writeLong(proof.payloadOffset)
                out.writeLong(proof.payloadBytes); out.writeInt(proof.checksum); out.text(proof.sourcePath); out.text(proof.sourceDirectoryIdentity); out.text(proof.storeDirectoryIdentity)
            }
            out.writeBoolean(record.output != null)
            record.output?.let { target ->
                out.text(target.id); out.text(target.displayName); out.text(target.mimeType)
                out.writeInt(target.storageType.storageCode.toInt()); out.text(target.directoryId); out.writeLong(target.startedAtMillis)
                out.writeBoolean(target.staging); out.text(target.stagingDisplayName); out.text(target.stagingIdentity)
                out.text(target.publishedIdentity)
            }
            out.writeBoolean(record.fingerprint != null)
            record.fingerprint?.let { fingerprint ->
                if (fingerprint.digest.sha256.size != 32) throw IOException("Invalid output digest")
                out.writeLong(fingerprint.digest.byteCount); out.write(fingerprint.digest.sha256)
                out.text(fingerprint.fileKey.orEmpty()); out.text(fingerprint.providerIdentity.orEmpty())
            }
        }
        return body.finishWithChecksum()
    }

    private fun DataOutputStream.text(value: String) {
        if (value.length > 8192) throw IOException("Oversized save field")
        writeUTF(value)
    }

    internal fun decode(bytes: ByteArray, directory: File): DurableSaveRecord =
        requireNotNull(parse(bytes, directory, materialize = true))

    internal fun validateEncoded(bytes: ByteArray, directory: File) {
        parse(bytes, directory, materialize = false)
    }

    private fun parse(bytes: ByteArray, directory: File, materialize: Boolean): DurableSaveRecord? = try {
        decodeChecked(bytes, directory, materialize)
    } catch (invalid: IOException) {
        throw InvalidDurableSaveManifest(invalid.message ?: "Invalid save manifest", invalid)
    } catch (invalid: RuntimeException) {
        throw InvalidDurableSaveManifest("Invalid save manifest", invalid)
    }

    private fun decodeChecked(bytes: ByteArray, directory: File, materialize: Boolean): DurableSaveRecord? {
        if (bytes.size !in 16..MAX_MANIFEST_BYTES) throw IOException("Invalid save manifest size")
        val payloadSize = bytes.size - 8
        val expected = DataInputStream(ByteArrayInputStream(bytes, payloadSize, 8)).readLong()
        if (CRC32().apply { update(bytes, 0, payloadSize) }.value != expected) throw IOException("Save manifest checksum mismatch")
        return DataInputStream(ByteArrayInputStream(bytes, 0, payloadSize)).use { input ->
            if (input.readInt() != MAGIC) throw IOException("Unsupported save manifest")
            val version = input.readInt()
            if (version !in 1..VERSION) throw IOException("Unsupported save manifest")
            val id = input.text()
            if (directory(id) != directory) throw IOException("Save manifest identity mismatch")
            val stage = DurableSaveStage.decode(input.readUnsignedByte())
            val request = DurableSaveRequest(input.text(), input.text(),
                ExportFormat.fromStorageCode(input.readUnsignedByte()) ?: throw IOException("Invalid save format"),
                ExportCodec.fromStorageCode(input.readUnsignedByte()) ?: throw IOException("Invalid save codec"),
                input.readInt(), input.readInt(),
                PcmSampleFormat.fromStorageCode(input.readUnsignedByte()) ?: throw IOException("Invalid save sample format"),
                input.readInt())
            if (request.sampleRate !in 1..384000 || request.channelCount !in 1..2 || !validFlacCompression(request.flacCompression)) {
                throw IOException("Invalid save output geometry")
            }
            val error = input.text()
            val creationName = input.text()
            val materializedDurationMillis = input.readLong()
            if (materializedDurationMillis < 0L) throw IOException("Invalid materialized save duration")
            val start = input.readLong(); val end = input.readLong(); val duration = input.readDouble()
            // Wall clocks can move backwards between chunks; PCM duration is frame-derived.
            if (start < 0 || end < 0 || !duration.isFinite() || duration <= 0) throw IOException("Invalid save time")
            val encodedRange = FrozenPcmRange(input.segments(directory), input.segments(directory), input.segments(directory), start, end, duration)
            if (encodedRange.segments.isEmpty()) throw IOException("Empty save source")
            val count = input.count()
            if (count == 0) throw IOException("Unpinned save source")
            val proofs = List(count) {
                val name = input.text(); DurablePcmPins.pinnedFilePath(directory, name)
                DurablePcmPins.ObjectProof(name, input.text(), input.readLong(), input.readLong(), input.readInt(),
                    if (version >= 2) input.text().also { if (it.isNotEmpty()) DurablePcmPins.checkedSourcePath(it) } else "",
                    if (version >= 2) input.text() else "", if (version >= 2) input.text() else "")
            }
            if (proofs.any { it.sourcePath.isNotEmpty() &&
                    (it.sourceDirectoryIdentity.isBlank() || it.storeDirectoryIdentity.isBlank()) }) {
                throw IOException("Save source directory proof missing")
            }
            val originalPaths = proofs.filter { it.sourcePath.isNotEmpty() }.map { it.sourcePath }
            if (originalPaths.toSet().size != originalPaths.size) throw IOException("Duplicate referenced PCM object")
            if (proofs.map { it.name }.toSet().size != proofs.size) throw IOException("Duplicate save source")
            val names = proofs.map { it.name }.toSet()
            if ((encodedRange.segments + encodedRange.leadingContext + encodedRange.trailingContext).any { it.record.file.name !in names }) {
                throw IOException("Unowned save source")
            }
            val proofByName = proofs.associateBy { it.name }
            val recordsByName = HashMap<String, PersistentAudioChunkStore.ChunkRecord>()
            for (segment in encodedRange.segments + encodedRange.leadingContext + encodedRange.trailingContext) {
                val source = segment.record
                val proof = requireNotNull(proofByName[source.file.name])
                if (proof.sourcePath.isNotEmpty() && File(proof.sourcePath).name != source.id.toString()) {
                    throw IOException("Save source id disagrees with original pathname")
                }
                if (proof.identity.isBlank() || proof.payloadOffset != source.payloadOffsetBytes ||
                    proof.payloadBytes != source.payloadBytes || proof.checksum != source.payloadChecksum) {
                    throw IOException("Save pin proof disagrees with source geometry")
                }
                Math.addExact(proof.payloadOffset, proof.payloadBytes)
                val previous = recordsByName.putIfAbsent(source.file.name, source)
                if (previous != null && previous != source) throw IOException("Conflicting save object geometry")
            }
            if (recordsByName.keys != names) throw IOException("Save manifest contains unreferenced pins")
            val target = if (input.readBoolean()) {
                val targetId = input.text(); val name = input.text(); val mime = input.text()
                val storage = RecordingStorageType.fromStorageCode(input.readInt()) ?: throw IOException("Invalid output storage")
                val parent = input.text(); val timestamp = input.readLong(); val staging = input.readBoolean()
                if (!recordingStorageIdIsValid(storage, targetId)) throw IOException("Invalid output identity")
                RecordingOutputTarget(targetId, name, mime, storage, parent, timestamp,
                    file = if (storage == RecordingStorageType.FILE) File(targetId) else null,
                    uri = if (storage != RecordingStorageType.FILE) android.net.Uri.parse(targetId) else null,
                    staging = staging, stagingDisplayName = input.text(), stagingIdentity = input.text(), publishedIdentity = input.text())
            } else null
            val fingerprint = if (input.readBoolean()) {
                val size = input.readLong()
                val digest = ByteArray(32).also(input::readFully)
                if (size < 0) throw IOException("Invalid output size")
                StableOutputFingerprint(CopyDigest(size, digest), input.text().takeIf(String::isNotEmpty), input.text().takeIf(String::isNotEmpty))
            } else null
            if (input.read() != -1) throw IOException("Trailing save manifest data")
            when (stage) {
                DurableSaveStage.PINNING, DurableSaveStage.QUEUED, DurableSaveStage.CANCELLED ->
                    if (target != null || fingerprint != null) throw IOException("Unmaterialized save has output authority")
                DurableSaveStage.WRITING ->
                    if (target != null && !target.staging) throw IOException("Partial save references a published asset")
                DurableSaveStage.VERIFIED, DurableSaveStage.PUBLISHING ->
                    if (target == null || !target.staging || fingerprint == null || fingerprint.digest.byteCount <= 0L) {
                        throw IOException("Verified save is missing staging authority")
                    }
                DurableSaveStage.PUBLISHED, DurableSaveStage.COMPLETE ->
                    if (target == null || target.staging || target.publishedIdentity.isBlank() ||
                        fingerprint == null || fingerprint.digest.byteCount <= 0L) {
                        throw IOException("Published save is missing output authority")
                    }
                DurableSaveStage.FAILED -> Unit
            }
            fun rebind(segments: List<PersistentAudioChunkStore.Segment>): List<PersistentAudioChunkStore.Segment> {
                val rebound = if (materialize) ArrayList<PersistentAudioChunkStore.Segment>(segments.size) else null
                for (segment in segments) {
                    val proof = requireNotNull(proofByName[segment.record.file.name])
                    // Legacy local pins perform a fresh directory identity check here. Validation
                    // retains every sourceFile call even when the reconstructed graph is unused.
                    val file = DurablePcmPins.sourceFile(directory, proof)
                    rebound?.add(segment.copy(record = segment.record.copy(file = file)))
                }
                return rebound ?: emptyList()
            }
            val segments = rebind(encodedRange.segments)
            val leading = rebind(encodedRange.leadingContext)
            val trailing = rebind(encodedRange.trailingContext)
            if (!materialize) return@use null
            val range = encodedRange.copy(segments = segments, leadingContext = leading, trailingContext = trailing)
            DurableSaveRecord(id, stage, request, DurablePcmPins.Snapshot(range, proofs), error, target, fingerprint,
                creationName, materializedDurationMillis)
        }
    }
    private fun DataInputStream.text(): String = readUTF().also { if (it.length > 8192) throw IOException("Oversized save field") }
    private fun DataInputStream.count(): Int = readInt().also {
        if (it !in 0..DurablePcmPins.MAX_OBJECTS) throw IOException("Invalid save source count")
    }

    private fun DataOutputStream.segments(segments: List<PersistentAudioChunkStore.Segment>, sourceNames: Map<String, String>) {
        if (segments.size > DurablePcmPins.MAX_OBJECTS) throw IOException("Too many save segments")
        writeInt(segments.size)
        for (segment in segments) {
            with(segment.record) {
                writeLong(id.toLong()); text(sourceNames[file.absolutePath] ?: throw IOException("Unowned source path")); writeLong(createdAtMillis); writeLong(payloadBytes)
                writeLong(sampleFrames); writeInt(sampleRate); writeInt(channelCount); writeByte(sampleFormat.storageCode.toInt())
                writeInt(payloadChecksum); writeLong(headerGeneration); writeLong(payloadOffsetBytes)
            }
            with(segment) {
                writeLong(startFrame); writeLong(frameCount); writeLong(payloadOffsetBytes)
                writeLong(payloadBytesAtAcquire); writeInt(payloadChecksumAtAcquire); writeDouble(timelineStartSeconds)
                writeDouble(timelineEndSeconds); writeBoolean(continuesPreviousChunkAtAcquire); writeLong(timelineStartFrame)
            }
        }
    }

    private fun DataInputStream.segments(directory: File): List<PersistentAudioChunkStore.Segment> = List(count()) {
        val id = readLong(); val name = text()
        if (id !in 0..UInt.MAX_VALUE.toLong()) throw IOException("Invalid PCM object id")
        val file = DurablePcmPins.pinnedFilePath(directory, name)
        val record = PersistentAudioChunkStore.ChunkRecord(id.toUInt(), file, PersistentAudioChunkStore.ChunkState.FINALIZED,
            readLong(), readLong(), readLong(), readInt(), readInt(),
            PcmSampleFormat.fromStorageCode(readUnsignedByte()) ?: throw IOException("Invalid PCM format"),
            readInt(), readLong(), readLong())
        val segment = PersistentAudioChunkStore.Segment(record, readLong(), readLong(), readLong(), readLong(), readInt(),
            readDouble(), readDouble(), readBoolean(), readLong())
        if (record.sampleRate !in 1..384000 || record.channelCount !in 1..2 || record.createdAtMillis < 0 ||
            record.payloadOffsetBytes < 0 || record.payloadBytes <= 0 || record.sampleFrames <= 0 ||
            record.payloadBytes / record.frameBytes != record.sampleFrames || record.payloadBytes % record.frameBytes != 0L ||
            segment.payloadOffsetBytes != record.payloadOffsetBytes || segment.payloadBytesAtAcquire <= 0 ||
            segment.payloadBytesAtAcquire > record.payloadBytes || segment.payloadBytesAtAcquire % record.frameBytes != 0L ||
            segment.startFrame < 0 || segment.frameCount <= 0 ||
            segment.startFrame > segment.payloadBytesAtAcquire / record.frameBytes - segment.frameCount ||
            !segment.timelineStartSeconds.isFinite() || !segment.timelineEndSeconds.isFinite() ||
            segment.timelineStartSeconds < 0 || segment.timelineEndSeconds < segment.timelineStartSeconds) {
            throw IOException("Invalid pinned PCM geometry")
        }
        segment
    }
}
