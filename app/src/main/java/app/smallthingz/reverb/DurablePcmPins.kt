package app.smallthingz.reverb

import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.io.RandomAccessFile
import java.lang.ref.WeakReference
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.zip.CRC32

/** Reference publication/removal and raw-store mutations share this lock. */
internal object DurablePcmPins {
    val mutationLock = Any()
    const val MAX_OBJECTS = 16_384
    private val pinName = Regex("pcm-[0-9]{1,5}")
    private val sourcePaths = BoundedSourcePathSyntaxCache(MAX_OBJECTS, 4L * 1024 * 1024, ::validateSourcePath)

    data class ObjectProof(
        val name: String,
        val identity: String,
        val payloadOffset: Long,
        val payloadBytes: Long,
        val checksum: Int,
        val sourcePath: String = "",
        val sourceDirectoryIdentity: String = "",
        val storeDirectoryIdentity: String = "",
    )

    data class Snapshot(val range: FrozenPcmRange, val objects: List<ObjectProof>)

    internal fun sourceRecords(source: FrozenPcmRange): List<PersistentAudioChunkStore.ChunkRecord> {
        val records = ArrayList<PersistentAudioChunkStore.ChunkRecord>()
        val seen = HashSet<String>()
        fun add(segments: List<PersistentAudioChunkStore.Segment>) {
            for (segment in segments) {
                val record = segment.record
                if (!seen.add(record.file.absolutePath)) continue
                if (records.size == MAX_OBJECTS) throw IOException("Save source exceeds object limit")
                records += record
            }
        }
        add(source.segments)
        add(source.leadingContext)
        add(source.trailingContext)
        if (records.isEmpty()) throw IOException("Save source exceeds object limit")
        return records
    }

    /** The caller retains the lease monitor and source references; store capture may continue. */
    fun link(
        directory: File,
        source: FrozenPcmRange,
        identityResolver: (File) -> String = ::resolveFileIdentity,
        descriptorMatches: (String, FileDescriptor) -> Boolean = ::descriptorMatches,
        syncFile: (FileDescriptor) -> Unit = FileDescriptor::sync,
        syncDirectory: (File) -> Unit = ::forceRecordingDirectoryDurable,
        beforeLinks: (Snapshot) -> Unit,
    ): Snapshot {
        val originals = sourceRecords(source)
        requireJobId(directory.name)
        val directoryIdentities = HashMap<File, String>()
        fun directoryIdentity(file: File) = directoryIdentities.getOrPut(file) { resolveDirectoryIdentity(file) }
        val proofs = originals.mapIndexed { index, record ->
            if (record.state != PersistentAudioChunkStore.ChunkState.FINALIZED) throw IOException("Save source was not sealed")
            val identity = synchronized(mutationLock) { identityResolver(record.file) }
            if (!identity.startsWith("stat:")) throw IOException("Save source object identity unavailable")
            ObjectProof("pcm-$index", identity, record.payloadOffsetBytes, record.payloadBytes,
                record.payloadChecksum, checkedSourcePath(record.file.absolutePath).absolutePath,
                directoryIdentity(requireNotNull(record.file.parentFile)),
                directoryIdentity(requireNotNull(record.file.parentFile.parentFile)))
        }
        val snapshot = Snapshot(source, proofs)
        beforeLinks(snapshot)
        for ((refsRoot, batch) in proofs.groupBy { referenceRoot(checkedSourcePath(it.sourcePath)) }) {
            val marker = DurablePcmBatchReferences.file(refsRoot, directory.name)
            val temporary = File(refsRoot, marker.name + ".pending")
            val payload = DurablePcmBatchReferences.encode(directory.name, batch)
            for (proof in batch) synchronized(mutationLock) {
                if (!sameFileObjectAcrossRename(proof.identity, identityResolver(checkedSourcePath(proof.sourcePath)))) {
                    throw IOException("Save source changed before referencing")
                }
            }
            val created = synchronized(mutationLock) {
                validateBatchSourceAuthority(directory, batch)
                ensureDirectory(refsRoot)
                if (storagePathState(marker) != StoragePathState.MISSING) throw IOException("PCM reference already exists")
                Files.createFile(temporary.toPath())
                resolveFileIdentity(temporary)
            }
            // The live lease protects source audio until this one batch and its namespace
            // are durable. A visible partial batch conservatively protects the whole store.
            val writtenIdentity = RandomAccessFile(temporary, "rw").use { output ->
                if (!descriptorMatches(created, output.fd) || output.length() != 0L) throw IOException("PCM reference candidate changed")
                output.write(payload)
                syncFile(output.fd)
                val written = resolveFileIdentity(temporary)
                if (!sameFileObjectAcrossRename(created, written) || !descriptorMatches(written, output.fd)) {
                    throw IOException("PCM reference changed")
                }
                written
            }
            synchronized(mutationLock) {
                if (storagePathState(marker) != StoragePathState.MISSING) throw IOException("PCM reference already exists")
                Files.move(temporary.toPath(), marker.toPath(), StandardCopyOption.ATOMIC_MOVE)
                if (!sameFileObjectAcrossRename(writtenIdentity, resolveFileIdentity(marker)) ||
                    DurablePcmBatchReferences.read(marker, descriptorMatches, useCache = false) != batch.associateBy { it.sourcePath }) {
                    throw IOException("PCM reference publication changed")
                }
                validateBatchSourceAuthority(directory, batch)
            }
            // The live range lease remains held; do not monopolize the capture mutation
            // lock while checking a large history's individual object identities.
            for (proof in batch) synchronized(mutationLock) {
                if (!sameFileObjectAcrossRename(proof.identity, identityResolver(checkedSourcePath(proof.sourcePath)))) {
                    throw IOException("Save source changed while being referenced")
                }
            }
            synchronized(mutationLock) { validateSourceAuthority(directory, batch.first()) }
            syncDirectory(refsRoot)
            syncDirectory(requireNotNull(refsRoot.parentFile))
            batch.map { checkedSourcePath(it.sourcePath).parentFile }.distinct().forEach {
                syncDirectory(it)
            }
        }
        return snapshot
    }

