package app.smallthingz.reverb

import android.content.Context
import android.util.Log
import java.io.IOException
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlinx.coroutines.flow.MutableStateFlow

internal object RecompressionEvents {
    val revision = MutableStateFlow(0L)
    @Synchronized fun changed() { revision.value += 1 }
}

/** A conversion always creates a new identity-bound output; the original is never deleted. */
internal fun writeConvertedRecordingCopy(
    context: Context,
    recording: RecordingEntity,
    format: ExportFormat,
    level: Int,
    startMillis: Long = 0,
    endMillis: Long? = null,
    suffix: String = " recompressed",
    checkCancelled: () -> Unit = {},
    beforePublish: () -> Unit = {},
): RecordingEntity {
    require(validFlacCompression(level))
    require(startMillis >= 0 && (endMillis == null || endMillis > startMillis))
    if (!recordingHasStableTrimIdentity(recording)) throw IOException("Recording identity is unavailable")
    return withRecordingWavChannelIdentityGuard(context, recording, RecordingReadCleanup.Propagate) { channel, sourceCurrent ->
        val isFlac = channelHasFlac(channel)
        val layout = if (isFlac) readFlacInfo(channel).pcmLayout else readWavPcmLayout(channel)
        if (format == ExportFormat.FLAC && layout.sampleFormat == PcmSampleFormat.PCM_FLOAT) {
            throw IOException(context.getString(R.string.flac_float_recompression_unsupported))
        }
        withAdmittedRecordingConversion(layout, format, startMillis, endMillis) { plan ->
            val first = plan.firstFrame
            val frames = plan.frameCount
            val sampleFormat = plan.sampleFormat
            checkCancelled()
            val output = createOutputTarget(context,
                generatedRecordingDisplayName(recording.displayName.substringBeforeLast('.', recording.displayName),
                    suffix, "." + format.extension, reserveCollisionSuffix = true),
                format.outputMimeType, recordingTimestampWithOffset(recording.startedAtMillis, first * 1000L / layout.sampleRate),
                StagingOutputKind.EXPORT_TRACKED)
            val writer = createRecordingAudioWriter(context, output, format, layout.sampleRate,
                layout.channelCount, sampleFormat, level)
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
            val fingerprint = writer.verify(context, checkCancelled)
            // Preserve verified staging on every later failure. The source can change during
            // cleanup verification, so deleting this copy could destroy its only retained bytes.
            if (!sourceCurrent()) throw IOException("Original recording changed during recompression")
            checkCancelled()
            beforePublish()
            requireVerifiedOutputRecoveryMarker(putVerifiedExportStaging(context, output, fingerprint), output.id)
            // Publication is irrevocable; later errors must not delete this verified copy.
            val published = finalizeOutputTarget(context, output, fingerprint)
            if (!removeVerifiedExportStaging(context, output.storageType, output.id, fingerprint)) {
                Log.w("RecordingRecompression", "Unable to retire verified staging marker: ${output.id}")
            }
            buildRecordingEntity(context, published, frames * 1000L / layout.sampleRate,
                buildCodecSummary(context, format, layout.sampleRate, layout.channelCount, sampleFormat, level), writer.totalFileBytesWritten)
        }
    }
}
