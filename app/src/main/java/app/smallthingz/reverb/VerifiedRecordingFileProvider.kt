package app.smallthingz.reverb

import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import java.io.FileNotFoundException

private val VERIFIED_FILE_PROVIDER_DEFAULT_COLUMNS = listOf(
    OpenableColumns.DISPLAY_NAME,
    OpenableColumns.SIZE,
)

internal fun verifiedFileProviderQueryColumns(projection: Array<out String>?): List<String> =
    (projection?.toList() ?: VERIFIED_FILE_PROVIDER_DEFAULT_COLUMNS)
        .filter { it == OpenableColumns.DISPLAY_NAME || it == OpenableColumns.SIZE }

internal fun verifiedFileProviderQueryValues(
    columns: List<String>,
    displayName: String,
    sizeBytes: Long?,
): List<Any?> = columns.map { column ->
    when (column) {
        OpenableColumns.DISPLAY_NAME -> displayName
        OpenableColumns.SIZE -> sizeBytes
        else -> null
    }
}

internal fun verifiedFileProviderMimeType(
    expectedIdentity: String,
    descriptorIdentity: String,
    mimeType: String?,
): String? = mimeType?.takeIf {
    fileDescriptorIdentityMatches(expectedIdentity, descriptorIdentity)
}

class VerifiedRecordingFileProvider : FileProvider() {
    private fun openVerifiedReadDescriptor(uri: Uri): ParcelFileDescriptor {
        val expectedIdentity = verifiedFileProviderIdentity(uri)
            ?: throw FileNotFoundException("Recording identity is missing")
        val descriptor = super.openFile(uri, "r")
            ?: throw FileNotFoundException("Unable to open recording")
        val currentIdentity = resolveFileDescriptorIdentity(descriptor.fileDescriptor)
        if (!fileDescriptorIdentityMatches(expectedIdentity, currentIdentity)) {
            runCatching { descriptor.close() }
            throw FileNotFoundException("Recording changed before it was opened")
        }
        return descriptor
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Recording provider is read-only")
        return openVerifiedReadDescriptor(uri)
    }

    override fun getType(uri: Uri): String? {
        val expectedIdentity = verifiedFileProviderIdentity(uri) ?: return null
        val descriptor = try {
            openVerifiedReadDescriptor(uri)
        } catch (_: Exception) {
            return null
        }
        return descriptor.use { opened ->
            verifiedFileProviderMimeType(
                expectedIdentity = expectedIdentity,
                descriptorIdentity = resolveFileDescriptorIdentity(opened.fileDescriptor),
                mimeType = runCatching { super.getType(uri) }.getOrNull(),
            )
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val columns = verifiedFileProviderQueryColumns(projection)
        val result = MatrixCursor(columns.toTypedArray(), 1)
        val expectedIdentity = verifiedFileProviderIdentity(uri) ?: return result
        val descriptor = try {
            openVerifiedReadDescriptor(uri)
        } catch (_: Exception) {
            return result
        }
        descriptor.use { opened ->
            val displayName = uri.lastPathSegment?.takeIf { it.isNotBlank() } ?: "recording"
            val sizeBytes = opened.statSize.takeIf { it >= 0L }
            val sampledIdentity = resolveFileDescriptorIdentity(opened.fileDescriptor)
            if (!fileDescriptorIdentityMatches(expectedIdentity, sampledIdentity)) return result
            result.addRow(verifiedFileProviderQueryValues(columns, displayName, sizeBytes))
        }
        return result
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Recording provider is read-only")
}
