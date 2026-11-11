package app.smallthingz.reverb

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal enum class FileCopyPurpose(val code: Int) { STAGING(1), RENAME(2), CLAIM_RESTORE(3) }
internal enum class FileCopyPhase(val code: Int) { RESERVED(1), CREATED(2), VERIFIED(3), CATALOGED(4) }
internal enum class FileCopyCleanupOwner { LIVE_ACCEPTED_OPERATION, RECONCILIATION }

/** A copied inode is authoritative only through this explicit source-to-created-target record. */
internal data class FileCopyPublication(
    val token: String,
    val purpose: FileCopyPurpose,
    val sourceId: String,
    val requestedName: String,
    val targetId: String,
    val sourceIdentity: String,
    val byteCount: Long,
    val sha256Hex: String,
    val phase: FileCopyPhase = FileCopyPhase.RESERVED,
    val createdIdentity: String = "",
    val targetIdentity: String = "",
) {
    fun sourceFingerprint() = StableOutputFingerprint(
        CopyDigest(byteCount, sha256Hex.hexToByteArray()), sourceIdentity, null,
    )
    fun targetFingerprint() = sourceFingerprint().copy(fileKey = targetIdentity)
    fun matchesSource(source: File, fingerprint: StableOutputFingerprint): Boolean =
        sourceId == source.absolutePath && sourceIdentity == fingerprint.fileKey &&
            copyDigestMatches(sourceFingerprint().digest, fingerprint.digest)
    fun matchesTarget(fingerprint: StableOutputFingerprint): Boolean =
        phase >= FileCopyPhase.VERIFIED && targetIdentity.isNotBlank() &&
            stableOutputFingerprintMatches(RecordingStorageType.FILE, targetFingerprint(), fingerprint)
}

private fun encodeFileCopyField(value: String): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))

internal fun encodeFileCopyPublication(record: FileCopyPublication): String = listOf(
    "v1", record.token, record.purpose.code.toString(), encodeFileCopyField(record.sourceId),
    encodeFileCopyField(record.requestedName), encodeFileCopyField(record.targetId),
    encodeFileCopyField(record.sourceIdentity), record.byteCount.toString(), record.sha256Hex,
    record.phase.code.toString(), encodeFileCopyField(record.createdIdentity), encodeFileCopyField(record.targetIdentity),
).joinToString("|")

internal fun decodeFileCopyPublication(raw: String): FileCopyPublication? = runCatching {
    val fields = raw.split('|')
    if (fields.size != 12 || fields[0] != "v1") return null
    fun decode(index: Int): String {
        val bytes = Base64.getUrlDecoder().decode(fields[index])
        val text = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        require(encodeFileCopyField(text) == fields[index])
        return text
    }
    val token = UUID.fromString(fields[1]).toString().takeIf { it == fields[1] } ?: return null
    val purpose = FileCopyPurpose.entries.singleOrNull { it.code.toString() == fields[2] } ?: return null
    val source = File(decode(3))
    val requestedName = decode(4)
    val target = File(decode(5))
    val sourceIdentity = decode(6)
    val bytes = fields[7].toLongOrNull()?.takeIf { it >= 0L } ?: return null
    val claimRestore = purpose == FileCopyPurpose.CLAIM_RESTORE
    if (bytes == 0L && !claimRestore) return null
    if (bytes.toString() != fields[7]) return null
    val hash = fields[8].takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } } ?: return null
    val phase = FileCopyPhase.entries.singleOrNull { it.code.toString() == fields[9] } ?: return null
    val created = decode(10)
    val published = decode(11)
    if (!source.isAbsolute || !target.isAbsolute || source == target || source.parentFile != target.parentFile ||
        !isDirectChildBasename(source.name) || !isDirectChildBasename(target.name) ||
        !isDirectChildBasename(requestedName) ||
        (!claimRestore && (!isDiscoverableFileRecordingName(requestedName) || !isDiscoverableFileRecordingName(target.name))) ||
        (claimRestore && (!isDeletionClaimFileName(source.name) || isDeletionClaimFileName(target.name))) ||
        sourceIdentity.isBlank() || (phase >= FileCopyPhase.CREATED && created.isBlank()) ||
        (phase >= FileCopyPhase.VERIFIED && published.isBlank()) ||
        (phase >= FileCopyPhase.VERIFIED && !sameFileObjectAcrossRename(created, published)) ||
        (phase == FileCopyPhase.RESERVED && (created.isNotEmpty() || published.isNotEmpty())) ||
        (phase == FileCopyPhase.CREATED && published.isNotEmpty())
    ) return null
    FileCopyPublication(token, purpose, source.path, requestedName, target.path, sourceIdentity,
        bytes, hash, phase, created, published)
}.getOrNull()

