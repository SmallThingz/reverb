package app.smallthingz.reverb

import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.ClosedChannelException
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class WavStructureFailureTypeTest {
    @Test fun readFailureIsNotMalformedContent() = fixture { file ->
        file.writeBytes(buildWavHeaderBytes(8000, 1, PcmSampleFormat.PCM_16, 2) + byteArrayOf(0, 0))
        val access = RandomAccessFile(file, "r")
        val channel = access.channel
        access.close()
        assertThrows(ClosedChannelException::class.java) { readWavPcmLayout(channel) }
    }

    @Test fun malformedReadableStructureIsSeparatelyClassified() = fixture { file ->
        for (bytes in listOf(byteArrayOf(1, 2),
            buildWavHeaderBytes(8000, 1, PcmSampleFormat.PCM_16, 2) + byteArrayOf(0, 0, 3))) {
            file.writeBytes(bytes)
            RandomAccessFile(file, "r").use { access ->
                assertThrows(InvalidWavStructureException::class.java) { readWavPcmLayout(access.channel) }
            }
        }
    }

    private fun fixture(block: (File) -> Unit) {
        val parent = File("build/tmp/wav-structure-test").apply { mkdirs() }
        val file = Files.createTempFile(parent.toPath(), "structure-", ".wav").toFile()
        try { block(file) } finally { file.delete() }
    }
}
