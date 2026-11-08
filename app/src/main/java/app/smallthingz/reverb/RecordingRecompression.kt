package app.smallthingz.reverb

import android.content.Context
import android.util.Log
import java.io.IOException
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
            checkCancelled()
            writeVerifiedRecordingFrameCopy(context, channel, layout, isFlac, plan,
                generatedRecordingDisplayName(recording.displayName.substringBeforeLast('.', recording.displayName),
                    suffix, "." + format.extension, reserveCollisionSuffix = true),
                recordingTimestampWithOffset(recording.startedAtMillis, plan.firstFrame * 1000L / layout.sampleRate),
                format, level, checkCancelled = checkCancelled) { output, fingerprint ->
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
                published
            }
        }
    }
}