internal data class FileCopyPublicationSnapshot(val revision: Long, val records: List<FileCopyPublication>)

/** Only metadata uses this mutex. Repository callers acquire their own mutex first. */
internal object FileCopyPublications {
    private val mutex = Mutex()
    private var revision = 0L
    private val activeSources = ConcurrentHashMap.newKeySet<String>()

    private fun readLocked(context: Context): List<FileCopyPublication> {
        val entries = getRecorderPreferences(context).requireDurableStringSet(PrefKey.FILE_COPY_PUBLICATIONS)
        if (entries.isEmpty()) return emptyList()
        val managed = managedRecordingFileDirectoryIds(context)
        return entries.map { raw ->
            val record = decodeFileCopyPublication(raw) ?: throw IOException("Invalid FILE copy publication journal")
            if (!recordingFileStorageIdIsManaged(record.sourceId, managed) ||
                !recordingFileStorageIdIsManaged(record.targetId, managed)
            ) throw IOException("FILE copy publication is outside managed storage")
            record
        }.sortedBy { it.token }.also { records ->
            if (records.map { it.token }.toSet().size != records.size) throw IOException("Duplicate FILE copy transaction")
        }
    }

    // Synchronous entry points are called by the existing storage IO workers only.
    fun snapshot(context: Context): FileCopyPublicationSnapshot = runBlocking {
        mutex.withLock { FileCopyPublicationSnapshot(revision, readLocked(context)) }
    }

    fun replace(context: Context, previous: FileCopyPublication?, next: FileCopyPublication?) = runBlocking {
        mutex.withLock { replaceLocked(context, previous, next) }
    }

    private fun replaceLocked(context: Context, previous: FileCopyPublication?, next: FileCopyPublication?) {
        val preferences = getRecorderPreferences(context)
        val current = preferences.requireDurableStringSet(PrefKey.FILE_COPY_PUBLICATIONS)
        val updated = current.toMutableSet()
        if (previous != null && !updated.remove(encodeFileCopyPublication(previous))) {
            throw IOException("FILE copy transaction changed")
        }
        if (next != null) {
            if (decodeFileCopyPublication(encodeFileCopyPublication(next)) != next) throw IOException("Invalid FILE copy transaction")
            if (updated.any { decodeFileCopyPublication(it)?.token == next.token }) throw IOException("Duplicate FILE copy transaction")
            updated += encodeFileCopyPublication(next)
        }
        if (updated == current) return
        if (!preferences.commitDurableStringSetReplacement(PrefKey.FILE_COPY_PUBLICATIONS, current, updated)) {
            throw IOException("Unable to persist FILE copy publication")
        }
        revision++
    }

    suspend fun <T> catalogCommit(
        context: Context,
        snapshot: FileCopyPublicationSnapshot?,
        acknowledge: List<FileCopyPublication> = emptyList(),
        commit: suspend () -> T,
    ): T {
        if (snapshot == null) {
            check(acknowledge.isEmpty())
            return commit()
        }
        return mutex.withLock {
            if (revision != snapshot.revision || readLocked(context) != snapshot.records) {
                throw IOException("FILE publication changed during catalog observation")
            }
            val result = commit()
            acknowledge.forEach { record ->
                if (record.phase == FileCopyPhase.VERIFIED) {
                    replaceLocked(context, record, record.copy(phase = FileCopyPhase.CATALOGED))
                }
            }
            result
        }
    }

    fun <T> withSource(source: File, action: () -> T): T {
        val key = source.absolutePath
        if (!activeSources.add(key)) throw IOException("FILE publication is already active")
        try { return action() } finally { activeSources.remove(key) }
    }

    fun sourceIsActive(sourceId: String): Boolean = sourceId in activeSources
}

internal fun pendingFileCopyCandidateIds(snapshot: FileCopyPublicationSnapshot): Set<String> =
    snapshot.records.filter { it.phase < FileCopyPhase.VERIFIED }.mapTo(HashSet()) { it.targetId }

