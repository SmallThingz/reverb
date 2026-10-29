package app.smallthingz.reverb

import android.content.Context
import android.os.Debug
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.CRC32
import kotlin.math.PI
import kotlin.math.sin
import org.json.JSONArray
import org.json.JSONObject

/** QA-owned files only; this measures conversion, while the preview fixture tests transport. */
internal fun verifyPcmStereoStreamBenchmark(context: Context, report: StringBuilder) {
    check(context.packageName == "app.smallthingz.reverb.reliability" &&
        context.applicationContext.javaClass == ReverbApplication::class.java) {
        "The conversion benchmark requires the isolated full-QA application"
    }
    val directory = Files.createTempDirectory(context.filesDir.toPath(), "pcm-stereo-benchmark-").toFile()
    val evidenceFile = File(context.filesDir, directory.name + ".json")
    val evidence = JSONObject()
        .put("selector", "pcm_stereo_stream_benchmark")
        .put("baseline_source_sha256", "0f630b946f0883a2024e554228fe0d030aeae1beb7d4e224c16b42f43cfa855c")
        .put("baseline_transform", "PcmStreamNormalizer object name only -> ScalarStreamNormalizerBaseline")
        .put("current_stream_source_sha256", "fce4ef7bf1f78bae1c76daf1424703bafd8f285fb2f7c53aafd30acab89b533d")
        .put("current_converter_source_sha256", "928180edc19fcc14743802a4977e46b57261cd879ac62139b27b0dc93c275a3f")
        .put("scope", "Whole verified stream conversion; no AudioTrack, microphone, playback scheduling or cutout-cause claim")
        .put("source_directory", directory.absolutePath)
    val cases = JSONArray()
    evidence.put("cases", cases)
    var failure: Throwable? = null
    try {
        for ((rate, channels) in listOf(96000 to 2, 48000 to 1)) {
            val sourceHashes = JSONArray()
            val segments = ArrayList<PersistentAudioChunkStore.Segment>()
            repeat(4) { chunk ->
                val bytes = ByteBuffer.allocate(rate * channels * 4).order(ByteOrder.LITTLE_ENDIAN)
                repeat(rate) { localFrame ->
                    val frame = chunk.toLong() * rate + localFrame
                    repeat(channels) { channel ->
                        bytes.putFloat((0.35 * sin(2 * PI * (733 + channel * 264) * frame / rate) +
                            0.1 * sin(2 * PI * 103 * frame / rate)).toFloat())
                    }
                }
                val payload = bytes.array()
                val file = File(directory, "$rate-$channels-$chunk.pcm").apply { writeBytes(payload) }
                val checksum = CRC32().apply { update(payload) }.value.toInt()
                sourceHashes.put(pcmBenchmarkSha256(payload))
                val record = PersistentAudioChunkStore.ChunkRecord((chunk + 1).toUInt(), file,
                    PersistentAudioChunkStore.ChunkState.FINALIZED, chunk * 1000L,
                    payload.size.toLong(), rate.toLong(), rate, channels, PcmSampleFormat.PCM_FLOAT,
                    checksum, 1L, 0L, continuesPreviousChunk = chunk > 0)
                segments += PersistentAudioChunkStore.Segment(record, 0L, rate.toLong(),
                    0L, payload.size.toLong(), checksum, chunk.toDouble(), chunk + 1.0)
            }
            val targetFrames = 4L * 24000
            val expectedBytes = targetFrames.toInt() * channels * 2
            val rows = JSONArray()
            val case = JSONObject().put("source_rate", rate).put("channels", channels)
                .put("source_format", "PCM_FLOAT").put("target_rate", 24000)
                .put("target_format", "PCM_16").put("target_frames", targetFrames)
                .put("source_payload_sha256", sourceHashes).put("output_batch_frames", 1200)
                .put("runs", rows)
            cases.put(case)
            var reference: ByteArray? = null
            fun run(paired: Boolean, warmup: Boolean, round: Int) {
                checkPcmConversionNotInterrupted()
                val output = ByteArrayOutputStream(expectedBytes)
                val consumer = PersistentAudioChunkStore.Consumer { bytes, offset, count ->
                    output.write(bytes, offset, count)
                    count
                }
                val cpuStart = Debug.threadCpuTimeNanos()
                val wallStart = SystemClock.elapsedRealtimeNanos()
                val produced = if (paired) {
                    PcmStreamNormalizer.normalize(segments, targetFrames, 0L, channels,
                        PcmSampleFormat.PCM_16, 1200 * channels * 2, consumer)
                } else {
                    ScalarStreamNormalizerBaseline.normalize(segments, targetFrames, 0L, channels,
                        PcmSampleFormat.PCM_16, 1200 * channels * 2, consumer)
                }
                val wallNanos = SystemClock.elapsedRealtimeNanos() - wallStart
                val cpuNanos = Debug.threadCpuTimeNanos() - cpuStart
                val actual = output.toByteArray()
                val identical = reference?.contentEquals(actual) ?: true
                rows.put(JSONObject().put("implementation", if (paired) "paired" else "scalar_baseline")
                    .put("warmup", warmup).put("round", round)
                    .put("wall_ns", wallNanos).put("thread_cpu_ns", cpuNanos)
                    .put("produced_bytes", produced).put("output_bytes", actual.size)
                    .put("output_sha256", pcmBenchmarkSha256(actual)).put("exact_reference_match", identical))
                check(produced == expectedBytes.toLong() && actual.size == expectedBytes && identical) {
                    "Conversion output mismatch for $rate Hz/$channels channels, paired=$paired round=$round"
                }
                if (reference == null) reference = actual
            }
            run(paired = false, warmup = true, round = -1)
            run(paired = true, warmup = true, round = -1)
            repeat(3) { round ->
                if (round % 2 == 0) {
                    run(paired = false, warmup = false, round = round)
                    run(paired = true, warmup = false, round = round)
                } else {
                    run(paired = true, warmup = false, round = round)
                    run(paired = false, warmup = false, round = round)
                }
            }
            case.put("exact_output_pass", true)
        }
        evidence.put("passed", true)
    } catch (error: Throwable) {
        failure = error
        evidence.put("passed", false).put("failure", error.stackTraceToString())
    } finally {
        fun retain(error: Throwable) {
            val primary = failure
            if (primary == null) failure = error else primary.addSuppressed(error)
        }
        try {
            val deleted = directory.deleteRecursively()
            evidence.put("source_cleanup_succeeded", deleted && !directory.exists())
            check(deleted && !directory.exists()) { "Benchmark source cleanup failed: $directory" }
        } catch (error: Throwable) { retain(error) }
        if (failure != null) evidence.put("passed", false).put("failure", failure!!.stackTraceToString())
        try { evidenceFile.writeText(evidence.toString(2)) } catch (error: Throwable) { retain(error) }
        report.appendLine("pcm_stereo_stream_benchmark evidence=${evidenceFile.absolutePath}")
    }
    failure?.let { throw it }
    report.appendLine("PASS pcm_stereo_stream_benchmark cases=${cases.length()} exact_outputs=true")
}

private fun pcmBenchmarkSha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
