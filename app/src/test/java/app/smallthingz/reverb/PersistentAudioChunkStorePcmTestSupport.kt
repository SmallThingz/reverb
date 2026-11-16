package app.smallthingz.reverb

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals

internal fun readStoreTestPcm16Mono8Khz(lease: PersistentAudioChunkStore.RangeLease): ByteArray {
    val output = ByteArrayOutputStream()
    val result = lease.readNormalized(8_000, 1, PcmSampleFormat.PCM_16) { bytes, offset, count ->
        output.write(bytes, offset, count)
        count
    }
    assertEquals(output.size().toLong(), result.sampleBytes)
    return output.toByteArray()
}
