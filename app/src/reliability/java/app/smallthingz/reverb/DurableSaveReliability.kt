package app.smallthingz.reverb

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.DocumentsContract
import android.system.Os
import android.system.OsConstants
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

private const val DURABLE_QA_RATE = 48_000
private const val DURABLE_QA_FRAMES = 96_000
private const val DURABLE_QA_WAIT_MS = 30_000L

/** Two real Service submissions share raw chunks, then survive removal of their original timeline. */
internal fun verifyDurableSaveOverlap(
    context: Context,
    tree: Uri,
    provider: ReliabilityDocumentsProvider,
    report: StringBuilder,
    expectNotifications: Boolean = true,
) {
    durableQaGuard(context)
    durableQaRequireNoPendingJobs(context)
    check(context.getSystemService(NotificationManager::class.java).areNotificationsEnabled() == expectNotifications) {
        "Notification fixture requires isolated QA notifications ${if (expectNotifications) "enabled" else "disabled"}"
    }
    val run = UUID.randomUUID().toString()
    val source = DurableQaSource(context, run)
    val gates = ConcurrentHashMap<String, DurableQaGate>()
    val hookLock = Any()
    var hooksEnabled = true
    val progressJob = AtomicReference<String>()
    val progressBatches = AtomicInteger()
    val progressGate = DurableQaGate()
    val lateBoundaries = listOf("PUBLISHED_DURABLE", "AFTER_CATALOG", "COMPLETE_DURABLE", "BEFORE_PIN_RELEASE", "AFTER_PIN_RELEASE")
    val lateGates = lateBoundaries.associateWith { DurableQaGate() }
    check(DurableSaveFaultHooks.onBoundary == null)
    DurableSaveFaultHooks.onBoundary = { id, boundary ->
        val gate = synchronized(hookLock) {
            when {
                !hooksEnabled -> null
                boundary == "BEFORE_WRITER" -> gates.computeIfAbsent(id) { DurableQaGate() }
                boundary == "PCM_BATCH" && id == progressJob.get() && progressBatches.incrementAndGet() == 2 -> progressGate
                id == progressJob.get() && boundary in lateGates -> lateGates.getValue(boundary)
                else -> null
            }
        }
        gate?.hold()
    }
    var session: DurableQaService? = null
    var success = false
    var primary: Throwable? = null
    try {
        check(setConfiguredExportTreeUri(context, tree))
        // Never reset this provider: it may contain evidence from an earlier failed run.
        provider.mode = ReliabilityDocumentsProvider.Mode.RENAME_NEW_ID
        durableQaConfigure(context, ExportFormat.WAV)
        session = DurableQaService(context)
        source.append()
        val wav = session.submit(source.store, "durable-$run-wav", 0.25f, 1.5f)
        val wavId = wav.awaitQueued()
        progressJob.set(wavId)
        durableQaAwait("first writer barrier") { gates[wavId]?.entered?.count == 0L }
        session.configure(ExportFormat.FLAC)
        val flac = session.submit(source.store, "durable-$run-flac", 0.5f, 1.75f)
        val flacId = flac.awaitQueued()
        check(wavId != flacId)
        val ids = setOf(wavId, flacId)
        val pending = DurableSaveJobs.state.value.filter { it.id in ids }
        check(pending.size == 2 && pending.all { it.publishedRecordingId == null && it.error == null })
        if (expectNotifications) verifyDurableQaNotifications(context, ids, report)
        else verifyDurableQaNotificationsDenied(context, ids)
        check(catalog(context).none { it.displayName.startsWith("durable-$run-") }) {
            "Queued saves appeared as playable catalog assets"
        }
        session.fenceAdmission()
        val wavProofs = verifyDurableQaPins(context, wavId, 12_000, 60_000)
        val flacProofs = verifyDurableQaPins(context, flacId, 24_000, 60_000)
        val sharedSources = wavProofs.map { it.sourcePath }.toSet().intersect(flacProofs.map { it.sourcePath }.toSet())
        check(sharedSources.isNotEmpty()) { "Overlap fixture did not share protected original blocks" }
        sharedSources.forEach { path ->
            val left = wavProofs.single { it.sourcePath == path }
            val right = flacProofs.single { it.sourcePath == path }
            check(DurablePcmPins.referenceFile(File(durableSaveJournalRoot(context), wavId), left) !=
                DurablePcmPins.referenceFile(File(durableSaveJournalRoot(context), flacId), right))
        }
        source.store.clear()
        source.close()
        check(verifyDurableQaPins(context, wavId, 12_000, 60_000) == wavProofs)
        check(verifyDurableQaPins(context, flacId, 24_000, 60_000) == flacProofs)
        verifyDurableQaRetirementTracked(source.root, wavProofs + flacProofs)
        gates.getValue(wavId).release.countDown()
        check(progressGate.entered.await(DURABLE_QA_WAIT_MS, TimeUnit.MILLISECONDS)) { "No second bounded PCM batch" }
        val progress = checkNotNull(DurableSaveJobs.state.value.single { it.id == wavId }.progress)
        check(progress > 0f && progress < 1f)
        if (expectNotifications) verifyDurableQaNotifications(context, ids, report)
        else verifyDurableQaNotificationsDenied(context, ids)
        report.append("DURABLE_PROGRESS job=$wavId fraction=$progress completed_batches=1\n")
        progressGate.release.countDown()
        for (boundary in lateBoundaries) {
            val gate = lateGates.getValue(boundary)
            check(gate.entered.await(DURABLE_QA_WAIT_MS, TimeUnit.MILLISECONDS)) { "Late save barrier not reached: $boundary" }
            val job = DurableSaveJobs.state.value.single { it.id == wavId }
            check(!job.terminallyRetired && job.phase != SavePhase.FAILED)
            check(job.publishedRecordingId != null && job.publishedRecordingIdentity != null)
            val observed = catalog(context).associate { it.id to it.fileIdentity }
            DurableSaveJobs.acknowledgePublished(observed)
            check(DurableSaveJobs.state.value.single { it.id == wavId } == job) {
                "Catalog observation retired a live source owner at $boundary"
            }
            val pendingRows = buildPendingSaveItems(listOf(job), observed)
            check(pendingRows.single().save.id == wavId)
            val output = catalog(context).firstOrNull { it.id == job.publishedRecordingId }
            if (boundary != "PUBLISHED_DURABLE") {
                check(output != null && recordingOwnedByPendingSave(output, pendingSaveOutputIdentities(listOf(job)))) {
                    "Published output became separately actionable at $boundary"
                }
            }
            if (expectNotifications) verifyDurableQaNotifications(context, ids, report)
            else verifyDurableQaNotificationsDenied(context, ids)
            report.append("DURABLE_LATE_PENDING job=$wavId boundary=$boundary progress=${job.progress} pending_rows=1 terminal=false catalog_action_blocked=${output != null}\n")
            gate.release.countDown()
        }
        val wavRecording = wav.awaitReady()
        val retired = DurableSaveJobs.state.value.filter { it.id == wavId }
        check(retired.all { it.terminallyRetired && !durableSaveNeedsProgressNotification(it) })
        val completedIdentity = mapOf(wavRecording.id to wavRecording.fileIdentity)
        check(buildPendingSaveItems(retired, completedIdentity).isEmpty())
        check(!recordingOwnedByPendingSave(wavRecording, pendingSaveOutputIdentities(retired)))
        DurableSaveJobs.acknowledgePublished(completedIdentity)
        check(DurableSaveJobs.state.value.none { it.id == wavId })
        durableQaAwait("second writer barrier") { gates[flacId]?.entered?.count == 0L }
        verifyDurableQaOutput(context, wavRecording, ExportFormat.WAV, 12_000, 60_000, report)
        verifyDurableQaReferencesReleased(context, wavId, wavProofs)
        check(verifyDurableQaPins(context, flacId, 24_000, 60_000) == flacProofs)
        gates.getValue(flacId).release.countDown()
        val flacRecording = flac.awaitReady()
        session.fence()
        verifyDurableQaOutput(context, flacRecording, ExportFormat.FLAC, 24_000, 60_000, report)
        val rows = catalog(context).filter { it.displayName.startsWith("durable-$run-") }
        check(rows.size == 2 && rows.map { it.id }.toSet() == setOf(wavRecording.id, flacRecording.id))
        ids.forEach { verifyDurableQaPinsReleased(context, it) }
        verifyDurableQaReferencesReleased(context, wavId, wavProofs)
        verifyDurableQaReferencesReleased(context, flacId, flacProofs)
        reapDurableQaRetiredSource(source.root, source.device, source.inode, wavProofs + flacProofs)
        durableQaAwait("completed job notifications retired") {
            context.getSystemService(NotificationManager::class.java).activeNotifications.none {
                it.tag in ids.map { id -> "reverb-save-job:$id" }
            }
        }
        if (!expectNotifications) {
            verifyDurableQaNotificationsDenied(context, ids)
            report.append("DURABLE_NOTIFICATIONS_DENIED jobs=${ids.sorted().joinToString(",")} saves_completed=true delivered=false\n")
        }
        check(wav.terminals.get() == 1 && flac.terminals.get() == 1)
        session.assertNoMicrophone()
        report.append("DURABLE_OVERLAP run=$run wav_job=$wavId flac_job=$flacId clear=true rows=2 references_released=true retired_sources_reaped=true\n")
        success = true
    } catch (error: Throwable) {
        primary = error
        throw error
    } finally {
        // On failure retain every file. Release only the test's barrier; do not turn a
        // timeout or teardown into user cancellation of a durably accepted job.
        synchronized(hookLock) {
            hooksEnabled = false
            DurableSaveFaultHooks.onBoundary = null
            gates.values.forEach { it.release.countDown() }
            progressGate.release.countDown()
            lateGates.values.forEach { it.release.countDown() }
        }
        try {
            session?.close()
            if (success) source.deleteAfterSuccess()
        } catch (cleanup: Throwable) {
            if (primary != null) primary.addSuppressed(cleanup) else throw cleanup
        }
    }
}