internal fun fileCopySourceIdsToHide(
    records: List<FileCopyPublication>,
    recordings: List<RecordingEntity>,
): Set<String> {
    val renames = records.filter { it.purpose == FileCopyPurpose.RENAME && it.phase >= FileCopyPhase.VERIFIED }
    if (renames.isEmpty()) return emptySet()
    val byId = recordings.associateBy { it.id }
    return renames.asSequence()
        .filter { record ->
            byId[record.targetId]?.fileIdentity == record.targetIdentity &&
                byId[record.sourceId]?.fileIdentity == record.sourceIdentity
        }.mapTo(HashSet()) { it.sourceId }
}

internal fun filterFileCopyCatalogRows(context: Context, rows: List<RecordingEntity>): List<RecordingEntity> {
    if (rows.none { it.storageType == RecordingStorageType.FILE }) return rows
    val snapshot = try { FileCopyPublications.snapshot(context) } catch (failure: Exception) {
        reportFileCopyRecoveryIncident(context, "journal-unavailable", failure)
        // These are already cataloged rows. New FILE imports still require a readable journal;
        // preserve this known snapshot instead of removing unrelated provider recordings.
        return rows
    }
    if (snapshot.records.isEmpty()) return rows
    val hidden = pendingFileCopyCandidateIds(snapshot) + fileCopySourceIdsToHide(snapshot.records, rows)
    return rows.filterNot { it.id in hidden }
}

internal fun fileCopyPublicationForTarget(
    snapshot: FileCopyPublicationSnapshot,
    target: RecordingEntity,
): FileCopyPublication? {
    if (target.storageType != RecordingStorageType.FILE) return null
    val matches = snapshot.records.filter { it.purpose != FileCopyPurpose.CLAIM_RESTORE &&
        it.targetId == target.id && it.targetIdentity == target.fileIdentity &&
        it.phase >= FileCopyPhase.VERIFIED }
    if (matches.size > 1) throw IOException("Conflicting FILE copy publication receipts")
    val record = matches.singleOrNull() ?: return null
    val current = readCurrentVerifiedFileFingerprint(File(target.id))
        ?: throw IOException("Copied publication is unavailable")
    if (!record.matchesTarget(current)) throw IOException("Copied publication changed before catalog acknowledgement")
    return record
}

internal interface FileCopyPublicationStore {
    fun records(): List<FileCopyPublication>
    fun replace(previous: FileCopyPublication?, next: FileCopyPublication?)
    fun preserveIncomplete(record: FileCopyPublication, fingerprint: StableOutputFingerprint)
}

internal class ContextFileCopyPublicationStore(private val context: Context) : FileCopyPublicationStore {
    override fun records() = FileCopyPublications.snapshot(context).records
    override fun replace(previous: FileCopyPublication?, next: FileCopyPublication?) =
        FileCopyPublications.replace(context, previous, next)

    override fun preserveIncomplete(record: FileCopyPublication, fingerprint: StableOutputFingerprint) {
        // A closed crash-left partial gains suppression only, never deletion authority.
        if (!suppressFileOutputWithoutDeletion(context, record.targetId, fingerprint.digest)) {
            throw IOException("Unable to preserve incomplete FILE publication")
        }
        replace(record, null)
        reportFileCopyRecoveryIncident(context, record.token,
            FileCopyRecoveryRequired("Incomplete or ambiguous publication preserved without deletion authority"))
    }
}

internal interface FileCopyWriter : AutoCloseable {
    val stream: OutputStream
    fun identity(): String
    fun size(): Long
    fun sync()
}

internal interface FileCopyPublicationIo {
    fun fingerprint(file: File): StableOutputFingerprint?
    fun verifiedFingerprint(file: File): StableOutputFingerprint? = fingerprint(file)
    fun state(file: File): StoragePathState
    fun isRegularFile(file: File): Boolean?
    fun createExclusive(file: File): FileCopyWriter?
    fun readSource(file: File, expected: StableOutputFingerprint, read: (InputStream) -> CopyDigest): CopyDigest
    fun forceFile(file: File, expected: StableOutputFingerprint)
    fun forceDirectory(directory: File)
}

