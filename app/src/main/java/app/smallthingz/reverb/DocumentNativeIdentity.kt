package app.smallthingz.reverb

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.system.Os
import android.system.OsConstants
import java.io.FileDescriptor
import java.util.Base64

private const val DOCUMENT_NATIVE_IDENTITY_PREFIX = "documentfd:"

internal data class DocumentNativeIdentity(
    val id: String,
    val sizeBytes: Long,
    val device: Long,
    val inode: Long,
    val changedSeconds: Long,
    val changedNanos: Long,
) {
    fun encode(): String {
        val encodedId = Base64.getUrlEncoder().withoutPadding().encodeToString(id.toByteArray(Charsets.UTF_8))
        return "$DOCUMENT_NATIVE_IDENTITY_PREFIX$encodedId:$sizeBytes:$device:$inode:$changedSeconds:$changedNanos"
    }
}

internal fun parseDocumentNativeIdentity(value: String): DocumentNativeIdentity? = runCatching {
    if (!value.startsWith(DOCUMENT_NATIVE_IDENTITY_PREFIX)) return@runCatching null
    val parts = value.split(':')
    if (parts.size != 7) return@runCatching null
    val id = String(Base64.getUrlDecoder().decode(parts[1]), Charsets.UTF_8)
    if (!documentRecordingIdIsValid(id)) return@runCatching null
    DocumentNativeIdentity(
        id = id,
        sizeBytes = parts[2].toLongOrNull()?.takeIf { it >= 0L } ?: return@runCatching null,
        device = parts[3].toLongOrNull()?.takeIf { it >= 0L } ?: return@runCatching null,
        inode = parts[4].toLongOrNull()?.takeIf { it > 0L } ?: return@runCatching null,
        changedSeconds = parts[5].toLongOrNull()?.takeIf { it >= 0L } ?: return@runCatching null,
        changedNanos = parts[6].toLongOrNull()?.takeIf { it in 0L..999_999_999L } ?: return@runCatching null,
    )
}.getOrNull()

private fun documentNativeIdentity(id: String, descriptor: FileDescriptor): DocumentNativeIdentity? = runCatching {
    val stat = Os.fstat(descriptor)
    if (!OsConstants.S_ISREG(stat.st_mode) || stat.st_ino <= 0L || stat.st_size < 0L) return@runCatching null
    DocumentNativeIdentity(id, stat.st_size, stat.st_dev, stat.st_ino, stat.st_ctim.tv_sec, stat.st_ctim.tv_nsec)
}.getOrNull()

/** A metadata-less provider must bind the actual opened file, not just another query of its URI. */
internal fun providerDescriptorMatchesIdentity(identity: String, descriptor: FileDescriptor): Boolean {
    if (!identity.startsWith(DOCUMENT_NATIVE_IDENTITY_PREFIX)) return true
    val expected = parseDocumentNativeIdentity(identity) ?: return false
    return expected == documentNativeIdentity(expected.id, descriptor)
}

private data class DocumentIdentityMetadata(
    val documentId: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long?,
    val modifiedMillis: Long?,
)

private fun queryDocumentIdentityMetadata(context: Context, uri: Uri): DocumentIdentityMetadata? {
    val columns = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
    )
    return context.contentResolver.query(uri, columns, null, null, null)?.use { cursor ->
        requireCompleteDocumentListing(
            cursor.extras.getBoolean(DocumentsContract.EXTRA_LOADING, false),
            cursor.extras.getString(DocumentsContract.EXTRA_ERROR),
        )
        if (!cursor.moveToFirst() || cursor.isNull(0) || cursor.isNull(1) || cursor.isNull(2)) return@use null
        val metadata = DocumentIdentityMetadata(
            cursor.getString(0), cursor.getString(1), cursor.getString(2),
            if (cursor.isNull(3)) null else cursor.getLong(3).takeIf { it >= 0L },
            if (cursor.isNull(4)) null else cursor.getLong(4).takeIf { it > 0L },
        )
        if (metadata.documentId != DocumentsContract.getDocumentId(uri) ||
            metadata.displayName.isBlank() || metadata.mimeType == DocumentsContract.Document.MIME_TYPE_DIR ||
            cursor.moveToNext()
        ) return@use null
        metadata
    }
}

/** Optional SAF size/mtime are not mandatory identity evidence when the descriptor provides stronger proof. */
internal fun readDocumentCatalogObservation(
    context: Context,
    uri: Uri,
    preferNative: Boolean = false,
): ProviderCatalogObservation? = runCatching {
    val before = queryDocumentIdentityMetadata(context, uri) ?: return@runCatching null
    val metadataIdentity = documentProviderIdentityFromMetadata(
        uri.toString(), before.sizeBytes != null, before.sizeBytes ?: 0L,
        before.modifiedMillis != null, before.modifiedMillis ?: 0L,
    )
    if (!preferNative && metadataIdentity.isNotBlank()) {
        return@runCatching ProviderCatalogObservation(before.displayName, metadataIdentity)
    }
    val descriptor = context.contentResolver.openFileDescriptor(uri, "r") ?: return@runCatching null
    descriptor.use { opened ->
        val native = documentNativeIdentity(uri.toString(), opened.fileDescriptor) ?: return@use null
        if (before.sizeBytes != null && before.sizeBytes != native.sizeBytes) return@use null
        if (before != queryDocumentIdentityMetadata(context, uri) ||
            native != documentNativeIdentity(uri.toString(), opened.fileDescriptor)
        ) return@use null
        ProviderCatalogObservation(before.displayName, native.encode())
    }
}.getOrNull()
