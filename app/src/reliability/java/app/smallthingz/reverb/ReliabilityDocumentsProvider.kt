package app.smallthingz.reverb

import android.database.Cursor
import android.database.MatrixCursor
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsProvider
import java.io.File
import java.io.FileNotFoundException

/** Real ContentResolver fixture, compiled only by scripts/reliability.gradle. */
class ReliabilityDocumentsProvider : DocumentsProvider() {
    enum class Mode {
        RENAME_NEW_ID,
        RENAME_SAME_ID,
        RENAME_NOOP,
        RENAME_PROVIDER_SUFFIX,
        COPY_INSTEAD_OF_RENAME,
        RENAME_THEN_THROW,
        CORRUPT_RENAMED_BYTES,
        LOADING_AFTER_RENAME,
        ERROR_AFTER_RENAME,
        UNAVAILABLE_AFTER_RENAME,
        DUPLICATE_LISTING_AFTER_RENAME,
        UNKNOWN_SIZE,
        UNKNOWN_MODIFIED,
        ZERO_MODIFIED,
        METADATA_APPEARS_AFTER_WRITE,
        UNKNOWN_SIZE_NONEMPTY,
        UNKNOWN_METADATA_PIPE,
        DELETE_NOOP,
        DELETE_THEN_THROW,
        DELETE_LOADING,
        DELETE_ERROR,
        DELETE_LISTING_UNAVAILABLE,
        DELETE_NOT_LISTED,
        WRITE_TIMEOUT,
    }

    private data class Entry(var file: File, val mimeType: String)
    private val entries = linkedMapOf<String, Entry>()
    private lateinit var root: File
    var mode = Mode.RENAME_NEW_ID
    var renamed = false
        private set
    var deleteCalls = 0
        private set
    var oldIdQueries = 0
        private set
    var readOpenCalls = 0
        private set
    var writeOpenCalls = 0
        private set
    private var substitutedRead = -1
    var afterNextListing: (() -> Unit)? = null

    override fun onCreate(): Boolean {
        root = File(requireNotNull(context).filesDir, "reliability-documents")
        check(root.mkdirs() || root.isDirectory)
        return true
    }

    @Synchronized
    fun reset(nextMode: Mode) {
        entries.clear()
        root.listFiles()?.forEach { check(it.delete()) }
        mode = nextMode
        renamed = false
        deleteCalls = 0
        oldIdQueries = 0
        readOpenCalls = 0
        writeOpenCalls = 0
        substitutedRead = -1
        afterNextListing = null
    }

    @Synchronized
    fun substituteReadAfter(reads: Int) {
        require(reads > 0)
        substitutedRead = readOpenCalls + reads
    }

    @Synchronized
    fun replaceDocumentWithSameBytes(documentId: String) {
        val entry = requireNotNull(entries[documentId])
        val replacement = File(root, ".replacement-${java.util.UUID.randomUUID()}")
        entry.file.copyTo(replacement)
        check(entry.file.renameTo(File(root, ".prior-${java.util.UUID.randomUUID()}")))
        check(replacement.renameTo(entry.file))
    }

    @Synchronized
    fun restoreExistingDocument(documentId: String) {
        check(documentId.startsWith("$ROOT_ID/"))
        val name = documentId.removePrefix("$ROOT_ID/")
        check(File(name).name == name)
        val file = File(root, name)
        check(file.isFile)
        entries[documentId] = Entry(file, "audio/wav")
    }

    @Synchronized
    fun retainedBytes(): List<ByteArray> = entries.values.map { it.file.readBytes() }

    override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(
        projection ?: arrayOf(
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_FLAGS,
        ),
    ).apply {
        addRow(columnNames.map {
            when (it) {
                DocumentsContract.Root.COLUMN_ROOT_ID -> ROOT_ID
                DocumentsContract.Root.COLUMN_DOCUMENT_ID -> ROOT_ID
                DocumentsContract.Root.COLUMN_TITLE -> "Reverb reliability fixture"
                DocumentsContract.Root.COLUMN_FLAGS -> DocumentsContract.Root.FLAG_SUPPORTS_CREATE
                else -> null
            }
        })
    }