/** Bounded metadata fan-out probe; concurrent append must finish while admission is deliberately held. */
internal fun verifyDurableSaveAdmissionContinuation(
    context: Context,
    tree: Uri,
    provider: ReliabilityDocumentsProvider,
    report: StringBuilder,
    chunkCount: Int = 256,
) {
    require(chunkCount in 1..4096) { "Admission probe chunk count must be in 1..4096" }
    durableQaGuard(context)
    durableQaRequireNoPendingJobs(context)
    check(DurableSaveFaultHooks.onBoundary == null)
    val run = UUID.randomUUID().toString()
    val source = DurableQaSource(context, run)
    val planGate = DurableQaGate()
    val writerGate = DurableQaGate()
    val job = AtomicReference<String>()
    val planEnteredNanos = java.util.concurrent.atomic.AtomicLong()
    val planResumedNanos = java.util.concurrent.atomic.AtomicLong()
    val pinsDurableNanos = java.util.concurrent.atomic.AtomicLong()
    val queuedDurableNanos = java.util.concurrent.atomic.AtomicLong()
    val appendWorker = Executors.newSingleThreadExecutor()
    var session: DurableQaService? = null
    var primary: Throwable? = null
    var success = false
    try {
        check(setConfiguredExportTreeUri(context, tree))
        provider.mode = ReliabilityDocumentsProvider.Mode.RENAME_NEW_ID
        durableQaConfigure(context, ExportFormat.WAV)
        session = DurableQaService(context)
        val chunkFrames = 256
        val selectedFrames = chunkCount * chunkFrames
        repeat(chunkCount) { index ->
            val bytes = durableQaPcm(index * chunkFrames, chunkFrames)
            check(source.store.append(bytes, 0, bytes.size) == bytes.size)
            source.store.sealActiveChunk()
        }
        val selectedEnd = source.store.durationSeconds().toFloat()
        DurableSaveFaultHooks.onBoundary = { id, boundary ->
            when (boundary) {
                "PIN_PLAN_DURABLE" -> {
                    check(job.compareAndSet(null, id)) { "Unexpected second admission during probe" }
                    planEnteredNanos.set(SystemClock.elapsedRealtimeNanos())
                    planGate.hold()
                    planResumedNanos.set(SystemClock.elapsedRealtimeNanos())
                }
                "PINS_DURABLE" -> {
                    check(job.get() == id)
                    check(pinsDurableNanos.compareAndSet(0L, SystemClock.elapsedRealtimeNanos()))
                }
                "QUEUED_DURABLE" -> {
                    check(job.get() == id)
                    check(queuedDurableNanos.compareAndSet(0L, SystemClock.elapsedRealtimeNanos()))
                }
                "BEFORE_WRITER" -> {
                    check(job.get() == id)
                    writerGate.hold()
                }
                else -> Unit
            }
        }
        val submittedNanos = SystemClock.elapsedRealtimeNanos()
        val receiver = session.submit(source.store, "durable-$run-admission", 0f, selectedEnd)
        check(planGate.entered.await(DURABLE_QA_WAIT_MS, TimeUnit.MILLISECONDS)) { "Admission did not reach durable pin plan" }
        val appendStartedNanos = SystemClock.elapsedRealtimeNanos()
        val appended = appendWorker.submit<Int> {
            val bytes = durableQaPcm(selectedFrames, 1024)
            source.store.append(bytes, 0, bytes.size)
        }
        check(appended.get(15, TimeUnit.SECONDS) == 2048) { "Synthetic capture continuation lost PCM" }
        val appendFinishedNanos = SystemClock.elapsedRealtimeNanos()
        check(planGate.release.count == 1L) { "Append did not finish inside held admission" }
        val liveAppendStarted = java.util.concurrent.atomic.AtomicLong()
        val liveAppendFinished = java.util.concurrent.atomic.AtomicLong()
        val liveAppend = appendWorker.submit<Int> {
            check(planGate.release.await(15, TimeUnit.SECONDS))
            liveAppendStarted.set(SystemClock.elapsedRealtimeNanos())
            val bytes = durableQaPcm(selectedFrames + 1024, 1024)
            source.store.append(bytes, 0, bytes.size).also {
                liveAppendFinished.set(SystemClock.elapsedRealtimeNanos())
            }
        }
        val releasedNanos = SystemClock.elapsedRealtimeNanos()
        planGate.release.countDown()
        val jobId = receiver.awaitQueued()
        val acknowledgedNanos = receiver.queuedAtNanos.get()
        check(job.get() == jobId)
        val plannedNanos = planEnteredNanos.get()
        val resumedNanos = planResumedNanos.get()
        val pinnedNanos = pinsDurableNanos.get()
        val queuedNanos = queuedDurableNanos.get()
        check(submittedNanos <= plannedNanos && plannedNanos <= releasedNanos &&
            releasedNanos <= resumedNanos && resumedNanos <= pinnedNanos &&
            pinnedNanos <= queuedNanos && queuedNanos <= acknowledgedNanos) {
            "Admission phase clocks are missing or out of order"
        }
        val heldNanos = releasedNanos - plannedNanos
        // Keep admission evidence if a later PCM/retirement assertion fails. This is
        // deliberately not a terminal result; every existing check below still runs.
        report.append("DURABLE_ADMISSION_PHASES status=NONTERMINAL verification_pending=true run=$run job=$jobId chunks=$chunkCount")
            .append(" queue_ms=${(acknowledgedNanos - submittedNanos) / 1_000_000.0}")
            .append(" intentional_pause_ms=${heldNanos / 1_000_000.0}")
            .append(" queue_without_pause_ms=${(acknowledgedNanos - submittedNanos - heldNanos) / 1_000_000.0}")
            .append(" submit_to_plan_ms=${(plannedNanos - submittedNanos) / 1_000_000.0}")
            .append(" plan_release_to_pins_ms=${(pinnedNanos - releasedNanos) / 1_000_000.0}")
            .append(" plan_resume_delay_ms=${(resumedNanos - releasedNanos) / 1_000_000.0}")
            .append(" pin_publication_ms=${(pinnedNanos - resumedNanos) / 1_000_000.0}")
            .append(" pins_to_queued_durable_ms=${(queuedNanos - pinnedNanos) / 1_000_000.0}")
            .append(" queued_durable_to_ack_ms=${(acknowledgedNanos - queuedNanos) / 1_000_000.0}\n")
        check(liveAppend.get(15, TimeUnit.SECONDS) == 2048)
        appendWorker.shutdown()
        check(appendWorker.awaitTermination(15, TimeUnit.SECONDS))
        check(writerGate.entered.await(DURABLE_QA_WAIT_MS, TimeUnit.MILLISECONDS))
        session.fenceAdmission()
        val proofs = verifyDurableQaPins(context, jobId, 0, selectedFrames)
        check(proofs.size == chunkCount) { "Admission probe did not reference the requested chunk fan-out" }
        val continuedPcm = durableQaPcm(0, selectedFrames + 2048)
        var continuedBytes = 0
        checkNotNull(source.store.acquireRange(0.0, source.store.durationSeconds())).use { continuation ->
            continuation.readNormalized(DURABLE_QA_RATE, 1, PcmSampleFormat.PCM_16) { bytes, offset, count ->
                check(count <= continuedPcm.size - continuedBytes)
                for (index in 0 until count) check(bytes[offset + index] == continuedPcm[continuedBytes + index])
                continuedBytes += count
                count
            }
        }
        check(continuedBytes == continuedPcm.size) { "Concurrent capture continuation changed stored PCM" }
        source.store.clear()
        source.close()
        check(verifyDurableQaPins(context, jobId, 0, selectedFrames) == proofs)
        verifyDurableQaRetirementTracked(source.root, proofs)
        writerGate.release.countDown()
        val saved = receiver.awaitReady()
        session.fence()
        verifyDurableQaOutput(context, saved, ExportFormat.WAV, 0, selectedFrames, report)
        check(catalog(context).count { it.displayName == saved.displayName } == 1)
        check(receiver.terminals.get() == 1)
        verifyDurableQaPinsReleased(context, jobId)
        verifyDurableQaReferencesReleased(context, jobId, proofs)
        reapDurableQaRetiredSource(source.root, source.device, source.inode, proofs)
        session.assertNoMicrophone()
        report.append("DURABLE_ADMISSION run=$run job=$jobId chunks=$chunkCount selected_pcm_bytes=${selectedFrames * 2}")
            .append(" appended_pcm_bytes=4096 append_completed_while_held=true")
            .append(" queue_ms=${(acknowledgedNanos - submittedNanos) / 1_000_000.0}")
            .append(" intentional_pause_ms=${heldNanos / 1_000_000.0}")
            .append(" queue_without_pause_ms=${(acknowledgedNanos - submittedNanos - heldNanos) / 1_000_000.0}")
            .append(" submit_to_plan_ms=${(plannedNanos - submittedNanos) / 1_000_000.0}")
            .append(" plan_release_to_pins_ms=${(pinnedNanos - releasedNanos) / 1_000_000.0}")
            .append(" plan_resume_delay_ms=${(resumedNanos - releasedNanos) / 1_000_000.0}")
            .append(" pin_publication_ms=${(pinnedNanos - resumedNanos) / 1_000_000.0}")
            .append(" pins_to_queued_durable_ms=${(queuedNanos - pinnedNanos) / 1_000_000.0}")
            .append(" queued_durable_to_ack_ms=${(acknowledgedNanos - queuedNanos) / 1_000_000.0}")
            .append(" append_ms=${(appendFinishedNanos - appendStartedNanos) / 1_000_000.0}")
            .append(" live_append_ms=${(liveAppendFinished.get() - liveAppendStarted.get()) / 1_000_000.0}")
            .append(" live_append_completed_before_ack=${liveAppendFinished.get() <= acknowledgedNanos}")
            .append(" references_released=true retired_sources_reaped=true\n")
        success = true
    } catch (error: Throwable) {
        primary = error
        throw error
    } finally {
        DurableSaveFaultHooks.onBoundary = null
        planGate.release.countDown()
        writerGate.release.countDown()
        appendWorker.shutdown()
        try {
            session?.close()
            if (success) source.deleteAfterSuccess(maximumEntries = 1024)
        } catch (cleanup: Throwable) {
            if (primary != null) primary.addSuppressed(cleanup) else throw cleanup
        }
    }
}

