package app.smallthingz.reverb

import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileNotFoundException
import java.io.FileInputStream
import java.nio.file.Files
import java.util.UUID
import org.json.JSONObject
import org.json.JSONTokener

/** Opt-in QA-only path-backed provider. No reset/delete operation and no volatile membership map. */
class DurableDocumentFixtureProvider : DocumentsProvider() {
    // Sibling QA variants may load this provider; actual file access is package-gated below.
    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: arrayOf(Root.COLUMN_ROOT_ID)) // Direct fixture URI only; no picker roots.

    @Synchronized
    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        val name = documentFixtureDirectChildName(parentDocumentId, documentId) ?: return false
        val owner = documentFixtureRoot(requireNotNull(context), parentDocumentId)
        val file = owner.file(name)
        // An old path-backed ID remains in this tree's scope after rename. Its document
        // query must report missing, rather than turn ordinary retirement into a grant error.
        if (storagePathState(file) == StoragePathState.MISSING) return true
        return OsConstants.S_ISREG(Os.lstat(file.path).st_mode)
    }

    @Synchronized
    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val (run, name) = documentFixtureId(documentId)
        val owner = documentFixtureRoot(requireNotNull(context), run)
        val file = if (name == null) owner.documents else owner.file(name)
        if (storagePathState(file) == StoragePathState.MISSING) throw FileNotFoundException(documentId)
        return rows(projection).apply { addDocument(this, documentId, file, name == null) }
    }

    @Synchronized
    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        val (run, name) = documentFixtureId(parentDocumentId)
        check(name == null)
        val owner = documentFixtureRoot(requireNotNull(context), run)
        val children = checkNotNull(owner.documents.listFiles())
        check(children.size <= 16) { "Unexpected synthetic provider membership; preserve it" }
        return rows(projection).apply {
            children.sortedBy { it.name }.forEach { file ->
                owner.file(file.name)
                check(OsConstants.S_ISREG(Os.lstat(file.path).st_mode))
                addDocument(this, "$run/${file.name}", file, false)
            }
        }
    }

    @Synchronized
    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val (run, name) = documentFixtureId(parentDocumentId)
        check(name == null && mimeType in setOf("audio/wav", "audio/flac"))
        val owner = documentFixtureRoot(requireNotNull(context), run)
        check(documentFixtureMime(displayName) == mimeType)
        val file = owner.file(displayName)
        documentFixtureWriteNew(file, ByteArray(0))
        return "$run/$displayName"
    }

    @Synchronized
    override fun renameDocument(documentId: String, displayName: String): String {
        val (run, name) = documentFixtureId(documentId)
        val owner = documentFixtureRoot(requireNotNull(context), run)
        val source = owner.file(requireNotNull(name))
        check(OsConstants.S_ISREG(Os.lstat(source.path).st_mode))
        check(documentFixtureMime(name) == documentFixtureMime(displayName))
        val destination = owner.file(displayName)
        check(storagePathState(destination) == StoragePathState.MISSING)
        // Count accepted invocations durably before physical mutation; restart never resets this count.
        documentFixtureWriteNew(File(owner.root, "rename-${UUID.randomUUID()}.json"),
            JSONObject().put("source", documentId).put("name", displayName).toString().toByteArray())
        Files.move(source.toPath(), destination.toPath())
        forceRecordingDirectoryDurable(owner.documents)
        return "$run/$displayName"
    }

    @Synchronized
    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        signal?.throwIfCanceled()
        val (run, name) = documentFixtureId(documentId)
        val file = documentFixtureRoot(requireNotNull(context), run).file(requireNotNull(name))
        check(OsConstants.S_ISREG(Os.lstat(file.path).st_mode))
        check(mode in setOf("r", "w", "wt", "wa", "rw", "rwt"))
        val expected = resolveFileIdentity(file)
        check(expected.isNotBlank())
        if (mode != "r") check(file.length() == 0L) { "Fixture writes only newly created empty documents" }
        val access = if (mode == "r") OsConstants.O_RDONLY else if (mode.startsWith("rw")) OsConstants.O_RDWR else OsConstants.O_WRONLY
        val descriptor = Os.open(file.path, access or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC, 0)
        var result: ParcelFileDescriptor? = null
        var failure: Throwable? = null
        try {
            check(fileDescriptorIdentityMatches(expected, resolveFileDescriptorIdentity(descriptor)) &&
                fileIdentityMatches(expected, resolveFileIdentity(file)))
            if (mode != "r") check(Os.fstat(descriptor).st_size == 0L)
            result = ParcelFileDescriptor.dup(descriptor)
        } catch (error: Throwable) { failure = error }
        failure = closePreservingPrimaryFailure(failure) { Os.close(descriptor) }
        if (failure != null) {
            val rejected = result
            throw requireNotNull(closePreservingPrimaryFailure(failure) { rejected?.close() })
        }
        return checkNotNull(result)
    }

    override fun deleteDocument(documentId: String) {
        throw UnsupportedOperationException("Synthetic death evidence is retained; deletion is not a fixture operation")
    }

    private fun rows(projection: Array<out String>?) = MatrixCursor(projection ?: arrayOf(
        Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
        Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS,
    ))

    private fun addDocument(cursor: MatrixCursor, id: String, file: File, directory: Boolean) {
        val stat = Os.lstat(file.path)
        check(if (directory) OsConstants.S_ISDIR(stat.st_mode) else OsConstants.S_ISREG(stat.st_mode))
        val values = mapOf<String, Any?>(
            Document.COLUMN_DOCUMENT_ID to id,
            Document.COLUMN_DISPLAY_NAME to file.name,
            Document.COLUMN_MIME_TYPE to if (directory) Document.MIME_TYPE_DIR else documentFixtureMime(file.name),
            Document.COLUMN_SIZE to if (directory) 0L else stat.st_size,
            Document.COLUMN_LAST_MODIFIED to file.lastModified(),
            Document.COLUMN_FLAGS to if (directory) Document.FLAG_DIR_SUPPORTS_CREATE else
                (Document.FLAG_SUPPORTS_WRITE or Document.FLAG_SUPPORTS_RENAME),
        )
        cursor.addRow(cursor.columnNames.map { values[it] }.toTypedArray())
    }
}