    @Synchronized
    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: COLUMNS)
        if (documentId != ROOT_ID && deleteCalls > 0 &&
            (mode == Mode.DELETE_LOADING || mode == Mode.DELETE_ERROR)
        ) {
            cursor.extras = deletionQueryExtras()
            return cursor
        }
        if (documentId == ROOT_ID) {
            cursor.addEntry(ROOT_ID, root, Document.MIME_TYPE_DIR)
        } else {
            val entry = entries[documentId] ?: run {
                oldIdQueries++
                // Path-backed Android document providers invalidate the old ID on rename.
                throw FileNotFoundException("Document no longer exists: $documentId")
            }
            cursor.addEntry(documentId, entry.file, entry.mimeType)
        }
        return cursor
    }

    @Synchronized
    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        check(parentDocumentId == ROOT_ID)
        if (deleteCalls > 0 && mode == Mode.DELETE_LISTING_UNAVAILABLE) {
            throw IllegalStateException("Fixture directory unavailable after deletion")
        }
        if (deleteCalls > 0 && (mode == Mode.DELETE_LOADING || mode == Mode.DELETE_ERROR)) {
            return MatrixCursor(projection ?: COLUMNS).apply { extras = deletionQueryExtras() }
        }
        if (mode == Mode.DELETE_NOT_LISTED) return MatrixCursor(projection ?: COLUMNS)
        if (renamed && mode == Mode.UNAVAILABLE_AFTER_RENAME) {
            throw IllegalStateException("Fixture provider temporarily unavailable")
        }
        return MatrixCursor(projection ?: COLUMNS).apply {
            for ((id, entry) in entries) addEntry(id, entry.file, entry.mimeType)
            if (renamed && mode == Mode.DUPLICATE_LISTING_AFTER_RENAME) {
                val (id, entry) = entries.entries.first()
                addEntry(id, entry.file, entry.mimeType)
            }
            if (renamed && mode == Mode.LOADING_AFTER_RENAME) {
                extras = Bundle().apply { putBoolean(DocumentsContract.EXTRA_LOADING, true) }
            }
            if (renamed && mode == Mode.ERROR_AFTER_RENAME) {
                extras = Bundle().apply { putString(DocumentsContract.EXTRA_ERROR, "Incomplete fixture listing") }
            }
            val callback = afterNextListing
            afterNextListing = null
            callback?.invoke()
        }
    }

    @Synchronized
    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        check(parentDocumentId == ROOT_ID)
        require(File(displayName).name == displayName)
        val file = File(root, displayName)
        check(file.createNewFile())
        if (mode == Mode.UNKNOWN_SIZE_NONEMPTY) file.writeText("Existing nonempty fixture bytes")
        val id = "$ROOT_ID/$displayName"
        entries[id] = Entry(file, mimeType)
        return id
    }

    @Synchronized
    override fun renameDocument(documentId: String, displayName: String): String {
        val entry = entries[documentId] ?: throw FileNotFoundException(documentId)
        require(File(displayName).name == displayName)
        if (mode == Mode.RENAME_NOOP) {
            renamed = true
            return documentId
        }
        val actualName = if (mode == Mode.RENAME_PROVIDER_SUFFIX) {
            displayName.removeSuffix(".wav") + " (provider).wav"
        } else displayName
        val destination = File(root, actualName)
        check(!destination.exists())
        if (mode == Mode.COPY_INSTEAD_OF_RENAME) {
            entry.file.copyTo(destination)
        } else {
            check(entry.file.renameTo(destination))
            entries.remove(documentId)
        }
        val nextId = if (mode == Mode.RENAME_SAME_ID) documentId else "$ROOT_ID/$actualName"
        entries[nextId] = Entry(destination, entry.mimeType)
        renamed = true
        if (mode == Mode.CORRUPT_RENAMED_BYTES) {
            val bytes = destination.readBytes()
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
            destination.writeBytes(bytes)
        }
        if (mode == Mode.RENAME_THEN_THROW) {
            throw IllegalStateException("Fixture transport failed after committed rename")
        }
        return nextId
    }

    @Synchronized
    override fun deleteDocument(documentId: String) {
        deleteCalls++
        val entry = entries[documentId] ?: throw FileNotFoundException(documentId)
        if (mode == Mode.DELETE_NOOP || mode == Mode.DELETE_LOADING || mode == Mode.DELETE_ERROR) return
        check(entry.file.delete())
        entries.remove(documentId)
        if (mode == Mode.DELETE_THEN_THROW) {
            throw IllegalStateException("Fixture transport failure after committed deletion")
        }
    }

    @Synchronized
    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        val entry = entries[documentId] ?: throw FileNotFoundException(documentId)
        if (mode == "r") {
            readOpenCalls++
            if (this.mode == Mode.UNKNOWN_METADATA_PIPE) {
                val pipe = ParcelFileDescriptor.createPipe()
                pipe[1].close()
                return pipe[0]
            }
            if (readOpenCalls == substitutedRead) {
                val substitute = File(root, ".substitute-${java.util.UUID.randomUUID()}")
                entry.file.copyTo(substitute)
                return ParcelFileDescriptor.open(substitute, ParcelFileDescriptor.MODE_READ_ONLY)
            }
        } else {
            writeOpenCalls++
            if (this.mode == Mode.WRITE_TIMEOUT) throw java.net.SocketTimeoutException("Fixture writable-open timeout")
        }
        return ParcelFileDescriptor.open(entry.file, ParcelFileDescriptor.parseMode(mode))
    }

    private fun deletionQueryExtras(): Bundle = Bundle().apply {
        if (mode == Mode.DELETE_LOADING) putBoolean(DocumentsContract.EXTRA_LOADING, true)
        if (mode == Mode.DELETE_ERROR) putString(DocumentsContract.EXTRA_ERROR, "Fixture incomplete deletion query")
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
        parentDocumentId == ROOT_ID && documentId.startsWith("$ROOT_ID/")

    private fun MatrixCursor.addEntry(id: String, file: File, mime: String) {
        addRow(columnNames.map {
            when (it) {
                Document.COLUMN_DOCUMENT_ID -> id
                Document.COLUMN_DISPLAY_NAME -> file.name
                Document.COLUMN_MIME_TYPE -> mime
                Document.COLUMN_SIZE -> if (
                    mode == Mode.UNKNOWN_SIZE || mode == Mode.UNKNOWN_SIZE_NONEMPTY ||
                    mode == Mode.UNKNOWN_METADATA_PIPE || (mode == Mode.METADATA_APPEARS_AFTER_WRITE && file.length() == 0L)
                ) null else file.length()
                Document.COLUMN_LAST_MODIFIED -> when (mode) {
                    Mode.UNKNOWN_MODIFIED, Mode.UNKNOWN_METADATA_PIPE -> null
                    Mode.ZERO_MODIFIED -> 0L
                    else -> file.lastModified().coerceAtLeast(1L)
                }
                Document.COLUMN_FLAGS -> if (mime == Document.MIME_TYPE_DIR) {
                    Document.FLAG_DIR_SUPPORTS_CREATE
                } else {
                    Document.FLAG_SUPPORTS_RENAME or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_WRITE
                }
                else -> null
            }
        })
    }

    companion object {
        const val ROOT_ID = "root"
        private val COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS,
        )
    }
}
