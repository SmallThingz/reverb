package app.smallthingz.reverb

import android.app.Instrumentation
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.AudioRecord
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal fun verifyCapturePreviewConcurrency(instrumentation: Instrumentation, report: StringBuilder) {
    val context = instrumentation.targetContext
    check(context.packageName == "app.smallthingz.reverb.reliability")
    check(Looper.myLooper() != Looper.getMainLooper())
    val evidence = Files.createTempDirectory(context.filesDir.toPath(), "capture-preview-concurrency-").toFile()
    val session = CapturePreviewSession(context, evidence)
    var primary: Throwable? = null
    try {
        session.bind()
        val previewEvidence = verifyRangePreviewCase(instrumentation, report,
            PreviewDiagnosticCase("stereo_96k_float_phase", 96_000, 2, PcmSampleFormat.PCM_FLOAT), session)
        val preview = JSONObject(previewEvidence.readText())
        session.verifyPreviewOverlap(preview)
        session.writeResult(null, previewEvidence)
        report.appendLine("PASS capture_preview_concurrency actual synthetic capture/store contention and native preview counters; audible continuity unverified")
    } catch (error: Throwable) {
        primary = error
        throw error
    } finally {
        // Stopping an owned writer is safe even if the preview's release outcome is unknown.
        // Store close/unbind remains restricted to the readers-released hook.
        try { session.afterPlayback() } catch (cleanup: Throwable) {
            if (primary != null) primary.addSuppressed(cleanup) else primary = cleanup
        }
        if (!session.previewCleanupArmed) try { session.closeBeforePreview() } catch (cleanup: Throwable) {
            if (primary != null) primary.addSuppressed(cleanup) else primary = cleanup
        }
        primary?.let { error ->
            try { session.writeResult(error, null) } catch (evidenceError: Throwable) { error.addSuppressed(evidenceError) }
            throw error
        }
    }
}

