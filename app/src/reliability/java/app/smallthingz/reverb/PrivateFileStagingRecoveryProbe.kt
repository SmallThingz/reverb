package app.smallthingz.reverb

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Environment
import android.system.Os
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/** Actual private FILE scanner/JNI/catalog; no Service, microphone, UI or default MediaStore scan. */
internal fun verifyPrivateFileStagingRecovery(context: Context, report: StringBuilder) {
    check(context.packageName == "app.smallthingz.reverb.uitheme.reliability")
    check(context.applicationContext.javaClass == Application::class.java)
    val singleton = RecordingDatabase::class.java.getDeclaredField("instance").apply { isAccessible = true }
    check(singleton.get(null) == null) { "Another QA database helper owns this process" }
    val root = File(context.filesDir, "file-recovery-${UUID.randomUUID()}").apply { check(mkdir()) }
    val receipt = File(root, "EVIDENCE.json")
    var db: RecordingDatabase? = null
    var failure: Throwable? = null
    var evidence: JSONObject? = null
    fun writeEvidence() = writeQaEvidenceAtomically(receipt) {
        it.write((checkNotNull(evidence).toString(2) + "\n").toByteArray())
    }
    report.appendLine("EVIDENCE current_file_staged_recovery ${receipt.path}")
    try {
        val detail = JSONObject().put("phase", "ADMITTED").put("root", root.path)
        evidence = detail
        val preferenceName = "file-recovery-${root.name}"
        detail.put("preferences", preferenceName)
        val preferences = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
        val owned = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getExternalFilesDir(type: String?): File { check(type == Environment.DIRECTORY_MUSIC); return root }
            override fun getNoBackupFilesDir(): File = File(root, "no-backup").apply { check(isDirectory || mkdir()) }
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = preferences
        }
        val directory = getSavedRecordingsDirectory(owned).apply { check(mkdir()) }
        val staged = File(directory, "reverb-partial-export__old-session__token__Y2xpcC53YXY.wav")
        val collision = File(directory, "clip.wav")
        val published = File(directory, "clip (2).wav")
        val ids = listOf(staged.absolutePath, collision.absolutePath, published.absolutePath)
        detail.put("entry", "listLegacyAppStorageRecordings -> RecordingRepository.register")
            .put("staged", staged.path).put("collision", collision.path).put("published", published.path)
        fun wav(sample: Short): ByteArray = ByteBuffer.allocate(1644).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII)); putInt(1636)
            put("WAVEfmt ".toByteArray(Charsets.US_ASCII)); putInt(16)
            putShort(1); putShort(1); putInt(8000); putInt(16000); putShort(2); putShort(16)
            put("data".toByteArray(Charsets.US_ASCII)); putInt(1600); repeat(800) { putShort(sample) }
        }.array()
        val bytes = wav(123)
        val other = wav(-123)
        val keys = listOf(PrefKey.VERIFIED_EXPORT_STAGING, PrefKey.PENDING_OUTPUT_CLEANUP, PrefKey.PENDING_RECORDING_DELETIONS)
        val journals = keys.associateWith { preferences.getStringSet(it.name, emptySet())!!.toSet() }
        writeEvidence()
        val active = RecordingDatabase.getInstance(owned).also { db = it }
        check(singleton.get(null) === active)
        check(File(active.writableDatabase.path).canonicalFile == context.getDatabasePath(checkNotNull(active.databaseName)).canonicalFile)
        val dao = active.recordingDao()
        runBlocking { ids.forEach { check(dao.findById(it) == null) } }
        check(staged.createNewFile()); FileOutputStream(staged).use { it.write(bytes); it.fd.sync() }
        check(collision.createNewFile()); FileOutputStream(collision).use { it.write(other); it.fd.sync() }
        forceRecordingDirectoryDurable(directory)
        val before = Os.lstat(staged.path)
        val unrelated = Os.lstat(collision.path)
        detail.put("source_dev", before.st_dev).put("source_ino", before.st_ino)
            .put("collision_dev", unrelated.st_dev).put("collision_ino", unrelated.st_ino)
        writeEvidence()
        val listed = listLegacyAppStorageRecordings(owned)
        check(listed.map { it.id }.toSet() == setOf(collision.path, published.path) && listed.size == 2)
        val row = listed.single { it.id == published.path }
        check(!staged.exists() && published.readBytes().contentEquals(bytes) && collision.readBytes().contentEquals(other))
        val after = Os.lstat(published.path)
        check(before.st_dev == after.st_dev && before.st_ino == after.st_ino) { "Native rename not established; copied inode is not proof" }
        check(Os.lstat(collision.path).let { it.st_dev == unrelated.st_dev && it.st_ino == unrelated.st_ino })
        check(row.displayName == "clip (2).wav" && row.storageType == RecordingStorageType.FILE && row.directoryId == directory.path)
        check(row.durationMillis == 100L && row.sizeBytes == 1644L)
        check(row.fileIdentity == "stat:${after.st_dev}:${after.st_ino}:${after.st_ctim.tv_sec}:${after.st_ctim.tv_nsec}:0")
        val registered = runBlocking { RecordingRepository.register(owned, row) }
        check(runBlocking { dao.findById(published.path) } == registered)
        val again = listLegacyAppStorageRecordings(owned)
        check(again.map { it.id }.toSet() == setOf(collision.path, published.path) && again.size == 2)
        check(again.single { it.id == published.path }.fileIdentity == row.fileIdentity)
        check(!staged.exists() && published.readBytes().contentEquals(bytes) && collision.readBytes().contentEquals(other))
        check(Os.lstat(published.path).let { it.st_dev == after.st_dev && it.st_ino == after.st_ino &&
            it.st_ctim.tv_sec == after.st_ctim.tv_sec && it.st_ctim.tv_nsec == after.st_ctim.tv_nsec })
        check(Os.lstat(collision.path).let { it.st_dev == unrelated.st_dev && it.st_ino == unrelated.st_ino &&
            it.st_ctim.tv_sec == unrelated.st_ctim.tv_sec && it.st_ctim.tv_nsec == unrelated.st_ctim.tv_nsec })
        check(runBlocking { dao.findById(published.path) } == registered)
        keys.forEach { check(preferences.getStringSet(it.name, emptySet()) == journals[it]) }
        detail.put("phase", "VERIFIED").put("published_dev", after.st_dev).put("published_ino", after.st_ino)
            .put("identity", row.fileIdentity).put("size", row.sizeBytes).put("duration_ms", row.durationMillis)
            .put("sha256", MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) })
            .put("collision_sha256", MessageDigest.getInstance("SHA-256").digest(other).joinToString("") { "%02x".format(it.toInt() and 255) })
        writeEvidence()
        runBlocking { ids.forEach { dao.deleteById(it) }; ids.forEach { check(dao.findById(it) == null) } }
        check(Os.lstat(published.path).let { it.st_dev == after.st_dev && it.st_ino == after.st_ino &&
            it.st_ctim.tv_sec == after.st_ctim.tv_sec && it.st_ctim.tv_nsec == after.st_ctim.tv_nsec })
        check(published.readBytes().contentEquals(bytes)); check(published.delete())
        check(Os.lstat(collision.path).let { it.st_dev == unrelated.st_dev && it.st_ino == unrelated.st_ino &&
            it.st_ctim.tv_sec == unrelated.st_ctim.tv_sec && it.st_ctim.tv_nsec == unrelated.st_ctim.tv_nsec })
        check(collision.readBytes().contentEquals(other)); check(collision.delete()); check(directory.delete())
        check(context.deleteSharedPreferences(preferenceName))
    } catch (error: Throwable) { failure = error }
    finally {
        failure = captureNativeParityCleanup(failure) {
            db?.let { check(singleton.get(null) === it); RecordingDatabase.resetAfterCorruption(); check(singleton.get(null) == null) }
        }
        failure = captureNativeParityCleanup(failure) {
            val detail = evidence ?: JSONObject().put("root", root.path).also { evidence = it }
            detail.put("phase", if (failure == null) "PASS_PAYLOAD_CATALOG_CLEANED" else "FAILED_PRESERVED")
            failure?.let { detail.put("error", it.toString()) }
            writeEvidence()
        }
    }
    failure?.let { throw it }
    report.appendLine("PASS private FILE recovery; unchanged packaged JNI; exact 1644-byte WAV, native inode, collision, catalog and repeat scan")
}
