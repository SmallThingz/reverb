package app.smallthingz.reverb

import android.content.Context
import android.util.Log
import java.io.IOException
import java.nio.channels.FileChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.math.roundToLong

private const val TRIM_COPY_BUFFER_BYTES = 128 * 1024
private const val TRIM_TAG = "RecordingTrim"

internal fun recordingHasStableTrimIdentity(recording: RecordingEntity): Boolean =
    recording.fileIdentity.isNotBlank()

// Discovery identifies containers by their bytes, so trim must use the same rule.
// A null layout delegates FLAC decoding to the conversion path, which validates it fully.
internal fun readWavTrimLayoutOrNull(source: FileChannel): WavPcmLayout? =
    if (channelHasFlac(source)) null else readWavPcmLayout(source)

internal fun recordingTrimFailureMessage(context: Context, failure: Throwable): String =
    if (failure is RecordingOutputCapacityException && failure.format == ExportFormat.WAV) {
        context.getString(R.string.trim_wav_limit,
            formatDurationInput(failure.maximumDurationMillis / 1000L))
    } else context.getString(R.string.trim_failed)

internal suspend fun runCommittedInlineTrim(
    save: suspend () -> RecordingEntity,
    onTerminal: (Result<RecordingEntity>) -> Unit,
) = withContext(NonCancellable) {
    val result = try {
        Result.success(save())
    } catch (error: Exception) {
        Result.failure(error)
    }
    onTerminal(result)
}

internal suspend fun saveTrimmedRecordingCopy(
    context: Context,
    recording: RecordingEntity,
    startMillis: Int,
    endMillis: Int,
): RecordingEntity {
    val appContext = context.applicationContext
    val operation = recordingMutations.tryBegin(recording.id)
        ?: throw IOException("Trim is already in progress for this recording")
    try {
        return withContext(Dispatchers.IO + NonCancellable) {
            val created = writeTrimmedRecordingCopy(appContext, recording, startMillis, endMillis)
            try {
                RecordingRepository.register(appContext, created)
            } catch (error: Exception) {
                if (!catalogRegistrationFailureAllowsVerifiedSaveSuccess(error)) {
                    Log.w(TRIM_TAG, "Trim output changed before catalog registration", error)
                    throw error
                }
                Log.w(TRIM_TAG, "Trim was saved but catalog registration failed", error)
                created
            }
        }
    } finally {
        recordingMutations.finish(operation)
    }
}

internal fun trimmedRecordingBaseName(displayName: String): String {
    val base = displayName.substringBeforeLast('.', displayName).trim()
    return generatedRecordingDisplayName(
        base.ifBlank { FALLBACK_DISPLAY_NAME }, " trim", ".wav", reserveCollisionSuffix = true,
    ).removeSuffix(".wav")
}


internal inline fun <T> publishVerifiedTrimAfterSourceValidation(
    sourceStillCurrent: () -> Boolean,
    persistRecoveryMarker: () -> Boolean,
    targetId: String,
    publish: () -> T,
): T {
    if (!sourceStillCurrent()) {
        throw IOException("Recording changed while trim was being read")
    }
    requireVerifiedOutputRecoveryMarker(persistRecoveryMarker(), targetId)
    return publish()
}

