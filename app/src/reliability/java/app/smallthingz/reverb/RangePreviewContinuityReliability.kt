package app.smallthingz.reverb

import android.app.Application
import android.app.Instrumentation
import android.os.Debug
import java.nio.ByteBuffer
import java.nio.ByteOrder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToLong
import kotlin.math.roundToInt

/** Optional QA composition only. Standalone preview retains its original ownership and teardown. */
internal interface CapturePreviewWorkload {
    val store: PersistentAudioChunkStore
    val root: File
    val evidencePath: String
    fun onPreviewCleanupArmed()
    fun beforePlay()
    fun afterPlayback()
    fun afterReadersReleased()
}


/** QA-owned PCM through the actual range-preview transport; no recorder Service or microphone. */
internal fun verifyRangePreviewContinuity(instrumentation: Instrumentation, report: StringBuilder) {
    val context = instrumentation.targetContext
    check(
        (context.packageName == "app.smallthingz.reverb.nativeparity.reliability" &&
            context.applicationContext.javaClass == Application::class.java) ||
        (context.packageName == "app.smallthingz.reverb.reliability" &&
            context.applicationContext.javaClass == ReverbApplication::class.java)
    ) { "Preview diagnostics require an isolated QA application" }
    for (case in listOf(
        PreviewDiagnosticCase("mono_48k_pcm16", 48_000, 1, PcmSampleFormat.PCM_16),
        PreviewDiagnosticCase("stereo_96k_float_phase", 96_000, 2, PcmSampleFormat.PCM_FLOAT),
        PreviewDiagnosticCase("mono_44k1_pcm16_fractional", 44_100, 1, PcmSampleFormat.PCM_16, startSeconds = 0.25),
    )) verifyRangePreviewCase(instrumentation, report, case)
}

internal data class PreviewDiagnosticCase(
    val name: String, val sampleRate: Int, val channels: Int, val format: PcmSampleFormat,
    val startSeconds: Double = 0.1375,
)

