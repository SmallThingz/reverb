package app.smallthingz.reverb

import android.content.Context
import android.util.Log
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.math.roundToLong

private const val TRIM_COPY_BUFFER_BYTES = 128 * 1024
private const val TRIM_TAG = "RecordingTrim"

internal fun recordingHasStableTrimIdentity(recording: RecordingEntity): Boolean =
    recording.fileIdentity.isNotBlank()

internal suspend fun saveTrimmedRecordingCopy(
    context: Context,
    recording: RecordingEntity,
    startMillis: Int,
    endMillis: Int,
): RecordingEntity {
    val appContext = context.applicationContext
    return withContext(Dispatchers.IO + NonCancellable) {
        val created = writeTrimmedRecordingCopy(appContext, recording, startMillis, endMillis)
        runCatching { RecordingRepository.register(appContext, created) }
            .onFailure { Log.w(TRIM_TAG, "Trim was saved but catalog registration failed", it) }
            .getOrDefault(created)
    }
}

internal fun trimmedRecordingBaseName(displayName: String): String {
    val base = displayName.substringBeforeLast('.', displayName).trim()
    return "${base.ifBlank { FALLBACK_DISPLAY_NAME }} trim"
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
    if (!recordingHasStableTrimIdentity(recording)) {
        throw IOException("Recording identity is unavailable for trim")
    }
    if (startMillis < 0 || endMillis <= startMillis) throw IOException("Invalid trim range")
    var target: RecordingOutputTarget? = null
    var verifiedComplete = false
    var cleanupFingerprint: StableOutputFingerprint? = null
    try {
        return withRecordingWavChannelIdentityGuard(context, recording) { source, sourceStillCurrent ->
            val layout = readWavPcmLayout(source)
            val startFrame = millisToFrame(startMillis, layout.sampleRate)
                .coerceIn(0L, layout.frameCount - 1L)
            val endFrame = millisToFrame(endMillis, layout.sampleRate)
                .coerceIn(startFrame + 1L, layout.frameCount)
            val selectedFrames = endFrame - startFrame
            if (selectedFrames <= 0L) throw IOException("Trim range contains no audio")

            val startedAtMillis = recording.startedAtMillis +
                (startFrame * 1000L / layout.sampleRate.toLong())
            val outputTarget = createOutputTarget(
                context = context,
                requestedDisplayName = "${trimmedRecordingBaseName(recording.displayName)}.wav",
                mimeType = ExportFormat.WAV.outputMimeType,
                startedAtMillis = startedAtMillis,
                stagingKind = StagingOutputKind.EXPORT_TRACKED,
            ).also { target = it }
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
            cleanupFingerprint = verifiedOutput
            // The source bytes are complete now, but the target is still hidden. Provider-backed
            // recordings can change while their descriptor remains open, so revalidate the
            // selected source before granting recovery authority or publishing a final name.
            val finalized = publishVerifiedTrimAfterSourceValidation(
                sourceStillCurrent = sourceStillCurrent,
                persistRecoveryMarker = { putVerifiedExportStaging(context, outputTarget, verifiedOutput) },
                targetId = outputTarget.id,
            ) {
                verifiedComplete = true
                finalizeOutputTarget(context, outputTarget, verifiedOutput).also { target = it }
            }
            if (!removeVerifiedExportStaging(context, outputTarget.storageType, stagingId)) {
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
    } catch (error: Exception) {
        if (!verifiedComplete) cleanupTrimTarget(context, target, cleanupFingerprint)
        throw error
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

private fun cleanupTrimTarget(
    context: Context,
    target: RecordingOutputTarget?,
    expectedFingerprint: StableOutputFingerprint?,
) {
    val current = target ?: return
    if (expectedFingerprint == null) {
        Log.w(TRIM_TAG, "Retaining unverified trim staging because cleanup identity is uncertain: ${current.id}")
        return
    }
    if (!suppressAndDeleteOutputTarget(context, current, expectedFingerprint = expectedFingerprint)) {
        Log.w(TRIM_TAG, "Deferred cleanup for failed trim ${current.displayName}")
    }
}