private class CapturePreviewSession(private val context: Context, private val evidence: File) : CapturePreviewWorkload {
    private val prefs = getRecorderPreferences(context)
    private val originalPreferences = prefs.all.mapValues { (_, value) -> if (value is Set<*>) value.toSet() else value }
    private val connected = CountDownLatch(1)
    private val ownerRef = AtomicReference<CaptureFrameIdentityService?>()
    private lateinit var admission: FrameFixtureAdmission
    private lateinit var handler: Handler
    private lateinit var recorder: CaptureFrameIdentityService
    private lateinit var input: PacedPreviewCaptureInput
    private var bound = false
    private var started = false
    private var inputInstallationAttempted = false
    private var stopped = false
    @Volatile private var writerDrained = false
    private var drainTask: FutureTask<Unit>? = null
    private var durabilityFence: java.util.concurrent.Future<*>? = null
    private var readersReleased = false
    var previewCleanupArmed = false
        private set
    private var unbindRequested = false
    private var closed = false
    private val foregroundOwner = Any()
    private var initialChunkCount = 0
    private var finalChunkCount = 0
    private var startNanos = 0L
    private var drainNanos = 0L
    private var stoppedNanos = 0L
    private var deliveredSha256: String? = null
    private var reopenedTailSha256: String? = null
    private var reopenedOriginalSha256: String? = null
    private var reopenedTailBytes = 0L
    private val syncCount = AtomicInteger()
    private val syncTimes = LongArray(256)
    private var syncOverflow = false
    private val syncLock = Any()
    private var result = JSONObject().put("passed", false).put("selector", "capture_preview_concurrency")
        .put("source", "synthetic_generated_no_dropped_frames").put("authority", "synthetic_runtime_cache")
        .put("native_capture_overrun", "UNMEASURED").put("audible_continuity", "UNVERIFIED")
    override lateinit var store: PersistentAudioChunkStore
        private set
    override lateinit var root: File
        private set
    override val evidencePath: String get() = evidence.name + "/capture.json"
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            ownerRef.set((binder as ReverbService.BackgroundRecorderBinder).service as CaptureFrameIdentityService)
            connected.countDown()
        }
        override fun onServiceDisconnected(name: ComponentName) = Unit
    }

    fun bind() {
        // Real QA settings are observed only for preservation; fixture startup uses its own disabled intent.
        val parent = File(context.noBackupFilesDir, "capture-preview-concurrency")
        check(parent.mkdirs() || parent.isDirectory)
        val ownedRoot = Files.createTempDirectory(parent.toPath(), "capture-").toFile()
        admission = createFrameFixtureAdmission(context, ownedRoot)
        check(frameFixtureAdmission.compareAndSet(null, admission)) { "Another synthetic capture owner is unresolved" }
        result.put("owned_root", ownedRoot.relativeTo(context.noBackupFilesDir).path)
        File(evidence, "capture.json").writeText(result.toString(2))
        bound = context.bindService(Intent(context, CaptureFrameIdentityService::class.java), connection, Context.BIND_AUTO_CREATE)
        check(bound && connected.await(15, TimeUnit.SECONDS)) { "Concurrent synthetic service did not bind" }
        recorder = checkNotNull(ownerRef.get())
        handler = recorder.frameField("audioHandler") as Handler
        onAudio {
            check(recorder.frameField("captureStoresReady") == true && recorder.frameField("audioRecord") == null)
            store = recorder.frameField("loopingAudioChunkStore") as PersistentAudioChunkStore
            check(!store.hasData())
            root = PersistentAudioChunkStore::class.java.getDeclaredField("rootDirectory")
                .apply { isAccessible = true }.get(store) as File
        }
    }

    override fun onPreviewCleanupArmed() {
        check(!previewCleanupArmed)
        previewCleanupArmed = true
    }

    override fun beforePlay() {
        check(!started && !stopped && bound)
        check(kotlin.math.abs(store.durationSeconds() - 20.0) < 1.0 / 96_000)
        initialChunkCount = checkNotNull(store.peekSnapshot()).chunkCount
        val initialBytes = store.countFilledBytes()
        check(initialBytes == 20L * 96_000 * 8)
        input = PacedPreviewCaptureInput(initialBytes, store)
        onAudio {
            recorder.frameSet("sampleRate", 96_000)
            recorder.frameSet("channelMode", ChannelMode.STEREO)
            recorder.frameSet("pcmSampleFormat", PcmSampleFormat.PCM_FLOAT)
            recorder.frameSet("silenceGateConfig", SilenceGateConfig(enabled = false))
            recorder.frameSet("silenceSampleRequest", null)
            recorder.frameSet("oneShotBufferEnabled", false)
            recorder.frameSet("loopingBufferEnabled", true)
            recorder.frameSet("activeBufferSlot", ReverbService.BufferSlot.LOOPING)
            recorder.frameSet("durableCaptureBufferSlot", ReverbService.BufferSlot.LOOPING)
            recorder.frameSet("durableCaptureIntentAuthorityValid", true)
            recorder.frameSet("durableListeningIntentEnabled", true)
            recorder.frameSet("state", ReverbService.STATE_LISTENING)
            recorder.frameSet("lastDurabilitySyncRequestNanos", 0L)
            recorder.frameInvoke("prepareCaptureReadBuffersOnAudioThread")
            (recorder.frameField("appUiForegroundOwners") as UiForegroundOwnerTracker).update(foregroundOwner, true)
            val generation = (recorder.frameField("listeningCommandGeneration") as AtomicLong).incrementAndGet()
            recorder.frameSet("audioRecordGeneration", generation)
            inputInstallationAttempted = true
            recorder.frameSet("audioRecord", input)
            val field = PersistentAudioChunkStore::class.java.getDeclaredField("directorySync").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val original = field.get(store) as (File) -> Unit
            val observe: (File) -> Unit = { directory ->
                original(directory)
                synchronized(syncLock) {
                    val index = syncCount.getAndIncrement()
                    if (index < syncTimes.size) syncTimes[index] = SystemClock.elapsedRealtimeNanos() else syncOverflow = true
                }
            }
            field.set(store, observe)
            startNanos = SystemClock.elapsedRealtimeNanos()
            started = true
            check(handler.post(recorder.frameField("audioReader") as Runnable))
        }
        check(input.firstAppendObserved.await(5, TimeUnit.SECONDS)) { "Actual capture append did not begin before preview" }
    }

    override fun afterPlayback() {
        if (stopped || !::handler.isInitialized) return
        if (!::input.isInitialized) { stopped = true; return }
        val existing = drainTask
        val drain = existing ?: FutureTask {
            var failure: Throwable? = null
            drainNanos = SystemClock.elapsedRealtimeNanos()
            try {
                check(store.countFilledBytes() == input.initialBytes + input.deliveredFrames * 8) { "Delivered capture bytes were not committed" }
                check(recorder.frameField("persistenceFailureBlocked") == false)
                val actual = recorder.frameField("audioRecord")
                check(actual === input || (actual == null && !inputInstallationAttempted))
            } catch (error: Throwable) { failure = error }
            try {
                val actual = recorder.frameField("audioRecord")
                check(actual === input || actual == null) { "Synthetic input owner changed; cleanup outcome unknown" }
                handler.removeCallbacks(recorder.frameField("audioReader") as Runnable)
                recorder.frameSet("durableListeningIntentEnabled", false)
                recorder.frameSet("state", ReverbService.STATE_READY)
                (recorder.frameField("appUiForegroundOwners") as UiForegroundOwnerTracker).update(foregroundOwner, false)
                if (actual === input) recorder.frameInvoke("releaseAudioRecord")
                else if (!inputInstallationAttempted && input.releases.get() == 0) {
                    // Preparation failed before ownership transfer; dispose only this never-installed fake.
                    input.release()
                }
                check(input.releases.get() == 1 && input.starts.get() == 0)
                deliveredSha256 = input.finishDigest()
                stoppedNanos = SystemClock.elapsedRealtimeNanos()
                writerDrained = true
            } catch (cleanup: Throwable) {
                if (failure != null) failure.addSuppressed(cleanup) else failure = cleanup
            }
            failure?.let { throw it }
            Unit
        }.also {
            drainTask = it
            // Queue the sole drain before waking a blocked read; later hooks only await this same task.
            check(handler.post(it))
            input.wakeForDrain.countDown()
        }
        var failure: Throwable? = null
        try { drain.get(15, TimeUnit.SECONDS) } catch (error: Throwable) { failure = error }
        if (writerDrained) {
            try {
                val fence = durabilityFence ?: (recorder.frameField("durabilitySyncExecutor") as ExecutorService)
                    .submit {}.also { durabilityFence = it }
                fence.get(15, TimeUnit.SECONDS)
                stopped = true
            } catch (cleanup: Throwable) {
                if (failure != null) failure.addSuppressed(cleanup) else failure = cleanup
            }
        }
        failure?.let { throw it }
    }

    fun closeBeforePreview() {
        if (!::admission.isInitialized || closed) return
        if (!bound) {
            check(frameFixtureAdmission.compareAndSet(admission, null))
            closed = true
            return
        }
        check(!unbindRequested)
        unbindRequested = true
        context.unbindService(connection)
        check(admission.destroyed.await(30, TimeUnit.SECONDS))
        val owner = checkNotNull(ownerRef.get())
        val thread = owner.frameField("audioThread") as Thread
        thread.join(30_000)
        check(!thread.isAlive && admission.factoryCalls.get() == 0)
        check(frameFixtureAdmission.compareAndSet(admission, null))
        closed = true
    }

    override fun afterReadersReleased() {
        check(stopped) { "Cannot close a store while its synthetic writer is still active" }
        if (closed) return
        readersReleased = true
        check(!unbindRequested)
        unbindRequested = true
        context.unbindService(connection)
        check(admission.destroyed.await(30, TimeUnit.SECONDS)) { "Synthetic service close outcome unknown" }
        val audioThread = recorder.frameField("audioThread") as Thread
        audioThread.join(30_000)
        check(!audioThread.isAlive) { "Synthetic audio/store close outcome unknown" }
        check(admission.factoryCalls.get() == 0)
        if (started) check(input.releases.get() == 1 && input.starts.get() == 0)
        check(prefs.all == originalPreferences) { "Synthetic workload changed user QA preferences" }
        if (!started) {
            check(frameFixtureAdmission.compareAndSet(admission, null))
            closed = true
            return
        }
        val loop = PersistentAudioChunkStore(recorder, legacyCacheFolderName = null)
        val one = PersistentAudioChunkStore(recorder, cacheFolderName = ONE_SHOT_BUFFER_CACHE_FOLDER_NAME,
            legacyCacheFolderName = null, overwriteOldest = false)
        var validationFailure: Throwable? = null
        try {
            val deadline = SystemClock.elapsedRealtimeNanos() + 5_000_000_000L
            PersistentAudioChunkStore.awaitDirectoryOwnership(listOf(loop, one)) { SystemClock.elapsedRealtimeNanos() >= deadline }
            finalChunkCount = checkNotNull(loop.peekSnapshot()).chunkCount
            if (started) {
                reopenedOriginalSha256 = hashRange(loop, 0.0, 20.0).second
                val tail = hashRange(loop, 20.0, loop.durationSeconds())
                reopenedTailBytes = tail.first
                reopenedTailSha256 = tail.second
                check(tail.first == input.deliveredFrames * 8 && tail.second == deliveredSha256) { "Reopened appended frame identities differ" }
                check(loop.countFilledBytes() == input.initialBytes + input.deliveredFrames * 8)
            }
        } catch (error: Throwable) { validationFailure = error }
        finally {
            var storesClosed = true
            for (reopened in listOf(loop, one)) try { reopened.close() } catch (cleanup: Throwable) {
                storesClosed = false
                if (validationFailure != null) validationFailure.addSuppressed(cleanup) else validationFailure = cleanup
            }
            if (storesClosed) try {
                check(frameFixtureAdmission.compareAndSet(admission, null))
                closed = true
            } catch (cleanup: Throwable) {
                if (validationFailure != null) validationFailure.addSuppressed(cleanup) else validationFailure = cleanup
            }
        }
        validationFailure?.let { throw it }
    }

    fun verifyPreviewOverlap(preview: JSONObject) {
        check(closed && stopped && started && readersReleased)
        check(preview.getBoolean("passed") && preview.getBoolean("native_coverage_complete"))
        check(preview.getLong("midstream_underrun_delta") == 0L)
        check(preview.getString("source_pcm_sha256") == reopenedOriginalSha256)
        val samples = preview.getJSONArray("native_samples")
        check(samples.length() >= 10)
        val first = samples.getJSONObject(0).getLong("elapsed_ns")
        val last = samples.getJSONObject(samples.length() - 1).getLong("elapsed_ns")
        check(startNanos <= first && drainNanos >= last) { "Capture workload did not span actual sampled preview" }
        val during = (0 until input.reads.get()).count { input.deliveredAt[it] in first..last }
        check(during >= 100) { "Insufficient actual capture reads overlapped playback" }
        val committedChecksDuring = (0 until input.reads.get()).count { input.startedAt[it] in first..last && input.committedAtEntry[it] > 0 }
        check(committedChecksDuring >= 100)
        check(finalChunkCount - initialChunkCount >= 3) { "Capture did not exercise at least two boundaries between new chunks" }
        val successfulSyncsDuring = synchronized(syncLock) {
            check(!syncOverflow)
            (0 until syncCount.get()).count { syncTimes[it] in first..last }
        }
        check(successfulSyncsDuring >= 2) { "Real directory durability work was not observed during playback" }
        val elapsed = (drainNanos - input.startedAt[0]).toDouble() / 1_000_000_000.0
        val deliveredRate = input.deliveredFrames / elapsed
        check(deliveredRate >= 96_000 * 0.9 && deliveredRate <= 96_000 * 1.1) { "Synthetic workload did not sustain the requested capture rate" }
        result.put("preview_first_native_ns", first).put("preview_last_native_ns", last)
            .put("capture_reads_during_preview", during).put("append_checks_during_preview", committedChecksDuring)
            .put("successful_directory_syncs_during_preview", successfulSyncsDuring)
            .put("delivered_frames_per_second", deliveredRate).put("rate_tolerance_fraction", 0.1)
            .put("original_source_pcm_sha256", reopenedOriginalSha256)
            .put("preview_midstream_underruns", preview.getLong("midstream_underrun_delta"))
    }

    fun writeResult(failure: Throwable?, previewEvidence: File?) {
        result.put("passed", failure == null).put("failure", failure?.stackTraceToString() ?: JSONObject.NULL)
            .put("preview_evidence", previewEvidence?.let { it.relativeTo(context.filesDir).path } ?: JSONObject.NULL)
            .put("started", started).put("capture_drained", stopped).put("preview_readers_released", readersReleased)
            .put("service_closed", closed).put("capture_start_ns", startNanos).put("capture_drain_ns", drainNanos)
            .put("capture_stopped_ns", stoppedNanos).put("initial_chunks", initialChunkCount).put("final_chunks", finalChunkCount)
            .put("new_capture_chunks", finalChunkCount - initialChunkCount)
            .put("completed_capture_rollovers", (finalChunkCount - initialChunkCount - 1).coerceAtLeast(0))
            .put("rollover_scope", "New chunk boundaries over the bounded capture workload; individual boundary timestamps are not measured")
            .put("successful_directory_syncs", syncCount.get())
            .put("source_store_root", if (::root.isInitialized) root.relativeTo(context.noBackupFilesDir).path else JSONObject.NULL)
            .put("capture_rate", 96_000).put("capture_channels", 2).put("capture_format", "PCM_FLOAT")
            .put("retention_bytes", 33_554_432).put("capture_budget_seconds", 20).put("sync_overflow", syncOverflow)
            .put("frame_encoding", "little-endian uint32 left=0x3f000000+frameIndex; right=left|0x80000000")
            .put("delivered_sha256", deliveredSha256 ?: JSONObject.NULL).put("reopened_tail_sha256", reopenedTailSha256 ?: JSONObject.NULL)
            .put("reopened_tail_bytes", reopenedTailBytes).put("reopened_original_sha256", reopenedOriginalSha256 ?: JSONObject.NULL)
            .put("native_factory_calls", if (::admission.isInitialized) admission.factoryCalls.get() else JSONObject.NULL)
            .put("pacing", "Fixed 40ms availability deadlines; generated frames never drop. This is not a bounded native microphone ring.")
            .put("observer_overhead", "Generated float bytes and SHA per read; constant-time committed-byte observations; successful real directory-sync timestamps. No per-read evidence-file writes.")
        if (::input.isInitialized) {
            result.put("delivered_frames", input.deliveredFrames).put("input_releases", input.releases.get()).put("input_starts", input.starts.get())
            val reads = JSONArray()
            for (index in 0 until minOf(input.reads.get(), input.startedAt.size)) reads.put(JSONObject()
                .put("start_ns", input.startedAt[index]).put("delivered_ns", input.deliveredAt[index])
                .put("availability_ns", input.availabilityAt[index]).put("committed_frames_at_entry", input.committedAtEntry[index]))
            result.put("reads", reads)
        }
        val successfulSyncTimes = JSONArray()
        synchronized(syncLock) {
            for (index in 0 until minOf(syncCount.get(), syncTimes.size)) successfulSyncTimes.put(syncTimes[index])
        }
        result.put("successful_directory_sync_times_ns", successfulSyncTimes)
        File(evidence, "capture.json").writeText(result.toString(2))
    }

    private fun onAudio(block: () -> Unit) {
        val task = FutureTask { block() }
        check(handler.post(task))
        task.get(15, TimeUnit.SECONDS)
    }

    private fun hashRange(source: PersistentAudioChunkStore, start: Double, end: Double): Pair<Long, String> {
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        requireNotNull(source.acquireRange(start, end)).use { lease ->
            lease.readNormalized(96_000, 2, PcmSampleFormat.PCM_FLOAT) { bytes, offset, count ->
                digest.update(bytes, offset, count); size += count; count
            }
        }
        return size to digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}

