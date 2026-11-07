package app.smallthingz.reverb

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Real Service APIs and pump, with finite synthetic input and fixture-owned stores/preferences. */
internal fun verifyCapturePipelineBoundaries(context: Context, report: StringBuilder) {
    verifyPipelineSavePrefix(context, report)
    verifyPipelineClearPrefix(context, report)
    verifyPipelineClearHeldGate(context, report)
    verifyPipelineClearGateRevoked(context, report, supersede = false)
    verifyPipelineClearGateRevoked(context, report, supersede = true)
    verifyPipelineClearKeepsActiveGate(context, report)
    verifyPipelineClearGateFailure(context, report)
    verifyPipelineVisualizationHandoff(context, report)
    verifyPipelineSampling(context, report)
}

private fun boundaryPcm(ordinal: Int): ByteArray = ByteBuffer.allocate(320).order(ByteOrder.LITTLE_ENDIAN)
    .apply { repeat(160) { putShort((ordinal * 1000 + it).toShort()) } }.array()

private fun <T> boundaryMain(work: () -> T): T {
    check(Looper.myLooper() != Looper.getMainLooper())
    val task = FutureTask(java.util.concurrent.Callable { work() })
    check(Handler(Looper.getMainLooper()).post(task))
    return task.get(10, TimeUnit.SECONDS)
}