private fun writeTrimmedRecordingCopy(
    context: Context,
    recording: RecordingEntity,
    startMillis: Int,
    endMillis: Int,
): RecordingEntity {
    val format = getConfiguredOutputFormat(context)
    if (format == ExportFormat.FLAC || recording.displayName.endsWith(".flac", ignoreCase = true)) {
        return writeConvertedRecordingCopy(context, recording, format, getConfiguredFlacCompression(context),
            startMillis.toLong(), endMillis.toLong(), " trim")
    }
    if (!recordingHasStableTrimIdentity(recording)) {
        throw IOException("Recording identity is unavailable for trim")
    }
    if (startMillis < 0 || endMillis <= startMillis) throw IOException("Invalid trim range")
    val trimmedWav = withRecordingWavChannelIdentityGuard(context, recording, RecordingReadCleanup.Propagate) { source, sourceStillCurrent ->
        val layout = readWavTrimLayoutOrNull(source) ?: return@withRecordingWavChannelIdentityGuard null
        val startFrame = millisToFrame(startMillis, layout.sampleRate)
            .coerceIn(0L, layout.frameCount - 1L)
        val endFrame = millisToFrame(endMillis, layout.sampleRate)
            .coerceIn(startFrame + 1L, layout.frameCount)
        val selectedFrames = endFrame - startFrame
        if (selectedFrames <= 0L) throw IOException("Trim range contains no audio")

        withAdmittedRecordingFrameRange(layout, ExportFormat.WAV, startFrame, selectedFrames) {
            val startedAtMillis = recordingTimestampWithOffset(
                startedAtMillis = recording.startedAtMillis,
                offsetMillis = startFrame * 1000L / layout.sampleRate.toLong(),
            )
            val outputTarget = createOutputTarget(
                context = context,
                requestedDisplayName = "${trimmedRecordingBaseName(recording.displayName)}.wav",
                mimeType = ExportFormat.WAV.outputMimeType,
                startedAtMillis = startedAtMillis,
                stagingKind = StagingOutputKind.EXPORT_TRACKED,
            )
            val writer = WavAudioFileWriter(
                context = context,
                target = outputTarget,
                sampleRate = layout.sampleRate,
                channelCount = layout.channelCount,
                sampleFormat = layout.sampleFormat,
            )
            writer.use {
                copyFrameRange(
                    source = source,
                    layout = layout,
                    startFrame = startFrame,
                    frameCount = selectedFrames,
                    writer = writer,
                )
            }
            if (writer.totalSampleBytesWritten <= 0L) throw IOException("Trim produced no audio")
            val expectedBytes = writer.totalFileBytesWritten
            val stagingId = outputTarget.id
            val verifiedOutput = verifyWavOutputTargetAndDigest(
                context = context,
                target = outputTarget,
                expectedFileBytes = expectedBytes,
                expectedPrefix = writer.expectedHeaderBytes,
                payloadOffsetBytes = writer.payloadOffsetBytes,
                payloadBytes = writer.totalSampleBytesWritten,
                expectedPayloadSha256 = writer.payloadSha256,
            )
            // Keep verified staging on every subsequent failure: the source can change during
            // publication, and this may be the only retained copy of the selected audio.
            // The source bytes are complete now, but the target is still hidden. Provider-backed
            // recordings can change while their descriptor remains open, so revalidate the
            // selected source before granting recovery authority or publishing a final name.
            val finalized = publishVerifiedTrimAfterSourceValidation(
                sourceStillCurrent = sourceStillCurrent,
                persistRecoveryMarker = { putVerifiedExportStaging(context, outputTarget, verifiedOutput) },
                targetId = outputTarget.id,
            ) {
                finalizeOutputTarget(context, outputTarget, verifiedOutput)
            }
            if (!removeVerifiedExportStaging(context, outputTarget.storageType, stagingId, verifiedOutput)) {
                Log.w(TRIM_TAG, "Unable to clear verified trim recovery marker: $stagingId")
            }
            val durationMillis = selectedFrames * 1000L / layout.sampleRate.toLong()
            buildRecordingEntity(
                context = context,
                target = finalized,
                durationMillis = durationMillis,
                codecSummary = buildCodecSummary(
                    context = context,
                    format = ExportFormat.WAV,
                    sampleRate = layout.sampleRate,
                    channelCount = layout.channelCount,
                    sampleFormat = layout.sampleFormat,
                ),
                knownSizeBytes = expectedBytes,
            )
        }
    }
    // Close the detection descriptor before conversion reacquires and revalidates the
    // selected recording. Conversion retains its own final source/publication checks.
    return trimmedWav ?: writeConvertedRecordingCopy(
        context, recording, format, getConfiguredFlacCompression(context),
        startMillis.toLong(), endMillis.toLong(), " trim",
    )
}

internal fun recordingTimestampWithOffset(
    startedAtMillis: Long,
    offsetMillis: Long,
): Long {
    require(startedAtMillis >= 0L) { "Recording timestamp must be non-negative" }
    require(offsetMillis >= 0L) { "Recording timestamp offset must be non-negative" }
    return if (startedAtMillis > Long.MAX_VALUE - offsetMillis) {
        Long.MAX_VALUE
    } else {
        startedAtMillis + offsetMillis
    }
}

private fun millisToFrame(millis: Int, sampleRate: Int): Long =
    (millis.toDouble() * sampleRate.toDouble() / 1000.0).roundToLong()

private fun copyFrameRange(
    source: java.nio.channels.FileChannel,
    layout: WavPcmLayout,
    startFrame: Long,
    frameCount: Long,
    writer: WavAudioFileWriter,
) {
    val frameBytes = layout.frameBytes
    val framesPerBuffer = maxOf(1, TRIM_COPY_BUFFER_BYTES / frameBytes)
    val buffer = ByteArray(framesPerBuffer * frameBytes)
    var copiedFrames = 0L
    while (copiedFrames < frameCount) {
        val frames = minOf(framesPerBuffer.toLong(), frameCount - copiedFrames).toInt()
        val byteCount = frames * frameBytes
        requireReadAt(
            channel = source,
            position = layout.dataOffsetBytes + (startFrame + copiedFrames) * frameBytes.toLong(),
            bytes = buffer,
            count = byteCount,
        )
        writer.write(buffer, 0, byteCount)
        copiedFrames += frames.toLong()
    }
}
