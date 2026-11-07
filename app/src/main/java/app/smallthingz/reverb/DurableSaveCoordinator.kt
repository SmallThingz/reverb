package app.smallthingz.reverb

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.runBlocking

/** Disk owns requests; memory retains only bounded UI summaries, never every source graph. */
internal class DurableSaveCoordinator private constructor(private val context: Context) {
    companion object {
        @Volatile private var instance: DurableSaveCoordinator? = null
        fun get(context: Context): DurableSaveCoordinator = instance ?: synchronized(this) {
            instance ?: DurableSaveCoordinator(context.applicationContext).also { instance = it }
        }
    }

    private val journal = DurableSaveJournal(durableSaveJournalRoot(context))
    private val journalLock = Any()
    private val admissionRecoveryLock = Any()
    private val materializerLock = Any()
    private val completed = LinkedHashMap<String, RecordingEntity>()
    private val pendingIds = linkedSetOf<String>()

    init { DurableSaveNotifications.start(context) }

    fun setRecoveryRequest(owner: Any, callback: () -> Unit) = DurableSaveJobs.setRecoveryRequest(owner, callback)
    fun clearRecoveryRequest(owner: Any) = DurableSaveJobs.clearRecoveryRequest(owner)
    fun pendingCount(): Int = synchronized(journalLock) { pendingIds.size }

    fun admit(lease: PersistentAudioChunkStore.RangeLease, request: DurableSaveRequest): DurableSaveHandle =
        synchronized(admissionRecoveryLock) { admitLocked(lease, request) }

    private fun admitLocked(lease: PersistentAudioChunkStore.RangeLease, request: DurableSaveRequest): DurableSaveHandle {
        require(request.sampleRate in 1..384000 && request.channelCount in 1..2 && validFlacCompression(request.flacCompression))
        if (request.format == ExportFormat.FLAC && (request.sampleFormat != PcmSampleFormat.PCM_16 ||
                !lease.hasExactPcm16Format(request.sampleRate, request.channelCount))) {
            throw IOException(context.getString(R.string.flac_history_format_mismatch))
        }
        val id = UUID.randomUUID().toString()
        var owned: DurableSaveRecord? = null
        var reservation: DurableSaveJournal.DirectoryReservation? = null
        try {
            lease.withSealedRange { range ->
                val base = request.name.trim().ifBlank { range.endedAtMillis.toString() }
                val resolved = request.copy(name = normalizeFileRecordingDisplayName("$base.${request.format.extension}"))
                val directory = synchronized(journalLock) {
                    journal.createDirectory(id) { reservation = it }
                }
                val pinned = DurablePcmPins.link(directory, range) { planned ->
                    val record = DurableSaveRecord(id, DurableSaveStage.PINNING, resolved, planned)
                    persist(record)
                    owned = record
                    DurableSaveFaultHooks.reached(id, "PIN_PLAN_DURABLE")
                }
                DurableSaveFaultHooks.reached(id, "PINS_DURABLE")
                val queued = requireNotNull(owned).copy(stage = DurableSaveStage.QUEUED, source = pinned)
                persist(queued)
                owned = queued
            }
            DurableSaveFaultHooks.reached(id, "QUEUED_DURABLE")
            val queued = requireNotNull(owned)
            synchronized(journalLock) { pendingIds += id }
            val publication = DurableSaveJobs.publish(queued.pending(SavePhase.QUEUED))
            return DurableSaveHandle(id, publication)
        } catch (failure: Exception) {
            if (owned == null) reservation?.let { reserved ->
                try {
                    synchronized(journalLock) { journal.abandonEmptyReservation(reserved) }
                } catch (cleanup: Exception) {
                    if (cleanup !== failure) failure.addSuppressed(cleanup)
                }
            }
            owned?.let { record -> reportFailure(record, failure) }
            throw failure
        }
    }

    /** Reads one source graph at a time; other jobs still recover when one record is unavailable. */
    fun recover(): List<DurableSaveHandle> = synchronized(admissionRecoveryLock) { recoverLocked() }