private fun verifyPipelineSavePrefix(context: Context, report: StringBuilder) {
    withFramePipeline(context, "save_admitted_prefix", isolateSaveCoordinator = true) { fixture ->
        val source = PipelineInput(false, automaticReads = 2, holdReadOrdinal = 2) { ordinal, _ -> boundaryPcm(ordinal) }
        val appendEntered = CountDownLatch(1)
        val appendRelease = CountDownLatch(1)
        val pinsEntered = CountDownLatch(1)
        val admissionProgress = CountDownLatch(1)
        val pinsRelease = CountDownLatch(1)
        val queued = CountDownLatch(1)
        val terminal = CountDownLatch(1)
        val savedId = AtomicReference<String>()
        val creationIntent = AtomicReference<DurableSaveRecord>()
        val hookFailure = AtomicReference<Throwable>()
        val receiverFailure = AtomicReference<String>()
        val queuedCount = AtomicInteger()
        val terminalCount = AtomicInteger()
        val expected = boundaryPcm(1) + boundaryPcm(2)
        val injectedFailure = "synthetic boundary qualification stops before output creation"
        check(DurableSaveFaultHooks.onBoundary == null)
        try {
            fixture.configure(source, ReverbService.BufferSlot.LOOPING)
            fixture.onAudio {
                fixture.service.frameSet("cachedConfigSnapshot", fixture.service.getConfigurationSnapshot().copy(
                    format = ExportFormat.WAV, codec = ExportCodec.PCM_16,
                    sampleRate = 16_000, channelMode = ChannelMode.MONO, sampleFormat = PcmSampleFormat.PCM_16))
            }
            fixture.injectFirstAppendBarrier(appendEntered, appendRelease)
            fixture.start()
            check(appendEntered.await(5, TimeUnit.SECONDS))
            check(source.heldReadEntered.await(5, TimeUnit.SECONDS))
            DurableSaveFaultHooks.onBoundary = { id, boundary ->
                if (boundary == "QUEUED_DURABLE") {
                    try {
                        val journal = DurableSaveJournal(durableSaveJournalRoot(fixture.admission.context))
                        val record = journal.read(id)
                        check(record.stage == DurableSaveStage.QUEUED)
                        check(record.source.objects.isNotEmpty())
                        record.source.objects.forEach { proof ->
                            check(File(proof.sourcePath).canonicalPath.startsWith(fixture.root.canonicalPath + File.separator))
                            DurablePcmPins.verify(journal.directory(id), proof)
                        }
                        val output = ByteArrayOutputStream()
                        record.source.range.readNormalized(16_000, 1, PcmSampleFormat.PCM_16, Long.MAX_VALUE) { bytes, offset, count ->
                            output.write(bytes, offset, count); count
                        }
                        check(output.toByteArray().contentEquals(expected)) { "Save pins did not contain the accepted native prefix" }
                        File(fixture.root, "saved-prefix.pcm16le").writeBytes(output.toByteArray())
                        File(fixture.root, "save-job.txt").writeText("id=$id\nframes=320\nsource=actual_capture_pump\n")
                    } catch (error: Throwable) { hookFailure.set(error); throw error }
                    finally { pinsEntered.countDown(); admissionProgress.countDown() }
                    check(pinsRelease.await(10, TimeUnit.SECONDS))
                }
                if (boundary == "CREATION_INTENT_DURABLE") {
                    val record = DurableSaveJournal(durableSaveJournalRoot(fixture.admission.context)).read(id)
                    check(record.stage == DurableSaveStage.WRITING && record.creationName.isNotBlank() && record.output == null)
                    creationIntent.set(record)
                    throw IOException(injectedFailure)
                }
            }
            val receiver = object : ReverbService.AudioFileReceiver {
                override fun saveQueued(jobId: String) {
                    savedId.set(jobId); queuedCount.incrementAndGet(); queued.countDown()
                }
                override fun fileReady(recording: RecordingEntity) {
                    receiverFailure.set("Unexpected physical output publication")
                    terminalCount.incrementAndGet(); terminal.countDown(); admissionProgress.countDown()
                }
                override fun fileFailed(message: String, error: Throwable?) {
                    receiverFailure.set(message + " " + (error?.stackTraceToString() ?: ""))
                    terminalCount.incrementAndGet(); terminal.countDown(); admissionProgress.countDown()
                }
                override fun fileCancelled() {
                    receiverFailure.set("Unexpected cancellation"); terminalCount.incrementAndGet(); terminal.countDown(); admissionProgress.countDown()
                }
            }
            boundaryMain { fixture.service.dumpRecordingRange(0f, 10f, receiver, "capture-prefix", ReverbService.BufferSlot.LOOPING) }
            check(pinsEntered.count == 1L && queued.count == 1L) { "Save overtook the blocked append/native prefix" }
            source.allowHeldRead.countDown()
            check(source.thirdEntered.await(5, TimeUnit.SECONDS))
            check(pinsEntered.count == 1L) { "Save overtook the blocked first append" }
            // Save preparation may reserve its actual control prefix asynchronously. Any later
            // admitted synthetic read is zero-only, so acknowledge all of them without adding PCM.
            source.unblockAll()
            appendRelease.countDown()
            check(admissionProgress.await(8, TimeUnit.SECONDS) && pinsEntered.count == 0L) {
                "Save did not reach QUEUED_DURABLE: receiver=${receiverFailure.get()} " +
                    "terminal=${terminalCount.get()} queued=${queuedCount.get()} nativeReads=${source.reads.get()} " +
                    "hookFailure=${hookFailure.get()?.stackTraceToString()} " +
                    "journal=${fixture.service.fixtureSaveJournalRoot()} " +
                    "expectedJournal=${durableSaveJournalRoot(fixture.admission.context)}"
            }
            hookFailure.get()?.let { throw it }
            check(queued.count == 1L) { "Save acknowledged before durable admission returned" }
            pinsRelease.countDown()
            check(queued.await(8, TimeUnit.SECONDS))
            check(terminal.await(10, TimeUnit.SECONDS))
            check(queuedCount.get() == 1 && terminalCount.get() == 1)
            check(receiverFailure.get()?.contains(injectedFailure) == true)
            val journal = DurableSaveJournal(durableSaveJournalRoot(fixture.admission.context))
            val failedRecord = journal.read(checkNotNull(savedId.get()))
            val intended = checkNotNull(creationIntent.get())
            // Failure is published to the receiver, while WRITING retains resumable ownership.
            check(failedRecord.stage == DurableSaveStage.WRITING && failedRecord.output == null)
            check(failedRecord.creationName == intended.creationName && failedRecord.creationName.isNotBlank())
            check(failedRecord.error.contains(injectedFailure))
            check(failedRecord.source.objects == intended.source.objects && failedRecord.source.objects.isNotEmpty())
            failedRecord.source.objects.forEach { DurablePcmPins.verify(journal.directory(failedRecord.id), it) }
            val retainedPrefix = ByteArrayOutputStream()
            failedRecord.source.range.readNormalized(16_000, 1, PcmSampleFormat.PCM_16, Long.MAX_VALUE) { bytes, offset, count ->
                retainedPrefix.write(bytes, offset, count); count
            }
            check(retainedPrefix.toByteArray().contentEquals(expected)) { "Failed creation lost its durable source prefix" }
            check(fixture.loop.pipelinePcm().contentEquals(expected))
            report.append("capture_pipeline_save_prefix PASS accepted_frames=320 durable_pins_exact=true output_creation_injected_failure=true\n")
        } finally {
            appendRelease.countDown(); pinsRelease.countDown(); source.unblockAll()
            DurableSaveFaultHooks.onBoundary = null
        }
    }
}