/**
 * Returns a durable run receipt while the real worker remains blocked. The instrumentation
 * controller must report the UUID and then kill ONLY this isolated process immediately.
 * No microphone, package clear, Service cancellation, or provider reset participates.
 */
internal fun prepareDurableSaveProcessDeath(
    context: Context,
    format: ExportFormat,
    boundary: String,
    report: StringBuilder,
): String {
    durableQaGuard(context)
    durableQaRequireNoPendingJobs(context)
    check(boundary in setOf("CREATION_INTENT_DURABLE", "BEFORE_WRITER", "PCM_BATCH", "VERIFIED_DURABLE", "BEFORE_PUBLISH",
        "AFTER_RENAME_BEFORE_MANIFEST", "PUBLISHED_DURABLE", "AFTER_CATALOG", "COMPLETE_DURABLE",
        "BEFORE_PIN_RELEASE", "AFTER_PIN_RELEASE"))
    check(DurableSaveFaultHooks.onBoundary == null)
    val run = UUID.randomUUID().toString()
    val gate = DurableQaGate()
    val heldId = AtomicReference<String>()
    val boundaryRecord = AtomicReference<DurableSaveRecord>()
    DurableSaveFaultHooks.onBoundary = { id, point ->
        if (point == boundary || (boundary == "AFTER_PIN_RELEASE" && point == "BEFORE_PIN_RELEASE")) {
            if (point != "AFTER_PIN_RELEASE") boundaryRecord.set(DurableSaveJournal(durableSaveJournalRoot(context)).read(id))
            if (point == boundary && heldId.compareAndSet(null, id)) gate.hold()
        }
    }
    val source = DurableQaSource(context, run)
    var session: DurableQaService? = null
    var prepared = false
    try {
        // Ordinary supported default destination (MediaStore on modern Android).
        check(setConfiguredExportTreeUri(context, null))
        durableQaConfigure(context, format)
        session = DurableQaService(context)
        source.append()
        val name = "durable-$run-crash"
        val receiver = session.submit(source.store, name, 0.25f, 1.75f)
        val jobId = receiver.awaitQueued()
        check(gate.entered.await(DURABLE_QA_WAIT_MS, TimeUnit.MILLISECONDS)) { "Crash boundary not reached: $boundary" }
        check(heldId.get() == jobId)
        session.fenceAdmission()
        source.store.clear()
        source.close()
        session.assertNoMicrophone()
        val receipt = JSONObject()
            .put("version", 3).put("run", run).put("pid", Process.myPid())
            .put("processBirthElapsed", Process.getStartElapsedRealtime())
            .put("job", jobId).put("name", name).put("format", format.storageCode.toInt())
            .put("boundary", boundary).put("first", 12_000).put("frames", 72_000)
            .put("source", source.root.name).put("sourceDev", source.device).put("sourceIno", source.inode)
        val record = checkNotNull(boundaryRecord.get())
        check(record.id == jobId)
        check(record.source.objects.all { it.sourcePath.isNotEmpty() })
        if (boundary == "AFTER_PIN_RELEASE") {
            verifyDurableQaReferencesReleased(context, jobId, record.source.objects)
        } else {
            record.source.objects.forEach { DurablePcmPins.verify(File(durableSaveJournalRoot(context), jobId), it) }
            verifyDurableQaRetirementTracked(source.root, record.source.objects)
        }
        receipt.put("sourceProofs", JSONArray().also { entries -> record.source.objects.forEach { proof ->
            entries.put(JSONObject().put("name", proof.name).put("identity", proof.identity)
                .put("offset", proof.payloadOffset).put("bytes", proof.payloadBytes)
                .put("checksum", proof.checksum).put("sourcePath", proof.sourcePath))
        } })
        receipt.put("stage", record.stage.code).put("creationName", record.creationName)
        if (boundary == "CREATION_INTENT_DURABLE") {
            check(record.stage == DurableSaveStage.WRITING && record.output == null)
            check(record.creationName.isNotEmpty())
            check(durableSaveCreationAttemptIsAbsent(context, record.request.directoryId, record.creationName)) {
                "Creation intent boundary already has a physical staging target"
            }
            receipt.put("creationDirectory", record.request.directoryId)
        }
        record.output?.let { output ->
            receipt.put("output", JSONObject().put("id", output.id)
                .put("storage", output.storageType.storageCode.toInt()).put("directory", output.directoryId)
                .put("staging", output.staging).put("stagingName", output.stagingDisplayName)
                .put("stagingIdentity", output.stagingIdentity).put("publishedIdentity", output.publishedIdentity))
        }
        val receiptFile = File(context.noBackupFilesDir, "durable-qa-$run.json")
        check(receiptFile.createNewFile())
        FileOutputStream(receiptFile).use { it.write(receipt.toString().toByteArray(Charsets.UTF_8)); it.fd.sync() }
        forceRecordingDirectoryDurable(context.noBackupFilesDir)
        // Keep all owners alive until the external process boundary. A bounded hook timeout
        // fails the save if the controller disappears; it never manufactures a crash result.
        DurableQaPrepared.owner = Triple(session, source, gate)
        report.append("DURABLE_PREPARED run=$run job=$jobId pid=${Process.myPid()} boundary=$boundary receipt=${receiptFile.name}\n")
        prepared = true
        return run
    } finally {
        if (!prepared) {
            gate.release.countDown()
            DurableSaveFaultHooks.onBoundary = null
            session?.close()
        }
    }
}

