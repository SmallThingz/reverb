package app.smallthingz.reverb

import android.content.Context
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest

/** One non-destructive writer/verification path; callers retain source and publication authority. */
internal fun writeVerifiedRecordingFrameCopy(
    context: Context,
    channel: FileChannel,
    layout: WavPcmLayout,
    isFlac: Boolean,
    plan: RecordingConversionPlan,
    requestedDisplayName: String,
    startedAtMillis: Long,
    format: ExportFormat,
    level: Int,
    emptyAudioError: String? = null,
    checkCancelled: () -> Unit = {},
    publishVerified: (RecordingOutputTarget, StableOutputFingerprint) -> RecordingOutputTarget,
): RecordingEntity {
    val frames = plan.frameCount
    val sampleFormat = plan.sampleFormat
    val output = createOutputTarget(context, requestedDisplayName, format.outputMimeType,
        startedAtMillis, StagingOutputKind.EXPORT_TRACKED)
    val writer = createRecordingAudioWriter(context, output, format, layout.sampleRate,
        layout.channelCount, sampleFormat, level)
    copyRecordingFrames(channel, layout, isFlac, plan, writer, checkCancelled)
    if (emptyAudioError != null && writer.totalSampleBytesWritten <= 0L) throw IOException(emptyAudioError)
    val fingerprint = writer.verify(context, checkCancelled)
    val published = publishVerified(output, fingerprint)
    return buildRecordingEntity(context, published, frames * 1000L / layout.sampleRate,
        buildCodecSummary(context, format, layout.sampleRate, layout.channelCount, sampleFormat, level),
        writer.totalFileBytesWritten)
}

/** Owns the writer until close; leaves source descriptor ownership with the caller. */
internal fun copyRecordingFrames(
    channel: FileChannel,
    layout: WavPcmLayout,
    isFlac: Boolean,
    plan: RecordingConversionPlan,
    writer: RecordingAudioWriter,
    checkCancelled: () -> Unit = {},
) {
    val first = plan.firstFrame
    val frames = plan.frameCount
    val sampleFormat = plan.sampleFormat
    writer.use {
        var convertedPcm8: ByteArray? = null
        fun consume(bytes: ByteArray, offset: Int, count: Int) {
            checkCancelled()
            if (sampleFormat == PcmSampleFormat.PCM_16 && layout.sampleFormat == PcmSampleFormat.PCM_8) {
                val convertedCount = count * 2
                val converted = convertedPcm8?.takeIf { it.size >= convertedCount }
                    ?: ByteArray(convertedCount).also { convertedPcm8 = it }
                repeat(count) { i -> converted[i * 2 + 1] = ((bytes[offset + i].toInt() and 255) - 128).toByte() }
                writer.write(converted, 0, convertedCount)
            } else writer.write(bytes, offset, count)
        }
        if (isFlac) {
            FlacPcmDecoder(channel, RecordingReadCleanup.Propagate).use { decoder ->
                // STREAMINFO's checksum covers the whole source, not a selected range.
                val digest = if (first == 0L && frames == decoder.info.frameCount) {
                    MessageDigest.getInstance("MD5")
                } else null
                decoder.readFrames(first, frames) { bytes, offset, count ->
                    digest?.update(bytes, offset, count)
                    consume(bytes, offset, count)
                }
                if (digest != null &&
                    !flacPcmChecksumMatches(decoder.info.md5, digest.digest())) {
                    throw IOException("Original FLAC audio checksum does not match")
                }
            }
        } else {
            val batchFrames = minOf(frames, maxOf(1, 128 * 1024 / layout.frameBytes).toLong()).toInt()
            val bytes = ByteArray(batchFrames * layout.frameBytes)
            val readBuffer = ByteBuffer.wrap(bytes)
            var copied = 0L
            while (copied < frames) {
                val count = minOf(batchFrames.toLong(), frames - copied).toInt()
                requireReadAt(channel, layout.dataOffsetBytes + (first + copied) * layout.frameBytes, readBuffer, count * layout.frameBytes)
                consume(bytes, 0, count * layout.frameBytes); copied += count
            }
        }
        checkCancelled()
    }
}
