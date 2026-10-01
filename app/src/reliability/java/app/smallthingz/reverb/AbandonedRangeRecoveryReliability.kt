package app.smallthingz.reverb

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.UUID

internal fun prepareAbandonedRangeForProcessDeath(context: Context) {
    check(context.packageName.endsWith(".reliability"))
    val name = "range-crash-${UUID.randomUUID()}"
    val root = File(context.noBackupFilesDir, name)
    val store = PersistentAudioChunkStore(root)
    store.configure(RetentionMode.SIZE, 65536L, 8000, 1)
    val old = ByteArray(4096) { (it * 7).toByte() }
    store.append(old, 0, old.size)
    store.sealActiveChunk()
    requireNotNull(store.acquireRange(0.0, store.durationSeconds())) // This owner dies with this process.
    val current = ByteArray(512) { (it * 31 + 11).toByte() }
    store.append(current, 0, current.size)
    store.sealActiveChunk()
    store.configure(RetentionMode.SIZE, 512L, 8000, 1)
    check(store.countFilledBytes() == current.size.toLong())
    val retired = File(root, "chunks/0")
    check(retired.isFile && File(root, "retired/0").readText().startsWith("v3|"))
    val claim = File(retired.parentFile, ".reverb-retired-delete-${UUID.randomUUID()}.pending")
    Files.move(retired.toPath(), claim.toPath())
    // An unrecognized sibling must be preserved, not treated as an expired snapshot.
    File(root, "chunks/unowned-old-bytes").writeText("Unowned bytes must survive")
    FileOutputStream(File(context.filesDir, "reliability-abandoned-range.txt")).use {
        it.write(name.toByteArray()); it.fd.sync()
    }
}

internal fun verifyAbandonedRangeAfterProcessDeath(context: Context) {
    check(context.packageName.endsWith(".reliability"))
    val name = File(context.filesDir, "reliability-abandoned-range.txt").readText()
    check(name.startsWith("range-crash-") && File(name).name == name)
    val root = File(context.noBackupFilesDir, name)
    PersistentAudioChunkStore(root).use { store ->
        store.configure(RetentionMode.SIZE, 512L, 8000, 1)
        val recovered = ByteArrayOutputStream()
        requireNotNull(store.acquireRange(0.0, store.durationSeconds())).use { lease ->
            lease.readNormalized(8000, 1, PcmSampleFormat.PCM_16) { bytes, offset, count ->
                recovered.write(bytes, offset, count); count
            }
        }
        check(recovered.toByteArray().contentEquals(ByteArray(512) { (it * 31 + 11).toByte() }))
    }
    check(File(root, "chunks").listFiles()!!.none { it.name.startsWith(".reverb-retired-delete-") })
    check(File(root, "retired").listFiles().orEmpty().isEmpty())
    val preserved = File(root, "preserved").listFiles().orEmpty()
    check(preserved.size == 1 && preserved.single().readText() == "Unowned bytes must survive") {
        "Known retirement was stranded, or unowned bytes were deleted"
    }
}