private fun verifyPipelineClearPrefix(context: Context, report: StringBuilder) {
    withFramePipeline(context, "clear_old_destination_prefix") { fixture ->
        val source = PipelineInput(false, automaticReads = 3, holdReadOrdinal = 2) { ordinal, _ -> boundaryPcm(ordinal) }
        val appendEntered = CountDownLatch(1)
        val appendRelease = CountDownLatch(1)
        try {
            fixture.configure(source, ReverbService.BufferSlot.ONE_SHOT)
            fixture.injectFirstAppendBarrier(appendEntered, appendRelease, fixture.one)
            fixture.start()
            check(appendEntered.await(5, TimeUnit.SECONDS))
            check(source.heldReadEntered.await(5, TimeUnit.SECONDS))
            check(fixture.service.selectCaptureBuffer(ReverbService.BufferSlot.LOOPING).accepted)
            val clearId = checkNotNull(boundaryMain { fixture.service.startClearBuffer(ReverbService.BufferSlot.ONE_SHOT) })
            check(fixture.service.currentBufferClearStatus()?.phase == BufferClearPhase.STARTING)
            source.allowHeldRead.countDown()
            check(source.thirdEntered.await(5, TimeUnit.SECONDS))
            appendRelease.countDown()
            source.permitFenceReturn()
            fixture.awaitCondition {
                val status = fixture.service.currentBufferClearStatus()
                status?.operationId == clearId && status.phase.isTerminal
            }
            check(fixture.service.currentBufferClearStatus()?.phase == BufferClearPhase.COMPLETED)
            fixture.fence {
                check(fixture.one.pipelinePcm().isEmpty()) { "Clear left or resurrected an old-destination queued append" }
                check(fixture.loop.pipelinePcm().contentEquals(boundaryPcm(3))) { "Post-handoff read did not retain its new destination" }
            }
            check(source.reads.get() >= 3)
            report.append("capture_pipeline_clear_prefix PASS old_destination_empty=true new_destination_frames=160\n")
        } finally { appendRelease.countDown(); source.unblockAll() }
    }
}

