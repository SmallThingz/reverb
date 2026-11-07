package app.smallthingz.reverb

import android.Manifest
import android.annotation.SuppressLint
import android.app.Instrumentation
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.os.Build
import android.os.SystemClock
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt
import org.json.JSONArray
import org.json.JSONObject

/** QA-only ambient-input statistics. Never opens a capture store or persists microphone PCM. */
@SuppressLint("MissingPermission")
internal fun verifyNativeSilenceDiagnostic(instrumentation: Instrumentation, report: StringBuilder,
    fixtureName: String, expectedSha256: String) {
    val context = instrumentation.targetContext
    val directory = File(context.filesDir, "native-silence-diagnostic-${System.currentTimeMillis()}")
    check(directory.mkdir()) { "Unable to reserve unique diagnostic evidence directory" }
    report.appendLine("DIAGNOSTIC evidence ${directory.absolutePath}")
    require(fixtureName.matches(Regex("silence-diagnostic-[A-Za-z0-9_-]+\\.json")))
    require(expectedSha256.matches(Regex("[0-9a-f]{64}")))
    val fixtureFile = File(context.filesDir, fixtureName)
    require(fixtureFile.length() in 1..16_384)
    val fixtureBytes = fixtureFile.readBytes()
    val sha = MessageDigest.getInstance("SHA-256").digest(fixtureBytes).joinToString("") { "%02x".format(it) }
    require(sha == expectedSha256) { "Silence diagnostic fixture changed" }
    val fixture = JSONObject(fixtureBytes.toString(Charsets.UTF_8))
    require(fixture.getInt("version") == 1)
    val rate = fixture.getInt("sample_rate").also { require(it in 7_000..192_000) }
    val channels = requireNotNull(ChannelMode.fromStorageCode(fixture.getInt("channel_mode")))
    val format = requireNotNull(PcmSampleFormat.fromStorageCode(fixture.getInt("pcm_format")))
    val source = requireNotNull(AudioSourceMode.entries.firstOrNull { it.sourceValue == fixture.getInt("audio_source") })
    val route = requireNotNull(InputRouteMode.fromStorageCode(fixture.getInt("input_route")))
    val saved = decodeSilenceGate(fixture.getString("silence_gate")).also { require(it.enabled) }
    val calibrationStart = (rate + 1L) / 2
    val histogram = requireNotNull(decodeSilenceCalibrationHistogram(fixture.getString("histogram")))
    require(histogram.input == SilenceCalibrationInput(rate, format, channels, source, route)) {
        "Fixture histogram belongs to a different microphone configuration"
    }
    check(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
        "Microphone permission unavailable; diagnostic did not run"
    }
    val result = JSONObject().put("fixture_sha256", sha).put("fixture", fixture)
        .put("source", "actual ambient microphone PCM; uncontrolled acoustic source")
        .put("factory", "QA copy of ReverbService.createAudioRecord builder; not production factory execution")
        .put("analysis_origin_frame", rate * 4L).put("calibration_start_frame", calibrationStart)
        .put("calibration_end_frame", rate * 4L).put("requested_frames", rate * 30L)
        .put("read_mode", "READ_NON_BLOCKING; approximately 10ms capacity, 5ms empty-read wait")
    val frameBytes = channels.channelCount * format.bytesPerSample
    val readBytes = max(1, rate / 100) * frameBytes
    val direct = ByteBuffer.allocateDirect(readBytes).order(ByteOrder.nativeOrder())
    val bytes = ByteArray(readBytes)
    val pcm = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder())
    val peaks = FloatArray(SILENCE_GRAPH_BINS) { -120f }
    val discard = object : SilenceGateOutput {
        override fun audio(bytes: ByteArray, offset: Int, count: Int, sourceFrame: Long) = Unit
        override fun gap() = Unit
    }
    val calibration = SilenceGateStream(rate, channels.channelCount, format, SilenceGateConfig(), discard,
        onSpectrum = { spectrum, _ ->
            check(!spectrum.invalidSamples) { "Non-finite microphone input" }
            spectrum.accumulateGraphPeaks(peaks)
        })
    val visualizer = AudioVisualizationAnalyzer()
    val sums = DoubleArray(300 * channels.channelCount)
    val maxima = DoubleArray(sums.size)
    val counts = IntArray(300)
    val visualSum = DoubleArray(300)
    val visualMax = FloatArray(300)
    val visualCounts = IntArray(300)
    val policySamples = JSONArray()
    val timestamp = AudioTimestamp()
    var savedGate: SilenceDiagnosticGate? = null
    var freshGate: SilenceDiagnosticGate? = null
    var record: AudioRecord? = null
    var started = false
    var failure: Throwable? = null
    var frames = 0L
    var reads = 0
    var emptyReads = 0
    var smallestRead = Int.MAX_VALUE
    var largestRead = 0
    var maxReadNanos = 0L
    var lastPolicyBucket = -1
    var silencedObservations = 0
    val startedAt = SystemClock.elapsedRealtimeNanos()
    try {
        val minimum = AudioRecord.getMinBufferSize(rate, channels.inputChannelMask, format.audioEncoding)
        check(minimum > 0) { "Requested microphone format unavailable: $minimum" }
        val acquired = AudioRecord.Builder().setAudioSource(source.sourceValue).setAudioFormat(
            AudioFormat.Builder().setEncoding(format.audioEncoding).setChannelMask(channels.inputChannelMask)
                .setSampleRate(rate).build()).setBufferSizeInBytes(max(minimum * 2, ReverbService.MIN_AUDIO_RECORD_BUFFER_SIZE)).build()
        record = acquired
        check(acquired.state == AudioRecord.STATE_INITIALIZED) { "Microphone initialization unavailable" }
        if (route == InputRouteMode.BUILTIN_MIC) {
            check(acquired.setPreferredDevice(requireNotNull(findBuiltInMicrophone(context)))) { "Preferred microphone unavailable" }
        }
        result.put("actual_sample_rate", acquired.sampleRate).put("actual_channel_count", acquired.channelCount)
            .put("actual_encoding", acquired.audioFormat).put("actual_source", acquired.audioSource)
            .put("native_session_id", acquired.audioSessionId).put("buffer_size_frames", acquired.bufferSizeInFrames)
        check(acquired.sampleRate == rate && acquired.channelCount == channels.channelCount && acquired.audioFormat == format.audioEncoding) {
            "Actual microphone configuration differs from requested fixture"
        }
        acquired.startRecording()
        started = true
        check(acquired.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Microphone did not start" }
        while (frames < rate * 30L) {
            check(SystemClock.elapsedRealtimeNanos() - startedAt < 40_000_000_000L) { "Microphone diagnostic timed out" }
            direct.clear()
            val requested = minOf(readBytes.toLong(), (rate * 30L - frames) * frameBytes).toInt()
            val before = SystemClock.elapsedRealtimeNanos()
            val read = acquired.read(direct, requested, AudioRecord.READ_NON_BLOCKING)
            maxReadNanos = max(maxReadNanos, SystemClock.elapsedRealtimeNanos() - before)
            check(read >= 0) { "Microphone read failed: $read" }
            if (read == 0) { emptyReads++; SystemClock.sleep(5); continue }
            check(read % frameBytes == 0) { "Microphone returned a partial PCM frame" }
            reads++; smallestRead = minOf(smallestRead, read); largestRead = max(largestRead, read)
            direct.position(0); direct.get(bytes, 0, read)
            val readFrames = read / frameBytes
            for (i in 0 until readFrames) {
                val bucket = ((frames + i) * 10 / rate).toInt().coerceAtMost(299)
                counts[bucket]++
                for (channel in 0 until channels.channelCount) {
                    val offset = i * frameBytes + channel * format.bytesPerSample
                    val sample = when (format) {
                        PcmSampleFormat.PCM_8 -> ((bytes[offset].toInt() and 255) - 128) / 128.0
                        PcmSampleFormat.PCM_16 -> pcm.getShort(offset) / 32768.0
                        PcmSampleFormat.PCM_FLOAT -> pcm.getFloat(offset).toDouble()
                    }
                    check(sample.isFinite()) { "Non-finite microphone input" }
                    val index = bucket * channels.channelCount + channel
                    sums[index] += sample * sample
                    maxima[index] = max(maxima[index], abs(sample))
                }
            }
            val bucket = (frames * 10 / rate).toInt().coerceAtMost(299)
            val visual = visualizer.analyze(bytes, 0, read, format, channels.channelCount, rate)
            visualSum[bucket] += visual.activity; visualMax[bucket] = max(visualMax[bucket], visual.activity); visualCounts[bucket]++
            if (bucket != lastPolicyBucket) {
                lastPolicyBucket = bucket
                val device = acquired.routedDevice
                val activeConfiguration = if (Build.VERSION.SDK_INT >= 29) acquired.activeRecordingConfiguration else null
                val silenced = activeConfiguration?.isClientSilenced
                val timestampStatus = acquired.getTimestamp(timestamp, AudioTimestamp.TIMEBASE_MONOTONIC)
                if (silenced == true) silencedObservations++
                policySamples.put(JSONObject().put("source_frame", frames).put("device_id", device?.id ?: JSONObject.NULL)
                    .put("device_type", device?.type ?: JSONObject.NULL).put("device_name", device?.productName?.toString() ?: JSONObject.NULL)
                    .put("client_silenced", silenced ?: JSONObject.NULL)
                    .put("system_sample_rate", activeConfiguration?.format?.sampleRate ?: JSONObject.NULL)
                    .put("system_channel_count", activeConfiguration?.format?.channelCount ?: JSONObject.NULL)
                    .put("system_encoding", activeConfiguration?.format?.encoding ?: JSONObject.NULL)
                    .put("elapsed_nanos", SystemClock.elapsedRealtimeNanos() - startedAt)
                    .put("timestamp_status", timestampStatus)
                    .put("native_frame_position", if (timestampStatus == AudioRecord.SUCCESS) timestamp.framePosition else JSONObject.NULL)
                    .put("native_timestamp_nanos", if (timestampStatus == AudioRecord.SUCCESS) timestamp.nanoTime else JSONObject.NULL))
            }
            var offsetFrames = 0
            if (frames < calibrationStart) offsetFrames = minOf(readFrames.toLong(), calibrationStart - frames).toInt()
            if (frames + offsetFrames < rate * 4L) {
                val count = minOf((readFrames - offsetFrames).toLong(), rate * 4L - frames - offsetFrames).toInt()
                if (count > 0) calibration.accept(bytes, offsetFrames * frameBytes, count * frameBytes)
                offsetFrames += count
            }
            if (frames + readFrames >= rate * 4L && savedGate == null) {
                calibration.finish()
                val fresh = saved.copy(points = sampledSilenceCurve(peaks.toList(), minOf(rate / 2, SILENCE_MAX_HZ), saved.clearanceDb))
                result.put("fresh_curve", encodeSilenceGate(fresh)).put("fresh_peaks", JSONArray(peaks.toList()))
                savedGate = SilenceDiagnosticGate(rate, channels.channelCount, format, saved)
                freshGate = SilenceDiagnosticGate(rate, channels.channelCount, format, fresh)
            }
            if (offsetFrames < readFrames && frames + offsetFrames >= rate * 4L) {
                savedGate!!.accept(bytes, offsetFrames * frameBytes, (readFrames - offsetFrames) * frameBytes)
                freshGate!!.accept(bytes, offsetFrames * frameBytes, (readFrames - offsetFrames) * frameBytes)
            }
            frames += readFrames
        }
        savedGate!!.finish(); freshGate!!.finish()
        check(silencedObservations == 0) { "Android reported client silencing; ambient-input diagnosis unavailable" }
    } catch (error: Throwable) {
        failure = error
    } finally {
        if (started) try { record?.stop() } catch (error: Throwable) {
            if (failure == null) failure = error else failure!!.addSuppressed(error)
        }
        try { record?.release() } catch (error: Throwable) {
            if (failure == null) failure = error else failure!!.addSuppressed(error)
        }
    }
    val intervals = JSONArray()
    for (bucket in counts.indices) if (counts[bucket] > 0) {
        val rms = JSONArray(); val peak = JSONArray()
        for (channel in 0 until channels.channelCount) {
            val index = bucket * channels.channelCount + channel
            rms.put(sqrt(sums[index] / counts[bucket])); peak.put(maxima[index])
        }
        intervals.put(JSONObject().put("source_frame", bucket * rate / 10L).put("frames", counts[bucket])
            .put("channel_rms", rms).put("channel_peak", peak)
            .put("visual_activity_mean", visualSum[bucket] / visualCounts[bucket].coerceAtLeast(1))
            .put("visual_activity_max", visualMax[bucket]))
    }
    result.put("status", if (failure == null) "COMPLETE" else "UNAVAILABLE_OR_FAILED")
        .put("error", failure?.toString() ?: JSONObject.NULL).put("accepted_frames", frames)
        .put("elapsed_nanos", SystemClock.elapsedRealtimeNanos() - startedAt).put("reads", reads).put("empty_reads", emptyReads)
        .put("minimum_read_bytes", if (reads == 0) 0 else smallestRead).put("maximum_read_bytes", largestRead)
        .put("maximum_read_duration_nanos", maxReadNanos).put("policy_samples", policySamples).put("intervals", intervals)
        .put("saved_gate", savedGate?.json() ?: JSONObject.NULL).put("fresh_gate", freshGate?.json() ?: JSONObject.NULL)
    try { File(directory, "result.json").writeText(result.toString(2)) }
    catch (error: Throwable) { if (failure == null) failure = error else failure!!.addSuppressed(error) }
    failure?.let { throw it }
    report.appendLine("PASS native_silence_diagnostic_current: completed ambient statistics only; evidence ${directory.absolutePath}")
    report.appendLine("frames=$frames source=actual ambient; factory=QA copy; no PCM persisted; no assertion of quiet room or filter accuracy")
}