/** Ordinary Application/Service recovery owns the resume; this never resubmits a Save. */
internal fun resumeDurableSaveProcessDeath(context: Context, runId: String, report: StringBuilder) {
    durableQaGuard(context)
    check(UUID.fromString(runId).toString() == runId)
    val receiptFile = File(context.noBackupFilesDir, "durable-qa-$runId.json")
    check(receiptFile.length() in 1..8192)
    val receipt = JSONObject(receiptFile.readText())
    check(receipt.getInt("version") == 3 && receipt.getString("run") == runId)
    check(receipt.getInt("pid") != Process.myPid() ||
        receipt.getLong("processBirthElapsed") != Process.getStartElapsedRealtime()) {
        "Resume must execute in a different process lifetime"
    }
    val jobId = receipt.getString("job")
    check(UUID.fromString(jobId).toString() == jobId)
    durableQaRequireOnlyJob(context, jobId)
    val name = receipt.getString("name")
    check(name == "durable-$runId-crash")
    val format = checkNotNull(ExportFormat.fromStorageCode(receipt.getInt("format")))
    val first = receipt.getInt("first")
    val frames = receipt.getInt("frames")
    check(first == 12_000 && frames == 72_000)
    val sourceName = receipt.getString("source")
    check(sourceName == "durable-qa-source-$runId")
    val sourceRoot = File(context.filesDir, sourceName)
    val sourceProofs = durableQaSourceProofs(receipt, sourceRoot)
    durableQaAwait("resume startup save hydration") {
        val readiness = DurableSaveJobs.readiness.value
        check(readiness.recoveryFailure == null) { durableQaRecoveryDescription(jobId) }
        readiness.loaded
    }
    durableQaRequireOnlyJob(context, jobId)
    val recoveryProgress = DurableQaRecoveryProgress(jobId)
    DurableQaService(context).use { session ->
        var recording: RecordingEntity? = null
        durableQaAwait("ordinary restart publication", 60_000,
            diagnostics = { durableQaRecoveryDescription(jobId) }) {
            recoveryProgress.checkProgress()
            val rows = catalog(context).filter { it.displayName == "$name.${format.extension}" }
            check(rows.size <= 1) { "Recovery duplicated the logical save" }
            recording = rows.singleOrNull()
            recording != null && durableQaPinsReleased(context, jobId)
        }
        session.fence()
        val saved = checkNotNull(recording)
        verifyDurableQaOutput(context, saved, format, first, frames, report)
        check(catalog(context).count { it.displayName == saved.displayName } == 1)
        verifyDurableQaPinsReleased(context, jobId)
        verifyDurableQaReferencesReleased(context, jobId, sourceProofs)
        reapDurableQaRetiredSource(sourceRoot, receipt.getLong("sourceDev"), receipt.getLong("sourceIno"), sourceProofs)
        verifyDurableQaPreviousOutputRetired(context, receipt, saved)
        session.assertNoMicrophone()
        archiveDurableQaOutput(context, runId, receiptFile, saved, report)
        report.append("DURABLE_RESUMED run=$runId job=$jobId boundary=${receipt.getString("boundary")}")
            .append(" recording=${saved.id} rows=1 pins_released=true new_save=false\n")
    }
    // Public output remains until the host verifies the private archive and explicitly
    // submits cleanup with its manifest hash. Only this proven source root is removed here.
    deleteDurableQaRoot(sourceRoot, receipt.getLong("sourceDev"), receipt.getLong("sourceIno"))
}

/** Settles one archived failed-overlap job through ordinary recovery; keeps its source evidence. */
internal fun recoverDurableSaveOverlapFailure(
    context: Context,
    tree: Uri,
    provider: ReliabilityDocumentsProvider,
    runId: String,
    jobId: String,
    manifestSha: String,
    report: StringBuilder,
) {
    durableQaGuard(context)
    check(UUID.fromString(runId).toString() == runId && UUID.fromString(jobId).toString() == jobId)
    check(manifestSha.matches(Regex("[0-9a-f]{64}")))
    durableQaAwait("failed-overlap startup hydration") {
        val readiness = DurableSaveJobs.readiness.value
        check(readiness.recoveryFailure == null) { "Save hydration failed: ${readiness.recoveryFailure}" }
        readiness.loaded
    }
    durableQaRequireOnlyJob(context, jobId)
    val journal = DurableSaveJournal(durableSaveJournalRoot(context))
    val manifestFile = File(journal.directory(jobId), "manifest")
    val manifestProof = durableQaPrivateFileProof(context, manifestFile, 65_536)
    check(durableQaHex(manifestProof.digest.sha256) == manifestSha) { "Pending manifest differs from archived evidence" }
    val record = journal.read(jobId)
    check(record.id == jobId && record.stage == DurableSaveStage.WRITING)
    val request = record.request
    check(request.name == "durable-$runId-flac.flac" && request.directoryId == tree.toString())
    check(request.format == ExportFormat.FLAC && request.codec == ExportCodec.PCM_16 &&
        request.sampleFormat == PcmSampleFormat.PCM_16 && request.sampleRate == DURABLE_QA_RATE && request.channelCount == 1)
    val sourceRoot = File(context.filesDir, "durable-qa-source-$runId")
    check(record.source.objects.isNotEmpty() && record.source.objects.all {
        File(it.sourcePath).parentFile == File(sourceRoot, BUFFER_CHUNKS_FOLDER_NAME)
    })
    check(verifyDurableQaPins(context, jobId, 24_000, 60_000) == record.source.objects)
    verifyDurableQaRetirementTracked(sourceRoot, record.source.objects)
    val target = checkNotNull(record.output)
    check(target.storageType == RecordingStorageType.DOCUMENT && target.staging && target.stagingIdentity.isNotBlank())
    check(target.directoryId == tree.toString() && target.mimeType == ExportFormat.FLAC.outputMimeType)
    check(target.stagingDisplayName == record.creationName && File(record.creationName).name == record.creationName)
    val documentId = DocumentsContract.getDocumentId(Uri.parse(target.id))
    check(documentId == "${ReliabilityDocumentsProvider.ROOT_ID}/${record.creationName}")
    check(Uri.parse(target.id).authority == tree.authority && DocumentsContract.getTreeDocumentId(Uri.parse(target.id)) ==
        DocumentsContract.getTreeDocumentId(tree))
    val partial = File(File(context.filesDir, "reliability-documents"), record.creationName)
    val partialStat = Os.lstat(partial.path)
    check(OsConstants.S_ISREG(partialStat.st_mode) && partialStat.st_size == 0L)
    val observedProviderIdentity = documentProviderIdentityFromMetadata(target.id, true, partial.length(), true, partial.lastModified())
    check(providerRecordingIdentityMatches(target.stagingIdentity, observedProviderIdentity)) {
        "Archived zero-byte creation identity no longer matches the exact fixture document"
    }
    val partialProof = checkNotNull(readStableOutputFingerprint(context, RecordingStorageType.FILE, partial.absolutePath))
    check(partialProof.digest.byteCount == 0L)
    check(stableOutputFingerprintMatches(RecordingStorageType.FILE, manifestProof,
        durableQaPrivateFileProof(context, manifestFile, 65_536)))
    check(getConfiguredExportTreeUri(context) == tree)
    // Application startup only hydrates manifests. Restore this exact provider mapping
    // before binding the Service, which owns the first real materialization attempt.
    provider.restoreExistingDocument(documentId, target.mimeType)
    provider.mode = ReliabilityDocumentsProvider.Mode.RENAME_NEW_ID
    val restored = checkNotNull(readStableOutputFingerprint(context, target.storageType, target.id, target.stagingIdentity))
    check(stagingFingerprintMatchesCreatedObject(target, restored) &&
        providerRecordingIdentityMatches(target.stagingIdentity, checkNotNull(restored.providerIdentity)))
    check(stableOutputFingerprintMatches(RecordingStorageType.FILE, partialProof,
        checkNotNull(readStableOutputFingerprint(context, RecordingStorageType.FILE, partial.absolutePath))))
    val recoveryProgress = DurableQaRecoveryProgress(jobId)
    DurableQaService(context).use { session ->
        var saved: RecordingEntity? = null
        durableQaAwait("exact failed-overlap ordinary recovery", 60_000,
            diagnostics = { durableQaRecoveryDescription(jobId) }) {
            recoveryProgress.checkProgress()
            val rows = catalog(context).filter { it.displayName == request.name }
            check(rows.size <= 1)
            saved = rows.singleOrNull()
            saved != null && durableQaPinsReleased(context, jobId)
        }
        session.fence()
        val recording = checkNotNull(saved)
        verifyDurableQaOutput(context, recording, ExportFormat.FLAC, 24_000, 60_000, report)
        verifyDurableQaPinsReleased(context, jobId)
        verifyDurableQaReferencesReleased(context, jobId, record.source.objects)
        check(storagePathState(partial) == StoragePathState.MISSING)
        check(catalog(context).count { it.displayName == request.name } == 1)
        session.assertNoMicrophone()
        report.append("DURABLE_OVERLAP_RECOVERED run=$runId job=$jobId manifest_sha256=$manifestSha recording=${recording.id}")
            .append(" rows=1 references_released=true previous_partial_retired=true source_evidence_retained=true\n")
    }
}