/** Clear must include a gate-held A prefix, without waiting for a later B native read. */
private fun verifyPipelineClearHeldGate(context: Context, report: StringBuilder) {
    withFramePipeline(context, "clear_gate_held_destination") { fixture ->
        val source = PipelineInput(true, automaticReads = 2, holdReadOrdinal = 2) { ordinal, _ -> boundaryPcm(ordinal) }
        val seed = boundaryPcm(7)
        try {
            fixture.configure(source, ReverbService.BufferSlot.ONE_SHOT, gated = true)
            fixture.onAudio {
                check(fixture.one.append(seed, 0, seed.size) == seed.size)
                fixture.one.sealActiveChunk()
                check(fixture.one.hasData()) // A is a normally clearable historical buffer after handoff.
            }
            fixture.start()
            check(source.firstEntered.await(5, TimeUnit.SECONDS))
            check(fixture.service.selectCaptureBuffer(ReverbService.BufferSlot.LOOPING).accepted)
            val clearId = checkNotNull(boundaryMain { fixture.service.startClearBuffer(ReverbService.BufferSlot.ONE_SHOT) })
            // Clear has reserved read 1 (A). Read 2 (B) cannot yet exist and is outside that fence.
            source.allowFirst.countDown()
            check(source.heldReadEntered.await(5, TimeUnit.SECONDS))
            fixture.awaitCondition {
                val status = fixture.service.currentBufferClearStatus()
                status?.operationId == clearId && status.phase.isTerminal
            }
            check(fixture.service.currentBufferClearStatus()?.phase == BufferClearPhase.COMPLETED)
            fixture.onAudio {
                check(fixture.one.pipelinePcm().isEmpty())
                check(fixture.service.frameField("silenceGateSlot") != ReverbService.BufferSlot.ONE_SHOT) {
                    "Clear completed while an old-destination gate could still emit audio"
                }
            }
            source.allowHeldRead.countDown()
            check(source.thirdEntered.await(5, TimeUnit.SECONDS))
            fixture.fence {
                fixture.service.frameInvoke("finishSilenceGate")
                check(fixture.one.pipelinePcm().isEmpty()) { "Later B packet resurrected cleared A gate audio" }
                check(fixture.loop.pipelinePcm().contentEquals(boundaryPcm(2))) { "B gate audio changed during A Clear" }
            }
            report.append("capture_pipeline_clear_held_gate PASS old_destination_empty=true new_destination_frames=160\n")
        } finally { source.unblockAll() }
    }
}

/** The real producer must preserve A PCM without granting its late frame B's command. */
private fun verifyPipelineVisualizationHandoff(context: Context, report: StringBuilder) {
    withFramePipeline(context, "visualization_old_destination") { fixture ->
        val source = PipelineInput(true, automaticReads = 2, holdReadOrdinal = 2) { ordinal, _ ->
            boundaryPcm(ordinal).copyOf(128)
        }
        val delivered = ArrayList<ReverbService.VisualizationFrame>() // Main-thread owned.
        val callback = ReverbService.VisualizationCallback { delivered.add(it) }
        try {
            fixture.configure(source, ReverbService.BufferSlot.ONE_SHOT)
            boundaryMain { fixture.service.setVisualizationCallback(callback) }
            fixture.start()
            check(source.firstEntered.await(5, TimeUnit.SECONDS))
            val commands = fixture.service.frameField("listeningCommandGeneration") as java.util.concurrent.atomic.AtomicLong
            val oldCommand = commands.get()
            check(fixture.service.selectCaptureBuffer(ReverbService.BufferSlot.LOOPING).accepted)
            val newCommand = commands.get()
            check(newCommand != oldCommand)
            source.allowFirst.countDown()
            check(source.heldReadEntered.await(5, TimeUnit.SECONDS))
            // B is held in its native read, so it cannot overwrite A in the latest-frame mailbox.
            fixture.awaitCondition { fixture.onAudio { fixture.one.pipelinePcm().size == 128 } }
            boundaryMain {
                check(delivered.isEmpty()) { "Late A visualization crossed the accepted B handoff" }
            }
            fixture.onAudio {
                check(fixture.one.pipelinePcm().contentEquals(boundaryPcm(1).copyOf(128)))
                check(fixture.loop.pipelinePcm().isEmpty())
            }
            source.allowHeldRead.countDown()
            check(source.thirdEntered.await(5, TimeUnit.SECONDS))
            fixture.awaitCondition { fixture.onAudio { fixture.loop.pipelinePcm().size == 128 } }
            boundaryMain {
                check(delivered.size == 1) { "Current B visualization was not delivered exactly once" }
                check(delivered.single().bufferSlot == ReverbService.BufferSlot.LOOPING)
                check(delivered.single().captureCommandGeneration == newCommand)
            }
            fixture.fence {
                check(fixture.one.pipelinePcm().contentEquals(boundaryPcm(1).copyOf(128)))
                check(fixture.loop.pipelinePcm().contentEquals(boundaryPcm(2).copyOf(128)))
            }
            report.append("capture_pipeline_visualization_handoff PASS stale_frames=0 current_frames=1 old_pcm_frames=64 new_pcm_frames=64\n")
        } finally {
            boundaryMain { fixture.service.clearVisualizationCallback(callback) }
            source.unblockAll()
        }
    }
}

