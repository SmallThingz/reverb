package app.smallthingz.reverb

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.io.File
import java.io.FileDescriptor

/** External boundary only; isolated file and actual descriptors, never native MediaStore proof. */
internal class CoordinatorMediaProvider(private val payload: File) : ContentProvider() {
    val ownedJobIds = mutableSetOf<String>()
    val uri: Uri = Uri.parse("content://media/external/audio/media/731941")
    val retryUri: Uri = Uri.parse("content://media/external/audio/media/731942")
    val sentinelUri: Uri = Uri.parse("content://media/external/audio/media/731943")
    private data class Row(val uri: Uri, val file: File, var name: String, var pending: Int = 1,
        var deleted: Boolean = false, var generation: Long = 9)
    private val rows = mutableListOf<Row>()
    var allowRetry = false
    val name: String? get() = rows.firstOrNull { it.uri == uri }?.name
    val pending: Int get() = rows.firstOrNull { it.uri == uri }?.pending ?: 1
    var inserts = 0
    var updates = 0
    val deleted: Boolean get() = rows.firstOrNull { it.uri == uri }?.deleted ?: false
    val deletedUris = mutableListOf<Uri>()
    val attemptedDeleteUris = mutableListOf<Uri>()
    val deletedPayloads = mutableMapOf<Uri, ByteArray>()
    var replaceOnNextRead: ByteArray? = null
    var copiedOutputReportedSize: Long? = null
    var beforeOpenRead: ((Uri) -> Unit)? = null
    var wrapReadDescriptor: ((Uri, ParcelFileDescriptor) -> ParcelFileDescriptor)? = null
    var retainedBeforeReplacement: File? = null
    var readReplacementCount = 0
    val openedModes = mutableListOf<String>()
    val catalogObservationUris = mutableListOf<Uri>()
    val ownedOutputUris: List<Uri> get() = rows.filter { it.uri != sentinelUri }.map { it.uri }
    fun output(uri: Uri): File = rows.single { it.uri == uri }.file
    fun isDeleted(uri: Uri): Boolean = rows.single { it.uri == uri }.deleted
    fun pendingOf(uri: Uri): Int = rows.single { it.uri == uri }.pending
    fun nameOf(uri: Uri): String = rows.single { it.uri == uri }.name
    fun addSentinel(bytes: ByteArray) {
        val file = File(payload.parentFile, "provider-sentinel.wav")
        check(file.createNewFile())
        file.writeBytes(bytes)
        rows += Row(sentinelUri, file, "sentinel.wav", pending = 0)
    }
    private val descriptors = mutableListOf<Pair<ParcelFileDescriptor, FileDescriptor>>()
    private val cursors = mutableListOf<Cursor>()
    override fun onCreate() = true
    override fun insert(uri: Uri, values: ContentValues?): Uri {
        check(uri == MediaStore.Audio.Media.EXTERNAL_CONTENT_URI && (inserts == 0 || (allowRetry && inserts == 1)))
        val row = requireNotNull(values)
        check(row.getAsString(MediaStore.MediaColumns.MIME_TYPE) == "audio/wav")
        check(row.getAsString(MediaStore.MediaColumns.RELATIVE_PATH) == "Music/Reverb/")
        check(row.getAsInteger(MediaStore.MediaColumns.IS_PENDING) == 1)
        check(row.getAsLong(MediaStore.MediaColumns.SIZE) == 0L)
        val output = if (inserts == 0) payload else File(payload.parentFile, "provider-retry.wav")
        check(output.createNewFile())
        val created = Row(if (inserts == 0) this.uri else retryUri, output,
            requireNotNull(row.getAsString(MediaStore.MediaColumns.DISPLAY_NAME)))
        rows += created
        inserts++
        return created.uri
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val columns = requireNotNull(projection)
        if (columns.contentEquals(arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns.GENERATION_MODIFIED,
                MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.IS_PENDING))) {
            catalogObservationUris += uri
        }
        val result = MatrixCursor(columns)
        cursors += result
        for (entry in rows.filter { !it.deleted && (uri == it.uri ||
            (uri == MediaStore.Audio.Media.EXTERNAL_CONTENT_URI && selectionArgs?.contains(it.name) == true)) }) {
            val row: Map<String, Any?> = mapOf(
                MediaStore.MediaColumns._ID to entry.uri.lastPathSegment!!.toLong(),
                MediaStore.MediaColumns.DISPLAY_NAME to entry.name,
                MediaStore.MediaColumns.MIME_TYPE to "audio/wav",
                MediaStore.MediaColumns.SIZE to if (entry.uri != sentinelUri && entry.file.length() > 0L) {
                    copiedOutputReportedSize ?: entry.file.length()
                } else entry.file.length(),
                MediaStore.MediaColumns.DATE_MODIFIED to 100L,
                MediaStore.MediaColumns.GENERATION_MODIFIED to entry.generation,
                MediaStore.MediaColumns.RELATIVE_PATH to "Music/Reverb/",
                MediaStore.MediaColumns.IS_PENDING to entry.pending,
            )
            result.addRow(columns.map { check(row.containsKey(it)) { "Unexpected column: $it" }; row[it] })
        }
        return result
    }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val entry = rows.single { it.uri == uri && !it.deleted }
        val flags = when (mode) {
            "r" -> ParcelFileDescriptor.MODE_READ_ONLY
            "rw" -> ParcelFileDescriptor.MODE_READ_WRITE
            else -> error("Unexpected descriptor mode $mode")
        }
        if (mode == "r") {
            beforeOpenRead?.invoke(uri)
            replaceOnNextRead?.let { bytes ->
                check(uri == this.uri) { "Replacement hook targeted an unowned row" }
                replaceOnNextRead = null
                val original = File(payload.parentFile, "provider-before-replacement.wav")
                check(entry.file.renameTo(original))
                retainedBeforeReplacement = original
                entry.file.writeBytes(bytes)
                entry.generation = 10
                readReplacementCount++
            }
        }
        val descriptor = ParcelFileDescriptor.open(entry.file, flags)
        openedModes += mode
        descriptors += descriptor to descriptor.fileDescriptor
        return if (mode == "r") wrapReadDescriptor?.invoke(uri, descriptor) ?: descriptor else descriptor
    }
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
        val entry = rows.single { it.uri == uri && !it.deleted }
        val row = requireNotNull(values)
        entry.name = row.getAsString(MediaStore.MediaColumns.DISPLAY_NAME) ?: entry.name
        entry.pending = row.getAsInteger(MediaStore.MediaColumns.IS_PENDING) ?: entry.pending
        updates++
        return 1
    }
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        attemptedDeleteUris += uri
        check(uri != sentinelUri) { "Sentinel cannot be deleted" }
        val entry = rows.single { it.uri == uri && !it.deleted }
        deletedPayloads[uri] = entry.file.readBytes()
        check(entry.file.delete()); entry.deleted = true; deletedUris += uri
        return 1
    }
    override fun getType(uri: Uri) = "audio/wav"
    fun assertClosed() {
        check(descriptors.all { !it.second.valid() }) { "Provider descriptor leaked" }
        check(cursors.all { it.isClosed }) { "Provider cursor leaked" }
    }
    fun closeLeaked() {
        var first: Throwable? = null
        descriptors.forEach { (owner, fd) -> if (fd.valid()) try { owner.close() } catch (e: Throwable) {
            if (first == null) first = e else if (first !== e) first!!.addSuppressed(e)
        } }
        cursors.forEach { if (!it.isClosed) try { it.close() } catch (e: Throwable) {
            if (first == null) first = e else if (first !== e) first!!.addSuppressed(e)
        } }
        first?.let { throw it }
    }
}
