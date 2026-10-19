package app.smallthingz.reverb

import java.io.IOException

internal class RecordingOutputCapacityException(
    val format: ExportFormat,
    val maximumDurationMillis: Long,
) : IOException("Selected audio exceeds the $format output capacity")

internal data class RecordingConversionPlan(
    val firstFrame: Long,
    val frameCount: Long,
    val sampleFormat: PcmSampleFormat,
)

/** Source geometry is known before any output, writer or decoder is acquired. */
internal inline fun <T> withAdmittedRecordingConversion(
    layout: WavPcmLayout,
    format: ExportFormat,
    startMillis: Long,
    endMillis: Long?,
    maximumOutputFrames: Long = exportFrameLimit(
        format, ExportCodec.PCM_16, layout.sampleRate, layout.channelCount,
        if (format == ExportFormat.FLAC) PcmSampleFormat.PCM_16 else layout.sampleFormat,
    ),
    convert: (RecordingConversionPlan) -> T,
): T {
    require(startMillis >= 0L && (endMillis == null || endMillis > startMillis))
    require(layout.sampleRate > 0 && layout.frameCount > 0L && maximumOutputFrames >= 0L)
    val first = (startMillis.toDouble() * layout.sampleRate / 1000).toLong()
        .coerceIn(0L, layout.frameCount - 1L)
    val end = endMillis?.let { (it.toDouble() * layout.sampleRate / 1000).toLong() }
        ?.coerceIn(first + 1L, layout.frameCount) ?: layout.frameCount
    return withAdmittedRecordingFrameRange(layout, format, first, end - first, maximumOutputFrames, convert)
}

/** Callers retain their own frame rounding; capacity is checked before acquiring any output. */
internal inline fun <T> withAdmittedRecordingFrameRange(
    layout: WavPcmLayout,
    format: ExportFormat,
    firstFrame: Long,
    frameCount: Long,
    maximumOutputFrames: Long = exportFrameLimit(
        format, ExportCodec.PCM_16, layout.sampleRate, layout.channelCount,
        if (format == ExportFormat.FLAC) PcmSampleFormat.PCM_16 else layout.sampleFormat,
    ),
    convert: (RecordingConversionPlan) -> T,
): T {
    require(layout.sampleRate > 0 && layout.frameCount > 0L && maximumOutputFrames >= 0L)
    require(firstFrame >= 0L && frameCount > 0L && firstFrame <= layout.frameCount - frameCount)
    if (frameCount > maximumOutputFrames) {
        throw RecordingOutputCapacityException(format, maximumOutputFrames * 1000L / layout.sampleRate)
    }
    return convert(RecordingConversionPlan(firstFrame, frameCount,
        if (format == ExportFormat.FLAC) PcmSampleFormat.PCM_16 else layout.sampleFormat))
}