    private val verificationHolds = HashMap<String, Int>()
    private val batchRemovalBarriers = HashMap<String, File>()

    /** Scratch belongs to this caller and must not be shared by concurrent verifications. */
    fun verify(
        directory: File,
        proof: ObjectProof,
        descriptorMatches: (String, FileDescriptor) -> Boolean = ::descriptorMatches,
        identityResolver: (File) -> String = ::resolveFileIdentity,
        afterBinding: () -> Unit = {},
        accessCloser: (RandomAccessFile) -> Unit = RandomAccessFile::close,
        scratch: ByteArray = ByteArray(64 * 1024),
    ) {
        require(scratch.isNotEmpty()) { "PCM verification scratch must not be empty" }
        if (proof.sourcePath.isEmpty()) {
            // Legacy local-link verification still shares its destructive release lock.
            synchronized(mutationLock) {
                verifySource(directory, proof, descriptorMatches, identityResolver, afterBinding, accessCloser, scratch)
            }
        } else {
            verifySource(directory, proof, descriptorMatches, identityResolver, afterBinding, accessCloser, scratch)
        }
    }

    private fun verifySource(
        directory: File,
        proof: ObjectProof,
        descriptorMatches: (String, FileDescriptor) -> Boolean,
        identityResolver: (File) -> String,
        afterBinding: () -> Unit,
        accessCloser: (RandomAccessFile) -> Unit,
        scratch: ByteArray,
    ) {
        val file = sourceFile(directory, proof)
        var input: RandomAccessFile? = null
        var heldMarker: String? = null
        var primaryFailure: Throwable? = null
        try {
            val before = synchronized(mutationLock) {
                val marker = if (proof.sourcePath.isNotEmpty()) {
                    validateSourceAuthority(directory, proof)
                    referenceFile(directory, proof).also {
                        requireReference(it, directory, proof, descriptorMatches)
                    }
                } else null
                val identity = identityResolver(file)
                if (!sameFileObjectAcrossRename(proof.identity, identity)) throw IOException("Pinned PCM object changed")
                val opened = RandomAccessFile(file, "r")
                input = opened
                if (!descriptorMatches(identity, opened.fd) || proof.payloadOffset < 0L || proof.payloadBytes <= 0L ||
                    opened.length() != Math.addExact(proof.payloadOffset, proof.payloadBytes)) {
                    throw IOException("Pinned PCM geometry changed")
                }
                marker?.absolutePath?.let { key ->
                    verificationHolds[key] = Math.addExact(verificationHolds[key] ?: 0, 1)
                    heldMarker = key
                }
                identity
            }
            val source = requireNotNull(input)
            afterBinding()
            source.seek(proof.payloadOffset)
            val crc = CRC32()
            val bytes = scratch
            var remaining = proof.payloadBytes
            while (remaining > 0L) {
                val count = source.read(bytes, 0, minOf(remaining, bytes.size.toLong()).toInt())
                if (count <= 0) throw IOException("Pinned PCM truncated")
                crc.update(bytes, 0, count)
                remaining -= count
            }
            synchronized(mutationLock) {
                if (proof.sourcePath.isNotEmpty()) {
                    validateSourceAuthority(directory, proof)
                    requireReference(referenceFile(directory, proof), directory, proof, descriptorMatches)
                }
                if (crc.value.toInt() != proof.checksum || !descriptorMatches(before, source.fd) ||
                    !fileIdentityMatches(before, identityResolver(file))) {
                    throw IOException("Pinned PCM changed during verification")
                }
            }
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            var terminalFailure = primaryFailure
            try {
                input?.let(accessCloser)
            } catch (close: Throwable) {
                if (terminalFailure == null) terminalFailure = close
                else if (close !== terminalFailure) terminalFailure.addSuppressed(close)
            }
            // The marker remains non-releasable through the descriptor's one terminal close.
            try {
                synchronized(mutationLock) {
                    heldMarker?.let { key ->
                        val count = checkNotNull(verificationHolds[key])
                        check(count > 0)
                        if (count == 1) verificationHolds.remove(key) else verificationHolds[key] = count - 1
                    }
                }
            } catch (cleanup: Throwable) {
                if (terminalFailure == null) terminalFailure = cleanup
                else if (cleanup !== terminalFailure) terminalFailure.addSuppressed(cleanup)
            }
            if (primaryFailure == null) terminalFailure?.let { throw it }
        }
    }