private class PacedPreviewCaptureInput(val initialBytes: Long, private val store: PersistentAudioChunkStore) : CaptureAudioInput {
    val reads = AtomicInteger()
    val starts = AtomicInteger()
    val releases = AtomicInteger()
    val firstAppendObserved = CountDownLatch(1)
    val wakeForDrain = CountDownLatch(1)
    val startedAt = LongArray(1024)
    val deliveredAt = LongArray(1024)
    val availabilityAt = LongArray(1024)
    val committedAtEntry = LongArray(1024)
    var deliveredFrames = 0L
        private set
    private val bytes = ByteArray(30_720)
    private val encoded = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    private val digest = MessageDigest.getInstance("SHA-256")
    override val state: Int get() = AudioRecord.STATE_INITIALIZED
    override val recordingState: Int get() = AudioRecord.RECORDSTATE_RECORDING
    override fun read(buffer: ByteBuffer, sizeInBytes: Int, readMode: Int): Int {
        val now = SystemClock.elapsedRealtimeNanos()
        val index = reads.getAndIncrement()
        check(index < startedAt.size && releases.get() == 0)
        check(sizeInBytes == bytes.size && readMode == AudioRecord.READ_BLOCKING)
        startedAt[index] = now
        if (index > 0) check(now - startedAt[0] < 20_000_000_000L) { "Synthetic capture exceeded its finite 20-second budget" }
        committedAtEntry[index] = (store.countFilledBytes() - initialBytes) / 8
        check(committedAtEntry[index] == deliveredFrames) { "Previous delivered frames did not commit before next read" }
        if (deliveredFrames > 0) firstAppendObserved.countDown()
        encoded.clear()
        val frames = bytes.size / 8
        check(deliveredFrames + frames <= 96_000L * 20) { "Synthetic capture frame budget exhausted" }
        repeat(frames) { offset ->
            val bits = 0x3f000000 + (deliveredFrames + offset).toInt()
            encoded.putInt(bits)
            encoded.putInt(bits or Int.MIN_VALUE)
        }
        val available = startedAt[0] + (deliveredFrames + frames) * 1_000_000_000L / 96_000
        availabilityAt[index] = available
        val wait = available - SystemClock.elapsedRealtimeNanos()
        if (wait > 0) wakeForDrain.await(wait, TimeUnit.NANOSECONDS)
        check(releases.get() == 0) { "Synthetic input released before owned read returned" }
        buffer.put(bytes, 0, bytes.size)
        digest.update(bytes)
        deliveredFrames += frames
        deliveredAt[index] = SystemClock.elapsedRealtimeNanos()
        return bytes.size
    }
    fun finishDigest(): String = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    override fun startRecording() { starts.incrementAndGet(); error("Never start a synthetic input") }
    override fun stop() { wakeForDrain.countDown() }
    override fun release() { check(releases.incrementAndGet() == 1) }
}
