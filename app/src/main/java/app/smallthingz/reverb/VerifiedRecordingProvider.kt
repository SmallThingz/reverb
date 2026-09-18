package app.smallthingz.reverb

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.core.net.toUri
import java.io.FileNotFoundException
import java.util.Base64

private const val VERIFIED_PROVIDER_PATH_VERSION = "v1"
private const val VERIFIED_PROVIDER_AUTHORITY_SUFFIX = ".recording-provider"

internal data class VerifiedProviderRequest(
    val storageType: RecordingStorageType,
    val sourceId: String,
    val expectedIdentity: String,
    val mimeType: String,
    val displayName: String,
    val sizeBytes: Long,
)

private fun encodeVerifiedProviderField(value: String): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))

private fun decodeVerifiedProviderField(value: String): String? = runCatching {
    String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8)
}.getOrNull()

internal fun verifiedProviderPathSegments(request: VerifiedProviderRequest): List<String> {
    require(request.storageType != RecordingStorageType.FILE)
    require(request.sourceId.isNotBlank())
    require(request.expectedIdentity.isNotBlank())
    return listOf(
        VERIFIED_PROVIDER_PATH_VERSION,
        request.storageType.storageCode.toInt().toString(),
        encodeVerifiedProviderField(request.sourceId),
        encodeVerifiedProviderField(request.expectedIdentity),
        encodeVerifiedProviderField(request.mimeType),
        encodeVerifiedProviderField(request.displayName),
        request.sizeBytes.coerceAtLeast(0L).toString(),
    )
}

internal fun decodeVerifiedProviderPathSegments(segments: List<String>): VerifiedProviderRequest? {
    if (segments.size != 7 || segments[0] != VERIFIED_PROVIDER_PATH_VERSION) return null
    val storageType = segments[1].toIntOrNull()?.let(RecordingStorageType::fromStorageCode)
        ?.takeIf { it != RecordingStorageType.FILE } ?: return null
    val sourceId = decodeVerifiedProviderField(segments[2])?.takeIf { it.isNotBlank() } ?: return null
    val expectedIdentity = decodeVerifiedProviderField(segments[3])?.takeIf { it.isNotBlank() } ?: return null
    val mimeType = decodeVerifiedProviderField(segments[4])?.takeIf { it.isNotBlank() } ?: FALLBACK_MIME_TYPE_AUDIO
    val displayName = decodeVerifiedProviderField(segments[5])?.takeIf { it.isNotBlank() } ?: "recording"
    val sizeBytes = segments[6].toLongOrNull()?.takeIf { it >= 0L } ?: return null
    return VerifiedProviderRequest(storageType, sourceId, expectedIdentity, mimeType, displayName, sizeBytes)
}

internal fun buildVerifiedProviderUri(
    context: Context,
    request: VerifiedProviderRequest,
): Uri {
    val source = request.sourceId.toUri()
    require(source.scheme == "content") { "Provider recording must use a content URI" }
    val currentIdentity = resolveProviderRecordingIdentity(context, request.storageType, source)
    check(request.expectedIdentity.isNotBlank() &&
        providerRecordingIdentityMatches(request.expectedIdentity, currentIdentity)
    ) { "Recording changed in provider: ${request.sourceId}" }
    return verifiedProviderPathSegments(request).fold(
        Uri.Builder()
            .scheme("content")
            .authority(context.packageName + VERIFIED_PROVIDER_AUTHORITY_SUFFIX),
    ) { builder, segment -> builder.appendPath(segment) }.build()
}

internal fun buildVerifiedProviderUri(context: Context, recording: RecordingEntity): Uri =
    buildVerifiedProviderUri(
        context,
        VerifiedProviderRequest(
            storageType = recording.storageType,
            sourceId = recording.id,
            expectedIdentity = recording.fileIdentity,
            mimeType = recording.mimeType.ifBlank { FALLBACK_MIME_TYPE_AUDIO },
            displayName = recording.displayName,
            sizeBytes = recording.sizeBytes,
        ),
    )

internal fun verifiedProviderMimeType(
    request: VerifiedProviderRequest,
    currentIdentity: String,
): String? = request.mimeType.takeIf {
    providerRecordingIdentityMatches(request.expectedIdentity, currentIdentity)
}

class VerifiedRecordingProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Recording provider is read-only")
        val request = decodeVerifiedProviderPathSegments(uri.pathSegments)
            ?: throw FileNotFoundException("Invalid verified recording URI")
        val context = context ?: throw FileNotFoundException("Recording provider is unavailable")
        val source = request.sourceId.toUri()
        if (source.scheme != "content") throw FileNotFoundException("Invalid recording source")

        val before = resolveProviderRecordingIdentity(context, request.storageType, source)
        if (!providerRecordingIdentityMatches(request.expectedIdentity, before)) {
            throw FileNotFoundException("Recording changed before it was opened")
        }
        val descriptor = try {
            context.contentResolver.openFileDescriptor(source, "r")
        } catch (error: Exception) {
            throw FileNotFoundException("Unable to open recording: ${error.message.orEmpty()}")
        } ?: throw FileNotFoundException("Unable to open recording")

        val after = resolveProviderRecordingIdentity(context, request.storageType, source)
        if (!providerRecordingIdentityMatches(request.expectedIdentity, after) ||
            !providerRecordingIdentityMatches(before, after)
        ) {
            throwAfterClosePreservingPrimary(
                FileNotFoundException("Recording changed while it was opened"),
            ) {
                descriptor.close()
            }
        }
        return descriptor
    }

    override fun getType(uri: Uri): String? {
        val request = decodeVerifiedProviderPathSegments(uri.pathSegments) ?: return null
        val context = context ?: return null
        val source = request.sourceId.toUri()
        if (source.scheme != "content") return null
        val currentIdentity = resolveProviderRecordingIdentity(context, request.storageType, source)
        return verifiedProviderMimeType(request, currentIdentity)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val request = decodeVerifiedProviderPathSegments(uri.pathSegments)
        val columns = projection?.toList() ?: listOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val result = MatrixCursor(columns.toTypedArray(), 1)
        if (request == null || !requestStillMatches(request)) return result
        result.addRow(columns.map { column ->
            when (column) {
                OpenableColumns.DISPLAY_NAME -> request.displayName
                OpenableColumns.SIZE -> request.sizeBytes
                else -> null
            }
        })
        return result
    }

    private fun requestStillMatches(request: VerifiedProviderRequest): Boolean {
        val context = context ?: return false
        val source = request.sourceId.toUri()
        if (source.scheme != "content") return false
        val current = resolveProviderRecordingIdentity(context, request.storageType, source)
        return providerRecordingIdentityMatches(request.expectedIdentity, current)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("Recording provider is read-only")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Recording provider is read-only")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException("Recording provider is read-only")
}
