package app.smallthingz.reverb

import android.app.Application
import android.app.Instrumentation
import android.media.AudioTrack
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
    var releaseWorker: ExecutorService? = null
    var submitted = false
    var workerDrained = false
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
    var observedTrack: AudioTrack? = null
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
        // Resolve QA-only fences and the volatile track field before submission.
        // trackLock does not cover native release; never hold it across getter calls.
        val activeTrackField = TimelineAudioPreviewController::class.java.getDeclaredField("activeTrack")
            .apply { isAccessible = true }
        fun observeTrack() {
            val track = activeTrackField.get(controller) as? AudioTrack ?: return
            val before = SystemClock.elapsedRealtimeNanos()
            try {
                val head = track.playbackHeadPosition.toLong() and 0xffff_ffffL
                val underruns = track.underrunCount
                val state = track.playState
                val after = SystemClock.elapsedRealtimeNanos()
                if (activeTrackField.get(controller) !== track || track.state != AudioTrack.STATE_INITIALIZED) {
                    unavailableSamples++
                    return
                }
                if (observedTrack !== track) {
                    if (observedTrack != null) trackChanges++
                    observedTrack = track
                    val route = track.routedDevice
                    trackMetadata = JSONObject().put("sample_rate", track.sampleRate)
                        .put("channels", track.channelCount).put("buffer_frames", track.bufferSizeInFrames)
                        .put("capacity_frames", track.bufferCapacityInFrames).put("session_id", track.audioSessionId)
                        .put("route_id", route?.id ?: JSONObject.NULL).put("route_type", route?.type ?: JSONObject.NULL)
                }
                if (sampleCount < sampleTimes.size) {
                    sampleTimes[sampleCount] = before
                    sampleCosts[sampleCount] = after - before
                    sampleHeads[sampleCount] = head
                    sampleUnderruns[sampleCount] = underruns
                    sampleStates[sampleCount] = state
                    sampleCount++
                } else sampleOverflow = true
            } catch (_: IllegalStateException) {
                // The controller owns release; a racing observation is not authority to touch it again.
                unavailableSamples++
            }
        }
        fun executor(name: String) = TimelineAudioPreviewController::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.get(controller) as ExecutorService
        worker = executor("executor")
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
        if (workerDrained && !releaseDrained) cleanup {
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
                check(parentReleased && releaseDrained && mainDrained) { "Preview reader/release quiescence unknown" }
                workload.afterReadersReleased()
                workloadReleased = true
            }
        } else cleanup { error("Preview worker quiescence unknown; parent/store left open and PCM retained") }
        cleanup { synchronized(callbackLock) { callbackFailure }?.let { throw it } }
        result.put("worker_drained", workerDrained).put("release_worker_drained", releaseDrained)
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