/** Host acknowledgement is the exact evidence manifest SHA, never a name/folder delete request. */
internal fun cleanupDurableSaveProcessDeath(
    context: Context,
    runId: String,
    evidenceSha: String,
    report: StringBuilder,
) {
    durableQaGuard(context)
    check(UUID.fromString(runId).toString() == runId)
    check(evidenceSha.matches(Regex("[0-9a-f]{64}")))
    durableQaRequireNoPendingJobs(context)
    val root = File(context.noBackupFilesDir, "durable-qa-evidence-$runId")
    check(OsConstants.S_ISDIR(Os.lstat(root.path).st_mode))
    val manifestFile = File(root, "evidence.json")
    val manifestProof = durableQaPrivateFileProof(context, manifestFile, 16_384)
    check(durableQaHex(manifestProof.digest.sha256) == evidenceSha) { "Host acknowledgement does not match evidence" }
    val evidence = JSONObject(manifestFile.readText())
    check(evidence.getInt("version") == 1 && evidence.getString("run") == runId)
    val rootStat = Os.lstat(root.path)
    check(rootStat.st_dev == evidence.getLong("rootDev") && rootStat.st_ino == evidence.getLong("rootIno"))
    val receiptFile = File(context.noBackupFilesDir, "durable-qa-$runId.json")
    val receiptProof = durableQaPrivateFileProof(context, receiptFile, 8192)
    check(durableQaHex(receiptProof.digest.sha256) == evidence.getString("receiptSha256"))
    val receipt = JSONObject(receiptFile.readText())
    check(receipt.getString("run") == runId && receipt.getString("job") == evidence.getString("job"))
    verifyDurableQaPinsReleased(context, receipt.getString("job"))
    check(receipt.getString("source") == "durable-qa-source-$runId")
    verifyDurableQaReferencesReleased(context, receipt.getString("job"),
        durableQaSourceProofs(receipt, File(context.filesDir, receipt.getString("source"))))
    val archived = File(root, "output.bin")
    val archivedProof = durableQaPrivateFileProof(context, archived, 1_048_576)
    check(archivedProof.digest.byteCount == evidence.getLong("bytes") &&
        durableQaHex(archivedProof.digest.sha256) == evidence.getString("sha256"))
    check(archivedProof.fileKey == evidence.getString("archiveIdentity")) { "Private evidence object changed" }
    val selected = durableQaRecordingFromEvidence(evidence.getJSONObject("recording"))
    check(selected.displayName == "durable-$runId-crash.${ExportFormat.fromStorageCode(receipt.getInt("format"))!!.extension}")
    check(selected.storageType != RecordingStorageType.DOCUMENT)
    val current = runBlocking { RecordingDatabase.getInstance(context).recordingDao().findById(selected.id) }
    if (current == null) {
        check(recordingAssetState(context, selected) == RecordingAssetState.MISSING) {
            "Missing catalog row does not authorize deleting public bytes"
        }
        report.append("DURABLE_CLEANUP run=$runId recording=${selected.id} already_missing=true evidence_sha256=$evidenceSha\n")
        return
    }
    check(sameRecordingActionTarget(selected, current) && selected.displayName == current.displayName &&
        selected.mimeType == current.mimeType && selected.sizeBytes == current.sizeBytes &&
        selected.durationMillis == current.durationMillis && selected.startedAtMillis == current.startedAtMillis) {
        "Receipt-bound catalog identity or geometry changed; preserve public output"
    }
    val liveProof = checkNotNull(readStableOutputFingerprint(context, selected.storageType, selected.id,
        selected.fileIdentity, reuseVerifiedProviderRevision = false))
    check(liveProof.digest.byteCount == archivedProof.digest.byteCount &&
        liveProof.digest.sha256.contentEquals(archivedProof.digest.sha256)) { "Public bytes differ from verified archive" }
    check((liveProof.providerIdentity ?: liveProof.fileKey) == evidence.getString("liveFingerprintIdentity")) {
        "Public output fingerprint identity changed"
    }
    check(recordingContentIdentityMatches(context, selected))
    check(stableOutputFingerprintMatches(RecordingStorageType.FILE, manifestProof,
        durableQaPrivateFileProof(context, manifestFile, 16_384)))
    check(stableOutputFingerprintMatches(RecordingStorageType.FILE, archivedProof,
        durableQaPrivateFileProof(context, archived, 1_048_576)))
    // The repository checks journal emptiness atomically with this exact deletion;
    // this fixture never requests unrelated pending-deletion replay.
    check(runBlocking { RecordingRepository.deleteExactWithoutPendingReplay(context, selected) }) {
        "Exact output deletion failed; retain evidence"
    }
    check(recordingAssetState(context, selected) == RecordingAssetState.MISSING)
    check(runBlocking { RecordingDatabase.getInstance(context).recordingDao().findById(selected.id) } == null)
    report.append("DURABLE_CLEANUP run=$runId recording=${selected.id} deleted=true evidence_sha256=$evidenceSha\n")
}

private fun archiveDurableQaOutput(context: Context, runId: String, receiptFile: File,
    recording: RecordingEntity, report: StringBuilder) {
    check(recording.displayName.startsWith("durable-$runId-crash."))
    val root = File(context.noBackupFilesDir, "durable-qa-evidence-$runId")
    check(root.mkdir()) { "Evidence archive already exists; preserve it for host inspection" }
    forceRecordingDirectoryDurable(context.noBackupFilesDir)
    val rootStat = Os.lstat(root.path)
    check(OsConstants.S_ISDIR(rootStat.st_mode))
    val before = checkNotNull(readStableOutputFingerprint(context, recording.storageType, recording.id,
        recording.fileIdentity, reuseVerifiedProviderRevision = false))
    check(before.digest.byteCount in 1..1_048_576)
    check(recording.sizeBytes == before.digest.byteCount)
    val archived = File(root, "output.bin")
    check(archived.createNewFile())
    var copied = 0L
    checkNotNull(openRecordingInputStream(context, recording)).use { input ->
        FileOutputStream(archived).use { output ->
            val bytes = ByteArray(32_768)
            while (true) {
                val count = input.read(bytes)
                if (count < 0) break
                check(count > 0 && copied + count <= before.digest.byteCount)
                output.write(bytes, 0, count)
                copied += count
            }
            output.fd.sync()
        }
    }
    check(copied == before.digest.byteCount)
    forceRecordingDirectoryDurable(root)
    val archiveProof = durableQaPrivateFileProof(context, archived, 1_048_576)
    check(archiveProof.digest.byteCount == before.digest.byteCount &&
        archiveProof.digest.sha256.contentEquals(before.digest.sha256))
    val after = checkNotNull(readStableOutputFingerprint(context, recording.storageType, recording.id,
        recording.fileIdentity, reuseVerifiedProviderRevision = false))
    check(stableOutputFingerprintMatches(recording.storageType, before, after))
    check(recordingContentIdentityMatches(context, recording))
    val receiptProof = durableQaPrivateFileProof(context, receiptFile, 8192)
    val receipt = JSONObject(receiptFile.readText())
    check(receipt.getString("run") == runId)
    val evidence = JSONObject().put("version", 1).put("run", runId).put("job", receipt.getString("job"))
        .put("receiptSha256", durableQaHex(receiptProof.digest.sha256))
        .put("bytes", copied).put("sha256", durableQaHex(archiveProof.digest.sha256))
        .put("archiveIdentity", archiveProof.fileKey).put("rootDev", rootStat.st_dev).put("rootIno", rootStat.st_ino)
        .put("liveFingerprintIdentity", before.providerIdentity ?: before.fileKey)
        .put("recording", JSONObject().put("id", recording.id).put("name", recording.displayName)
            .put("mime", recording.mimeType).put("storage", recording.storageType.storageCode.toInt())
            .put("directory", recording.directoryId).put("identity", recording.fileIdentity)
            .put("started", recording.startedAtMillis).put("duration", recording.durationMillis)
            .put("bytes", recording.sizeBytes).put("codec", recording.codecSummary))
    val manifest = File(root, "evidence.json")
    check(manifest.createNewFile())
    FileOutputStream(manifest).use { it.write(evidence.toString().toByteArray(Charsets.UTF_8)); it.fd.sync() }
    forceRecordingDirectoryDurable(root)
    val proof = durableQaPrivateFileProof(context, manifest, 16_384)
    report.append("DURABLE_EVIDENCE run=$runId manifest=${manifest.absolutePath} payload=${archived.absolutePath}")
        .append(" evidence_sha256=${durableQaHex(proof.digest.sha256)} payload_sha256=${durableQaHex(archiveProof.digest.sha256)} bytes=$copied\n")
}