private fun verifyPipelineSampling(context: Context, report: StringBuilder) {
    withFramePipeline(context, "running_sample_capture_clock") { fixture ->
        val source = PipelineInput(true, automaticReads = 3, holdReadOrdinal = 2) { ordinal, _ -> boundaryPcm(ordinal) }
        val timestamps = AtomicInteger()
        val requested = java.util.concurrent.CopyOnWriteArrayList<Int>()
        val clockedSource = object : CaptureAudioInput by source {
            override fun read(buffer: ByteBuffer, sizeInBytes: Int, readMode: Int): Int {
                requested += sizeInBytes
                return source.read(buffer, sizeInBytes, readMode)
            }
            override fun lastReadStartedAtNanos(readEndedAtNanos: Long): Long {
                timestamps.incrementAndGet()
                return readEndedAtNanos - 10_000_000L
            }
        }
        val request = SilenceSampleRequest(held = true)
        try {
            fixture.configure(clockedSource, ReverbService.BufferSlot.LOOPING)
            fixture.start()
            check(source.firstEntered.await(5, TimeUnit.SECONDS))
            boundaryMain { fixture.service.sampleSilence(request) }
            source.allowFirst.countDown()
            check(source.heldReadEntered.await(5, TimeUnit.SECONDS))
            fixture.awaitCondition { fixture.service.frameField("silenceSampleRequest") === request || !request.running() }
            check(request.running()) { "sampleSilence admission failed: ${request.state.value}" }
            source.allowHeldRead.countDown()
            check(source.thirdEntered.await(5, TimeUnit.SECONDS))
            fixture.awaitCondition { timestamps.get() >= 2 }
            // This wrapper is not the fixture's private PipelineInput; explicitly release the native zero read.
            val done = FutureTask(java.util.concurrent.Callable {
                val observed = SilenceSampleRequest::class.java.getDeclaredField("clockedPcmObserved").apply { isAccessible = true }
                check(observed.getBoolean(request)) { "Native timestamp was not delivered through consumeQueuedCapture to calibration" }
                check(!request.captureTimingUnavailable())
                request.cancel()
            })
            check(fixture.io.post(pauseInput = true, allowTerminal = true, rejected = { done.cancel(false) }) { done.run() } ==
                CaptureControlMailbox.Admission.ACCEPTED)
            source.permitFenceReturn()
            done.get(10, TimeUnit.SECONDS)
            check(requested.first() == 32_000)
            check(requested[2] == 1_280) { "Running calibration did not select the 40ms native cadence: $requested" }
            report.append("capture_pipeline_sampling PASS timestamp_calls=${timestamps.get()} background_bytes=32000 sampling_bytes=1280\n")
        } finally { request.cancel(); source.unblockAll() }
    }
}