    private fun recoverLocked(): List<DurableSaveHandle> = synchronized(journalLock) {
        val eligible = ArrayList<DurableSaveHandle>()
        try {
            val presentedFailures = LinkedHashMap<String, String>()
            val result = journal.recoverEach(onRecord = { record ->
                when (record.stage) {
                    DurableSaveStage.PINNING, DurableSaveStage.CANCELLED -> {
                        val cancelled = record.copy(stage = DurableSaveStage.CANCELLED)
                        journal.write(cancelled)
                        journal.retire(cancelled)
                        pendingIds -= record.id
                        DurableSaveJobs.remove(record.id)
                    }
                    else -> {
                        pendingIds += record.id
                        val publication = DurableSaveJobs.publish(record.pending(SavePhase.RECOVERING))
                        eligible += DurableSaveHandle(record.id, publication)
                    }
                }
            }, onFailure = { id, failure ->
                presentedFailures[id] = actionableErrorMessage(context,
                    context.getString(R.string.save_jobs_recovery_failed), failure)
            })
            result.failures.forEach { (id, error) ->
                DurableSaveJobs.publish(PendingSave(id, "Save recovery", 0, 0, SavePhase.FAILED, error = presentedFailures[id] ?: safeReportedErrorDetail(error, 512) ?: context.getString(R.string.failure_reason_unknown)))
            }
            pendingIds.retainAll(result.presentIds)
            eligible.removeAll { it.id !in result.presentIds }
            DurableSaveJobs.reconcileAuthoritativeJobs(result.presentIds)
            DurableSaveJobs.recovered(result.failures.entries.firstOrNull()?.let { (id, error) ->
                presentedFailures[id] ?: safeReportedErrorDetail(error, 512) ?: context.getString(R.string.failure_reason_unknown) })
        } catch (failure: Exception) {
            DurableSaveJobs.recovered(actionableErrorMessage(context, context.getString(R.string.save_jobs_recovery_failed), failure))
            throw failure
        }
        eligible
    }