    /** Terminal authority removes only this job's exact reference; legacy links remain supported. */
    fun release(directory: File, proofs: List<ObjectProof>,
        descriptorMatches: (String, FileDescriptor) -> Boolean = ::descriptorMatches,
        syncDirectory: (File) -> Unit = ::forceRecordingDirectoryDurable) {
        val affectedRoots = linkedSetOf<String>()
        var primaryFailure: Throwable? = null
        try {
            val releasedBatches = HashSet<File>()
            for ((root, batch) in proofs.filter { it.sourcePath.isNotEmpty() }
                .groupBy { referenceRoot(checkedSourcePath(it.sourcePath)) }) synchronized(mutationLock) {
                val marker = DurablePcmBatchReferences.file(root, directory.name)
                val pending = File(root, marker.name + ".pending")
                validateBatchSourceAuthority(directory, batch)
                affectedRoots += root.parentFile.absolutePath
                if (storagePathState(marker) == StoragePathState.MISSING &&
                    storagePathState(pending) == StoragePathState.MISSING) {
                    // After process death an unlink may be visible but not directory-durable.
                    // A nonempty legacy child cannot supply the missing batch-parent barrier.
                    val rootState = storagePathState(root)
                    val barrier = when (rootState) {
                        StoragePathState.PRESENT -> root
                        StoragePathState.MISSING -> requireNotNull(root.parentFile)
                        StoragePathState.UNAVAILABLE -> throw IOException("PCM batch absence unavailable")
                    }
                    requireDirectory(barrier)
                    val barrierIdentity = resolveDirectoryIdentity(barrier)
                    batchRemovalBarriers[marker.absolutePath] = root
                    syncDirectory(barrier)
                    if (!fileIdentityMatches(barrierIdentity, resolveDirectoryIdentity(barrier)) ||
                        (rootState == StoragePathState.MISSING && storagePathState(root) != StoragePathState.MISSING) ||
                        storagePathState(marker) != StoragePathState.MISSING ||
                        storagePathState(pending) != StoragePathState.MISSING) {
                        throw IOException("PCM batch references changed during absence proof")
                    }
                    batchRemovalBarriers.remove(marker.absolutePath)
                    // Legacy markers still need their own exact ownership cleanup below.
                    return@synchronized
                }
                if ((verificationHolds[marker.absolutePath] ?: 0) != 0) {
                    throw IOException("PCM reference verification is still active; cleanup must retry")
                }
                val expected = batch.associateBy { it.sourcePath }
                // Validate both candidates before removing either: a torn candidate preserves
                // the entire batch, even if the final marker itself is valid.
                val candidates = listOf(marker, pending).filter { storagePathState(it) != StoragePathState.MISSING }
                    .map { candidate ->
                        val identity = resolveFileIdentity(candidate)
                        if (DurablePcmBatchReferences.read(candidate, descriptorMatches, useCache = false) != expected ||
                            !fileIdentityMatches(identity, resolveFileIdentity(candidate))) {
                            throw IOException("PCM batch reference proof mismatch")
                        }
                        candidate to identity
                    }
                batchRemovalBarriers[marker.absolutePath] = root
                for ((candidate, identity) in candidates) {
                    if (!fileIdentityMatches(identity, resolveFileIdentity(candidate))) throw IOException("PCM batch changed before retirement")
                    Files.delete(candidate.toPath())
                }
                requireDirectory(root)
                syncDirectory(root)
                batchRemovalBarriers.remove(marker.absolutePath)
                releasedBatches += root
            }
            for (proof in proofs) synchronized(mutationLock) {
                if (proof.sourcePath.isEmpty()) {
                    val file = ownedFile(directory, proof.name)
                    if (storagePathState(file) != StoragePathState.MISSING) {
                        verify(directory, proof)
                        Files.delete(file.toPath())
                    }
                    forceRecordingDirectoryDurable(directory)
                } else {
                    if (referenceRoot(checkedSourcePath(proof.sourcePath)) in releasedBatches) return@synchronized
                    validateSourceAuthority(directory, proof)
                    val source = checkedSourcePath(proof.sourcePath)
                    affectedRoots += source.parentFile.parentFile.absolutePath
                    val marker = referenceFile(directory, proof)
                    if ((verificationHolds[marker.absolutePath] ?: 0) != 0) {
                        throw IOException("PCM reference verification is still active; cleanup must retry")
                    }
                    val parent = requireNotNull(marker.parentFile)
                    if (storagePathState(parent) == StoragePathState.MISSING) {
                        val refsRoot = requireNotNull(parent.parentFile)
                        val barrier = when (storagePathState(refsRoot)) {
                            StoragePathState.PRESENT -> refsRoot
                            StoragePathState.MISSING -> requireNotNull(refsRoot.parentFile)
                            StoragePathState.UNAVAILABLE -> throw IOException("PCM reference absence unavailable")
                        }
                        requireDirectory(barrier)
                        forceRecordingDirectoryDurable(barrier)
                        if (storagePathState(parent) != StoragePathState.MISSING) throw IOException("PCM references changed during absence proof")
                        return@synchronized
                    }
                    requireDirectory(parent)
                    val parentIdentity = resolveDirectoryIdentity(parent)
                    val expected = markerBytes(directory.name, proof)
                    for (candidate in listOf(marker, File(parent, marker.name + ".pending"))) {
                        if (storagePathState(candidate) == StoragePathState.MISSING) continue
                        requireMarker(candidate, expected, descriptorMatches)
                        Files.delete(candidate.toPath())
                    }
                    forceRecordingDirectoryDurable(parent)
                    if (Files.newDirectoryStream(parent.toPath()).use { !it.iterator().hasNext() }) {
                        if (!fileIdentityMatches(parentIdentity, resolveDirectoryIdentity(parent))) {
                            throw IOException("PCM reference directory changed before retirement")
                        }
                        Files.delete(parent.toPath())
                        forceRecordingDirectoryDurable(requireNotNull(parent.parentFile))
                    }
                }
            }
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            // A later reference failure must not strand earlier unprotected retired audio.
            // Notification only schedules fresh store checks; it grants no deletion authority.
            try {
                notifyReleased(affectedRoots)
            } catch (notificationFailure: Throwable) {
                if (primaryFailure == null) throw notificationFailure
                if (notificationFailure !== primaryFailure) primaryFailure.addSuppressed(notificationFailure)
            }
        }
    }

