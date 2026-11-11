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
        val controller = TimelineAudioPreviewController(::callbackFailure,
            collectShuttleTimings = selector == "range_preview_short_controls")
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
                // Continuous commands use their own clock; native getters can block behind writes.
                data class ContinuousRead(val phaseBefore: Int, val phaseAfter: Int,
                    val began: Long, val ended: Long, val sample: PreviewTrackObservation?)
                data class ContinuousWindow(val phase: Int, val owner: PreviewTrackObservation,
                    val start: Long?, val end: Long?, val cadence: Boolean, val result: JSONObject)
                val continuousReads = ArrayList<ContinuousRead>() // Observer writes; read only after join.
                val latestRead = AtomicReference<ContinuousRead?>(null)
                val continuousPhase = AtomicInteger()
                val continuousOverflow = AtomicBoolean(false)
                val continuousWindows = ArrayList<ContinuousWindow>()
                val continuousRuns = JSONArray()
                result.put("continuous_fast_runs", continuousRuns)
                    .put("continuous_observer_requested_interval_ns", 8_000_000L)
                    .put("continuous_observer_limit", 4_096)
                    .put("continuous_scope", "fixed requested bounds and observed output progress; no queued-source-span or audible-quality proof")
                fun waitUntil(deadline: Long) {
                    while (true) {
                        val remaining = deadline - SystemClock.elapsedRealtimeNanos()
                        if (remaining <= 0L) return
                        Thread.sleep(remaining / 1_000_000L, (remaining % 1_000_000L).toInt())
                    }
                }
                observeConcurrently.set(true)
                observer = Thread({
                    try {
                        while (observeConcurrently.get()) {
                            val phaseBefore = continuousPhase.get()
                            val began = SystemClock.elapsedRealtimeNanos()
                            val sample = controller.observeTrack()
                            val ended = SystemClock.elapsedRealtimeNanos()
                            val read = ContinuousRead(phaseBefore, continuousPhase.get(), began, ended, sample)
                            if (continuousReads.size == 4_096) {
                                continuousOverflow.set(true)
                                error("Continuous observer buffer overflow")
                            }
                            continuousReads.add(read)
                            latestRead.set(read)
                            val slots = (SystemClock.elapsedRealtimeNanos() - began) / 8_000_000L + 1L
                            waitUntil(began + slots * 8_000_000L)
                        }
                    } catch (error: Throwable) { callbackFailure(error) }
                }, "Reverb-QA-continuous-observer").also { it.start() }
                try {
                    var priorOwner: Any? = null
                    for ((runIndex, direction) in listOf(1, -1).withIndex()) {
                        val phaseId = runIndex + 1
                        val rate = direction * 2f
                        val commands = JSONArray()
                        val skipped = JSONArray()
                        val continuous = JSONObject().put("direction", direction).put("rate", rate)
                            .put("audible_speed", shuttleAudibleSpeed(rate))
                            .put("start_seconds", .05).put("end_seconds", .95)
                            .put("requested_commands", 25).put("warmup_commands", 8)
                            .put("requested_period_ns", 16_000_000L).put("commands", commands).put("skipped_slots", skipped)
                            .put("status", "INCOMPLETE").put("audible_quality", "UNVERIFIED")
                        continuousRuns.put(continuous)
                        continuousPhase.set(phaseId)
                        val startCompleted = onMain {
                            controller.startShuttle(parent, if (direction > 0) .088 else .912, rate, .05, .95)
                            SystemClock.elapsedRealtimeNanos()
                        }
                        continuous.put("start_completed_elapsed_ns", startCompleted)
                        val startupDeadline = SystemClock.elapsedRealtimeNanos() + 10_000_000_000L
                        var startup: ContinuousRead? = null
                        while (startup == null) {
                            failures.peek()?.let { throw it }
                            startup = latestRead.get()?.takeIf { it.phaseBefore == phaseId && it.phaseAfter == phaseId &&
                                it.began >= startCompleted && (it.sample?.playbackHeadFrames ?: 0L) > 0L }
                            check(SystemClock.elapsedRealtimeNanos() < startupDeadline) { "Continuous startup did not advance" }
                            if (startup == null) Thread.sleep(4L)
                        }
                        val owner = checkNotNull(checkNotNull(startup).sample)
                        check(owner.identity !== priorOwner) { "Continuous reversal retained retired owner" }
                        priorOwner = owner.identity
                        continuous.put("epoch", owner.epoch).put("sample_rate", owner.sampleRate)
                        val clockStart = SystemClock.elapsedRealtimeNanos() + 16_000_000L
                        continuous.put("requested_steady_start_ns", clockStart + 8L * 16_000_000L)
                            .put("requested_steady_end_ns", clockStart + 24L * 16_000_000L)
                        var steadyStart: Long? = null
                        var steadyEnd: Long? = null
                        var wholePeriodLateCommands = 0
                        var index = 0
                        fun skipSlot() {
                            skipped.put(JSONObject().put("index", index)
                                .put("requested_elapsed_ns", clockStart + index * 16_000_000L))
                            index++
                        }
                        while (index < 25) {
                            waitUntil(clockStart + index * 16_000_000L)
                            val due = ((SystemClock.elapsedRealtimeNanos() - clockStart) / 16_000_000L).toInt()
                            while (index < minOf(due, 25)) skipSlot()
                            if (index == 25) break
                            val deadline = clockStart + index * 16_000_000L
                            val target = (if (direction > 0) .12 else .88) + direction * index * .032
                            val executed = onMain {
                                val began = SystemClock.elapsedRealtimeNanos()
                                controller.updateShuttle(target, rate, .05, .95)
                                began to SystemClock.elapsedRealtimeNanos()
                            }
                            val returned = SystemClock.elapsedRealtimeNanos()
                            if (executed.first - deadline >= 16_000_000L) wholePeriodLateCommands++
                            commands.put(JSONObject().put("index", index).put("target_seconds", target)
                                .put("requested_elapsed_ns", deadline).put("main_begin_ns", executed.first)
                                .put("main_end_ns", executed.second).put("returned_elapsed_ns", returned)
                                .put("lateness_ns", executed.first - deadline))
                            if (index == 8) steadyStart = executed.first
                            if (index == 24) steadyEnd = executed.first
                            index++
                            while (index < 25 && clockStart + index * 16_000_000L <= returned) skipSlot()
                            failures.peek()?.let { throw it }
                        }
                        continuousWindows.add(ContinuousWindow(phaseId, owner, steadyStart, steadyEnd,
                            commands.length() == 25 && skipped.length() == 0 && wholePeriodLateCommands == 0, continuous))
                        continuous.put("whole_period_late_commands", wholePeriodLateCommands)
                        onMain { controller.stopShuttle() }
                    }
                } finally {
                    try { onMain { controller.stopShuttle() } }
                    finally {
                        observeConcurrently.set(false)
                        observer!!.join(5_000L)
                        val drained = !observer!!.isAlive
                        result.put("continuous_observer_drained", drained)
                            .put("continuous_observer_overflow", continuousOverflow.get())
                        check(drained) { "Continuous observer did not drain after native retirement" }
                        val reads = JSONArray()
                        for (read in continuousReads) {
                            val sample = read.sample
                            val identity = sample?.let { identities[it.identity] ?:
                                (identities.size + 1).also { id -> identities[it.identity] = id } }
                            reads.put(JSONObject().put("phase_before", read.phaseBefore).put("phase_after", read.phaseAfter)
                                .put("read_begin_ns", read.began).put("read_end_ns", read.ended)
                                .put("read_cost_ns", read.ended - read.began).put("identity", identity ?: JSONObject.NULL)
                                .put("epoch", sample?.epoch ?: JSONObject.NULL).put("head_frames", sample?.playbackHeadFrames ?: JSONObject.NULL)
                                .put("accepted_write_frames", sample?.acceptedWriteFrames ?: JSONObject.NULL)
                                .put("queued_frames", sample?.let { it.acceptedWriteFrames - it.playbackHeadFrames } ?: JSONObject.NULL)
                                .put("play_state", sample?.playState ?: JSONObject.NULL).put("underruns", sample?.underrunCount ?: JSONObject.NULL))
                        }
                        result.put("continuous_observations", reads)
                        check(!continuousOverflow.get()) { "Continuous observations were lost" }
                        for (window in continuousWindows) {
                            val start = window.start
                            val end = window.end
                            val covered = if (start != null && end != null) continuousReads.filter {
                                it.phaseBefore == window.phase && it.phaseAfter == window.phase && it.began >= start && it.ended <= end
                            } else emptyList()
                            val overlappingReadNanos = if (start != null && end != null) continuousReads.sumOf {
                                (minOf(it.ended, end) - maxOf(it.began, start)).coerceAtLeast(0L)
                            } else 0L
                            val counters = covered.filter { it.sample != null }
                            for (read in counters) {
                                val sample = checkNotNull(read.sample)
                                check(sample.playbackHeadFrames >= 0L && sample.acceptedWriteFrames >= 0L &&
                                    sample.underrunCount >= 0 && sample.playbackHeadFrames <= sample.acceptedWriteFrames) {
                                    "Invalid continuous native counters"
                                }
                            }
                            val sameOwner = counters.all {
                                val sample = checkNotNull(it.sample)
                                sample.identity === window.owner.identity && sample.epoch == window.owner.epoch
                            }
                            check(sameOwner) { "Continuous fixed-bound updates replaced native owner" }
                            var advances = 0
                            var maximumStall = 0L
                            var stalledSince = counters.firstOrNull()?.ended ?: 0L
                            for ((previous, next) in counters.zipWithNext()) {
                                val a = checkNotNull(previous.sample); val b = checkNotNull(next.sample)
                                check(b.playbackHeadFrames >= a.playbackHeadFrames && b.acceptedWriteFrames >= a.acceptedWriteFrames) {
                                    "Continuous native counters regressed"
                                }
                                if (b.playbackHeadFrames > a.playbackHeadFrames) { advances++; stalledSince = next.ended }
                                else maximumStall = maxOf(maximumStall, next.ended - stalledSince)
                            }
                            val first = counters.firstOrNull(); val last = counters.lastOrNull()
                            val entry = covered.firstOrNull()?.sample
                            val entryPlaying = entry != null && entry.playState == android.media.AudioTrack.PLAYSTATE_PLAYING &&
                                entry.playbackHeadFrames > 0L
                            val established = window.cadence && entryPlaying && counters.size >= 2 && advances > 0
                            window.result.put("status", if (established) "OBSERVED_PROGRESS" else "INSUFFICIENT_OR_UNESTABLISHED_WINDOW")
                                .put("cadence_established", window.cadence).put("counter_window_established", established)
                                .put("steady_entry_playing_with_progress", entryPlaying)
                                .put("steady_entry_play_state", entry?.playState ?: JSONObject.NULL)
                                .put("steady_entry_head_frames", entry?.playbackHeadFrames ?: JSONObject.NULL)
                                .put("steady_entry_queued_frames", entry?.let { it.acceptedWriteFrames - it.playbackHeadFrames } ?: JSONObject.NULL)
                                .put("actual_steady_start_ns", start ?: JSONObject.NULL).put("actual_steady_end_ns", end ?: JSONObject.NULL)
                                .put("counter_samples", counters.size).put("null_samples", covered.size - counters.size)
                                .put("same_native_owner", if (counters.isEmpty()) JSONObject.NULL else sameOwner)
                                .put("head_advance_samples", advances).put("maximum_sampled_head_stall_ns", maximumStall)
                                .put("observed_counter_begin_ns", first?.began ?: JSONObject.NULL).put("observed_counter_end_ns", last?.ended ?: JSONObject.NULL)
                                .put("blind_prefix_ns", if (first != null && start != null) first.began - start else JSONObject.NULL)
                                .put("blind_suffix_ns", if (last != null && end != null) end - last.ended else JSONObject.NULL)
                                .put("observed_read_cost_ns", covered.sumOf { it.ended - it.began })
                                .put("read_busy_overlap_ns", overlappingReadNanos)
                                .put("maximum_read_cost_ns", covered.maxOfOrNull { it.ended - it.began } ?: JSONObject.NULL)
                            val firstSample = first?.sample; val lastSample = last?.sample
                            if (firstSample != null && lastSample != null) window.result
                                .put("first_head_frames", firstSample.playbackHeadFrames).put("last_head_frames", lastSample.playbackHeadFrames)
                                .put("first_accepted_frames", firstSample.acceptedWriteFrames).put("last_accepted_frames", lastSample.acceptedWriteFrames)
                                .put("first_underruns", firstSample.underrunCount).put("last_underruns", lastSample.underrunCount)
                                .put("first_play_state", firstSample.playState).put("last_play_state", lastSample.playState)
                                .put("first_queued_frames", firstSample.acceptedWriteFrames - firstSample.playbackHeadFrames)
                                .put("last_queued_frames", lastSample.acceptedWriteFrames - lastSample.playbackHeadFrames)
                                .put("head_frames_delta", lastSample.playbackHeadFrames - firstSample.playbackHeadFrames)
                                .put("accepted_frames_delta", lastSample.acceptedWriteFrames - firstSample.acceptedWriteFrames)
                                .put("observed_underrun_delta", lastSample.underrunCount - firstSample.underrunCount)
                                .put("underrun_scope", "between first/last fully covered reads only; not the whole requested window")
                        }
                    }
                }
                // Isolate the cached-helper measurement from our own native transport/cleanup workers.
                checkNotNull(worker).submit {}.get(15, TimeUnit.SECONDS)
                checkNotNull(controlWorker).submit {}.get(15, TimeUnit.SECONDS)
                checkNotNull(releaseWorker).submit {}.get(15, TimeUnit.SECONDS)
                onMain {}
                qualifyNativeShuttlePcm(result)
                val helperCosts = JSONArray()
                val combinedCosts = JSONArray()
                result.put("combined_render_cost", JSONObject().put("sample_rate", fastSampleRate)
                    .put("warmup_calls_per_case", 2).put("measured_calls_per_case", 8)
                    .put("scope", "actual renderShuttlePcm16 on cached PCM; excludes cache read, crossfade and native writes")
                    .put("cpu_scope", "instrumentation thread; not audio-worker or uninstrumented baseline performance")
                    .put("allocation_scope", "process-wide ART counter delta; includes stats reads and other QA threads")
                    .put("cases", combinedCosts))
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
                            val tail = if (enabled) retainedTail else null
                            fun render() = renderShuttlePcm16(cached, 0.0, nextStart, nextStart + grainSeconds,
                                fastSampleRate, channels, tail, rate, .05, .95)
                            val reference = transformShuttlePcm16(sliceShuttlePcm16(cached, 0.0, nextStart,
                                nextStart + grainSeconds, fastSampleRate, channels, tail, rate, .05, .95), rate, channels)
                            check(render().contentEquals(reference)) { "Combined render changed the reference PCM" }
                            fun checksum(bytes: ByteArray) = bytes.size.toLong() * 31L +
                                bytes.first().toLong() * 17L + bytes[bytes.size / 2].toLong() * 7L + bytes.last()
                            repeat(2) { render() }
                            val combinedAllocationBefore = Debug.getRuntimeStat("art.gc.bytes-allocated")?.toLongOrNull()
                            val combinedCpuBefore = Debug.threadCpuTimeNanos()
                            val combinedWallBefore = SystemClock.elapsedRealtimeNanos()
                            var outputChecksum = 0L
                            repeat(8) { outputChecksum += checksum(render()) }
                            val combinedWall = SystemClock.elapsedRealtimeNanos() - combinedWallBefore
                            val combinedCpuAfter = Debug.threadCpuTimeNanos()
                            val combinedAllocationAfter = Debug.getRuntimeStat("art.gc.bytes-allocated")?.toLongOrNull()
                            check(outputChecksum == checksum(reference) * 8)
                            combinedCosts.put(JSONObject().put("channels", channels).put("rate", rate)
                                .put("audible_speed", shuttleAudibleSpeed(rate)).put("tail_present", enabled)
                                .put("output_frames_per_call", reference.size / (channels * 2))
                                .put("sampled_output_checksum", outputChecksum).put("reference_bytes_equal", true)
                                .put("wall_nanos", combinedWall)
                                .put("thread_cpu_nanos", if (combinedCpuBefore >= 0L && combinedCpuAfter >= combinedCpuBefore)
                                    combinedCpuAfter - combinedCpuBefore else JSONObject.NULL)
                                .put("process_allocated_bytes_delta", if (combinedAllocationBefore != null &&
                                    combinedAllocationAfter != null && combinedAllocationAfter >= combinedAllocationBefore)
                                    combinedAllocationAfter - combinedAllocationBefore else JSONObject.NULL))
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
        if (selector == "range_preview_short_controls") cleanup {
            // close()/quiet acknowledgement alone cannot finish a canceled worker's last read.
            // Drain after the original executor's termination fence, preserving its old epochs.
            val timings = preview?.drainShuttleTimings() ?: PreviewShuttleTimingBatch(emptyList(), 0)
            val records = JSONArray()
            for (sample in timings.records) {
                check(sample.epoch > 0L && sample.elapsedNanos >= 0L && sample.threadCpuNanos >= 0L)
                records.put(JSONObject().put("epoch", sample.epoch).put("stage", sample.stage.name)
                    .put("started_elapsed_ns", sample.startedAtElapsedRealtimeNanos)
                    .put("duration_ns", sample.elapsedNanos).put("thread_cpu_ns", sample.threadCpuNanos)
                    .put("completed", sample.completed).put("current_at_end", sample.currentAtEnd)
                    .put("output_bytes", sample.outputBytes ?: JSONObject.NULL))
            }
            result.put("shuttle_stage_timings", records).put("shuttle_stage_timings_dropped", timings.dropped)
                .put("shuttle_stage_timings_final", workerDrained && worker?.isTerminated == true)
                .put("shuttle_stage_timings_scope", "opt-in wall/CPU stage samples; not audible quality or baseline performance")
            check(timings.dropped == 0) { "Shuttle timing queue overflowed" }
            check(workerDrained && worker?.isTerminated == true) { "Shuttle timing drain is not final" }
            check(timings.records.any { it.stage == PreviewShuttleStage.READ_NORMALIZE }) { "Shuttle read timing missing" }
            check(timings.records.any { it.stage == PreviewShuttleStage.FIRST_WRITE && (it.outputBytes ?: 0) > 0 }) {
                "Successful first-write timing missing"
            }
        }
        cleanup { check(preview?.observeTrack() == null && preview?.playbackActive != true) }
        if (workerDrained && controlDrained && releaseDrained && mainDrained && observer?.isAlive != true) {
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

private fun qualifyNativeShuttlePcm(result: JSONObject) {
    check(Looper.myLooper() != Looper.getMainLooper())
    fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    fun bits(samples: FloatArray): ByteArray = ByteBuffer.allocate(samples.size * 4)
        .order(ByteOrder.LITTLE_ENDIAN).also { buffer -> samples.forEach { buffer.putInt(it.toRawBits()) } }.array()
    fun pcm(samples: FloatArray): ByteArray = ByteBuffer.allocate(samples.size * 2)
        .order(ByteOrder.LITTLE_ENDIAN).also { buffer -> samples.forEach {
            buffer.putShort(it.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
        } }.array()
    val cases = JSONArray()
    val proof = JSONObject().put("passed", false)
        .put("scope", "active process ABI; direct native filter versus unchanged Kotlin scalar converter; not sound quality or timing")
        .put("process_arch", System.getProperty("os.arch") ?: "unknown")
        .put("process_is_64_bit", android.os.Process.is64Bit())
        .put("cases", cases)
    result.put("native_fir_qualification", proof)
    for ((frames, outputs) in listOf(7 to 2, 257 to 234, 2534 to 2304, 6912 to 2304)) {
        for (channels in 1..2) for (reverse in listOf(false, true)) {
            val first = 13
            val input = ByteArray((frames + 26) * channels * 2)
            repeat(input.size / 2) { index ->
                val value = when (index % 11) {
                    0 -> -32768
                    1 -> 32767
                    2 -> 0
                    else -> (index * 7919 + index / 3).toShort().toInt()
                }
                input[index * 2] = value.toByte()
                input[index * 2 + 1] = (value shr 8).toByte()
            }
            val step = (frames - 1).toDouble() / (outputs - 1).toDouble()
            val converter = PcmRateConverter(frames, step) { frame, channel ->
                val oriented = if (reverse) frames - 1 - frame else frame
                val offset = ((first + oriented) * channels + channel) * 2
                ((input[offset].toInt() and 255) or (input[offset + 1].toInt() shl 8)).toShort().toFloat()
            }
            val reference = FloatArray(outputs * channels)
            repeat(outputs) { frame ->
                val position = frame.toDouble() * (frames - 1).toDouble() / (outputs - 1).toDouble()
                repeat(channels) { channel -> reference[frame * channels + channel] = converter.sample(position, channel) }
            }
            for (sampleFrames in listOf(outputs, minOf(17, outputs)).distinct()) {
                val row = JSONObject().put("source_frames", frames).put("output_frames", outputs)
                    .put("sample_frames", sampleFrames).put("first_source_frame", first)
                    .put("channels", channels).put("reverse", reverse)
                    .put("source_step_bits", java.lang.Long.toHexString(step.toRawBits()))
                    .put("input_sha256", sha(input)).put("passed", false)
                cases.put(row)
                val actual = checkNotNull(ShuttlePcmNative.filter(input, first, frames, channels, reverse,
                    outputs, sampleFrames, step)) { "Native FIR unavailable for $row" }
                val expected = reference.copyOf(sampleFrames * channels)
                check(actual.size == expected.size) { "Native FIR output size: $row" }
                val actualBits = bits(actual)
                val expectedBits = bits(expected)
                val actualPcm = pcm(actual)
                val expectedPcm = pcm(expected)
                row.put("native_float_sha256", sha(actualBits)).put("reference_float_sha256", sha(expectedBits))
                    .put("native_pcm_sha256", sha(actualPcm)).put("reference_pcm_sha256", sha(expectedPcm))
                check(actualBits.contentEquals(expectedBits)) { "Native FIR Float bits differ: $row" }
                check(actualPcm.contentEquals(expectedPcm)) { "Native FIR rounded/clamped PCM differs: $row" }
                row.put("native_nonnull", true).put("passed", true)
            }
        }
    }
    var linearGates = 0
    for (channels in 1..2) for (reverse in listOf(false, true)) {
        val input = ByteArray(31 * channels * 2) { (it * 73).toByte() }
        check(ShuttlePcmNative.filter(input, 0, 31, channels, reverse, 31, 31, 1.0) == null) {
            "Unit-step shuttle must retain the Kotlin linear path"
        }
        linearGates++
    }
    check(cases.length() == 28 && linearGates == 4)
    proof.put("native_cases", cases.length()).put("linear_gate_cases", linearGates).put("passed", true)
}