private fun durableQaRecordingFromEvidence(record: JSONObject) = RecordingEntity(
    id = record.getString("id"), displayName = record.getString("name"), mimeType = record.getString("mime"),
    startedAtMillis = record.getLong("started"), durationMillis = record.getLong("duration"),
    sizeBytes = record.getLong("bytes"), codecSummary = record.getString("codec"),
    storageType = checkNotNull(RecordingStorageType.fromStorageCode(record.getInt("storage"))),
    directoryId = record.getString("directory"), fileIdentity = record.getString("identity"),
)

private fun durableQaPrivateFileProof(context: Context, file: File, maximumBytes: Long): StableOutputFingerprint {
    val stat = Os.lstat(file.path)
    check(OsConstants.S_ISREG(stat.st_mode) && stat.st_size in 1..maximumBytes)
    return checkNotNull(readStableOutputFingerprint(context, RecordingStorageType.FILE, file.absolutePath))
}

private fun durableQaHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }

private object DurableQaPrepared { var owner: Any? = null }

private class DurableQaGate {
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    fun hold() {
        entered.countDown()
        check(release.await(120, TimeUnit.SECONDS)) { "Durable QA barrier expired; evidence retained" }
    }
}

private class DurableQaReceiver : ReverbService.AudioFileReceiver {
    val terminals = AtomicInteger()
    val queuedAtNanos = java.util.concurrent.atomic.AtomicLong()
    private val queued = CountDownLatch(1)
    private val terminal = CountDownLatch(1)
    private val job = AtomicReference<String>()
    private val saved = AtomicReference<RecordingEntity>()
    private val failure = AtomicReference<Throwable>()
    override fun saveQueued(jobId: String) {
        queuedAtNanos.compareAndSet(0L, SystemClock.elapsedRealtimeNanos())
        if (!job.compareAndSet(null, jobId)) failure.set(IllegalStateException("Duplicate queued acknowledgement"))
        queued.countDown()
    }
    override fun fileReady(recording: RecordingEntity) {
        saved.set(recording); terminals.incrementAndGet(); terminal.countDown()
    }
    override fun fileFailed(message: String, error: Throwable?) {
        failure.set(IllegalStateException(message, error)); terminals.incrementAndGet(); queued.countDown(); terminal.countDown()
    }
    override fun fileCancelled() = fileFailed("Unexpected durable-save cancellation", null)
    fun awaitQueued(): String {
        check(queued.await(DURABLE_QA_WAIT_MS, TimeUnit.MILLISECONDS)) { "Durable acknowledgement timed out" }
        failure.get()?.let { throw it }
        return checkNotNull(job.get())
    }
    fun awaitReady(): RecordingEntity {
        check(terminal.await(DURABLE_QA_WAIT_MS, TimeUnit.MILLISECONDS)) { "Durable terminal result timed out" }
        failure.get()?.let { throw it }
        return checkNotNull(saved.get())
    }
}

private class DurableQaService(private val context: Context) : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private val connected = CountDownLatch(1)
    private val value = AtomicReference<ReverbService>()
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            value.set((binder as ReverbService.BackgroundRecorderBinder).service)
            connected.countDown()
        }
        override fun onServiceDisconnected(name: ComponentName) = Unit
    }
    private var bound = false
    val service: ReverbService get() = checkNotNull(value.get())
    private val audio: Handler get() = ReverbService::class.java.getDeclaredField("audioHandler")
        .apply { isAccessible = true }.get(service) as Handler
    init {
        bound = context.bindService(Intent(context, ReverbService::class.java), connection, Context.BIND_AUTO_CREATE)
        check(bound)
        try {
            check(connected.await(DURABLE_QA_WAIT_MS, TimeUnit.MILLISECONDS)) { "Durable QA Service did not bind" }
            assertNoMicrophone()
        } catch (error: Throwable) {
            context.unbindService(connection); bound = false
            throw error
        }
    }
    fun configure(format: ExportFormat) {
        durableQaConfigure(context, format)
        on(audio) { service.loadConfiguration() }
        check(service.getConfigurationSnapshot().format == format)
    }
    fun submit(store: PersistentAudioChunkStore, name: String, start: Float, end: Float): DurableQaReceiver {
        val receiver = DurableQaReceiver()
        val snapshot = ReverbService.TimelineSnapshot(checkNotNull(store.acquireRange(0.0, store.durationSeconds())))
        val ownership = AtomicInteger(0)
        val task = FutureTask {
            if (ownership.compareAndSet(0, 1)) service.dumpRecordingRange(snapshot, start, end, receiver, name)
        }
        try {
            check(main.post(task))
            task.get(DURABLE_QA_WAIT_MS, TimeUnit.MILLISECONDS)
        } catch (error: Throwable) {
            if (ownership.compareAndSet(0, 2)) {
                main.removeCallbacks(task)
                try { snapshot.close() } catch (close: Throwable) { error.addSuppressed(close) }
            }
            // Once main claimed it, Service owns the snapshot even if the caller timed out.
            throw error
        }
        return receiver
    }
    fun assertNoMicrophone() {
        on(audio) {
            check(ReverbService::class.java.getDeclaredField("audioRecord").apply { isAccessible = true }
                .get(service) == null) { "Synthetic fixture acquired a live microphone" }
        }
        check(!readDurableCaptureIntentPreferences(getRecorderPreferences(context)).enabled)
    }
    fun fenceAdmission() {
        on(main) { Unit }
        val admission = ReverbService::class.java.getDeclaredField("durableSaveAdmissionExecutor")
            .apply { isAccessible = true }.get(service) as ExecutorService
        admission.submit {}.get(DURABLE_QA_WAIT_MS, TimeUnit.MILLISECONDS)
    }
    fun fence() {
        fenceAdmission()
        val worker = ReverbService::class.java.getDeclaredField("exportWorkExecutor")
            .apply { isAccessible = true }.get(service) as ExecutorService
        worker.submit {}.get(DURABLE_QA_WAIT_MS, TimeUnit.MILLISECONDS)
        on(main) { Unit }
    }
    override fun close() {
        if (bound) { context.unbindService(connection); bound = false }
    }
    private fun on(handler: Handler, action: () -> Unit) {
        val task = FutureTask(action)
        check(handler.post(task))
        task.get(DURABLE_QA_WAIT_MS, TimeUnit.MILLISECONDS)
    }
}

private class DurableQaSource(context: Context, run: String) {
    val root = File(context.filesDir, "durable-qa-source-$run")
    val device: Long
    val inode: Long
    val store: PersistentAudioChunkStore
    private var closed = false
    init {
        check(root.mkdir())
        val stat = Os.lstat(root.path)
        device = stat.st_dev; inode = stat.st_ino
        store = PersistentAudioChunkStore(root)
        store.configure(RetentionMode.SIZE, 4_194_304L, DURABLE_QA_RATE, 1, PcmSampleFormat.PCM_16)
    }
    fun append() {
        // Separate sealed and active objects exercise admission's sealing of the acquired tail.
        for (first in 0 until DURABLE_QA_FRAMES step 24_000) {
            val bytes = durableQaPcm(first, 24_000)
            check(store.append(bytes, 0, bytes.size) == bytes.size)
            if (first < 72_000) store.sealActiveChunk()
        }
    }
    fun close() {
        if (!closed) { store.close(); closed = true }
        check(!store.hasRangeLeaseCloseFailure()) { "QA source close failed; preserve evidence" }
    }
    fun deleteAfterSuccess(maximumEntries: Int = 128) {
        close()
        deleteDurableQaRoot(root, device, inode, maximumEntries)
    }
}

private fun durableQaGuard(context: Context) {
    check(context.packageName == "app.smallthingz.reverb.reliability")
    check(Looper.myLooper() != Looper.getMainLooper())
    check(!readDurableCaptureIntentPreferences(getRecorderPreferences(context)).enabled)
}

private fun durableQaRequireNoPendingJobs(context: Context) {
    durableQaAwait("authoritative startup save recovery") {
        val readiness = DurableSaveJobs.readiness.value
        check(readiness.recoveryFailure == null) { "Startup save recovery failed: ${readiness.recoveryFailure}" }
        readiness.loaded
    }
    check(DurableSaveJobs.state.value.none { !it.terminallyRetired }) {
        "A previous accepted or unknown save remains pending; preserve it before another fixture"
    }
    val root = durableSaveJournalRoot(context)
    if (storagePathState(root) == StoragePathState.MISSING) return
    check(root.listFiles()?.isEmpty() == true) { "A previous durable job owns evidence; preserve it before another fixture" }
}