internal object DefaultFileCopyPublicationIo : FileCopyPublicationIo {
    override fun fingerprint(file: File) = readStableFileOutputFingerprint(file)
    override fun verifiedFingerprint(file: File) = readCurrentVerifiedFileFingerprint(file)
    override fun state(file: File) = storagePathState(file)
    override fun isRegularFile(file: File): Boolean? = observeStoragePath(file).let {
        if (it.state == StoragePathState.PRESENT) it.isRegularFile else null
    }
    override fun forceDirectory(directory: File) = forceRecordingDirectoryDurable(directory)

    override fun forceFile(file: File, expected: StableOutputFingerprint) {
        FileInputStream(file).use { input ->
            if (!fileDescriptorIdentityMatches(expected.fileKey.orEmpty(), resolveFileDescriptorIdentity(input.fd))) {
                throw IOException("Copied publication changed before durability")
            }
            input.fd.sync()
            if (!fileIdentityMatches(expected.fileKey.orEmpty(), resolveFileIdentity(file))) {
                throw IOException("Copied publication changed during durability")
            }
        }
    }

    override fun createExclusive(file: File): FileCopyWriter? {
        val descriptor = try {
            Os.open(file.path, OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_RDWR or
                OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC, 0x1B6)
        } catch (error: ErrnoException) {
            if (error.errno == OsConstants.EEXIST) return null
            throw IOException("Unable to exclusively create copied publication", error)
        }
        val output = try { FileOutputStream(descriptor) } catch (error: Throwable) {
            try { Os.close(descriptor) } catch (close: Throwable) { if (close !== error) error.addSuppressed(close) }
            throw error
        }
        return object : FileCopyWriter {
            private var closed = false
            override val stream: OutputStream = output
            override fun identity() = resolveFileDescriptorIdentity(descriptor)
            override fun size() = output.channel.size()
            override fun sync() = descriptor.sync()
            override fun close() {
                if (closed) return
                closed = true
                // Android's public FileOutputStream(fd) does not own the supplied descriptor.
                // Close both owners once, even when closing the stream/channel fails.
                var failure = closePreservingPrimaryFailure(null) { output.close() }
                failure = closePreservingPrimaryFailure(failure) { Os.close(descriptor) }
                failure?.let { throw if (it is ErrnoException) IOException("Unable to close publication descriptor", it) else it }
            }
        }
    }

    override fun readSource(file: File, expected: StableOutputFingerprint, read: (InputStream) -> CopyDigest): CopyDigest {
        val selected = expected.fileKey?.takeIf { it.isNotBlank() } ?: throw IOException("Missing copy source identity")
        if (!fileIdentityMatches(selected, resolveFileIdentity(file))) throw IOException("Copy source changed")
        return FileInputStream(file).use { input ->
            val opened = resolveFileDescriptorIdentity(input.fd)
            if (!fileDescriptorIdentityMatches(selected, opened)) throw IOException("Copy source changed during open")
            val digest = read(input)
            if (resolveFileDescriptorIdentity(input.fd) != opened ||
                !fileIdentityMatches(selected, resolveFileIdentity(file)) || !copyDigestMatches(expected.digest, digest)
            ) throw IOException("Copy source changed while reading")
            digest
        }
    }
}

internal class FileCopyRecoveryRequired(message: String) : IOException(message)
internal class FileCopyCleanupPendingException(message: String, cause: Throwable? = null) : IOException(message, cause)