internal const val DOCUMENT_FIXTURE_PACKAGE = "app.smallthingz.reverb.reliability"
internal const val DOCUMENT_FIXTURE_AUTHORITY = "$DOCUMENT_FIXTURE_PACKAGE.durable.documents"

internal data class DocumentFixtureRoot(val root: File, val documents: File, val admission: JSONObject) {
    fun file(name: String): File {
        check(documentFixtureValidBasename(name))
        val path = File(documents, name)
        check(storagePathState(path) == StoragePathState.MISSING || OsConstants.S_ISREG(Os.lstat(path.path).st_mode))
        return path
    }
    fun renameCalls(): Int {
        val entries = checkNotNull(root.listFiles())
        check(entries.size <= 32)
        val events = entries.filter { it.name.startsWith("rename-") }
        check(events.size <= 8)
        events.forEach { event ->
            check(event.name.endsWith(".json"))
            val token = event.name.removePrefix("rename-").removeSuffix(".json")
            check(UUID.fromString(token).toString() == token)
            val receipt = documentFixtureReadJson(event, 4096)
            check(documentFixtureId(receipt.getString("source")).first == admission.getString("run"))
            file(receipt.getString("name"))
        }
        return events.size
    }
}

internal fun documentFixtureDirectChildName(parentId: String, childId: String): String? {
    if (runCatching { UUID.fromString(parentId).toString() }.getOrNull() != parentId) return null
    val prefix = "$parentId/"
    if (!childId.startsWith(prefix)) return null
    val name = childId.removePrefix(prefix)
    return name.takeIf { documentFixtureValidBasename(it) && (it.endsWith(".wav") || it.endsWith(".flac")) }
}

private fun documentFixtureValidBasename(name: String): Boolean =
    name.isNotBlank() && name != "." && name != ".." &&
        name.none { it == '/' || it == '\\' || it == '\u0000' } &&
        name.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8) == name &&
        name.toByteArray(Charsets.UTF_8).size <= 255

internal fun documentFixtureId(id: String): Pair<String, String?> {
    val parts = id.split('/', limit = 2)
    check(UUID.fromString(parts[0]).toString() == parts[0])
    return parts[0] to parts.getOrNull(1)
}

internal fun documentFixtureMime(name: String): String = when {
    name.endsWith(".wav") -> "audio/wav"
    name.endsWith(".flac") -> "audio/flac"
    else -> error("Unsupported synthetic document name")
}

