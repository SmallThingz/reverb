package app.smallthingz.reverb

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.zip.CRC32

internal fun verifyChunkHeaderCacheOwnership(context: Context) {
    check(context.packageName.endsWith(".reliability"))
    val root = File(context.filesDir, "header-cache-${UUID.randomUUID()}")
    val payload = ByteArray(512) { (it * 17).toByte() }
    PersistentAudioChunkStore(root).use { store ->
        store.configure(RetentionMode.SIZE, 65536L, 8000, 1)
        store.append(payload, 0, payload.size)
        store.sealActiveChunk()
    }
    repeat(2) {
        PersistentAudioChunkStore(root).use { store ->
            store.configure(RetentionMode.SIZE, 65536L, 8000, 1)
            check(store.durationSeconds() == 0.032)
        }
    }
    val chunk = File(root, "chunks/0")
    // A valid, same-size changed header must invalidate the cached geometry.
    RandomAccessFile(chunk, "rw").use { access ->
        val header = ByteArray(128)
        access.readFully(header)
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(24, 16000)
        buffer.putInt(40, CRC32().apply { update(header, 0, 40) }.value.toInt())
        access.seek(0); access.write(header); access.fd.sync()
    }
    PersistentAudioChunkStore(root).use { store ->
        store.configure(RetentionMode.SIZE, 65536L, 8000, 1)
        check(store.durationSeconds() == 0.016) { "Cache replayed geometry from an older file revision" }
    }
    // Corrupt the immutable header without changing file size. No old cache may bless it.
    RandomAccessFile(chunk, "rw").use { access -> access.seek(24); access.write(0); access.fd.sync() }
    val corruptBytes = chunk.readBytes()
    PersistentAudioChunkStore(root).use { store ->
        store.configure(RetentionMode.SIZE, 65536L, 8000, 1)
        check(!store.hasData()) { "Cache hid a corrupted durable header" }
    }
    check(File(root, "preserved").listFiles().orEmpty().any { it.readBytes().contentEquals(corruptBytes) })
}
