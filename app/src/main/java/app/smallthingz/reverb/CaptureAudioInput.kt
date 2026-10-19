package app.smallthingz.reverb

import android.media.AudioRecord
import java.nio.ByteBuffer

/** One owned microphone resource; production construction/configuration stays in the Service. */
internal interface CaptureAudioInput {
    val state: Int
    val recordingState: Int
    fun startRecording()
    fun read(buffer: ByteBuffer, sizeInBytes: Int, readMode: Int): Int
    fun stop()
    fun release()
}

internal class AndroidCaptureAudioInput(private val record: AudioRecord) : CaptureAudioInput {
    override val state: Int get() = record.state
    override val recordingState: Int get() = record.recordingState
    override fun startRecording() = record.startRecording()
    override fun read(buffer: ByteBuffer, sizeInBytes: Int, readMode: Int): Int =
        record.read(buffer, sizeInBytes, readMode)
    override fun stop() = record.stop()
    override fun release() = record.release()
}
