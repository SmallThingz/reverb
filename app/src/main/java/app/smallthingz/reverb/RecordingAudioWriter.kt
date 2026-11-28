package app.smallthingz.reverb

import android.content.Context
import java.io.Closeable
import smallthingz.ui.util.closePreservingPrimaryFailure

internal interface RecordingAudioWriter : Closeable {
    val totalSampleBytesWritten: Long
    val totalFileBytesWritten: Long
    fun write(bytes: ByteArray, offset: Int, count: Int)
    fun verify(context: Context, checkCancelled: () -> Unit = {}): StableOutputFingerprint
    fun abort(primaryFailure: Throwable) = close()
}

internal inline fun RecordingAudioWriter.useForDurableSave(crossinline writePcm: () -> Unit) {
    try {
        writePcm()
    } catch (failure: Throwable) {
        closePreservingPrimaryFailure(failure) { abort(failure) }
        throw failure
    }
    close()
}

internal fun createRecordingAudioWriter(
    context: Context,
    target: RecordingOutputTarget,
    format: ExportFormat,
    sampleRate: Int,
    channelCount: Int,
    sampleFormat: PcmSampleFormat,
    compressionLevel: Int,
): RecordingAudioWriter = when (format) {
    ExportFormat.WAV -> WavAudioFileWriter(context, target, sampleRate, channelCount, sampleFormat)
    ExportFormat.FLAC -> {
        require(sampleFormat == PcmSampleFormat.PCM_16) { "FLAC output requires 16-bit PCM; select 16-bit capture in Settings" }
        FlacAudioFileWriter(context, target, sampleRate, channelCount, compressionLevel)
    }
}