    private val stores = mutableMapOf<String, MutableList<WeakReference<PersistentAudioChunkStore>>>()
    fun registerStore(root: File, store: PersistentAudioChunkStore) = synchronized(stores) {
        val entries = stores.getOrPut(root.absolutePath) { mutableListOf() }
        entries.removeAll { it.get() == null }
        if (entries.none { it.get() === store }) entries += WeakReference(store)
    }
    private fun notifyReleased(roots: Set<String>) {
        val listeners = synchronized(stores) { roots.flatMap { stores[it].orEmpty().mapNotNull { ref -> ref.get() } } }
        var failure: Throwable? = null
        for (listener in listeners) {
            try {
                listener.onDurableSavePinsReleased()
            } catch (error: Throwable) {
                if (failure == null) failure = error
                else if (error !== failure) failure.addSuppressed(error)
            }
        }
        failure?.let { throw it }
    }

    fun sourceFile(directory: File, proof: ObjectProof): File =
        if (proof.sourcePath.isEmpty()) ownedFile(directory, proof.name) else checkedSourcePath(proof.sourcePath)

    fun hasReference(directory: File, proof: ObjectProof): Boolean = if (proof.sourcePath.isEmpty()) {
        storagePathState(ownedFile(directory, proof.name)) != StoragePathState.MISSING
    } else {
        val marker = referenceFile(directory, proof)
        synchronized(mutationLock) {
            marker.absolutePath in batchRemovalBarriers || storagePathState(marker) != StoragePathState.MISSING ||
                storagePathState(File(marker.parentFile, marker.name + ".pending")) != StoragePathState.MISSING
        }
    }