private fun verifyPipelineClearGateRevoked(context: Context, report: StringBuilder, supersede: Boolean) {
    val token = if (supersede) "clear_gate_superseded" else "clear_gate_cancelled"
    withFramePipeline(context, token) { fixture ->
        val source = PipelineInput(true, automaticReads = 2, holdReadOrdinal = 2) { ordinal, _ -> boundaryPcm(ordinal) }
        val seed = boundaryPcm(7)
        try {
            fixture.configure(source, ReverbService.BufferSlot.ONE_SHOT, gated = true)
            fixture.onAudio {
                check(fixture.one.append(seed, 0, seed.size) == seed.size)
                fixture.one.sealActiveChunk()
            }
            fixture.start()
            check(source.firstEntered.await(5, TimeUnit.SECONDS))
            check(fixture.service.selectCaptureBuffer(ReverbService.BufferSlot.LOOPING).accepted)
            val clearId = checkNotNull(boundaryMain { fixture.service.startClearBuffer(ReverbService.BufferSlot.ONE_SHOT) })
            if (supersede) check(fixture.service.selectCaptureBuffer(ReverbService.BufferSlot.ONE_SHOT).accepted)
            else check(fixture.service.cancelBufferClear(clearId))
            source.allowFirst.countDown()
            check(source.heldReadEntered.await(5, TimeUnit.SECONDS))
            fixture.awaitCondition {
                val status = fixture.service.currentBufferClearStatus()
                status?.operationId == clearId && status.phase.isTerminal
            }
            check(fixture.service.currentBufferClearStatus()?.phase == BufferClearPhase.CANCELLED)
            fixture.onAudio {
                check(fixture.service.frameField("silenceGateSlot") == ReverbService.BufferSlot.ONE_SHOT)
                check(fixture.one.pipelinePcm().contentEquals(seed)) { "Revoked Clear unexpectedly drained or deleted A" }
                check(fixture.loop.pipelinePcm().isEmpty())
            }
            source.allowHeldRead.countDown()
            check(source.thirdEntered.await(5, TimeUnit.SECONDS))
            fixture.fence {
                fixture.service.frameInvoke("finishSilenceGate")
                val expectedA = seed + boundaryPcm(1) + if (supersede) boundaryPcm(2) else ByteArray(0)
                check(fixture.one.pipelinePcm().contentEquals(expectedA)) { "Revoked Clear lost accepted A audio" }
                check(fixture.loop.pipelinePcm().contentEquals(if (supersede) ByteArray(0) else boundaryPcm(2)))
            }
            report.append("capture_pipeline_$token PASS no_clear=true accepted_pcm_preserved=true\n")
        } finally { source.unblockAll() }
    }
}

private fun verifyPipelineClearKeepsActiveGate(context: Context, report: StringBuilder) {
    withFramePipeline(context, "clear_preserves_active_other_gate") { fixture ->
        val source = PipelineInput(false, automaticReads = 1, holdReadOrdinal = 2) { ordinal, _ -> boundaryPcm(ordinal) }
        try {
            fixture.configure(source, ReverbService.BufferSlot.LOOPING, gated = true)
            fixture.onAudio {
                val seed = boundaryPcm(7)
                check(fixture.one.append(seed, 0, seed.size) == seed.size)
                fixture.one.sealActiveChunk()
            }
            fixture.start()
            check(source.heldReadEntered.await(5, TimeUnit.SECONDS))
            fixture.awaitCondition { (fixture.io.currentInput()?.processedFrames ?: 0L) >= 160L }
            val activeGate = fixture.onAudio {
                check(fixture.service.frameField("silenceGateSlot") == ReverbService.BufferSlot.LOOPING)
                check(fixture.loop.pipelinePcm().isEmpty())
                checkNotNull(fixture.service.frameField("silenceGateStream"))
            }
            val clearId = checkNotNull(boundaryMain { fixture.service.startClearBuffer(ReverbService.BufferSlot.ONE_SHOT) })
            source.allowHeldRead.countDown()
            source.permitFenceReturn() // Read 2 is a zero read inside Clear's already-reserved prefix.
            fixture.awaitCondition {
                val status = fixture.service.currentBufferClearStatus()
                status?.operationId == clearId && status.phase.isTerminal
            }
            check(fixture.service.currentBufferClearStatus()?.phase == BufferClearPhase.COMPLETED)
            fixture.onAudio {
                check(fixture.service.frameField("silenceGateStream") === activeGate) { "A Clear replaced the active B gate" }
                check(fixture.loop.pipelinePcm().isEmpty()) { "A Clear flushed B's held partial window" }
                check(fixture.one.pipelinePcm().isEmpty())
            }
            fixture.fence {
                fixture.service.frameInvoke("finishSilenceGate")
                check(fixture.loop.pipelinePcm().contentEquals(boundaryPcm(1)))
            }
            report.append("capture_pipeline_clear_active_other_gate PASS gate_identity_preserved=true held_pcm_unchanged=true\n")
        } finally { source.unblockAll() }
    }
}

