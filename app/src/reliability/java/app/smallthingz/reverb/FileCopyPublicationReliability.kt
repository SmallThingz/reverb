package app.smallthingz.reverb

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

/** Owned QA files/preferences only. Actual descriptor, fingerprint, fsync and journal backends. */
internal fun verifyFileCopyPublicationBackend(context: Context, report: StringBuilder) {
    check(context.packageName.endsWith(".reliability"))
    val token = UUID.randomUUID().toString()
    val root = File(context.filesDir, "file-copy-publication-$token")
    check(root.mkdir())
    val external = File(root, "music").apply { check(mkdir()) }
    val scoped = object : ContextWrapper(context) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = root
        override fun getExternalFilesDir(type: String?): File = external
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            context.getSharedPreferences("file-copy-$token-$name", mode)
    }
    val directory = getSavedRecordingsDirectory(scoped).apply { check(mkdir()) }
    forceRecordingDirectoryDurable(root)
    forceRecordingDirectoryDurable(external)
    val store = ContextFileCopyPublicationStore(scoped)
    val io = DefaultFileCopyPublicationIo
    val closeProbe = requireNotNull(io.createExclusive(File(directory, "descriptor-close.pending")))
    val closedDescriptor = (closeProbe.stream as FileOutputStream).fd
    check(closeProbe.size() == 0L) // Also acquires the stream's channel before close.
    closeProbe.close()
    closeProbe.close() // Terminal-once: must not retry the raw descriptor.
    val closeProof = runCatching { Os.fstat(closedDescriptor) }.exceptionOrNull()
    check(closeProof is ErrnoException && closeProof.errno == OsConstants.EBADF)
    report.appendLine("COPY_BACKEND writer_stream_channel_and_raw_fd_closed=true close_repeated=noop")
    val pcm = ByteArray(65_536) { (it * 13 + 9).toByte() }
    val bytes = buildWavHeaderBytes(16_000, 1, PcmSampleFormat.PCM_16, pcm.size.toLong()) + pcm
    fun staging(name: String): Pair<File, StableOutputFingerprint> {
        val file = createLocalStagingFile(directory, name, StagingOutputKind.COPY).file
        FileOutputStream(file).use { it.write(bytes); it.fd.sync() }
        return file to requireNotNull(readStableFileOutputFingerprint(file))
    }

    val (source, expected) = staging("clip.wav")
    val foreign = File(directory, "clip.wav")
    val foreignBytes = bytes.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
    var creatorRan = false
    var copied = 0L
    val published = FileCopyPublications.withSource(source) {
        publishStagedFile(source, "clip.wav", expectedFingerprint = expected,
            moveFile = { _, _ -> throw NoReplaceMoveUnsupported("Owned fixture selects compatibility copy") },
            beforeMove = { candidate ->
                if (!creatorRan) {
                    check(candidate == foreign && candidate.createNewFile())
                    FileOutputStream(candidate).use { it.write(foreignBytes); it.fd.sync() }
                    creatorRan = true
                }
            },
            copyOnUnsupported = { candidate ->
                val record = copyFileForPublication(store, io, source, candidate, "clip.wav", expected,
                    FileCopyPurpose.STAGING, progress = { done, total ->
                        check(done >= copied && total == bytes.size.toLong())
                        copied = done
                    })
                PublishedStagedFile(File(record.targetId), record.targetIdentity)
            },
        )
    }
    check(published.file.name == "clip (2).wav")
    check(source.readBytes().contentEquals(bytes))
    check(foreign.readBytes().contentEquals(foreignBytes))
    check(published.file.readBytes().contentEquals(bytes))
    check(copied == bytes.size.toLong())
    val firstRecord = store.records().single()
    check(firstRecord.phase == FileCopyPhase.VERIFIED)
    check(!sameFileObjectAcrossRename(expected.fileKey.orEmpty(), published.identity))
    check(firstRecord.matchesTarget(requireNotNull(io.fingerprint(published.file))))
    check(pendingFileCopyCandidateIds(FileCopyPublications.snapshot(scoped)).isEmpty())
    report.appendLine("COPY_BACKEND collision=preserved source=retained distinct_inode=true progress_bytes=$copied")

    // Reconstruct the exact durable phase at loss of the final journal update. This is
    // deterministic restart-state coverage, not a claim that a process was killed.
    val beforeFinalMarker = firstRecord.copy(phase = FileCopyPhase.CREATED, targetIdentity = "")
    store.replace(firstRecord, beforeFinalMarker)
    val reconstructedStore = ContextFileCopyPublicationStore(scoped)
    val resumed = requireNotNull(recoverFileCopyPublication(reconstructedStore, io, source, "clip.wav", expected))
    check(resumed.targetId == published.file.path && resumed.targetIdentity == published.identity)
    check(resumed.phase == FileCopyPhase.VERIFIED && source.readBytes().contentEquals(bytes))
    check(directory.listFiles()!!.count { it.name == "clip (2).wav" } == 1)
    report.appendLine("COPY_BACKEND replay=CREATED_COMPLETE_RECONSTRUCTION same_candidate=true source=retained")

    val (partialSource, partialExpected) = staging("interrupted.wav")
    val partial = File(directory, "interrupted.wav")
    var record = FileCopyPublication(UUID.randomUUID().toString(), FileCopyPurpose.STAGING,
        partialSource.path, partial.name, partial.path, partialExpected.fileKey.orEmpty(),
        partialExpected.digest.byteCount, partialExpected.digest.sha256.toHexString())
    store.replace(null, record)
    requireNotNull(io.createExclusive(partial)).use { writer ->
        val empty = requireNotNull(io.fingerprint(partial))
        check(empty.digest.byteCount == 0L && fileDescriptorIdentityMatches(empty.fileKey.orEmpty(), writer.identity()))
        io.forceDirectory(directory)
        val created = record.copy(phase = FileCopyPhase.CREATED, createdIdentity = requireNotNull(empty.fileKey))
        store.replace(record, created)
        record = created
        writer.stream.write(bytes, 0, bytes.size / 2)
        writer.sync()
    }
    val beforePartialReplay = partial.readBytes()
    val partialFailure = runCatching {
        recoverFileCopyPublication(ContextFileCopyPublicationStore(scoped), io, partialSource,
            partial.name, partialExpected)
    }.exceptionOrNull()
    check(partialFailure is FileCopyRecoveryRequired)
    check(partial.readBytes().contentEquals(beforePartialReplay))
    check(partialSource.readBytes().contentEquals(bytes))
    check(partial.path in pendingOutputCleanupIds(scoped))
    check(store.records().none { it.token == record.token })
    val library = listLegacyAppStorageRecordings(scoped)
    check(library.none { it.id == partial.path || it.id == partialSource.path || it.id == source.path })
    check(library.any { it.id == published.file.path && it.fileIdentity == published.identity })
    report.appendLine("COPY_BACKEND replay=CREATED_PARTIAL_RECONSTRUCTION partial=preserved suppression_only=true library_partial=false")

    fun heldClaim(payload: ByteArray): Pair<File, StableOutputFingerprint> {
        val claim = File(directory, ".reverb-delete-${UUID.randomUUID()}.pending")
        requireNotNull(io.createExclusive(claim)).use { writer ->
            writer.stream.write(payload)
            writer.sync()
        }
        io.forceDirectory(directory)
        return claim to requireNotNull(io.fingerprint(claim))
    }
    val unsupportedMove: (File, File) -> Unit = { _, _ -> throw NoReplaceMoveUnsupported("Owned claim fixture") }
    val restoreMove: (File, File) -> Unit = { claim, target -> moveClaimForRecovery(scoped, claim, target, unsupportedMove) }
    val (held, heldProof) = heldClaim(bytes)
    val original = File(directory, "claim-original.wav")
    check(original.createNewFile())
    FileOutputStream(original).use { it.write(foreignBytes); it.fd.sync() }
    val legacy = PendingDeletionIntent(original.path, heldProof.digest.byteCount, heldProof.digest.sha256.toHexString(),
        false, RecordingStorageType.FILE, deletionClaimTokenFromFileName(held.name), "nio:owned-fixture:1")
    check(replayClaimedFileDeletion(legacy, held, restoreMove = restoreMove) == FileDeletionClaimResult.MISMATCH_PRESERVED)
    check(storagePathState(held) == StoragePathState.MISSING)
    check(original.readBytes().contentEquals(foreignBytes))
    val recovered = directory.listFiles()!!.single {
        it.name.startsWith("recovered-delete-race-") && it.name.contains(requireNotNull(legacy.claimToken).take(8))
    }
    check(recovered.readBytes().contentEquals(bytes))
    check(store.records().none { it.sourceId == held.path })
    report.appendLine("CLAIM_COPY real_descriptor_retirement=true collision=preserved visible_recovery=true")

    val (emptyClaim, emptyProof) = heldClaim(byteArrayOf())
    val emptyTarget = File(directory, "restored-empty.payload")
    val emptyIntent = PendingDeletionIntent(emptyTarget.path, 0, emptyProof.digest.sha256.toHexString(), false,
        RecordingStorageType.FILE, deletionClaimTokenFromFileName(emptyClaim.name), "nio:owned-empty:1")
    check(replayClaimedFileDeletion(emptyIntent, emptyClaim, restoreMove = restoreMove) == FileDeletionClaimResult.MISMATCH_PRESERVED)
    check(storagePathState(emptyClaim) == StoragePathState.MISSING && emptyTarget.isFile && emptyTarget.length() == 0L)
    report.appendLine("CLAIM_COPY zero_bytes=true non_audio_name=true catalog_not_required=true")

    val (replayClaim, replayProof) = heldClaim(bytes)
    val replayTarget = File(directory, "claim-verified-replay.wav")
    val verifiedClaimCopy = copyFileForPublication(store, io, replayClaim, replayTarget, replayTarget.name,
        replayProof, FileCopyPurpose.CLAIM_RESTORE)
    check(replayClaim.isFile)
    recoverPendingFileCopyPublications(scoped)
    check(storagePathState(replayClaim) == StoragePathState.MISSING)
    check(store.records().none { it.token == verifiedClaimCopy.token })
    check(resolveFileIdentity(replayTarget) == verifiedClaimCopy.targetIdentity)
    check(replayTarget.readBytes().contentEquals(bytes))
    recoverPendingFileCopyPublications(scoped)
    check(directory.listFiles()!!.count { it.name == replayTarget.name } == 1)
    report.appendLine("CLAIM_COPY replay=VERIFIED_RECONSTRUCTION existing_claim_retired=true duplicate=false")

    val (warmClaim, warmProof) = heldClaim(bytes)
    val warmTarget = File(directory, "claim-full-read-proof.wav")
    val warmRecord = copyFileForPublication(store, io, warmClaim, warmTarget, warmTarget.name,
        warmProof, FileCopyPurpose.CLAIM_RESTORE)
    // Deliberately poison only the process cache in this owned fixture. This is a stale-proof
    // fault model, not a claim that this device's filesystem reproduced a ctime collision.
    val wrongDigest = CopyDigest(bytes.size.toLong(), ByteArray(32))
    VerifiedFileDigestCache.remember(warmClaim, warmRecord.sourceFingerprint().copy(digest = wrongDigest))
    VerifiedFileDigestCache.remember(warmTarget, warmRecord.targetFingerprint().copy(digest = wrongDigest))
    check(VerifiedFileDigestCache.current(warmClaim)?.digest?.sha256?.contentEquals(wrongDigest.sha256) == true)
    check(VerifiedFileDigestCache.current(warmTarget)?.digest?.sha256?.contentEquals(wrongDigest.sha256) == true)
    check(retireCopiedDeletionClaim(warmRecord)) // Actual source and target bytes override both stale cached digests.
    check(storagePathState(warmClaim) == StoragePathState.MISSING)
    check(VerifiedFileDigestCache.current(warmTarget)?.digest?.sha256?.contentEquals(warmRecord.sourceFingerprint().digest.sha256) == true)
    recoverPendingFileCopyPublications(scoped)
    check(store.records().none { it.token == warmRecord.token })
    report.appendLine("CLAIM_COPY destructive_full_reads=true warm_cache_fault=injected source_and_target=true ctime_collision_claimed=false")

    val finalPolicy = PendingDeletionIntent(warmRecord.sourceId, warmRecord.byteCount, warmRecord.sha256Hex, false,
        RecordingStorageType.FILE, deletionClaimTokenFromFileName(File(warmRecord.sourceId).name), warmRecord.sourceIdentity,
        RecordingStorageType.FILE, warmRecord.targetId, warmRecord.targetIdentity)
    check(pendingFileDeletionMoveTargetIsCurrent(finalPolicy))
    java.io.RandomAccessFile(warmTarget, "rw").use { changed ->
        changed.seek(bytes.lastIndex.toLong())
        changed.write((bytes.last().toInt() xor 1) and 0xff)
        changed.fd.sync()
    }
    check(warmTarget.length() == bytes.size.toLong())
    check(!pendingFileDeletionMoveTargetIsCurrent(finalPolicy))
    report.appendLine("CLAIM_COPY final_target_policy_same_size_mutation=rejected actual_bytes_read=true")

    val (ambiguousClaim, ambiguousProof) = heldClaim(byteArrayOf())
    val ambiguousTarget = File(directory, "claim-empty-ambiguous.wav")
    val reserved = FileCopyPublication(UUID.randomUUID().toString(), FileCopyPurpose.CLAIM_RESTORE,
        ambiguousClaim.path, ambiguousTarget.name, ambiguousTarget.path, ambiguousProof.fileKey.orEmpty(),
        0L, ambiguousProof.digest.sha256.toHexString())
    store.replace(null, reserved)
    requireNotNull(io.createExclusive(ambiguousTarget)).use { it.sync() }
    io.forceDirectory(directory)
    val ambiguity = runCatching {
        moveClaimForRecovery(scoped, ambiguousClaim, ambiguousTarget) { _, _ -> error("reservation bypassed") }
    }.exceptionOrNull()
    check(ambiguity is FileCopyRecoveryRequired)
    check(ambiguousClaim.isFile && ambiguousTarget.isFile)
    check(ambiguousTarget.path in pendingOutputCleanupIds(scoped))
    check(store.records().none { it.token == reserved.token })
    val nextTarget = File(directory, "claim-empty-preserved.payload")
    moveClaimForRecovery(scoped, ambiguousClaim, nextTarget, unsupportedMove)
    check(storagePathState(ambiguousClaim) == StoragePathState.MISSING)
    check(nextTarget.isFile && nextTarget.length() == 0L && ambiguousTarget.isFile)
    check(ambiguousTarget.path in pendingOutputCleanupIds(scoped))
    report.appendLine("CLAIM_COPY replay=RESERVED_EMPTY_RECONSTRUCTION ambiguous=preserved new_target=true equal_bytes_not_adopted=true")
    report.appendLine("COPY_BACKEND evidence_root=${root.absolutePath} native_unsupported=injected actual_process_death=false")
}