internal fun verifyRangePreviewCase(
    instrumentation: Instrumentation, report: StringBuilder, case: PreviewDiagnosticCase,
    workload: CapturePreviewWorkload? = null,
): File {
    val context = instrumentation.targetContext
    check(Looper.myLooper() != Looper.getMainLooper())
    val startSeconds = case.startSeconds
    val endSeconds = startSeconds + 16.0
    val evidence = File(context.filesDir, "range-preview-continuity-${case.name}-${System.currentTimeMillis()}")
    check(evidence.mkdir()) { "Preview evidence already exists" }
    val root = workload?.root ?: File(context.filesDir, "range-preview-pcm-${UUID.randomUUID()}")
    val result = JSONObject().put("passed", false).put("selector", "range_preview_continuity")
        .put("case", case.name).put("production_head", "BOUND_BY_HOST_ARTIFACT_RECEIPT")
        .put("source_directory", root.name).put("source_pcm_archived_on_host", false)
        .put("audible_continuity", "UNVERIFIED").put("underrun_metadata", "PENDING")
        .put("physical_output_downmix", "UNVERIFIED; speaker route may downmix stereo")
        .put("output_frames_observed", JSONObject.NULL).put("expected_output_frames", 384_000)
        .put("source_sample_rate", case.sampleRate).put("source_frames", case.sampleRate * 20)
        .put("source_channels", case.channels).put("source_format", case.format.name)
        .put("range_start_seconds", startSeconds).put("range_end_seconds", endSeconds)
    if (workload != null) result.put("selector", "capture_preview_concurrency")
        .put("transport_fixture", "range_preview_continuity").put("capture_evidence", workload.evidencePath)
    File(evidence, "result.json").writeText(result.toString(2))
    report.append("PARITY_EVIDENCE ").append(evidence.name).append("/result.json\n")
    var source: PersistentAudioChunkStore? = null
    var snapshot: ReverbService.TimelineSnapshot? = null
    var preview: TimelineAudioPreviewController? = null
    var worker: ExecutorService? = null
    var controlWorker: ExecutorService? = null
    var releaseWorker: ExecutorService? = null
    var submitted = false
    var workerDrained = false
    var controlDrained = false
    var releaseDrained = false
    var mainDrained = false
    var workloadReleased = workload == null
    var parentReleased = false
    fun awaitMain() {
        mainDrained = false
        val fence = FutureTask { Unit }
        check(Handler(Looper.getMainLooper()).post(fence))
        fence.get(15, TimeUnit.SECONDS)
        mainDrained = true
    }
    var failure: Throwable? = null
    val callbackLock = Any()
    var callbackFailure: Throwable? = null
    fun callbackFailed(error: Throwable) = synchronized(callbackLock) {
        val primary = callbackFailure
        if (primary == null) callbackFailure = error
        else if (primary !== error) primary.addSuppressed(error)
    }
    val terminal = CountDownLatch(1)
    val positions = DoubleArray(2048)
    val times = LongArray(2048)
    var progressCount = 0
    var progressOverflow = false
    var started = 0
    var finished = 0
    var errors = 0
    var startedAt = 0L
    var finishedAt = 0L
    // Fixed primitive arrays avoid per-sample allocations or main-thread work while audio plays.
    val sampleTimes = LongArray(4096)
    val sampleCosts = LongArray(4096)
    val sampleHeads = LongArray(4096)
    val sampleUnderruns = IntArray(4096)
    val sampleStates = IntArray(4096)
    var sampleCount = 0
    var sampleOverflow = false
    var unavailableSamples = 0
    var observedTrack: Any? = null
    var trackChanges = 0
    var trackMetadata: JSONObject? = null
    try {
        workload?.onPreviewCleanupArmed()
        if (workload == null) check(root.mkdir()) { "Synthetic PCM directory already exists" }
        val store = workload?.store ?: PersistentAudioChunkStore(root, overwriteOldest = false)
        source = store
        store.configure(RetentionMode.SIZE, if (workload == null) 16_777_216L else 33_554_432L,
            case.sampleRate, case.channels, case.format)
        val frameBytes = case.channels * case.format.bytesPerSample
        val bytes = ByteArray(32_768 / frameBytes * frameBytes)
        val encoded = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val expected = MessageDigest.getInstance("SHA-256")
        var frame = 0
        while (frame < case.sampleRate * 20) {
            val frames = minOf(bytes.size / frameBytes, case.sampleRate * 20 - frame)
            encoded.clear()
            for (index in 0 until frames) {
                val sourceFrame = frame + index
                val period = case.sampleRate / 200
                val phase = sourceFrame % period
                val triangle = if (phase < period / 2) phase - period / 4 else period * 3 / 4 - phase
                val value = triangle.toFloat() / (period / 4) * 0.125f
                val oppositePhase = sourceFrame % case.sampleRate < case.sampleRate / 5
                for (channel in 0 until case.channels) {
                    val channelValue = if (channel == 1 && oppositePhase) -value else value
                    if (case.format == PcmSampleFormat.PCM_FLOAT) encoded.putFloat(channelValue)
                    else encoded.putShort((channelValue * 32768f).toInt().toShort())
                }
            }
            val count = frames * frameBytes
            check(store.append(bytes, 0, count) == count)
            expected.update(bytes, 0, count)
            frame += frames
        }
        store.sealActiveChunk()
        check(kotlin.math.abs(store.durationSeconds() - 20.0) < 1.0 / case.sampleRate)
        val expectedDigest = expected.digest()
        result.put("source_pcm_sha256", expectedDigest.toHexString())
        val parent = ReverbService.TimelineSnapshot(checkNotNull(store.acquireRange(0.0, 20.0)), ::callbackFailed)
        snapshot = parent
        val controller = TimelineAudioPreviewController(::callbackFailed)
        preview = controller
        // onFinished precedes stream's finally; callbacks alone cannot authorize store close.
        // Only immutable observations leave the controller's native-owner lifetime gate.
        fun observeTrack() {
            val before = SystemClock.elapsedRealtimeNanos()
            val track = controller.observeTrack()
            val after = SystemClock.elapsedRealtimeNanos()
            if (track == null) {
                unavailableSamples++
                return
            }
            if (observedTrack !== track.identity) {
                if (observedTrack != null) trackChanges++
                observedTrack = track.identity
                trackMetadata = JSONObject().put("sample_rate", track.sampleRate)
                    .put("channels", track.channelCount).put("buffer_frames", track.bufferSizeInFrames)
                    .put("capacity_frames", track.bufferCapacityInFrames).put("session_id", track.audioSessionId)
                    .put("route_id", JSONObject.NULL).put("route_type", JSONObject.NULL)
                    .put("observation", "immutable lifetime-guarded controller sample; route not sampled")
            }
            if (sampleCount < sampleTimes.size) {
                sampleTimes[sampleCount] = before
                sampleCosts[sampleCount] = after - before
                sampleHeads[sampleCount] = track.playbackHeadFrames
                sampleUnderruns[sampleCount] = track.underrunCount
                sampleStates[sampleCount] = track.playState
                sampleCount++
            } else sampleOverflow = true
        }
        fun executor(name: String) = TimelineAudioPreviewController::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.get(controller) as ExecutorService
        worker = executor("executor")
        controlWorker = executor("controlExecutor")
        releaseWorker = executor("releaseExecutor")
        result.put("submission_uptime_ms", SystemClock.uptimeMillis())
        File(evidence, "result.json").writeText(result.toString(2))
        workload?.beforePlay()
        submitted = true
        val accepted = controller.play(parent, startSeconds, endSeconds,
            onProgress = { position ->
                if (progressCount < positions.size) {
                    positions[progressCount] = position
                    times[progressCount] = SystemClock.uptimeMillis()
                    progressCount++
                } else progressOverflow = true
            },
            onStarted = { started++; startedAt = SystemClock.uptimeMillis() },
            onFinished = { finished++; finishedAt = SystemClock.uptimeMillis(); terminal.countDown() },
            onError = { error -> errors++; callbackFailed(error); terminal.countDown() },
        )
        submitted = accepted
        check(accepted) { "Actual preview submission rejected" }
        val deadline = SystemClock.elapsedRealtime() + 60_000L
        while (!terminal.await(20, TimeUnit.MILLISECONDS)) {
            observeTrack()
            check(SystemClock.elapsedRealtime() < deadline) { "Actual preview terminal callback timed out" }
        }
        observeTrack()
        checkNotNull(worker).submit {}.get(15, TimeUnit.SECONDS)
        workerDrained = true
        checkNotNull(controlWorker).submit {}.get(15, TimeUnit.SECONDS)
        controlDrained = true
        checkNotNull(releaseWorker).submit {}.get(15, TimeUnit.SECONDS)
        releaseDrained = true
        awaitMain()
        workload?.afterPlayback()
        synchronized(callbackLock) { callbackFailure }?.let { throw it }
        check(started == 1 && finished == 1 && errors == 0) { "Unexpected preview callback counts" }
        check(!progressOverflow && progressCount > 0) { "Preview progress missing or exceeded bounded evidence" }
        var previous = startSeconds
        for (index in 0 until progressCount) {
            check(positions[index].isFinite() && positions[index] >= previous && positions[index] <= endSeconds)
            previous = positions[index]
        }
        check(previous > startSeconds) { "Preview playback head never advanced" }
        check(sampleCount >= 10 && !sampleOverflow && trackChanges == 0) { "Native track sampling incomplete" }
        checkNotNull(trackMetadata).let { metadata ->
            check(metadata.getInt("sample_rate") == 24_000 && metadata.getInt("channels") == case.channels) {
                "Actual preview track format differs from selected source channels"
            }
        }
        val retainedDigest = MessageDigest.getInstance("SHA-256")
        var retainedBytes = 0L
        checkNotNull(store.acquireRange(0.0, 20.0)).use { lease ->
            lease.readNormalized(case.sampleRate, case.channels, case.format) { array, offset, count ->
                retainedDigest.update(array, offset, count)
                retainedBytes += count
                count
            }
        }
        check(retainedBytes == case.sampleRate.toLong() * 20 * frameBytes && retainedDigest.digest().contentEquals(expectedDigest)) {
            "Preview changed synthetic source PCM"
        }
        result.put("source_pcm_preserved", true)
    } catch (error: Throwable) {
        failure = error
    } finally {
        fun cleanup(action: () -> Unit) {
            try { action() } catch (error: Throwable) {
                val primary = failure
                if (primary == null) failure = error else if (primary !== error) primary.addSuppressed(error)
            }
        }
        // On uncertainty cancel this controller only, then fence. Never reset or delete failed PCM.
        if (failure != null) cleanup { preview?.stop() }
        if (!submitted) workerDrained = true
        if (!workerDrained) cleanup {
            checkNotNull(worker).submit {}.get(15, TimeUnit.SECONDS)
            workerDrained = true
        }
        if (workerDrained && !controlDrained) cleanup {
            controlWorker?.submit {}?.get(15, TimeUnit.SECONDS)
            controlDrained = true
        }
        if (controlDrained && !releaseDrained) cleanup {
            releaseWorker?.submit {}?.get(15, TimeUnit.SECONDS)
            releaseDrained = true
        }
        cleanup { preview?.close() }
        if (workerDrained) {
            cleanup { awaitMain() }
            cleanup { workload?.afterPlayback() }
            cleanup { snapshot?.close(); parentReleased = true }
            if (workload == null) cleanup { source?.close() }
            else cleanup {
                check(parentReleased && controlDrained && releaseDrained && mainDrained) { "Preview reader/release quiescence unknown" }
                workload.afterReadersReleased()
                workloadReleased = true
            }
        } else cleanup { error("Preview worker quiescence unknown; parent/store left open and PCM retained") }
        cleanup { synchronized(callbackLock) { callbackFailure }?.let { throw it } }
        result.put("worker_drained", workerDrained).put("control_worker_drained", controlDrained)
            .put("release_worker_drained", releaseDrained)
            .put("main_callbacks_drained", mainDrained)
            .put("started_callbacks", if (mainDrained) started else JSONObject.NULL)
            .put("finished_callbacks", if (mainDrained) finished else JSONObject.NULL)
            .put("error_callbacks", if (mainDrained) errors else JSONObject.NULL)
            .put("started_uptime_ms", if (mainDrained) startedAt else JSONObject.NULL)
            .put("finished_uptime_ms", if (mainDrained) finishedAt else JSONObject.NULL)
        val progress = JSONArray()
        if (mainDrained) {
            for (index in 0 until progressCount) progress.put(JSONObject()
                .put("uptime_ms", times[index]).put("source_seconds", positions[index]))
        }
        result.put("progress", progress).put("progress_overflow", if (mainDrained) progressOverflow else JSONObject.NULL)
        val nativeSamples = JSONArray()
        var midstreamUnderruns = 0L
        var midstreamPairs = 0
        var firstMiddleHead = -1L
        var lastMiddleHead = -1L
        var maximumHead = -1L
        var maximumObservationGapNanos = 0L
        var nativeHeadsMonotonic = true
        val tailMargin = maxOf(6_000, trackMetadata?.optInt("buffer_frames", 6_000) ?: 6_000)
        // At 24 kHz this permits 100 ms of observer uncertainty near boundaries.
        // It is a coverage requirement, not evidence that a shorter stream is sample-exact.
        val boundaryToleranceFrames = 2_400L
        for (index in 0 until sampleCount) {
            val head = sampleHeads[index]
            val midstream = head >= 6_000 && head < 384_000 - tailMargin
            maximumHead = maxOf(maximumHead, head)
            if (index > 0) {
                maximumObservationGapNanos = maxOf(maximumObservationGapNanos,
                    sampleTimes[index] - sampleTimes[index - 1])
                if (head < sampleHeads[index - 1]) nativeHeadsMonotonic = false
            }
            if (midstream) {
                if (firstMiddleHead < 0) firstMiddleHead = head
                lastMiddleHead = head
            }
            nativeSamples.put(JSONObject().put("elapsed_ns", sampleTimes[index])
                .put("getter_cost_ns", sampleCosts[index]).put("head_frames", head)
                .put("underruns", sampleUnderruns[index]).put("play_state", sampleStates[index])
                .put("phase", if (head < 6_000) "startup" else if (midstream) "midstream" else "tail"))
            if (index > 0 && midstream && sampleHeads[index - 1] >= 6_000 &&
                sampleHeads[index - 1] < 384_000 - tailMargin) {
                midstreamPairs++
                val delta = sampleUnderruns[index].toLong() - sampleUnderruns[index - 1]
                if (delta >= 0) midstreamUnderruns += delta
                else cleanup { error("Native underrun counter regressed") }
            }
        }
        result.put("native_samples", nativeSamples).put("track", trackMetadata ?: JSONObject.NULL)
            .put("sample_count", sampleCount).put("sample_overflow", sampleOverflow)
            .put("unavailable_samples", unavailableSamples).put("track_changes", trackChanges)
            .put("midstream_pairs", midstreamPairs).put("midstream_underrun_delta", midstreamUnderruns)
            .put("first_midstream_head", firstMiddleHead).put("last_midstream_head", lastMiddleHead)
            .put("maximum_playback_head_observed", maximumHead)
            .put("maximum_observation_gap_ns", maximumObservationGapNanos)
            .put("native_heads_monotonic", nativeHeadsMonotonic)
            .put("coverage_boundary_tolerance_frames", boundaryToleranceFrames)
            .put("underrun_scope", "Counter deltas only between observed heads from 250 ms to max(buffer,250 ms) before end; startup and tail retained separately. Sampling can miss terminal data and does not measure exact writes, CRC time, or sound.")
        val coverageComplete = sampleCount >= 10 && !sampleOverflow && trackChanges == 0 &&
            nativeHeadsMonotonic && midstreamPairs > 0 &&
            firstMiddleHead in 6_000L..(6_000L + boundaryToleranceFrames) &&
            lastMiddleHead >= 384_000L - tailMargin - boundaryToleranceFrames &&
            maximumHead >= 384_000L - boundaryToleranceFrames && maximumHead <= 384_000L
        result.put("underrun_metadata", if (coverageComplete) "OBSERVED_WITH_BOUNDED_EDGE_COVERAGE" else "INCOMPLETE")
            .put("native_coverage_complete", coverageComplete)
        if (!coverageComplete) cleanup { error("Native preview observation coverage incomplete") }
        if (midstreamUnderruns > 0) cleanup { error("Actual midstream preview underruns: $midstreamUnderruns") }
        cleanup {
            check(workloadReleased) { "Concurrent capture source stability unknown; files retained without a frozen hash claim" }
            val files = JSONArray()
            if (root.exists()) for (file in root.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(root).path }) {
                val digest = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { input ->
                    val block = ByteArray(65_536)
                    while (true) {
                        val count = input.read(block)
                        if (count < 0) break
                        digest.update(block, 0, count)
                    }
                }
                files.put(JSONObject().put("path", file.relativeTo(root).path)
                    .put("bytes", file.length()).put("sha256", digest.digest().toHexString()))
            }
            result.put("source_files", files)
        }
        result.put("passed", failure == null).put("failure", failure?.stackTraceToString() ?: JSONObject.NULL)
        cleanup { File(evidence, "result.json").writeText(result.toString(2)) }
    }
    failure?.let { throw it }
    report.append("PASS range_preview_continuity ").append(case.name)
        .append(" native counters, transport callbacks and source preservation; audible continuity unverified\n")
    return File(evidence, "result.json")
}

