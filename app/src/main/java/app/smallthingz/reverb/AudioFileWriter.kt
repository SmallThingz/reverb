package app.smallthingz.reverb

import java.io.Closeable

internal interface AudioFileWriter : Closeable {
    val totalSampleBytesWritten: Long
    val target: RecordingOutputTarget

    fun write(
        bytes: ByteArray,
        offset: Int,
        count: Int,
    )
}