private fun verifyPipelineClearGateFailure(context: Context, report: StringBuilder) {
    withFramePipeline(context, "clear_gate_flush_failure") { fixture ->
        val source = PipelineInput(true, automaticReads = 2, holdReadOrdinal = 2) { ordinal, _ -> boundaryPcm(ordinal) }
        val syncField = PersistentAudioChunkStore::class.java.getDeclaredField("directorySync").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST") val originalSync = syncField.get(fixture.one) as (File) -> Unit
        val injected = java.util.concurrent.atomic.AtomicBoolean()
        val seed = boundaryPcm(7)
        try {
            fixture.configure(source, ReverbService.BufferSlot.ONE_SHOT, gated = true)
            val seedFiles = fixture.onAudio {
                check(fixture.one.append(seed, 0, seed.size) == seed.size)
                fixture.one.sealActiveChunk()
                val field = PersistentAudioChunkStore::class.java.getDeclaredField("chunksDirectory").apply { isAccessible = true }
                val directory = field.get(fixture.one) as File
                checkNotNull(directory.listFiles()).filter { it.isFile && it.name.toLongOrNull() != null }
                    .associateWith { it.readBytes() }.also { check(it.size == 1) }
            }
            val marker = checkNotNull(RecordingIncidentStore.beginKnownCaptureStop(fixture.admission.context).marker)
            val previousInterrupted = fixture.service.frameField("persistenceInterruptedCaptureEpoch")
            syncField.set(fixture.one, { directory: File ->
                if (injected.compareAndSet(false, true)) {
                    val stack = Thread.currentThread().stackTrace.map { it.methodName }
                    check(stack.contains("finishSilenceGate") && stack.contains("createActiveChunkLocked"))
                    File(fixture.root, "gate-flush-failure-stack.txt").writeText(stack.joinToString("\n"))
                    // The later B read is outside Clear's prefix and must be revoked, not committed.
                    check(source.heldReadEntered.await(5, TimeUnit.SECONDS))
                    throw IOException("synthetic Clear held-gate append failure")
                }
                originalSync(directory)
            })
            fixture.start()
            check(source.firstEntered.await(5, TimeUnit.SECONDS))
            check(fixture.service.selectCaptureBuffer(ReverbService.BufferSlot.LOOPING).accepted)
            val clearId = checkNotNull(boundaryMain { fixture.service.startClearBuffer(ReverbService.BufferSlot.ONE_SHOT) })
            source.allowFirst.countDown()
            fixture.awaitCondition {
                val status = fixture.service.currentBufferClearStatus()
                status?.operationId == clearId && status.phase.isTerminal
            }
            check(injected.get())
            check(fixture.service.currentBufferClearStatus()?.phase == BufferClearPhase.FAILED)
            check(fixture.service.frameField("persistenceFailureBlocked") == true)
            check(fixture.service.frameField("persistenceInterruptedCaptureEpoch") != previousInterrupted)
            check(fixture.service.frameField("state") != ReverbService.STATE_LISTENING)
            check(RecordingIncidentStore.readIncidents(fixture.admission.context).any {
                !it.deleted && it.pid == marker.pid && it.captureArmedAtMillis == marker.armedAtMillis
            }) { "Gate flush persistence failure has no matching interruption evidence" }
            // Do not query a failed store. The exact sealed seed object must survive unchanged.
            seedFiles.forEach { (file, bytes) -> check(file.isFile && file.readBytes().contentEquals(bytes)) }
            syncField.set(fixture.one, originalSync)
            source.unblockAll()
            check(source.released.await(15, TimeUnit.SECONDS))
            report.append("capture_pipeline_clear_gate_failure PASS clear_failed=true seed_preserved=true capture_revoked=true\n")
        } finally {
            syncField.set(fixture.one, originalSync)
            source.unblockAll()
        }
    }
}