    /** Unknown or partial markers veto source mutation independently of hydration. */
    fun isProtected(file: File,
        descriptorMatches: (String, FileDescriptor) -> Boolean = ::descriptorMatches): Boolean = synchronized(mutationLock) {
        val root = referenceRoot(file)
        if (batchRemovalBarriers.values.any { it == root }) return@synchronized true
        when (storagePathState(root)) {
            StoragePathState.MISSING -> false
            StoragePathState.UNAVAILABLE -> throw IOException("PCM reference root unavailable")
            StoragePathState.PRESENT -> {
                requireDirectory(root)
                if (hasUnknownReferenceEntry(root)) return@synchronized true
                for (batch in referenceRootChecks[root.absolutePath]?.batches.orEmpty()) {
                    val proofs = try { DurablePcmBatchReferences.read(batch, descriptorMatches) }
                    catch (_: IOException) { return@synchronized true }
                    if (file.absolutePath in proofs) return@synchronized true
                }
                val refs = File(root, file.name)
                when (storagePathState(refs)) {
                    StoragePathState.MISSING -> false
                    StoragePathState.UNAVAILABLE -> throw IOException("PCM references unavailable")
                    StoragePathState.PRESENT -> {
                        requireDirectory(refs)
                        Files.newDirectoryStream(refs.toPath()).use { it.iterator().hasNext() }
                    }
                }
            }
        }
    }
    private data class ReferenceRootCheck(val revision: String?, val unknown: Boolean, val batches: List<File>)
    private val referenceRootChecks = mutableMapOf<String, ReferenceRootCheck>()
    private fun hasUnknownReferenceEntry(root: File): Boolean {
        val identity = resolveDirectoryIdentity(root)
        val revision = referenceRootRevision(root, identity)
        val cached = referenceRootChecks[root.absolutePath]
        if (revision != null && cached != null && cached.revision == revision) return cached.unknown
        val batches = ArrayList<File>()
        val unknown = Files.newDirectoryStream(root.toPath()).use { entries ->
            entries.any { entry ->
                val name = entry.fileName.toString()
                if (DurablePcmBatchReferences.isBatchName(name)) {
                    batches += entry.toFile()
                    batches.size > DurableSaveJournal.MAX_JOBS || resolveFileIdentity(entry.toFile()).isBlank()
                } else name.toUIntOrNull()?.toString() != name || resolveDirectoryIdentity(entry.toFile()).isBlank()
            }
        }
        if (!fileIdentityMatches(identity, resolveDirectoryIdentity(root)) ||
            (revision != null && revision != referenceRootRevision(root, identity))) {
            throw IOException("PCM reference membership changed")
        }
        if (referenceRootChecks.size >= DurableSaveJournal.MAX_JOBS && root.absolutePath !in referenceRootChecks) referenceRootChecks.clear()
        referenceRootChecks[root.absolutePath] = ReferenceRootCheck(revision, unknown, batches)
        return unknown
    }
    private fun referenceRootRevision(root: File, identity: String): String? = runCatching {
        val stat = android.system.Os.lstat(root.absolutePath)
        if (stat.st_ino == 0L) null else "$identity:${stat.st_ctim.tv_sec}:${stat.st_ctim.tv_nsec}"
    }.getOrNull()