/** Explicit short selectors only; the original three 16-second cases are never called here. */
internal fun verifyShortRangePreview(instrumentation: Instrumentation, report: StringBuilder, selector: String) {
    val context = instrumentation.targetContext
    check(context.packageName == "app.smallthingz.reverb.uitheme.reliability" &&
        context.applicationContext.javaClass == Application::class.java) {
        "Short preview fixtures require the isolated current-QA package with plain Application"
    }
    check(selector in setOf("range_preview_short_end", "range_preview_short_controls", "range_preview_short_retirement"))
    check(Looper.myLooper() != Looper.getMainLooper())
    val evidence = File(context.filesDir, "$selector-${UUID.randomUUID()}")
    check(evidence.mkdir())
    val root = File(evidence, "synthetic-pcm")
    check(root.mkdir())
    val result = JSONObject().put("selector", selector).put("passed", false)
        .put("source_duration_ms", 1_000).put("source_sample_rate", 48_000)
        .put("source_channels", 1).put("source_format", "PCM_16")
        .put("production_head", "BOUND_BY_HOST_ARTIFACT_RECEIPT")
        .put("microphone_or_capture_service", "NOT_USED")
        .put("audible_quality", "UNVERIFIED").put("hardware_pipeline_latency", "UNMEASURED")
        .put("source_directory", root.absolutePath).put("source_pcm_preserved", false)
    val resultFile = File(evidence, "result.json")
    resultFile.writeText(result.toString(2))
    report.append("PARITY_EVIDENCE ").append(evidence.name).append("/result.json\n")
    val failures = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
    fun callbackFailure(error: Throwable) { failures.add(error) }
    fun <T> onMain(action: () -> T): T {
        val task = FutureTask<T> { action() }
        check(Handler(Looper.getMainLooper()).post(task))
        return task.get(15, TimeUnit.SECONDS)
    }
    class Run(val label: String, val start: Double, val end: Double) {
        val retired = AtomicBoolean(false)
        val started = AtomicInteger()
        val finished = AtomicInteger()
        val errors = AtomicInteger()
        val staleCallbacks = AtomicInteger()
        val startedAt = AtomicLong()
        val latest = AtomicReference<PreviewPlaybackSample?>(null)
        val progress = JSONArray() // Main writes; read only after the Main fence.
        var maximumHead = 0L // Instrumentation thread only.
        var nativeSamples = 0
    }
    val runs = ArrayList<Run>()
    val currentRun = AtomicReference<Run?>(null)
    val nativeSamples = JSONArray()
    val identities = java.util.IdentityHashMap<Any, Int>()
    var source: PersistentAudioChunkStore? = null
    var snapshot: ReverbService.TimelineSnapshot? = null
    var preview: TimelineAudioPreviewController? = null
    var worker: ExecutorService? = null
    var controlWorker: ExecutorService? = null
    var releaseWorker: ExecutorService? = null
    var closed = false
    var workerDrained = false
    var controlDrained = false
    var releaseDrained = false
    var mainDrained = false
    var observer: Thread? = null
    val observeConcurrently = AtomicBoolean(false)
    val concurrentObservations = AtomicInteger()
    val successfulConcurrentObservations = AtomicInteger()
    val closedForObserver = AtomicBoolean(false)
    val postCloseNullObserved = CountDownLatch(1)
    var failure: Throwable? = null
    var sourceDigest: ByteArray? = null
    try {
        val pcm = ByteArray(48_000 * 2)
        val encoded = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        repeat(48_000) { frame -> encoded.putShort((((frame % 240) - 120) * 24).toShort()) }
        val expectedDigest = MessageDigest.getInstance("SHA-256").digest(pcm)
        sourceDigest = expectedDigest
        result.put("source_pcm_sha256", expectedDigest.toHexString())
        val store = PersistentAudioChunkStore(root, overwriteOldest = false)
        source = store
        store.configure(RetentionMode.SIZE, 2_097_152L, 48_000, 1, PcmSampleFormat.PCM_16)
        check(store.append(pcm, 0, pcm.size) == pcm.size)
        store.sealActiveChunk()
        check(kotlin.math.abs(store.durationSeconds() - 1.0) < 1.0 / 48_000)
        val parent = ReverbService.TimelineSnapshot(checkNotNull(store.acquireRange(0.0, 1.0)), ::callbackFailure)
        snapshot = parent
        val controller = TimelineAudioPreviewController(::callbackFailure)
        preview = controller
        // Executor reflection exposes only QA fences, never an AudioTrack or its native lifetime.
        fun executor(name: String) = TimelineAudioPreviewController::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.get(controller) as ExecutorService
        worker = executor("executor"); controlWorker = executor("controlExecutor"); releaseWorker = executor("releaseExecutor")
        fun observe() {
            val sample = controller.observeTrack() ?: return
            check(sample.sampleRate == 24_000 && sample.channelCount == 1)
            val identity = identities[sample.identity] ?: (identities.size + 1).also { identities[sample.identity] = it }
            val run = checkNotNull(currentRun.get()) { "Track remained published without a current fixture command" }
            val expectedFrames = ((run.end - run.start) * sample.sampleRate).roundToLong()
            check(sample.playbackHeadFrames in 0L..expectedFrames) { "Native head passed the selected frame boundary" }
            check(sample.acceptedWriteFrames in sample.playbackHeadFrames..expectedFrames) {
                "Accepted native writes did not match the bounded playback interval"
            }
            if (run.label == "below-buffer-preview") check(expectedFrames < sample.bufferCapacityInFrames) {
                "Short preview was not smaller than its observed native buffer capacity"
            }
            run.maximumHead = maxOf(run.maximumHead, sample.playbackHeadFrames)
            run.nativeSamples++
            check(nativeSamples.length() < 4_096) { "Bounded native evidence overflow" }
            nativeSamples.put(JSONObject().put("elapsed_ms", SystemClock.elapsedRealtime())
                .put("run", run.label).put("identity", identity).put("epoch", sample.epoch)
                .put("head_frames", sample.playbackHeadFrames).put("expected_frames", expectedFrames)
                .put("sample_rate", sample.sampleRate).put("play_state", sample.playState)
                .put("underruns", sample.underrunCount).put("buffer_frames", sample.bufferSizeInFrames)
                .put("buffer_capacity_frames", sample.bufferCapacityInFrames)
                .put("start_threshold_frames", sample.startThresholdInFrames ?: JSONObject.NULL)
                .put("accepted_write_frames", sample.acceptedWriteFrames))
        }
        fun await(label: String, condition: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 10_000L
            while (!condition()) {
                observe()
                failures.peek()?.let { throw it }
                check(SystemClock.elapsedRealtime() < deadline) { "$label timed out" }
                Thread.sleep(4L)
            }
        }
        fun launch(label: String, start: Double, end: Double): Run {
            val run = Run(label, start, end)
            runs.add(run)
            fun callback(action: () -> Unit) {
                if (run.retired.get()) { run.staleCallbacks.incrementAndGet(); return }
                try { action() } catch (error: Throwable) { callbackFailure(error) }
            }
            onMain {
                currentRun.get()?.retired?.set(true)
                currentRun.set(run)
                check(controller.play(parent, start, end,
                    onStarted = { callback { run.started.incrementAndGet(); run.startedAt.set(SystemClock.elapsedRealtime()) } },
                    onFinished = { callback { run.finished.incrementAndGet() } },
                    onError = { error -> callback { run.errors.incrementAndGet(); callbackFailure(error) } },
                    onClockSample = { sample -> callback {
                        val previous = run.latest.get()?.positionSeconds ?: start
                        check(sample.positionSeconds >= previous && sample.positionSeconds <= end + 0.000001)
                        check(sample.queuedUntilSeconds >= sample.positionSeconds && sample.queuedUntilSeconds <= end + 0.000001)
                        check(sample.sampledAtElapsedRealtimeMillis <= SystemClock.elapsedRealtime())
                        check(sample.acceptedWriteFrames in 1L..((end - start) * 24_000).roundToLong())
                        check(run.progress.length() < 512) { "Bounded progress evidence overflow" }
                        run.latest.set(sample)
                        run.progress.put(JSONObject().put("sampled_elapsed_ms", sample.sampledAtElapsedRealtimeMillis)
                            .put("delivered_elapsed_ms", SystemClock.elapsedRealtime())
                            .put("source_seconds", sample.positionSeconds).put("queued_until_seconds", sample.queuedUntilSeconds)
                            .put("accepted_write_frames", sample.acceptedWriteFrames))
                    } },
                )) { "Short preview submission rejected" }
            }
            return run
        }
        fun awaitRunning(run: Run) = await("${run.label} native start") {
            run.started.get() == 1 && run.maximumHead > 0 && controller.playbackActive
        }
        fun awaitFinished(run: Run, requireNativeObservation: Boolean = true) {
            await("${run.label} finish") { run.finished.get() == 1 }
            onMain {}
            check(run.started.get() == 1 && run.errors.get() == 0 && (!requireNativeObservation || run.nativeSamples > 0))
            check(!controller.playbackActive) { "Worker still active after finished callback" }
            val last = checkNotNull(run.latest.get()) { "Actual-head clock sample missing" }
            check(last.acceptedWriteFrames == ((run.end - run.start) * 24_000).roundToLong()) {
                "Terminal accepted-write count did not match the exact selected interval"
            }
            check(kotlin.math.abs(last.positionSeconds - run.end) <= 1.0 / 24_000) {
                "Terminal consumed-head sample did not reach selected end"
            }
        }
        when (selector) {
            "range_preview_short_end" -> {
                val run = launch("selected-end", .125, .875)
                awaitRunning(run)
                val entered = CountDownLatch(1)
                val resumeMain = CountDownLatch(1)
                val releasedNormally = AtomicBoolean(false)
                check(Handler(Looper.getMainLooper()).post {
                    entered.countDown()
                    releasedNormally.set(resumeMain.await(2, TimeUnit.SECONDS))
                })
                try {
                    check(entered.await(2, TimeUnit.SECONDS))
                    await("native end while Main callback is delayed") { !controller.playbackActive }
                    check(run.finished.get() == 0) { "Main was not delayed across the native end" }
                    result.put("native_end_independent_of_main_callback", true)
                } finally { resumeMain.countDown() }
                awaitFinished(run)
                check(releasedNormally.get()) { "Main-delay probe exceeded its finite two-second hold" }
            }
            "range_preview_short_controls" -> {
                val first = launch("play-before-pause", .05, .95)
                awaitRunning(first)
                onMain { first.retired.set(true); controller.stop(); currentRun.set(null) }
                check(!controller.playbackActive && controller.observeTrack() == null)
                val seek = launch("seek-after-pause", .60, .90)
                awaitFinished(seek)
                val tiny = launch("below-buffer-preview", .20, .22)
                // The20ms selection may finish before the observation worker gets a turn.
                // Its mandatory terminal clock still comes from the actual consumed native head.
                awaitFinished(tiny, requireNativeObservation = false)
                result.put("below_buffer_preview_finished", true)
                    .put("below_buffer_native_observation", tiny.nativeSamples > 0)
                    .put("below_buffer_selected_frames", 480)
                    .put("below_buffer_requested_minimum_frames", 1_200)
                val shuttleCommands = JSONArray()
                val shuttleSamples = JSONArray()
                result.put("shuttle_commands", shuttleCommands).put("shuttle_samples", shuttleSamples)
                    .put("stable_shuttle_head_advanced", false)
                var phase = "initial"
                var phaseCommandAt = SystemClock.elapsedRealtime()
                var lastShuttleIdentity: Any? = null
                var lastShuttleHead = 0L
                var setupObservations = 0
                var advancingSamples = 0
                fun observeShuttle(): PreviewTrackObservation? {
                    val sample = controller.observeTrack() ?: return null
                    check(sample.channelCount == 1 && sample.sampleRate in listOf(48_000, 96_000))
                    val identity = identities[sample.identity] ?: (identities.size + 1).also { identities[sample.identity] = it }
                    val changed = lastShuttleIdentity !== sample.identity
                    if (changed) { setupObservations++; lastShuttleHead = 0L }
                    if (sample.playbackHeadFrames > lastShuttleHead) advancingSamples++
                    result.put("observed_shuttle_setups", setupObservations)
                        .put("shuttle_advancing_samples", advancingSamples).put("last_shuttle_phase", phase)
                    check(shuttleSamples.length() < 4_096)
                    shuttleSamples.put(JSONObject().put("phase", phase).put("elapsed_ms", SystemClock.elapsedRealtime())
                        .put("since_command_ms", SystemClock.elapsedRealtime() - phaseCommandAt)
                        .put("identity", identity).put("epoch", sample.epoch).put("head_frames", sample.playbackHeadFrames)
                        .put("sample_rate", sample.sampleRate).put("underruns", sample.underrunCount)
                        .put("play_state", sample.playState).put("buffer_frames", sample.bufferSizeInFrames)
                        .put("buffer_capacity_frames", sample.bufferCapacityInFrames)
                        .put("start_threshold_frames", sample.startThresholdInFrames ?: JSONObject.NULL)
                        .put("accepted_write_frames", sample.acceptedWriteFrames)
                        .put("new_observed_owner", changed))
                    lastShuttleIdentity = sample.identity
                    lastShuttleHead = sample.playbackHeadFrames
                    return sample
                }
                fun awaitShuttleHead(label: String, condition: (PreviewTrackObservation) -> Boolean): PreviewTrackObservation {
                    val deadline = SystemClock.elapsedRealtime() + 10_000L
                    while (true) {
                        val sample = observeShuttle()
                        if (sample != null && condition(sample)) return sample
                        failures.peek()?.let { throw it }
                        check(SystemClock.elapsedRealtime() < deadline) { "$label: no advancing native output before fixture deadline" }
                        Thread.sleep(4L)
                    }
                }
                onMain {
                    tiny.retired.set(true); currentRun.set(null)
                    controller.startShuttle(parent, .4, .5f, .30, .70)
                }
                val initial = awaitShuttleHead("Initial shuttle") { it.playbackHeadFrames > 0L }
                // This interior +0.5x command admits one 24ms output grain: its body plus
                // the once-emitted pending tail. Drain ALL of it before testing new output.
                val initialGrainFrames = initial.sampleRate.toLong() * 24L / 1_000L
                val drained = awaitShuttleHead("Initial shuttle grain drain") {
                    check(it.identity === initial.identity && it.epoch == initial.epoch)
                    check(it.playbackHeadFrames <= initialGrainFrames) { "Unchanged initial target replayed audio" }
                    it.playbackHeadFrames == initialGrainFrames
                }
                val initialHead = drained.playbackHeadFrames
                result.put("initial_grain_output_frame_budget", initialGrainFrames)
                    .put("initial_drained_head_frames", initialHead)
                repeat(4) { index ->
                    phase = "expand-$index"; phaseCommandAt = SystemClock.elapsedRealtime()
                    val lower = (300 - (index + 1) * 25) / 1_000.0
                    val upper = (700 + (index + 1) * 25) / 1_000.0
                    shuttleCommands.put(JSONObject().put("phase", phase).put("elapsed_ms", phaseCommandAt)
                        .put("start_seconds", lower).put("end_seconds", upper))
                    onMain { controller.updateShuttle(.41 + index * .01, .5f, lower, upper) }
                    val until = SystemClock.elapsedRealtime() + 33L
                    while (SystemClock.elapsedRealtime() < until) {
                        observeShuttle()?.let { sample ->
                            check(sample.identity === initial.identity && sample.epoch == initial.epoch) {
                                "Same-direction bounds expansion replaced the native owner"
                            }
                        }
                        Thread.sleep(4L)
                    }
                }
                val expanded = awaitShuttleHead("Expanded shuttle") {
                    check(it.identity === initial.identity && it.epoch == initial.epoch)
                    it.playbackHeadFrames > initialHead
                }
                result.put("expansion_retained_native_identity", true)
                    .put("expansion_head_frames_advanced", expanded.playbackHeadFrames - initialHead)
                val advancingBeforeContractions = advancingSamples
                repeat(8) { index ->
                    phase = "contract-$index"; phaseCommandAt = SystemClock.elapsedRealtime()
                    val lower = (200 + (index + 1) * 20) / 1_000.0
                    val upper = (800 - (index + 1) * 20) / 1_000.0
                    shuttleCommands.put(JSONObject().put("phase", phase).put("elapsed_ms", phaseCommandAt)
                        .put("start_seconds", lower).put("end_seconds", upper))
                    onMain { controller.updateShuttle(.44 + index * .005, .5f, lower, upper) }
                    val until = SystemClock.elapsedRealtime() + 33L
                    while (SystemClock.elapsedRealtime() < until) { observeShuttle(); Thread.sleep(4L) }
                }
                val contractionAdvances = advancingSamples - advancingBeforeContractions
                result.put("contraction_advancing_samples", contractionAdvances)
                    .put("contraction_window_had_no_observed_output_progress", contractionAdvances == 0)
                phase = "stable-last-bounds"; phaseCommandAt = SystemClock.elapsedRealtime()
                val beforeStable = controller.observeTrack()
                onMain { controller.updateShuttle(.50, .5f, .36, .64) }
                val stableHead = awaitShuttleHead("Stable last shuttle bounds") {
                    it.playbackHeadFrames > if (beforeStable != null && it.identity === beforeStable.identity)
                        beforeStable.playbackHeadFrames else 0L
                }
                check(stableHead.identity !== initial.identity && stableHead.epoch > initial.epoch) {
                    "Contracted bounds retained the pre-contraction native owner"
                }
                result.put("stable_shuttle_head_advanced", true)
                    .put("stable_shuttle_first_advance_delay_ms", SystemClock.elapsedRealtime() - phaseCommandAt)
                    .put("stable_shuttle_head_frames", stableHead.playbackHeadFrames)
                    .put("observed_shuttle_setups", setupObservations)
                    .put("shuttle_advancing_samples", advancingSamples)
                onMain { controller.stopShuttle() }
                check(controller.observeTrack() == null)
                val fastRuns = JSONArray()
                result.put("fast_shuttle_runs", fastRuns)
                    .put("fast_shuttle_sampling", "paced consumed-body acknowledgements; not continuous-audibility evidence")
                var previousFastOwner: Any? = null
                var fastSampleRate = 0
                for (direction in listOf(1, -1)) {
                    val rate = direction * 2f
                    val label = if (direction > 0) "fast-forward" else "fast-reverse"
                    val startTarget = if (direction > 0) .38 else .62
                    val fastResult = JSONObject().put("phase", label).put("rate", rate)
                        .put("start_seconds", .36).put("end_seconds", .64).put("passed", false)
                        .put("expected_retained_tail_updates", 7)
                    val deliveries = JSONArray()
                    fastResult.put("commands", deliveries); fastRuns.put(fastResult)
                    phase = "$label-0"; phaseCommandAt = SystemClock.elapsedRealtime()
                    shuttleCommands.put(JSONObject().put("phase", phase).put("elapsed_ms", phaseCommandAt)
                        .put("kind", "start").put("rate", rate).put("target_seconds", startTarget)
                        .put("start_seconds", .36).put("end_seconds", .64))
                    onMain { controller.startShuttle(parent, startTarget, rate, .36, .64) }
                    val fastOwner = awaitShuttleHead("$label initial body") { it.playbackHeadFrames > 0L }
                    check(fastOwner.identity !== previousFastOwner) { "Direction restart retained its retired native owner" }
                    previousFastOwner = fastOwner.identity
                    fastSampleRate = maxOf(fastSampleRate, fastOwner.sampleRate)
                    val bodyFrames = fastOwner.sampleRate.toLong() * 16L / 1_000L
                    val initialUnderruns = fastOwner.underrunCount
                    var finalUnderruns = initialUnderruns
                    repeat(8) { index ->
                        val target = startTarget + direction * index * .032
                        if (index > 0) {
                            phase = "$label-$index"; phaseCommandAt = SystemClock.elapsedRealtime()
                            shuttleCommands.put(JSONObject().put("phase", phase).put("elapsed_ms", phaseCommandAt)
                                .put("kind", "update").put("rate", rate).put("target_seconds", target)
                                .put("start_seconds", .36).put("end_seconds", .64))
                            onMain { controller.updateShuttle(target, rate, .36, .64) }
                        }
                        val expectedFrames = bodyFrames * (index + 1)
                        val consumed = awaitShuttleHead("$label body $index") {
                            check(it.identity === fastOwner.identity && it.epoch == fastOwner.epoch) {
                                "Same-direction fast updates replaced the native owner"
                            }
                            check(it.playbackHeadFrames <= expectedFrames && it.acceptedWriteFrames <= expectedFrames) {
                                "Fast command emitted more than one 16 ms body or replayed an old target"
                            }
                            it.playbackHeadFrames == expectedFrames && it.acceptedWriteFrames == expectedFrames
                        }
                        finalUnderruns = consumed.underrunCount
                        val consumedAt = SystemClock.elapsedRealtime()
                        deliveries.put(JSONObject().put("index", index).put("target_seconds", target)
                            .put("command_elapsed_ms", phaseCommandAt)
                            .put("consumed_elapsed_ms", consumedAt)
                            .put("command_to_consumed_ms", consumedAt - phaseCommandAt)
                            .put("head_frames", consumed.playbackHeadFrames)
                            .put("accepted_write_frames", consumed.acceptedWriteFrames)
                            .put("underruns", consumed.underrunCount))
                    }
                    fastResult.put("passed", true).put("sample_rate", fastOwner.sampleRate)
                        .put("epoch", fastOwner.epoch).put("native_identity", identities[fastOwner.identity])
                        .put("body_frames", bodyFrames).put("consumed_body_frames", bodyFrames * 8)
                        .put("native_owner_retained_across_updates", true)
                        .put("underruns_at_first_observation", initialUnderruns)
                        .put("underruns_after_updates", finalUnderruns)
                        .put("observed_underrun_delta", finalUnderruns - initialUnderruns)
                    onMain { controller.stopShuttle() }
                    check(controller.observeTrack() == null)
                }
                // Isolate the cached-helper measurement from our own native transport/cleanup workers.
                checkNotNull(worker).submit {}.get(15, TimeUnit.SECONDS)
                checkNotNull(controlWorker).submit {}.get(15, TimeUnit.SECONDS)
                checkNotNull(releaseWorker).submit {}.get(15, TimeUnit.SECONDS)
                onMain {}
                val helperCosts = JSONArray()
                result.put("phase_helper_cost", JSONObject().put("sample_rate", fastSampleRate)
                    .put("sample_rate_selection", "highest sample rate actually observed in the two fast native runs")
                    .put("warmup_calls_per_case", 8).put("measured_calls_per_case", 16)
                    .put("scope", "exact helper on cached synthetic PCM; instrumentation-thread CPU, not audio-worker timing")
                    .put("allocation_scope", "process-wide ART counter delta; includes stats reads and other QA threads")
                    .put("cases", helperCosts))
                for (channels in 1..2) {
                    val cached = ByteArray(fastSampleRate * channels * 2)
                    val cacheBuffer = ByteBuffer.wrap(cached).order(ByteOrder.LITTLE_ENDIAN)
                    repeat(fastSampleRate) { frame -> repeat(channels) { channel ->
                        cacheBuffer.putShort((.6 * kotlin.math.sin(2 * Math.PI * 243.05555555555554 *
                            frame / fastSampleRate + channel * .73) * 32767).roundToInt().toShort())
                    } }
                    for (rate in listOf(2f, -2f, 21f, -21f, 4096f, -4096f)) {
                        val grainSeconds = .024 * shuttleAudibleSpeed(rate).toDouble()
                        val firstStart = if (rate > 0f) .25 else .65 - grainSeconds
                        val firstGrain = transformShuttlePcm16(sliceShuttlePcm16(cached, 0.0, firstStart,
                            firstStart + grainSeconds, fastSampleRate, channels), rate, channels)
                        val overlapBytes = (fastSampleRate * .008).roundToInt() * channels * 2
                        val retainedTail = firstGrain.copyOfRange(firstGrain.size - overlapBytes, firstGrain.size)
                        val nextStart = firstStart + if (rate > 0f) .032 else -.032
                        val nominalStart = (nextStart * fastSampleRate).roundToInt()
                        val grainFrames = ((nextStart + grainSeconds) * fastSampleRate).roundToInt() - nominalStart
                        fun align(enabled: Boolean) = alignedShuttleStartFrame(cached, channels, nominalStart,
                            grainFrames, retainedTail, rate, fastSampleRate, enabled,
                            (.05 * fastSampleRate).roundToInt(), (.95 * fastSampleRate).roundToInt())
                        val chosen = align(true)
                        check(chosen != nominalStart) {
                            "Synthetic phase-cost case did not change alignment: $channels channels, rate $rate, $fastSampleRate Hz"
                        }
                        for (enabled in listOf(false, true)) {
                            repeat(8) { align(enabled) }
                            val allocatedBefore = Debug.getRuntimeStat("art.gc.bytes-allocated")?.toLongOrNull()
                            val cpuBefore = Debug.threadCpuTimeNanos()
                            val wallBefore = SystemClock.elapsedRealtimeNanos()
                            var frameChecksum = 0L
                            repeat(16) { frameChecksum += align(enabled) }
                            val wallNanos = SystemClock.elapsedRealtimeNanos() - wallBefore
                            val cpuAfter = Debug.threadCpuTimeNanos()
                            val allocatedAfter = Debug.getRuntimeStat("art.gc.bytes-allocated")?.toLongOrNull()
                            val allocatedDelta = if (allocatedBefore != null && allocatedAfter != null &&
                                allocatedAfter >= allocatedBefore) allocatedAfter - allocatedBefore else null
                            check(frameChecksum == (if (enabled) chosen else nominalStart).toLong() * 16)
                            helperCosts.put(JSONObject().put("channels", channels).put("rate", rate)
                                .put("audible_speed", shuttleAudibleSpeed(rate)).put("alignment_enabled", enabled)
                                .put("nominal_start_frame", nominalStart).put("chosen_start_frame", if (enabled) chosen else nominalStart)
                                .put("grain_frames", grainFrames).put("retained_tail_frames", retainedTail.size / (channels * 2))
                                .put("wall_nanos", wallNanos)
                                .put("thread_cpu_nanos", if (cpuBefore >= 0L && cpuAfter >= cpuBefore) cpuAfter - cpuBefore else JSONObject.NULL)
                                .put("process_allocated_bytes_delta", allocatedDelta ?: JSONObject.NULL)
                                .put("frame_checksum", frameChecksum))
                        }
                    }
                }
                val stable = launch("stable-last-bounds", .36, .64)
                awaitFinished(stable)
                result.put("bounds_expansions", 4).put("bounds_contractions", 8)
                    .put("stable_last_command_completed", true)
                // A DOWN/UP-style play/pause pair in one Main turn must never start later.
                val pendingStarts = AtomicInteger()
                onMain {
                    stable.retired.set(true)
                    check(controller.play(parent, .1, .9, onStarted = { pendingStarts.incrementAndGet() },
                        onFinished = { pendingStarts.incrementAndGet() }, onError = ::callbackFailure))
                    controller.stop()
                    currentRun.set(null)
                }
                checkNotNull(worker).submit {}.get(15, TimeUnit.SECONDS)
                checkNotNull(controlWorker).submit {}.get(15, TimeUnit.SECONDS)
                checkNotNull(releaseWorker).submit {}.get(15, TimeUnit.SECONDS)
                onMain {}
                check(pendingStarts.get() == 0 && !controller.playbackActive && controller.observeTrack() == null)
                result.put("same_turn_play_pause_callbacks", pendingStarts.get())
            }
            "range_preview_short_retirement" -> {
                val run = launch("retired-active-preview", .0, 1.0)
                awaitRunning(run)
                observeConcurrently.set(true)
                observer = Thread({
                    try {
                        while (observeConcurrently.get()) {
                            val afterClose = closedForObserver.get()
                            val sample = controller.observeTrack()
                            if (afterClose) {
                                check(sample == null) { "Retired controller still exposed a native owner" }
                                postCloseNullObserved.countDown()
                            } else if (sample != null) {
                                check(sample.playbackHeadFrames >= 0L)
                                successfulConcurrentObservations.incrementAndGet()
                            }
                            concurrentObservations.incrementAndGet()
                            Thread.sleep(1L)
                        }
                    } catch (error: Throwable) { callbackFailure(error) }
                }, "Reverb-QA-preview-observer").also { it.start() }
                await("concurrent live native observations") { successfulConcurrentObservations.get() >= 4 }
                val closeHead = onMain {
                    check(controller.playbackActive) { "Retirement probe reached an already-finished run" }
                    val sample = checkNotNull(controller.observeTrack()) { "Retirement probe has no live native owner" }
                    check(sample.playbackHeadFrames < 24_000L) { "Retirement probe reached source EOF" }
                    check(controller.playbackActive) { "Retirement probe completed during native observation" }
                    run.retired.set(true)
                    controller.close()
                    closed = true
                    currentRun.set(null)
                    closedForObserver.set(true)
                    sample.playbackHeadFrames
                }
                check(!controller.playbackActive && controller.observeTrack() == null)
                check(!onMain { controller.play(parent, .1, .9,
                    onStarted = { callbackFailure(IllegalStateException("Closed preview started")) },
                    onFinished = { callbackFailure(IllegalStateException("Closed preview finished")) },
                    onError = ::callbackFailure) }) { "Closed preview accepted new playback" }
                check(postCloseNullObserved.await(5, TimeUnit.SECONDS)) {
                    "Concurrent observer did not acknowledge retirement"
                }
                observeConcurrently.set(false)
                observer!!.join(5_000L)
                check(!observer!!.isAlive) { "Native observation did not retire" }
                result.put("post_close_play_rejected", true).put("close_accepted_while_playback_active", true)
                    .put("native_head_at_accepted_close", closeHead).put("observer_acknowledged_post_close_null", true)
            }
        }
    } catch (error: Throwable) { failure = error }
    finally {
        fun cleanup(action: () -> Unit) {
            try { action() } catch (error: Throwable) {
                if (failure == null) failure = error else if (failure !== error) failure!!.addSuppressed(error)
            }
        }
        observeConcurrently.set(false)
        cleanup { observer?.join(5_000L); check(observer?.isAlive != true) }
        cleanup {
            if (!closed) onMain {
                currentRun.get()?.retired?.set(true)
                preview?.close()
                closed = true
                currentRun.set(null)
            }
        }
        // close queues shutdown in stream -> control -> release order. Never close a parent
        // snapshot/store while a native/source reader's terminal ownership is uncertain.
        cleanup { workerDrained = worker?.awaitTermination(15, TimeUnit.SECONDS) ?: true; check(workerDrained) }
        cleanup { controlDrained = controlWorker?.awaitTermination(15, TimeUnit.SECONDS) ?: true; check(controlDrained) }
        cleanup { releaseDrained = releaseWorker?.awaitTermination(15, TimeUnit.SECONDS) ?: true; check(releaseDrained) }
        cleanup { onMain {}; mainDrained = true }
        cleanup { check(preview?.observeTrack() == null && preview?.playbackActive != true) }
        if (workerDrained && controlDrained && releaseDrained && mainDrained) {
            cleanup {
                val store = source
                if (store != null && sourceDigest != null) {
                    val retained = MessageDigest.getInstance("SHA-256")
                    var count = 0L
                    checkNotNull(store.acquireRange(0.0, 1.0)).use { lease ->
                        lease.readNormalized(48_000, 1, PcmSampleFormat.PCM_16) { bytes, offset, size ->
                            retained.update(bytes, offset, size); count += size; size
                        }
                    }
                    check(count == 96_000L && retained.digest().contentEquals(checkNotNull(sourceDigest)))
                    result.put("source_pcm_preserved", true)
                }
            }
            cleanup { snapshot?.close() }
            cleanup { source?.close() }
        } else cleanup { error("Preview quiescence unknown; synthetic parent/store retained") }
        while (true) { val error = failures.poll() ?: break; cleanup { throw error } }
        val runResults = JSONArray()
        if (mainDrained) for (run in runs) {
            cleanup { check(run.staleCallbacks.get() == 0) { "Retired ${run.label} delivered callbacks" } }
            runResults.put(JSONObject().put("label", run.label).put("start_seconds", run.start).put("end_seconds", run.end)
                .put("started", run.started.get()).put("finished", run.finished.get()).put("errors", run.errors.get())
                .put("stale_callbacks", run.staleCallbacks.get()).put("started_elapsed_ms", run.startedAt.get())
                .put("native_samples", run.nativeSamples).put("maximum_native_head", run.maximumHead).put("progress", run.progress))
        }
        result.put("runs", runResults).put("native_samples", nativeSamples).put("native_track_identities", identities.size)
            .put("concurrent_observation_calls", concurrentObservations.get())
            .put("concurrent_live_observations", successfulConcurrentObservations.get())
            .put("observer_post_close_null_count", if (postCloseNullObserved.count == 0L) 1 else 0)
            .put("worker_drained", workerDrained).put("control_worker_drained", controlDrained)
            .put("release_worker_drained", releaseDrained).put("main_callbacks_drained", mainDrained)
            .put("passed", failure == null).put("failure", failure?.stackTraceToString() ?: JSONObject.NULL)
        cleanup { resultFile.writeText(result.toString(2)) }
    }
    failure?.let { throw it }
    report.append("PASS ").append(selector)
        .append(" bounded native-head/callback/retirement evidence; audible quality and pipeline latency unverified\n")
}