internal fun documentFixtureCreate(context: Context, run: String): DocumentFixtureRoot {
    check(context.packageName == DOCUMENT_FIXTURE_PACKAGE)
    check(documentFixtureId(run).second == null)
    val parent = File(context.noBackupFilesDir, "document-death-fixtures")
    if (storagePathState(parent) == StoragePathState.MISSING) {
        check(parent.mkdir()); forceRecordingDirectoryDurable(context.noBackupFilesDir)
    }
    check(OsConstants.S_ISDIR(Os.lstat(parent.path).st_mode))
    val root = File(parent, run)
    check(root.mkdir())
    forceRecordingDirectoryDurable(parent)
    val documents = File(root, "documents")
    check(documents.mkdir())
    forceRecordingDirectoryDurable(root)
    val rootStat = Os.lstat(root.path)
    val documentStat = Os.lstat(documents.path)
    val admission = JSONObject().put("version", 1).put("run", run)
        .put("rootDev", rootStat.st_dev).put("rootIno", rootStat.st_ino)
        .put("documentsDev", documentStat.st_dev).put("documentsIno", documentStat.st_ino)
    documentFixtureWriteNew(File(root, "admission.json"), admission.toString().toByteArray())
    return documentFixtureRoot(context, run)
}

internal fun documentFixtureRoot(context: Context, run: String): DocumentFixtureRoot {
    check(context.packageName == DOCUMENT_FIXTURE_PACKAGE)
    check(documentFixtureId(run).second == null)
    val parent = File(context.noBackupFilesDir, "document-death-fixtures")
    check(OsConstants.S_ISDIR(Os.lstat(parent.path).st_mode))
    val root = File(parent, run)
    val rootStat = Os.lstat(root.path)
    check(OsConstants.S_ISDIR(rootStat.st_mode))
    val admissionFile = File(root, "admission.json")
    check(OsConstants.S_ISREG(Os.lstat(admissionFile.path).st_mode) && admissionFile.length() in 1..4096)
    val admission = documentFixtureReadJson(admissionFile, 4096)
    check(admission.getInt("version") == 1 && admission.getString("run") == run)
    check(admission.getLong("rootDev") == rootStat.st_dev && admission.getLong("rootIno") == rootStat.st_ino)
    val documents = File(root, "documents")
    val stat = Os.lstat(documents.path)
    check(OsConstants.S_ISDIR(stat.st_mode) && stat.st_dev == admission.getLong("documentsDev") &&
        stat.st_ino == admission.getLong("documentsIno"))
    return DocumentFixtureRoot(root, documents, admission)
}

internal fun documentFixtureWriteNew(file: File, bytes: ByteArray) {
    check(bytes.size <= 32768)
    val descriptor = Os.open(file.path, OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_WRONLY or
        OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC, 0x180)
    var failure: Throwable? = null
    try {
        var position = 0
        while (position < bytes.size) {
            val count = Os.write(descriptor, bytes, position, bytes.size - position)
            check(count > 0); position += count
        }
        Os.fsync(descriptor)
        val identity = resolveFileIdentity(file)
        check(identity.isNotBlank() && fileDescriptorIdentityMatches(identity, resolveFileDescriptorIdentity(descriptor)))
        forceRecordingDirectoryDurable(requireNotNull(file.parentFile))
        check(fileIdentityMatches(identity, resolveFileIdentity(file)) &&
            fileDescriptorIdentityMatches(identity, resolveFileDescriptorIdentity(descriptor)))
    } catch (error: Throwable) { failure = error }
    closePreservingPrimaryFailure(failure) { Os.close(descriptor) }?.let { throw it }
}

internal fun documentFixtureReadJson(file: File, maximum: Int): JSONObject {
    val identity = resolveFileIdentity(file)
    check(identity.isNotBlank() && file.length() in 1..maximum.toLong())
    val bytes = FileInputStream(file).use { input ->
        check(fileDescriptorIdentityMatches(identity, resolveFileDescriptorIdentity(input.fd)))
        val buffer = ByteArray(maximum + 1)
        var size = 0
        while (size < buffer.size) {
            val count = input.read(buffer, size, buffer.size - size)
            if (count < 0) break
            check(count > 0); size += count
        }
        check(size in 1..maximum && fileIdentityMatches(identity, resolveFileIdentity(file)) &&
            fileDescriptorIdentityMatches(identity, resolveFileDescriptorIdentity(input.fd)))
        buffer.copyOf(size)
    }
    val text = bytes.toString(Charsets.UTF_8)
    check(text.toByteArray(Charsets.UTF_8).contentEquals(bytes))
    val parser = JSONTokener(text)
    val value = parser.nextValue()
    check(value is JSONObject && parser.nextClean() == '\u0000')
    return value
}