private fun durableQaRequireOnlyJob(context: Context, jobId: String) {
    val root = durableSaveJournalRoot(context)
    if (storagePathState(root) != StoragePathState.MISSING) {
        val directories = checkNotNull(root.listFiles()) { "Save journal unavailable" }
        check(directories.all { it.name == jobId }) { "Another job owns evidence; refuse cross-run resume" }
    }
    check(DurableSaveJobs.state.value.none { it.id != jobId && !it.terminallyRetired }) {
        "Another pending save is active during resume"
    }
}

private fun durableQaRecoveryDescription(jobId: String): String {
    val readiness = DurableSaveJobs.readiness.value
    val pending = DurableSaveJobs.state.value.firstOrNull { it.id == jobId }
    return "job=$jobId phase=${pending?.phase} error=${pending?.error} published=${pending?.publishedRecordingId}" +
        " hydrationLoaded=${readiness.loaded} hydrationError=${readiness.recoveryFailure}"
}

private class DurableQaRecoveryProgress(private val jobId: String) {
    private val priorError = DurableSaveJobs.state.value.firstOrNull { it.id == jobId }?.error
    private var observedFreshProgress = false
    fun checkProgress() {
        val readiness = DurableSaveJobs.readiness.value
        val pending = DurableSaveJobs.state.value.firstOrNull { it.id == jobId }
        check(readiness.recoveryFailure == null) { "Durable recovery failed: ${durableQaRecoveryDescription(jobId)}" }
        if (pending != null && pending.phase != SavePhase.FAILED && pending.error == null) observedFreshProgress = true
        // Hydration can republish the previous attempt's error. Without an attempt ID,
        // the same error after a conflated retry is ambiguous; keep the bounded wait and
        // print its exact state on timeout rather than aborting a valid retry as stale failure.
        check(pending?.phase != SavePhase.FAILED ||
            (priorError != null && pending.error == priorError && !observedFreshProgress)) {
            "Durable recovery failed: ${durableQaRecoveryDescription(jobId)}"
        }
    }
}

private fun durableQaConfigure(context: Context, format: ExportFormat) {
    check(getRecorderPreferences(context).edit()
        .putBoolean(PrefKey.AUDIO_MEMORY_ENABLED, false)
        .putInt(PrefKey.OUTPUT_FORMAT, format.storageCode.toInt())
        .putInt(PrefKey.OUTPUT_CODEC, ExportCodec.PCM_16.storageCode.toInt())
        .putInt(PrefKey.PCM_SAMPLE_FORMAT, PcmSampleFormat.PCM_16.storageCode.toInt())
        .putInt(PrefKey.SAMPLE_RATE, DURABLE_QA_RATE)
        .putInt(PrefKey.CHANNEL_MODE, ChannelMode.MONO.storageCode.toInt())
        .putInt(PrefKey.FLAC_COMPRESSION_LEVEL, DEFAULT_FLAC_COMPRESSION).commit())
}

private fun durableQaPcm(first: Int, frames: Int): ByteArray = ByteArray(frames * 2).also { bytes ->
    for (frame in 0 until frames) {
        val sample = (((first + frame) * 73 + (first + frame) / 17) % 60_001) - 30_000
        bytes[frame * 2] = sample.toByte()
        bytes[frame * 2 + 1] = (sample shr 8).toByte()
    }
}

private fun catalog(context: Context): List<RecordingEntity> = runBlocking {
    RecordingDatabase.getInstance(context).recordingDao().listAll()
}

private fun verifyDurableQaPins(context: Context, id: String, first: Int, frames: Int): List<DurablePcmPins.ObjectProof> {
    val journal = DurableSaveJournal(durableSaveJournalRoot(context))
    val record = journal.read(id)
    check(record.source.objects.isNotEmpty())
    record.source.objects.forEach { proof ->
        check(proof.sourcePath.isNotEmpty()) { "Fixture must exercise durable original-block references" }
        val source = DurablePcmPins.sourceFile(journal.directory(id), proof)
        check(source.absolutePath == proof.sourcePath && DurablePcmPins.hasReference(journal.directory(id), proof))
        check(DurablePcmPins.isProtected(source))
        check(OsConstants.S_ISREG(Os.lstat(DurablePcmPins.referenceFile(journal.directory(id), proof).path).st_mode))
        DurablePcmPins.verify(journal.directory(id), proof)
    }
    val expected = durableQaPcm(first, frames)
    var position = 0
    record.source.range.readNormalized(DURABLE_QA_RATE, 1, PcmSampleFormat.PCM_16, Long.MAX_VALUE) { bytes, offset, count ->
        check(count <= expected.size - position)
        for (index in 0 until count) check(bytes[offset + index] == expected[position + index])
        position += count
        count
    }
    check(position == expected.size) { "Pinned source selected a different range" }
    return record.source.objects
}

private fun verifyDurableQaReferencesReleased(context: Context, id: String, proofs: List<DurablePcmPins.ObjectProof>) {
    val directory = File(durableSaveJournalRoot(context), id)
    proofs.forEach { proof ->
        check(proof.sourcePath.isNotEmpty())
        check(!DurablePcmPins.hasReference(directory, proof)) { "Completed job retained its original-block reference: $id" }
    }
}

private fun durableQaSourceProofs(receipt: JSONObject, root: File): List<DurablePcmPins.ObjectProof> {
    val entries = receipt.getJSONArray("sourceProofs")
    check(entries.length() in 1..8)
    return (0 until entries.length()).map { index ->
        val value = entries.getJSONObject(index)
        val proof = DurablePcmPins.ObjectProof(value.getString("name"), value.getString("identity"),
            value.getLong("offset"), value.getLong("bytes"), value.getInt("checksum"), value.getString("sourcePath"))
        check(File(proof.sourcePath).parentFile == File(root, BUFFER_CHUNKS_FOLDER_NAME))
        check(File(proof.sourcePath).name.toUIntOrNull() != null && proof.name.matches(Regex("pcm-[0-9]{1,5}")))
        check(proof.identity.startsWith("stat:") && proof.payloadOffset > 0 && proof.payloadBytes in 1..192_000)
        proof
    }
}

private fun verifyDurableQaRetirementTracked(root: File, proofs: List<DurablePcmPins.ObjectProof>) {
    proofs.distinctBy { it.sourcePath }.forEach { proof ->
        val source = File(proof.sourcePath)
        check(source.parentFile == File(root, BUFFER_CHUNKS_FOLDER_NAME))
        check(DurablePcmPins.isProtected(source))
        val marker = File(File(root, "retired"), source.name)
        check(OsConstants.S_ISREG(Os.lstat(marker.path).st_mode) && marker.length() in 1..8192) {
            "Closed original store lost its retirement authority"
        }
    }
}

private fun reapDurableQaRetiredSource(root: File, device: Long, inode: Long, proofs: List<DurablePcmPins.ObjectProof>) {
    fun checkRoot() {
        val stat = Os.lstat(root.path)
        check(stat.st_dev == device && stat.st_ino == inode && OsConstants.S_ISDIR(stat.st_mode))
    }
    checkRoot()
    val sources = proofs.map { File(it.sourcePath) }.distinct()
    check(sources.size in 1..4096 && sources.all { it.parentFile == File(root, BUFFER_CHUNKS_FOLDER_NAME) })
    sources.forEach { check(!DurablePcmPins.isProtected(it)) { "Source still has durable references; do not reap" } }
    val references = File(root, "durable-pcm-refs")
    if (storagePathState(references) != StoragePathState.MISSING) {
        val children = checkNotNull(references.listFiles())
        check(children.size <= sources.size)
        children.forEach { child ->
            check(child.name in sources.map { it.name } && OsConstants.S_ISDIR(Os.lstat(child.path).st_mode))
            check(child.listFiles()?.isEmpty() == true) { "Unknown reference evidence must be preserved" }
        }
    }
    // Reopening hands persisted retirement authority to the normal store recovery path.
    // The fixture itself never unlinks raw PCM or retirement markers.
    val reopened = PersistentAudioChunkStore(root)
    reopened.use { store ->
        store.configure(RetentionMode.SIZE, 4_194_304L, DURABLE_QA_RATE, 1, PcmSampleFormat.PCM_16)
        check(store.durationSeconds() == 0.0) { "Cleared source reappeared in the live timeline" }
        store.checkpoint()
    }
    check(!reopened.hasRangeLeaseCloseFailure())
    checkRoot()
    sources.forEach { check(storagePathState(it) == StoragePathState.MISSING) { "Normal recovery did not reclaim retired source: $it" } }
    for (name in listOf(BUFFER_CHUNKS_FOLDER_NAME, "retired")) {
        val directory = File(root, name)
        check(storagePathState(directory) == StoragePathState.MISSING || directory.listFiles()?.isEmpty() == true) {
            "Normal recovery left unaccounted retirement evidence: $directory"
        }
    }
}

