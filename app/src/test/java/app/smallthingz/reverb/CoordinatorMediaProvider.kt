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
    var name: String? = null
    var pending = 1
    var inserts = 0
    var updates = 0
    var deleted = false
    private val descriptors = mutableListOf<Pair<ParcelFileDescriptor, FileDescriptor>>()
    private val cursors = mutableListOf<Cursor>()
    override fun onCreate() = true
    override fun insert(uri: Uri, values: ContentValues?): Uri {
        check(uri == MediaStore.Audio.Media.EXTERNAL_CONTENT_URI && inserts == 0)
        val row = requireNotNull(values)
        check(row.getAsString(MediaStore.MediaColumns.MIME_TYPE) == "audio/wav")
        check(row.getAsString(MediaStore.MediaColumns.RELATIVE_PATH) == "Music/Reverb/")
        check(row.getAsInteger(MediaStore.MediaColumns.IS_PENDING) == 1)
        check(row.getAsLong(MediaStore.MediaColumns.SIZE) == 0L)
        name = requireNotNull(row.getAsString(MediaStore.MediaColumns.DISPLAY_NAME))
        check(payload.createNewFile())
        inserts++
        return this.uri
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val columns = requireNotNull(projection)
        val result = MatrixCursor(columns)
        cursors += result
        val present = inserts == 1 && !deleted
        val selected = uri == this.uri || (uri == MediaStore.Audio.Media.EXTERNAL_CONTENT_URI &&
            selectionArgs?.contains(name) == true)
        if (present && selected) {
            val row: Map<String, Any?> = mapOf(
                MediaStore.MediaColumns._ID to 731941L,
                MediaStore.MediaColumns.DISPLAY_NAME to name,
                MediaStore.MediaColumns.MIME_TYPE to "audio/wav",
                MediaStore.MediaColumns.SIZE to payload.length(),
                MediaStore.MediaColumns.DATE_MODIFIED to 100L,
                MediaStore.MediaColumns.GENERATION_MODIFIED to 9L,
                MediaStore.MediaColumns.RELATIVE_PATH to "Music/Reverb/",
                MediaStore.MediaColumns.IS_PENDING to pending,
            )
            result.addRow(columns.map { check(row.containsKey(it)) { "Unexpected column: $it" }; row[it] })
        }
        return result
    }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        check(uri == this.uri && inserts == 1 && !deleted)
        val flags = when (mode) {
            "r" -> ParcelFileDescriptor.MODE_READ_ONLY
            "rw" -> ParcelFileDescriptor.MODE_READ_WRITE
            else -> error("Unexpected descriptor mode $mode")
        }
        return ParcelFileDescriptor.open(payload, flags).also { descriptors += it to it.fileDescriptor }
    }
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
        check(uri == this.uri && !deleted)
        val row = requireNotNull(values)
        name = row.getAsString(MediaStore.MediaColumns.DISPLAY_NAME) ?: name
        pending = row.getAsInteger(MediaStore.MediaColumns.IS_PENDING) ?: pending
        updates++
        return 1
    }
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        check(uri == this.uri && !deleted)
        check(payload.delete()); deleted = true
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