    /** Serial across Service instances as an old worker may still be unwinding during rebind. */
    fun materialize(
        id: String,
        onFailurePublished: (DurableSavePublicationReceipt) -> Unit = {},
        checkActive: () -> Unit,
    ): RecordingEntity = synchronized(materializerLock) {
        completed[id]?.let { return@synchronized it }
        var record = synchronized(journalLock) { journal.read(id) }
        var publicationOwner: RecordingOperationToken? = null
        fun reservePublishedOutput(target: RecordingOutputTarget) {
            if (publicationOwner?.recordingId == target.id) return
            check(publicationOwner == null) { "Save publication changed its owned output" }
            publicationOwner = recordingMutations.tryBegin(target.id)
                ?: throw IOException("Published recording is busy; save completion remains pending")
        }
        fun commit(updated: DurableSaveRecord) {
            // Error reporting must retain the latest observed output/rename identity even if
            // its manifest directory barrier fails; it must not overwrite that receipt with an older URI.
            record = updated
            persist(updated)
        }
        var lastProgressAt = 0L
        fun publishProgress(progress: Float, force: Boolean = false) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (force || now - lastProgressAt >= 150L) {
                lastProgressAt = now
                DurableSaveJobs.publish(record.pending(SavePhase.SAVING, progress))
            }
        }
        try {
            checkActive()
            validateDestination(record)
            DurableSaveJobs.publish(record.pending(
                if (record.stage == DurableSaveStage.QUEUED && record.error.isEmpty()) SavePhase.SAVING else SavePhase.RECOVERING,
            ))
            if (record.stage == DurableSaveStage.WRITING) {
                val partial = record.output
                if (partial == null && record.creationName.isNotEmpty()) {
                    if (!durableSaveCreationAttemptIsAbsent(context, record.request.directoryId, record.creationName)) {
                        throw IOException("Output creation was interrupted; its staging attempt remains tracked")
                    }
                }
                if (partial != null) {
                    if (outputCleanupAssetState(context, partial.storageType, partial.id) == OutputCleanupAssetState.MISSING) {
                        if (partial.storageType == RecordingStorageType.FILE &&
                            !confirmMissingFileRecordingDurable(requireNotNull(partial.file))) {
                            throw IOException("Interrupted output absence is not durable")
                        }
                        record.fingerprint?.let { fingerprint ->
                            if (!suppressAndDeleteOutputTarget(context, partial, fingerprint)) {
                                throw IOException("Interrupted output cleanup is pending")
                            }
                        }
                    } else {
                    val fingerprint = readStableOutputFingerprint(context, partial.storageType, partial.id,
                        partial.stagingIdentity.takeIf(String::isNotBlank))
                        ?: recoverEmptyMediaStoreOutputFingerprint(context, partial)
                        ?: throw IOException("Interrupted output is unavailable; staging is preserved")
                    if (partial.stagingIdentity.isBlank() || !stagingFingerprintMatchesCreatedObject(partial, fingerprint)) {
                        throw IOException("Interrupted output ownership is uncertain")
                    }
                    commit(record.copy(fingerprint = fingerprint))
                    if (!suppressAndDeleteOutputTarget(context, partial, fingerprint)) {
                        throw IOException("Interrupted output cleanup is pending")
                    }
                    }
                }
                commit(record.copy(stage = DurableSaveStage.QUEUED, output = null, fingerprint = null, creationName = "", error = ""))
            }
            if (record.stage == DurableSaveStage.QUEUED) {
                // One invocation owns this scratch across both complete verification passes.
                val verificationScratch = ByteArray(64 * 1024)
                val directory = journal.directory(id)
                record.source.objects.forEachIndexed { index, proof ->
                    checkActive()
                    DurablePcmPins.verify(directory, proof, scratch = verificationScratch)
                    publishProgress(0.10f * (index + 1) / record.source.objects.size)
                }
                val request = record.request
                val tree = canonicalDocumentTreeId(request.directoryId)?.let(Uri::parse)
                if (tree == null && request.directoryId != getOutputDirectoryId(context, null)) {
                    throw IOException("Saved destination is no longer available")
                }
                checkActive()
                val target = createOutputTargetInDirectory(context, tree, request.name, request.format.outputMimeType,
                    record.source.range.startedAtMillis, StagingOutputKind.COPY, beforeCreate = { name ->
                        commit(record.copy(stage = DurableSaveStage.WRITING, creationName = name, error = ""))
                        DurableSaveFaultHooks.reached(id, "CREATION_INTENT_DURABLE")
                    })
                DurableSaveFaultHooks.reached(id, "AFTER_CREATE_BEFORE_MANIFEST")
                commit(record.copy(stage = DurableSaveStage.WRITING, output = target))
                if (target.stagingIdentity.isBlank()) throw IOException("Output creation has no stable object identity")
                DurableSaveFaultHooks.reached(id, "BEFORE_WRITER")
                val writer = createRecordingAudioWriter(context, target, request.format, request.sampleRate,
                    request.channelCount, request.sampleFormat, request.flacCompression)
                writer.use {
                    record.source.range.readNormalized(request.sampleRate, request.channelCount, request.sampleFormat,
                        exportFrameLimit(request.format, request.codec, request.sampleRate, request.channelCount, request.sampleFormat)) {
                            bytes, offset, count ->
                        checkActive()
                        writer.write(bytes, offset, count)
                        DurableSaveFaultHooks.reached(id, "PCM_BATCH")
                        val total = record.source.range.durationSeconds * request.sampleRate * request.channelCount * request.sampleFormat.bytesPerSample
                        // PCM submission is only one phase: codec drain, complete output/source
                        // verification, publication and pin retirement still own real work.
                        publishProgress(0.10f + 0.55f * (writer.totalSampleBytesWritten / total).toFloat().coerceIn(0f, 1f))
                        count
                    }
                    checkActive()
                    if (writer.totalSampleBytesWritten <= 0) throw IOException("Save produced no PCM")
                }
                checkActive()
                publishProgress(0.70f, force = true)
                val fingerprint = writer.verify(context, checkActive)
                publishProgress(0.80f, force = true)
                record.source.objects.forEachIndexed { index, proof ->
                    checkActive()
                    DurablePcmPins.verify(directory, proof, scratch = verificationScratch)
                    publishProgress(0.80f + 0.10f * (index + 1) / record.source.objects.size)
                }
                val duration = (writer.totalSampleBytesWritten * 1000.0 /
                    (request.sampleRate.toLong() * request.channelCount * request.sampleFormat.bytesPerSample)).toLong()
                commit(record.copy(stage = DurableSaveStage.VERIFIED, fingerprint = fingerprint,
                    materializedDurationMillis = duration))
                DurableSaveFaultHooks.reached(id, "VERIFIED_DURABLE")
            }
            if (record.stage == DurableSaveStage.VERIFIED || record.stage == DurableSaveStage.PUBLISHING) {
                publishProgress(0.90f, force = true)
                val target = record.output ?: throw IOException("Save output manifest is incomplete")
                val fingerprint = record.fingerprint ?: throw IOException("Save verification manifest is incomplete")
                if (!putVerifiedExportStaging(context, target, fingerprint, id)) {
                    throw IOException("Unable to persist verified save recovery")
                }
                checkActive()
                val recovered = if (record.stage == DurableSaveStage.PUBLISHING)
                    recoverDurableSavePublication(context, target, fingerprint) else null
                val published = recovered ?: finalizeOutputTarget(context, target, fingerprint) { exactTarget ->
                    checkActive()
                    commit(record.copy(stage = DurableSaveStage.PUBLISHING, output = exactTarget))
                    DurableSaveFaultHooks.reached(id, "BEFORE_PUBLISH")
                }
                record = record.copy(stage = DurableSaveStage.PUBLISHED, output = published,
                    fingerprint = outputCleanupFingerprintForTarget(published, fingerprint))
                reservePublishedOutput(published)
                DurableSaveFaultHooks.reached(id, "AFTER_RENAME_BEFORE_MANIFEST")
                commit(record)
                publishProgress(0.94f, force = true)
                DurableSaveFaultHooks.reached(id, "PUBLISHED_DURABLE")
            }
            if (record.stage != DurableSaveStage.PUBLISHED && record.stage != DurableSaveStage.COMPLETE) {
                throw IOException("Save cannot materialize from ${record.stage}")
            }
            val target = record.output ?: throw IOException("Published save identity is missing")
            val fingerprint = record.fingerprint ?: throw IOException("Published save proof is missing")
            reservePublishedOutput(target)
            val request = record.request
            val recording = buildRecordingEntity(context, target, record.materializedDurationMillis,
                buildCodecSummary(context, request.format, request.sampleRate, request.channelCount, request.sampleFormat,
                    request.flacCompression), knownSizeBytes = fingerprint.digest.byteCount)
            val registered = try {
                runBlocking { RecordingRepository.register(context, recording) }
            } catch (failure: Exception) {
                if (!catalogRegistrationFailureAllowsVerifiedSaveSuccess(failure)) throw failure
                recording
            }
            DurableSaveFaultHooks.reached(id, "AFTER_CATALOG")
            publishProgress(0.96f, force = true)
            commit(record.copy(stage = DurableSaveStage.COMPLETE, error = ""))
            DurableSaveFaultHooks.reached(id, "COMPLETE_DURABLE")
            val current = readStableOutputFingerprint(context, target.storageType, target.id, fingerprint.providerIdentity)
            if (current == null || !stableOutputFingerprintMatches(target.storageType, fingerprint, current)) {
                throw IOException("Published save changed before releasing source pins")
            }
            publishProgress(0.98f, force = true)
            var retired = false
            try {
                DurableSaveFaultHooks.reached(id, "BEFORE_STAGING_MARKER_RETIREMENT")
                if (!removeVerifiedExportStagingForJob(context, id, target.storageType, fingerprint)) {
                    throw IOException("Verified save recovery-marker cleanup is pending")
                }
                DurableSaveFaultHooks.reached(id, "BEFORE_PIN_RELEASE")
                journal.releasePins(record)
                synchronized(journalLock) { journal.retireMetadata(record); pendingIds -= id }
                DurableSaveFaultHooks.reached(id, "AFTER_PIN_RELEASE")
                DurableSaveJobs.remove(id)
                retired = true
            } catch (cleanup: Exception) {
                reportFailure(record, cleanup)
            }
            if (retired) completed[id] = registered
            while (completed.size > DurableSaveJournal.MAX_JOBS) completed.remove(completed.keys.first())
            registered
        } catch (failure: Exception) {
            val publication = reportFailure(record, failure)
            try { onFailurePublished(publication) } catch (deliveryFailure: Exception) {
                if (deliveryFailure !== failure) failure.addSuppressed(deliveryFailure)
            }
            throw failure
        } finally {
            publicationOwner?.let(recordingMutations::finish)
        }
    }

    private fun persist(record: DurableSaveRecord) = synchronized(journalLock) { journal.write(record) }

    private fun validateDestination(record: DurableSaveRecord) {
        val directory = record.request.directoryId
        val tree = canonicalDocumentTreeId(directory)
        if (directory != getOutputDirectoryId(context, null) && tree != directory) {
            throw IOException("Saved destination is not managed")
        }
        record.output?.let { target ->
            if (target.directoryId != directory) throw IOException("Save output destination changed")
            val valid = when (target.storageType) {
                RecordingStorageType.FILE -> directory == getSharedMusicRecordingsDirectory().absolutePath &&
                    File(target.id).parentFile?.absolutePath == directory
                RecordingStorageType.MEDIASTORE -> directory == MEDIA_STORE_DIRECTORY_ID
                RecordingStorageType.DOCUMENT -> tree == directory && documentRecordingBelongsToTree(target.id, directory)
            }
            if (!valid) throw IOException("Save output location is not owned")
        }
    }

    private fun reportFailure(record: DurableSaveRecord, failure: Exception): DurableSavePublicationReceipt {
        val failed = record.copy(error = actionableErrorMessage(context, context.getString(R.string.save_failed), failure))
        try { persist(failed) } catch (journalFailure: Exception) {
            if (journalFailure !== failure) failure.addSuppressed(journalFailure)
        }
        return requireNotNull(DurableSaveJobs.publish(failed.pending(SavePhase.FAILED)))
    }
}