private class SilenceDiagnosticGate(val rate: Int, channels: Int, format: PcmSampleFormat, val config: SilenceGateConfig) {
    private val frameBytes = channels * format.bytesPerSample
    private var windowStart = 0L
    private var windowEnd = 0L
    private var lastAudioEnd = 0L
    private var open = false
    private var crossings = 0
    private var transitionsCount = 0
    private val transitions = JSONArray()
    private val excess = DoubleArray(260) { Double.NEGATIVE_INFINITY }
    private val frequency = DoubleArray(260)
    private val channelAtPeak = IntArray(260)
    private val baselineCounts = IntArray(260)
    private var powers: DoubleArray? = null
    private var frequencies: DoubleArray? = null
    private var fittedPowers: DoubleArray? = null
    private var fittedFrequencies: DoubleArray? = null
    private var thresholds: DoubleArray? = null
    private val stream = SilenceGateStream(rate, channels, format, config, object : SilenceGateOutput {
        override fun audio(bytes: ByteArray, offset: Int, count: Int, sourceFrame: Long) {
            if (!open) transition(sourceFrame, true)
            open = true
            lastAudioEnd = sourceFrame + count / frameBytes
        }
        override fun gap() {
            if (open) transition(max(windowStart, lastAudioEnd), false)
            open = false
        }
    }, onSpectrum = { spectrum, count ->
        if (powers == null) {
            fun array(name: String) = SilenceSpectrum::class.java.getDeclaredField(name).apply { isAccessible = true }.get(spectrum) as DoubleArray
            powers = array("detectedPowers"); frequencies = array("detectedFrequencies")
            fittedPowers = array("fittedPowers"); fittedFrequencies = array("fittedFrequencies")
            thresholds = spectrum.thresholdPowers(config)
        }
        if (count > 0) { windowStart = windowEnd; windowEnd += count }
        val bucket = (windowStart * 10 / rate).toInt().coerceIn(0, 259)
        fun observe(power: Double, hz: Double, channel: Int) {
            if (power <= 0.0 || hz !in SILENCE_MIN_HZ.toDouble()..SILENCE_MAX_HZ.toDouble()) return
            val db = 10 * log10(power) - config.threshold(hz)
            if (db > excess[bucket]) { excess[bucket] = db; frequency[bucket] = hz; channelAtPeak[bucket] = channel }
        }
        for (index in powers!!.indices) observe(powers!![index], frequencies!![index], index / spectrum.powers.size)
        for (index in fittedPowers!!.indices) observe(fittedPowers!![index], fittedFrequencies!![index], index / 2)
        if (spectrum.exceeds(thresholds!!, config)) { crossings++; baselineCounts[bucket]++ }
    })
    private fun transition(frame: Long, open: Boolean) {
        transitionsCount++
        if (transitions.length() < 256) transitions.put(JSONObject().put("source_frame", frame).put("open", open))
    }
    fun accept(bytes: ByteArray, offset: Int, count: Int) = stream.accept(bytes, offset, count)
    fun finish() = stream.finish()
    fun json(): JSONObject {
        val observations = JSONArray()
        for (i in excess.indices) if (excess[i].isFinite()) observations.put(JSONObject().put("source_frame", i * rate / 10L)
            .put("maximum_baseline_excess_db", excess[i]).put("frequency_hz", frequency[i]).put("channel", channelAtPeak[i])
            .put("baseline_crossings", baselineCounts[i]))
        return JSONObject().put("source_frame_origin", "relative to analysis_origin_frame")
            .put("spectrum_observation", "QA reflection of actual detector peak arrays; includes boundary observations")
            .put("retained_frames", stream.retainedFrames).put("skipped_frames", stream.skippedFrames)
            .put("analyzed_frames", windowEnd)
            .put("baseline_crossing_observations", crossings).put("transition_count", transitionsCount)
            .put("transitions_truncated", transitionsCount > 256).put("transitions", transitions).put("spectral_intervals", observations)
    }
}
