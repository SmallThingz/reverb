package app.smallthingz.reverb

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.UUID
import java.util.zip.CRC32

internal fun verifyParallelHistoryRecovery(context: Context) {
    check(context.packageName.endsWith(".reliability"))
    val root = File(context.filesDir, "parallel-history-${UUID.randomUUID()}")
    val chunks = File(root, "chunks").apply { check(mkdirs()) }
    val payload = ByteArray(512)
    val crc = CRC32().apply { update(payload) }.value.toInt()
    repeat(1024) { id -> File(chunks, id.toString()).writeBytes(fixtureChunkHeader(id, payload.size, crc) + payload) }
    RandomAccessFile(File(chunks, "17"), "rw").use { file -> file.seek(40); file.writeInt(0) }
    val corrupt = File(chunks, "17").readBytes()
    val outside = File(root, "unowned-audio").apply { writeText("Unowned audio must survive") }
    check(File(chunks, "18").delete()) // Replace only this freshly generated synthetic fixture.
    Files.createSymbolicLink(File(chunks, "18").toPath(), outside.toPath())
    RandomAccessFile(File(chunks, "19"), "rw").use { it.setLength(256) }
    val truncated = File(chunks, "19").readBytes()
    File(root, "retired").apply { check(mkdir()) }
    File(root, "retired/1000").writeText("v2|1000|${fixtureCreatedAt(1000)}|48000|1|2")
    val activeHeader = fixtureChunkHeader(1023, payload.size, crc)
    ByteBuffer.wrap(activeHeader).order(ByteOrder.LITTLE_ENDIAN).putInt(56, 1)
    ByteBuffer.wrap(activeHeader).order(ByteOrder.LITTLE_ENDIAN)
        .putInt(80, CRC32().apply { update(activeHeader, 48, 32) }.value.toInt())
    val tail = ByteArray(128) { 0x23 }
    File(chunks, "1023").writeBytes(activeHeader + payload + tail)

    repeat(2) {
        PersistentAudioChunkStore(root).use { store ->
            store.configure(RetentionMode.SIZE, Long.MAX_VALUE, 48000, 1, PcmSampleFormat.PCM_16,
                deferRetentionCleanup = true)
            check(store.peekSnapshot()?.chunkCount == 1020)
            val expected = ByteArray(1020 * payload.size) + tail
            val output = ByteArrayOutputStream()
            requireNotNull(store.acquireRange(0.0, store.durationSeconds())).use { lease ->
                lease.readNormalized(48000, 1, PcmSampleFormat.PCM_16) { bytes, offset, count ->
                    output.write(bytes, offset, count); count
                }
            }
            check(output.toByteArray().contentEquals(expected)) { "Parallel startup changed recovered PCM" }
            var steps = 0
            while (store.retentionMaintenanceNeeded()) {
                check(++steps <= 1)
                check(store.performRetentionMaintenanceStep().progressed)
            }
        }
        check(!File(chunks, "1000").exists())
        check(outside.readText() == "Unowned audio must survive")
        val preserved = File(root, "preserved").listFiles().orEmpty().map(File::readBytes)
        check(preserved.any { it.contentEquals(corrupt) })
        check(preserved.any { it.contentEquals(truncated) })
    }
}