private fun verifyDurableQaOutput(context: Context, recording: RecordingEntity, format: ExportFormat,
    first: Int, frames: Int, report: StringBuilder) {
    check(recording.mimeType == format.outputMimeType && recording.displayName.endsWith(".${format.extension}"))
    val expected = durableQaPcm(first, frames)
    val before = checkNotNull(readStableOutputFingerprint(context, recording.storageType, recording.id,
        recording.fileIdentity, reuseVerifiedProviderRevision = false))
    withRecordingWavChannelIdentityGuard(context, recording, RecordingReadCleanup.Propagate) { channel, stillCurrent ->
        // Playback preview performs float scaling even at native rate; inspect actual
        // container PCM/native codec output so this oracle remains strictly bit-exact.
        if (format == ExportFormat.WAV) {
            val layout = readWavPcmLayout(channel)
            check(layout.sampleRate == DURABLE_QA_RATE && layout.channelCount == 1)
            check(layout.sampleFormat == PcmSampleFormat.PCM_16 && layout.frameCount == frames.toLong())
            check(layout.dataBytes == expected.size.toLong())
            val actual = ByteArray(expected.size)
            val buffer = ByteBuffer.wrap(actual)
            var position = layout.dataOffsetBytes
            while (buffer.hasRemaining()) {
                val count = channel.read(buffer, position)
                check(count > 0) { "WAV PCM truncated during exact verification" }
                position += count
            }
            check(actual.contentEquals(expected)) { "Durable WAV payload changed selected PCM" }
        } else {
            FlacPcmDecoder(channel, RecordingReadCleanup.Propagate).use { decoder ->
                check(decoder.info.sampleRate == DURABLE_QA_RATE && decoder.info.channelCount == 1)
                check(decoder.info.bitsPerSample == 16 && decoder.info.frameCount == frames.toLong())
                var position = 0
                decoder.readFrames(0, frames.toLong()) { bytes, offset, count ->
                    check(count <= expected.size - position)
                    for (index in 0 until count) check(bytes[offset + index] == expected[position + index]) {
                        "Durable FLAC decode changed selected PCM at byte ${position + index}"
                    }
                    position += count
                }
                check(position == expected.size)
            }
        }
        check(stillCurrent()) { "Recording identity changed during exact PCM verification" }
    }
    val after = checkNotNull(readStableOutputFingerprint(context, recording.storageType, recording.id,
        recording.fileIdentity, reuseVerifiedProviderRevision = false))
    check(stableOutputFingerprintMatches(recording.storageType, before, after))
    val digest = MessageDigest.getInstance("SHA-256").digest(expected).joinToString("") { "%02x".format(it.toInt() and 255) }
    report.append("DURABLE_PCM recording=${recording.id} format=${format.extension} frames=$frames pcm_sha256=$digest\n")
}

private fun verifyDurableQaNotifications(context: Context, ids: Set<String>, report: StringBuilder) {
    val manager = context.getSystemService(NotificationManager::class.java)
    val expected = ids.associateWith { id -> DurableSaveJobs.state.value.single { it.id == id }.progress }
    durableQaAwait("identity-bound save progress notifications") {
        val observed = manager.activeNotifications
        ids.all { id ->
            val notification = observed.singleOrNull { it.tag == "reverb-save-job:$id" && it.id == 44 }?.notification
                ?: return@all false
            val progress = expected.getValue(id)
            notification.extras.getInt(Notification.EXTRA_PROGRESS_MAX) == 100 &&
                notification.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE) == (progress == null) &&
                (progress == null || notification.extras.getInt(Notification.EXTRA_PROGRESS) == (progress * 100).toInt())
        }
    }
    check(ids.map { "reverb-save-job:$it" to 44 }.toSet().size == ids.size)
    report.append("DURABLE_NOTIFICATIONS jobs=${ids.sorted().joinToString(",")} observed=${ids.size} progress_matches=true\n")
}

private fun verifyDurableQaNotificationsDenied(context: Context, ids: Set<String>) {
    val manager = context.getSystemService(NotificationManager::class.java)
    check(!manager.areNotificationsEnabled()) { "Notification permission changed during denied-delivery fixture" }
    val tags = ids.mapTo(HashSet()) { "reverb-save-job:$it" }
    check(manager.activeNotifications.none { it.tag in tags }) { "A denied save notification was unexpectedly delivered" }
}

private fun durableQaPinsReleased(context: Context, id: String): Boolean {
    val directory = File(durableSaveJournalRoot(context), id)
    return storagePathState(directory) == StoragePathState.MISSING
}

private fun verifyDurableQaPinsReleased(context: Context, id: String) {
    check(durableQaPinsReleased(context, id)) { "Completed durable save retained source links: $id" }
    val markers = getRecorderPreferences(context).requireDurableStringSet(PrefKey.VERIFIED_EXPORT_STAGING)
    check(markers.none { raw -> checkNotNull(decodeVerifiedExportStagingRecord(raw)).jobId == id }) {
        "Completed durable save retained its verification marker: $id"
    }
}

private fun verifyDurableQaPreviousOutputRetired(context: Context, receipt: JSONObject, saved: RecordingEntity) {
    if (receipt.getString("boundary") == "CREATION_INTENT_DURABLE") {
        check(!receipt.has("output") && receipt.getInt("stage") == DurableSaveStage.WRITING.code)
        val creationDirectory = receipt.getString("creationDirectory")
        check(creationDirectory == saved.directoryId)
        check(durableSaveCreationAttemptIsAbsent(context, creationDirectory, receipt.getString("creationName"))) {
            "Original pre-creation staging name is no longer absent"
        }
        return
    }
    val output = receipt.optJSONObject("output") ?: error("Crash boundary did not capture output authority")
    val storage = checkNotNull(RecordingStorageType.fromStorageCode(output.getInt("storage")))
    val oldId = output.getString("id")
    check(output.getString("stagingIdentity").isNotBlank())
    if (oldId != saved.id) {
        val state = when (storage) {
            RecordingStorageType.FILE -> fileRecordingAssetState(File(oldId))
            RecordingStorageType.MEDIASTORE -> queryUriAssetState(context, Uri.parse(oldId),
                arrayOf(MediaStore.MediaColumns._ID), oldId)
            RecordingStorageType.DOCUMENT -> error("Process-death fixture must use the ordinary default destination")
        }
        check(state == RecordingAssetState.MISSING) { "Previous materialization is not positively retired: $oldId ($state)" }
    }
    val creationName = receipt.getString("creationName")
    check(creationName.isNotBlank())
    if (storage == RecordingStorageType.MEDIASTORE) {
        context.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns._ID), "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
            arrayOf(creationName), null).use { cursor ->
            check(cursor != null && !cursor.moveToFirst()) { "Previous hidden MediaStore staging name still exists" }
        }
    } else {
        check(storagePathState(File(output.getString("directory"), creationName)) == StoragePathState.MISSING)
    }
}

private fun durableQaAwait(description: String, timeoutMillis: Long = DURABLE_QA_WAIT_MS,
    diagnostics: (() -> String)? = null, condition: () -> Boolean) {
    val deadline = SystemClock.elapsedRealtime() + timeoutMillis
    while (!condition()) {
        check(SystemClock.elapsedRealtime() < deadline) {
            "Timed out waiting for $description" + (diagnostics?.let { "; ${it()}" } ?: "")
        }
        Thread.sleep(20)
    }
}

/** All workers/readers have fenced before this bounded, no-follow current-run-only cleanup. */
private fun deleteDurableQaRoot(root: File, device: Long, inode: Long, maximumEntries: Int = 128) {
    check(maximumEntries in 1..1024)
    fun rootIdentity() {
        val stat = Os.lstat(root.path)
        check(stat.st_dev == device && stat.st_ino == inode && OsConstants.S_ISDIR(stat.st_mode))
    }
    rootIdentity()
    val entries = ArrayList<java.nio.file.Path>()
    Files.walk(root.toPath()).use { paths ->
        val iterator = paths.iterator()
        while (iterator.hasNext()) {
            check(entries.size < maximumEntries) { "Unexpected fixture membership; preserve source" }
            entries.add(iterator.next())
        }
    }
    val proofs = entries.associateWith { path ->
        Os.lstat(path.toString()).also { check(OsConstants.S_ISDIR(it.st_mode) || OsConstants.S_ISREG(it.st_mode)) }
    }
    entries.sortedByDescending { it.nameCount }.forEach { path ->
        rootIdentity()
        val expected = proofs.getValue(path)
        val actual = Os.lstat(path.toString())
        check(actual.st_dev == expected.st_dev && actual.st_ino == expected.st_ino && actual.st_mode == expected.st_mode)
        if (OsConstants.S_ISREG(actual.st_mode)) check(actual.st_size == expected.st_size && actual.st_ctime == expected.st_ctime)
        Files.delete(path)
    }
}