    internal fun referenceFile(directory: File, proof: ObjectProof): File = synchronized(mutationLock) {
        requireJobId(directory.name)
        val source = checkedSourcePath(proof.sourcePath)
        val batch = DurablePcmBatchReferences.file(referenceRoot(source), directory.name)
        if (storagePathState(batch) != StoragePathState.MISSING ||
            storagePathState(File(batch.parentFile, batch.name + ".pending")) != StoragePathState.MISSING ||
            batch.absolutePath in batchRemovalBarriers) return@synchronized batch
        File(File(referenceRoot(source), source.name), directory.name)
    }
    private fun referenceRoot(source: File): File = File(requireNotNull(source.parentFile.parentFile), "durable-pcm-refs")
    private fun markerBytes(jobId: String, proof: ObjectProof): ByteArray =
        ("reverb-pcm-reference-v1\n$jobId\n${proof.name}\n${proof.sourcePath}\n${proof.identity}\n" +
            "${proof.payloadOffset}\n${proof.payloadBytes}\n${proof.checksum}\n${proof.sourceDirectoryIdentity}\n${proof.storeDirectoryIdentity}\n").toByteArray(Charsets.UTF_8)
    private fun requireReference(file: File, directory: File, proof: ObjectProof,
        descriptorMatches: (String, FileDescriptor) -> Boolean) {
        if (DurablePcmBatchReferences.isBatchName(file.name)) {
            if (DurablePcmBatchReferences.read(file, descriptorMatches)[proof.sourcePath] != proof) {
                throw IOException("PCM batch reference proof mismatch")
            }
        } else requireMarker(file, markerBytes(directory.name, proof), descriptorMatches)
    }
    private fun requireMarker(file: File, expected: ByteArray,
        descriptorMatches: (String, FileDescriptor) -> Boolean = ::descriptorMatches) {
        val identity = resolveFileIdentity(file).takeIf(String::isNotBlank) ?: throw IOException("PCM reference unavailable")
        RandomAccessFile(file, "r").use { input ->
            if (input.length() != expected.size.toLong() || !descriptorMatches(identity, input.fd)) throw IOException("PCM reference changed")
            val bytes = ByteArray(expected.size)
            input.readFully(bytes)
            if (!bytes.contentEquals(expected) || !fileIdentityMatches(identity, resolveFileIdentity(file)) ||
                !descriptorMatches(identity, input.fd)) throw IOException("PCM reference proof mismatch")
        }
    }
    internal fun checkedSourcePath(path: String): File = sourcePaths.resolve(path)