internal fun recoverFileCopyPublication(
    store: FileCopyPublicationStore,
    io: FileCopyPublicationIo,
    source: File,
    requestedName: String,
    expected: StableOutputFingerprint,
): FileCopyPublication? {
    val related = store.records().filter { it.matchesSource(source, expected) }
    if (related.isEmpty()) return null
    if (related.size != 1) throw FileCopyRecoveryRequired("Conflicting FILE publication attempts")
    var record = related.single()
    // RESERVED owns suppression, not a completed output tied to the requested name.
    if (record.phase != FileCopyPhase.RESERVED &&
        record.requestedName != requestedName && File(record.targetId).name != requestedName) {
        throw FileCopyRecoveryRequired("An earlier FILE publication needs recovery")
    }
    val target = File(record.targetId)
    if (record.phase == FileCopyPhase.RESERVED && io.state(target) == StoragePathState.MISSING) {
        io.forceDirectory(requireNotNull(target.parentFile))
        store.replace(record, null)
        return null
    }
    if (record.phase == FileCopyPhase.RESERVED && io.isRegularFile(target) == false) {
        io.forceDirectory(requireNotNull(target.parentFile))
        store.replace(record, null)
        return null
    }
    val current = (if (record.phase >= FileCopyPhase.VERIFIED) io.verifiedFingerprint(target) else io.fingerprint(target))
        ?: throw FileCopyRecoveryRequired("Copied publication is absent or unavailable; source is retained")
    when (record.phase) {
        FileCopyPhase.RESERVED -> {
            store.preserveIncomplete(record, current)
            throw FileCopyRecoveryRequired("Interrupted file creation preserved; retry will choose another name")
        }
        FileCopyPhase.CREATED -> {
            if (!sameFileObjectAcrossRename(record.createdIdentity, current.fileKey.orEmpty())) {
                throw FileCopyRecoveryRequired("Copied publication changed; both files are preserved")
            }
            if (!copyDigestMatches(record.sourceFingerprint().digest, current.digest)) {
                store.preserveIncomplete(record, current)
                throw FileCopyRecoveryRequired("Incomplete copied publication preserved; retry will choose another name")
            }
            io.forceFile(target, current)
            io.forceDirectory(requireNotNull(target.parentFile))
            val verified = record.copy(phase = FileCopyPhase.VERIFIED, targetIdentity = requireNotNull(current.fileKey))
            store.replace(record, verified)
            record = verified
        }
        FileCopyPhase.VERIFIED, FileCopyPhase.CATALOGED -> {
            if (!record.matchesTarget(current)) throw FileCopyRecoveryRequired("Verified copied publication changed")
        }
    }
    return record
}

internal fun copyFileForPublication(
    store: FileCopyPublicationStore,
    io: FileCopyPublicationIo,
    source: File,
    destination: File,
    requestedName: String,
    expected: StableOutputFingerprint,
    purpose: FileCopyPurpose,
    progress: (Long, Long) -> Unit = { _, _ -> },
    checkpoint: (FileCopyPhase) -> Unit = {},
): FileCopyPublication {
    var record = FileCopyPublication(UUID.randomUUID().toString(), purpose, source.absolutePath,
        requestedName, destination.absolutePath, expected.fileKey.orEmpty(), expected.digest.byteCount,
        expected.digest.sha256.toHexString())
    store.replace(null, record)
    checkpoint(FileCopyPhase.RESERVED)
    val writer = io.createExclusive(destination)
    if (writer == null) {
        store.replace(record, null)
        throw java.nio.file.FileAlreadyExistsException(destination.path)
    }
    writer.use {
        val created = writer.identity().takeIf { it.isNotBlank() }
            ?: throw IOException("Copied publication creation identity is unavailable")
        val path = io.fingerprint(destination)
        if (writer.size() != 0L || path?.digest?.byteCount != 0L ||
            !fileDescriptorIdentityMatches(path.fileKey.orEmpty(), created)
        ) throw IOException("Copied publication creation handoff changed")
        io.forceDirectory(requireNotNull(destination.parentFile))
        if (!fileDescriptorIdentityMatches(io.fingerprint(destination)?.fileKey.orEmpty(), writer.identity())) {
            throw IOException("Copied publication changed during creation durability")
        }
        val updated = record.copy(phase = FileCopyPhase.CREATED, createdIdentity = requireNotNull(requireNotNull(path).fileKey))
        store.replace(record, updated)
        record = updated
        checkpoint(FileCopyPhase.CREATED)
        var copied = 0L
        val counting = object : OutputStream() {
            override fun write(value: Int) {
                writer.stream.write(value)
                copied++
                progress(copied, expected.digest.byteCount)
            }
            override fun write(bytes: ByteArray, offset: Int, count: Int) {
                writer.stream.write(bytes, offset, count)
                copied += count
                progress(copied, expected.digest.byteCount)
            }
        }
        io.readSource(source, expected) { input -> copyWithSha256(input, counting) }
        writer.sync()
    }
    val verified = io.fingerprint(destination) ?: throw IOException("Unable to verify copied publication")
    if (!copyDigestMatches(expected.digest, verified.digest) ||
        !sameFileObjectAcrossRename(record.createdIdentity, verified.fileKey.orEmpty())
    ) throw IOException("Copied publication is not the complete created output")
    io.forceDirectory(requireNotNull(destination.parentFile))
    val complete = record.copy(phase = FileCopyPhase.VERIFIED, targetIdentity = requireNotNull(verified.fileKey))
    store.replace(record, complete)
    checkpoint(FileCopyPhase.VERIFIED)
    return complete
}