    private fun validateSourcePath(path: String): File {
        val file = File(path)
        if (!file.isAbsolute || file.normalize().path != path || path.length > 8192 || '\n' in path || '\r' in path ||
            file.parentFile?.parentFile == null || file.parentFile.name != BUFFER_CHUNKS_FOLDER_NAME ||
            file.name.toUIntOrNull()?.toString() != file.name) throw IOException("Invalid PCM source path")
        return file
    }
    internal fun validateSourceAuthority(directory: File, proof: ObjectProof) {
        val source = checkedSourcePath(proof.sourcePath)
        val chunks = requireNotNull(source.parentFile)
        val store = requireNotNull(chunks.parentFile)
        val journalParent = requireNotNull(directory.parentFile.parentFile)
        val allowedParents = if (journalParent.name == "no_backup") {
            setOf(journalParent, File(journalParent.parentFile, "files"))
        } else setOf(journalParent)
        if (store.parentFile !in allowedParents ||
            !fileIdentityMatches(proof.sourceDirectoryIdentity, resolveDirectoryIdentity(chunks)) ||
            !fileIdentityMatches(proof.storeDirectoryIdentity, resolveDirectoryIdentity(store))) {
            throw IOException("PCM source directory authority changed")
        }
    }

    private fun validateBatchSourceAuthority(directory: File, proofs: List<ObjectProof>) {
        val first = proofs.first()
        val chunks = checkedSourcePath(first.sourcePath).parentFile
        if (proofs.any { checkedSourcePath(it.sourcePath).parentFile != chunks ||
                it.sourceDirectoryIdentity != first.sourceDirectoryIdentity ||
                it.storeDirectoryIdentity != first.storeDirectoryIdentity }) {
            throw IOException("PCM batch directory authorities disagree")
        }
        validateSourceAuthority(directory, first)
    }

    private fun descriptorMatches(identity: String, fd: FileDescriptor): Boolean =
        fileDescriptorIdentityMatches(identity, resolveFileDescriptorIdentity(fd))

    private fun requireJobId(id: String) {
        if (runCatching { UUID.fromString(id).toString() }.getOrNull() != id) throw IOException("Invalid PCM reference owner")
    }
    private fun requireDirectory(directory: File) {
        if (resolveDirectoryIdentity(directory).isBlank()) throw IOException("PCM reference directory unavailable")
    }
    private fun ensureDirectory(directory: File) {
        if (storagePathState(directory) == StoragePathState.MISSING && !directory.mkdir()) throw IOException("Cannot create PCM references")
        requireDirectory(directory)
    }

    internal fun ownedFile(directory: File, name: String): File {
        val file = pinnedFilePath(directory, name)
        if (resolveDirectoryIdentity(directory).isBlank()) throw IOException("Save directory unavailable")
        return file
    }

    internal fun pinnedFilePath(directory: File, name: String): File {
        if (!name.matches(pinName)) throw IOException("Invalid save pin name")
        return File(directory, name)
    }
}

/** Caches only lexical validation; every file/directory identity observation remains fresh. */
internal class BoundedSourcePathSyntaxCache(
    private val maxEntries: Int,
    private val maxPathBytes: Long,
    private val validate: (String) -> File,
) {
    init { require(maxEntries > 0 && maxPathBytes > 0) }
    private val lock = Any()
    private val paths = LinkedHashMap<String, File>(16, 0.75f, true)
    private var retainedPathBytes = 0L

    fun resolve(path: String): File {
        synchronized(lock) { paths[path]?.let { return it } }
        // No normalization, callbacks, storage observations or mutation-lock acquisition
        // run while holding the cache lock.
        val file = validate(path)
        val bytes = retainedBytes(path, file)
        if (bytes > maxPathBytes) return file
        return synchronized(lock) {
            paths[path]?.let { return@synchronized it }
            while (paths.size >= maxEntries || retainedPathBytes > maxPathBytes - bytes) {
                val iterator = paths.entries.iterator()
                val oldest = iterator.next()
                retainedPathBytes -= retainedBytes(oldest.key, oldest.value)
                iterator.remove()
            }
            paths[path] = file
            retainedPathBytes += bytes
            file
        }
    }

    private fun retainedBytes(path: String, file: File): Long =
        path.length.toLong() * 2L + if (file.path === path) 0L else file.path.length.toLong() * 2L
}
