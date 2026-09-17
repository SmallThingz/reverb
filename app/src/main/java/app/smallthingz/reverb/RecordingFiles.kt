package app.smallthingz.reverb

import android.content.ClipData
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.system.Os
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.UUID

private val TAG = "RecordingFiles"
private const val ILLEGAL_FILENAME_CHARS = "\\/*?\"<>|"

internal fun hasIllegalRecordingNameCharacters(name: String): Boolean =
    name.any { it in ILLEGAL_FILENAME_CHARS }

private val SUPPORTED_RECORDING_EXTENSIONS = ExportFormat.entries.map { it.extension }.toSet()
private const val FILE_COPY_BUFFER_BYTES = 128 * 1024
private const val VERIFIED_FILE_IDENTITY_QUERY = "reverb_identity"
private const val STAGING_OUTPUT_PREFIX = "reverb-partial-"
private const val STAGING_SESSION_SEPARATOR = "__"
private val OUTPUT_STAGING_SESSION_ID = UUID.randomUUID().toString()

internal enum class StagingOutputKind(
    val storageCode: Byte,
    private val legacyWireName: String,
) {
    EXPORT(1, "export"), // Legacy pre-verification-marker staging.
    EXPORT_TRACKED(2, "export-v2"),
    COPY(3, "copy"),
    ;

    companion object {
        fun fromWireValue(value: String): StagingOutputKind? {
            val code = value.toIntOrNull()
            if (code != null) return entries.firstOrNull { it.storageCode.toInt() == code }
            return entries.firstOrNull { it.legacyWireName == value }
        }
    }
}

internal data class StagingOutputMetadata(
    val kind: StagingOutputKind,
    val sessionId: String,
    val finalDisplayName: String,
)
internal const val MEDIA_STORE_DIRECTORY_ID = "mediastore:external:Music/Reverb"
private val MEDIA_STORE_RELATIVE_PATH = "${Environment.DIRECTORY_MUSIC}/${APP_STORAGE_FOLDER_NAME}/"


enum class RecordingStorageType(val storageCode: Byte) {
    FILE(1),
    DOCUMENT(2),
    MEDIASTORE(3),
    ;

    companion object {
        fun fromStorageCode(value: Int): RecordingStorageType? =
            entries.firstOrNull { it.storageCode.toInt() == value }

        fun fromLegacyName(value: String?): RecordingStorageType? = entries.firstOrNull { it.name == value }
    }
}

internal enum class RecordingAssetState {
    PRESENT,
    MISSING,
    UNAVAILABLE,
}

data class RecordingOutputTarget(
    val id: String,
    val displayName: String,
    val mimeType: String,
    val storageType: RecordingStorageType,
    val directoryId: String,
    val startedAtMillis: Long,
    val file: File? = null,
    val uri: Uri? = null,
    val staging: Boolean = false,
    val publishedIdentity: String = "",
)

/** Legacy app-private location used by older Reverb builds; always scanned for recovery. */
fun getSavedRecordingsDirectory(context: Context): File {
    val baseDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
        ?: File(context.filesDir, "recordings")
    return File(baseDir, APP_STORAGE_FOLDER_NAME)
}

@Suppress("DEPRECATION")
internal fun getSharedMusicRecordingsDirectory(): File =
    File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), APP_STORAGE_FOLDER_NAME)

internal fun usesMediaStoreDefaultStorage(sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
    sdkInt >= Build.VERSION_CODES.Q

internal fun requiresLegacyPublicStoragePermission(sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
    sdkInt < Build.VERSION_CODES.Q

fun getConfiguredExportTreeUri(context: Context): Uri? {
    val raw = getRecorderPreferences(context).safeString(PrefKey.EXPORT_DIRECTORY_URI) ?: return null
    return raw.takeIf { it.isNotBlank() }?.toUri()
}

fun setConfiguredExportTreeUri(
    context: Context,
    treeUri: Uri?,
): Boolean {
    val editor = getRecorderPreferences(context).edit()
    if (treeUri != null) {
        editor.putString(PrefKey.EXPORT_DIRECTORY_URI, treeUri.toString())
    } else {
        editor.remove(PrefKey.EXPORT_DIRECTORY_URI)
    }
    return editor.commit()
}

fun getConfiguredOutputDirectoryId(context: Context): String {
    return getOutputDirectoryId(context, getConfiguredExportTreeUri(context))
}

fun getOutputDirectoryId(
    context: Context,
    treeUri: Uri?,
): String {
    if (treeUri != null) return treeUri.toString()
    return if (usesMediaStoreDefaultStorage()) {
        MEDIA_STORE_DIRECTORY_ID
    } else {
        getSharedMusicRecordingsDirectory().absolutePath
    }
}

fun describeConfiguredOutputDirectory(context: Context): String {
    return describeOutputDirectory(context, getConfiguredExportTreeUri(context))
}

fun describeOutputDirectory(
    context: Context,
    treeUri: Uri?,
): String {
    if (treeUri == null) {
        return "${Environment.DIRECTORY_MUSIC}/${APP_STORAGE_FOLDER_NAME}"
    }
    val documentId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
    return documentId
        ?.substringAfterLast(':')
        ?.trimEnd('/')
        ?.substringAfterLast('/')
        ?.takeIf { it.isNotBlank() }
        ?: treeUri.toString()
}

internal fun encodeVerifiedFileProviderIdentity(identity: String): String {
    require(identity.isNotBlank()) { "Recording identity is required" }
    return Base64.getUrlEncoder().withoutPadding().encodeToString(identity.toByteArray(Charsets.UTF_8))
}

internal fun decodeVerifiedFileProviderIdentity(encoded: String): String? = runCatching {
    String(Base64.getUrlDecoder().decode(encoded), Charsets.UTF_8)
}.getOrNull()?.takeIf { it.isNotBlank() }

internal fun verifiedFileProviderIdentity(uri: Uri): String? {
    val encoded = uri.getQueryParameter(VERIFIED_FILE_IDENTITY_QUERY)
        ?.takeIf { it.isNotBlank() } ?: return null
    return decodeVerifiedFileProviderIdentity(encoded)
}

internal fun buildVerifiedFileProviderUri(
    context: Context,
    file: File,
    expectedIdentity: String,
): Uri {
    require(expectedIdentity.isNotBlank()) { "Recording identity is required" }
    return FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        .buildUpon()
        .appendQueryParameter(
            VERIFIED_FILE_IDENTITY_QUERY,
            encodeVerifiedFileProviderIdentity(expectedIdentity),
        )
        .build()
}

fun buildRecordingUri(
    context: Context,
    recording: RecordingEntity,
): Uri {
    return when (recording.storageType) {
        RecordingStorageType.FILE -> {
            check(recordingFileIdentityMatches(recording)) { "Recording changed on disk: ${recording.id}" }
            buildVerifiedFileProviderUri(context, File(recording.id), recording.fileIdentity)
        }

        RecordingStorageType.DOCUMENT,
        RecordingStorageType.MEDIASTORE,
        -> {
            check(recording.fileIdentity.isNotBlank()) {
                "Recording identity is unavailable; refresh the Library before sharing"
            }
            buildVerifiedProviderUri(context, recording)
        }
    }
}

fun buildShareRecordingsIntent(
    context: Context,
    recordings: Collection<RecordingEntity>,
): Intent {
    require(recordings.isNotEmpty()) { "At least one recording is required" }

    val recordingList = recordings.toList()
    val uris = recordingList.map { buildRecordingUri(context, it) }
    val mimeTypes = recordingList
        .map { it.mimeType.ifBlank { FALLBACK_MIME_TYPE_AUDIO } }
        .distinct()
    val mimeType = mimeTypes.singleOrNull() ?: FALLBACK_MIME_TYPE_AUDIO
    val action = if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE

    return Intent(action).apply {
        type = mimeType
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (uris.size == 1) {
            putExtra(Intent.EXTRA_STREAM, uris.single())
        } else {
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }

        clipData = ClipData.newUri(
            context.contentResolver,
            recordingList.first().displayName,
            uris.first(),
        ).apply {
            uris.drop(1).forEach { addItem(ClipData.Item(it)) }
        }
    }
}

fun formatRecordingStartTimestamp(context: Context, startedAtMillis: Long): String {
    val date = java.util.Date(startedAtMillis)
    val timeFormat = android.text.format.DateFormat.getTimeFormat(context)
    return timeFormat.format(date)
}

fun formatRecordingDateHeader(context: Context, startedAtMillis: Long): String {
    val date = java.util.Date(startedAtMillis)
    val dateFormat = android.text.format.DateFormat.getLongDateFormat(context)
    return dateFormat.format(date)
}

private data class RecordingMediaMetadata(
    val durationMillis: Long,
    val codecSummary: String,
)

private data class RetrievedMediaMetadata(
    val durationMillis: Long?,
    val bitrate: Int?,
    val sampleRate: Int?,
)

private fun inspectRecordingMedia(file: File): RecordingMediaMetadata {
    val retriever = MediaMetadataRetriever()
    val metadata = runCatching {
        retriever.setDataSource(file.absolutePath)
        RetrievedMediaMetadata(
            durationMillis = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull(),
            bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull(),
            sampleRate = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)?.toIntOrNull()
            } else null,
        )
    }.onFailure { Log.w(TAG, "Unable to inspect recording metadata for $file", it) }
        .getOrNull()
    runCatching { retriever.release() }

    val duration = metadata?.durationMillis?.takeIf { it > 0L }
        ?: if (file.extension.equals(ExportFormat.WAV.extension, ignoreCase = true)) {
            readWavDurationMillis(file)
        } else {
            0L
        }
    return RecordingMediaMetadata(
        durationMillis = duration,
        codecSummary = resolveRecordingCodecInfo(
            extension = file.extension,
            bitrate = metadata?.bitrate,
            sampleRate = metadata?.sampleRate,
        ),
    )
}

private fun inspectRecordingMedia(
    context: Context,
    uri: Uri,
    displayName: String,
): RecordingMediaMetadata {
    val retriever = MediaMetadataRetriever()
    val metadata = runCatching {
        retriever.setDataSource(context, uri)
        RetrievedMediaMetadata(
            durationMillis = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull(),
            bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull(),
            sampleRate = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)?.toIntOrNull()
            } else null,
        )
    }.onFailure { Log.w(TAG, "Unable to inspect recording metadata for $uri", it) }
        .getOrNull()
    runCatching { retriever.release() }

    val duration = metadata?.durationMillis?.takeIf { it > 0L }
        ?: if (displayName.endsWith(".${ExportFormat.WAV.extension}", ignoreCase = true)) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use(::readWavDurationMillis) ?: 0L
            }.onFailure { Log.w(TAG, "Unable to inspect WAV duration for $uri", it) }.getOrDefault(0L)
        } else {
            0L
        }
    return RecordingMediaMetadata(
        durationMillis = duration,
        codecSummary = resolveRecordingCodecInfo(
            extension = displayName.substringAfterLast('.', ""),
            bitrate = metadata?.bitrate,
            sampleRate = metadata?.sampleRate,
        ),
    )
}

private fun resolveRecordingCodecInfo(
    extension: String,
    bitrate: Int?,
    sampleRate: Int?,
): String {
    val ext = extension.uppercase()
    return buildString {
        append(ext)
        sampleRate?.takeIf { it > 0 }?.let {
            append(CODEC_SUMMARY_SEPARATOR)
            append(sampleRateLabel(it))
        }
        bitrate?.takeIf { it > 0 }?.let {
            append(CODEC_SUMMARY_SEPARATOR)
            append(it / 1000)
            append(" kbps")
        }
    }
}

private fun describeFileRecordingLocation(
    context: Context,
    file: File,
): String {
    val normalizedPath = file.absolutePath.replace('\\', '/')
    val appStoragePath = getSavedRecordingsDirectory(context).absolutePath.replace('\\', '/').trimEnd('/')
    if (normalizedPath == appStoragePath || normalizedPath.startsWith("$appStoragePath/")) {
        val relativePath = normalizedPath.removePrefix(appStoragePath).trimStart('/')
        return appendRelativePath(
            "${context.getString(R.string.app_storage_label)}/${APP_STORAGE_FOLDER_NAME}",
            relativePath.replace('\\', '/').trim('/'),
        )
    }
    return normalizedPath
}

private fun describeDocumentRecordingLocation(
    context: Context,
    recording: RecordingEntity,
): String {
    val documentUri = recording.id.toUri()
    val directoryUri = recording.directoryId.takeIf { it.isNotBlank() }?.let(Uri::parse)

    describeDocumentIdPath(context, runCatching {
        DocumentsContract.getDocumentId(documentUri)
    }.onFailure { Log.w(TAG, "getDocumentId failed for $documentUri", it) }.getOrNull())?.let {
        return it
    }
    describeDocumentIdPath(context, directoryUri?.let { treeUri ->
        runCatching { DocumentsContract.getTreeDocumentId(treeUri) }
            .onFailure { error -> Log.w(TAG, "getTreeDocumentId failed for $treeUri", error) }.getOrNull()
    })?.let {
        val normalizedBasePath = it.trimEnd('/')
        return if (normalizedBasePath == recording.displayName ||
            normalizedBasePath.endsWith("/${recording.displayName}")
        ) {
            normalizedBasePath
        } else {
            "$normalizedBasePath/${recording.displayName}"
        }
    }
    return Uri.decode(documentUri.toString())
}

private fun describeDocumentIdPath(
    context: Context,
    documentId: String?,
): String? {
    val decodedDocumentId = documentId?.let(Uri::decode)?.takeIf { it.isNotBlank() } ?: return null
    val separatorIndex = decodedDocumentId.indexOf(':')
    if (separatorIndex <= 0) {
        return decodedDocumentId
    }

    val volumeId = decodedDocumentId.substring(0, separatorIndex)
    val relativePath = decodedDocumentId.substring(separatorIndex + 1).trim('/')
    describeAppStorageRelativePath(context, relativePath)?.let { return it }

    val rootLabel = when {
        volumeId.equals("primary", ignoreCase = true) -> context.getString(R.string.volume_internal_shared_storage)
        volumeId.equals("home", ignoreCase = true) -> context.getString(R.string.volume_documents)
        else -> context.getString(R.string.volume_storage_template, volumeId)
    }
    return appendRelativePath(rootLabel, relativePath)
}

private fun describeAppStorageRelativePath(
    context: Context,
    relativePath: String,
): String? {
    val normalizedPath = relativePath.replace('\\', '/').trim('/')
    val appStorageRelativeRoot = "Android/data/${context.packageName}/files/" +
        "${Environment.DIRECTORY_MUSIC}/${APP_STORAGE_FOLDER_NAME}"
    if (normalizedPath == appStorageRelativeRoot || normalizedPath.startsWith("$appStorageRelativeRoot/")) {
        val tail = normalizedPath.removePrefix(appStorageRelativeRoot).trimStart('/')
        return appendRelativePath(
            "${context.getString(R.string.app_storage_label)}/${APP_STORAGE_FOLDER_NAME}",
            tail.replace('\\', '/').trim('/'),
        )
    }
    return null
}

private fun appendRelativePath(
    basePath: String,
    relativePath: String,
): String {
    val normalizedRelativePath = relativePath.trim('/')
    return if (normalizedRelativePath.isEmpty()) {
        basePath
    } else {
        "$basePath/$normalizedRelativePath"
    }
}

fun buildCodecSummary(
    context: Context,
    format: ExportFormat,
    sampleRate: Int,
    channelCount: Int,
    sampleFormat: PcmSampleFormat = PcmSampleFormat.PCM_16,
): String {
    val channelLabel = if (channelCount >= 2) {
        context.getString(R.string.channel_mode_stereo)
    } else {
        context.getString(R.string.channel_mode_mono)
    }
    return buildString {
        append(context.getString(sampleFormat.labelRes))
        append(CODEC_SUMMARY_SEPARATOR)
        append(context.getString(format.labelRes))
        append(CODEC_SUMMARY_SEPARATOR)
        append(sampleRateLabel(sampleRate))
        append(CODEC_SUMMARY_SEPARATOR)
        append(channelLabel)
    }
}

fun describeRecordingLocation(
    context: Context,
    recording: RecordingEntity,
): String {
    return when (recording.storageType) {
        RecordingStorageType.FILE -> describeFileRecordingLocation(context, File(recording.id))
        RecordingStorageType.DOCUMENT -> describeDocumentRecordingLocation(context, recording)
        RecordingStorageType.MEDIASTORE -> "${Environment.DIRECTORY_MUSIC}/${APP_STORAGE_FOLDER_NAME}/${recording.displayName}"
    }
}


fun resolveRecordingStartTimeMillis(file: File): Long {
    parseRecordingStartTimeMillis(file.nameWithoutExtension)?.let { return it }
    return file.lastModified().takeIf { it > 0L } ?: System.currentTimeMillis()
}

fun resolveRecordingStartTimeMillis(
    displayName: String,
    fallbackMillis: Long,
): Long {
    parseRecordingStartTimeMillis(displayName.substringBeforeLast('.', displayName))?.let { return it }
    return fallbackMillis.takeIf { it > 0L } ?: System.currentTimeMillis()
}

fun recordingEndTimestampMillis(startedAtMillis: Long, durationSeconds: Double): Long {
    if (!durationSeconds.isFinite() || durationSeconds <= 0.0) return startedAtMillis
    val durationMillis = (durationSeconds * 1000.0).toLong().coerceAtLeast(0L)
    return if (startedAtMillis > Long.MAX_VALUE - durationMillis) Long.MAX_VALUE
    else startedAtMillis + durationMillis
}

fun createOutputTarget(
    context: Context,
    requestedName: String?,
    startedAtMillis: Long,
    format: ExportFormat,
    codec: ExportCodec,
    defaultNameTimestampMillis: Long = startedAtMillis,
): RecordingOutputTarget {
    val baseName = sanitizeBaseName(
        if (requestedName.isNullOrBlank()) defaultNameTimestampMillis.toString() else requestedName.trim(),
    )
    val displayName = "$baseName.${format.extension}"
    val mimeType = format.outputMimeType
    return createOutputTarget(
        context,
        displayName,
        mimeType,
        startedAtMillis,
        stagingKind = StagingOutputKind.EXPORT_TRACKED,
    )
}

internal fun createOutputTarget(
    context: Context,
    requestedDisplayName: String,
    mimeType: String,
    startedAtMillis: Long,
    stagingKind: StagingOutputKind = StagingOutputKind.COPY,
): RecordingOutputTarget = createOutputTargetInDirectory(
    context = context,
    targetTreeUri = getConfiguredExportTreeUri(context),
    requestedDisplayName = requestedDisplayName,
    mimeType = mimeType,
    startedAtMillis = startedAtMillis,
    stagingKind = stagingKind,
)

internal fun createOutputTargetInDirectory(
    context: Context,
    targetTreeUri: Uri?,
    requestedDisplayName: String,
    mimeType: String,
    startedAtMillis: Long,
    stagingKind: StagingOutputKind = StagingOutputKind.COPY,
): RecordingOutputTarget {
    return if (targetTreeUri == null) {
        if (usesMediaStoreDefaultStorage()) {
            createMediaStoreOutputTarget(context, requestedDisplayName, mimeType, startedAtMillis, stagingKind)
        } else {
            createLocalOutputTarget(
                context, requestedDisplayName, mimeType, startedAtMillis,
                storageDir = getSharedMusicRecordingsDirectory(),
                stagingKind = stagingKind,
            )
        }
    } else {
        createDocumentOutputTarget(
            context, targetTreeUri, requestedDisplayName, mimeType, startedAtMillis, stagingKind,
        )
    }
}

fun openWritableParcelFileDescriptor(
    context: Context,
    target: RecordingOutputTarget,
): ParcelFileDescriptor {
    return when (target.storageType) {
        RecordingStorageType.FILE -> {
            ParcelFileDescriptor.open(
                requireNotNull(target.file),
                ParcelFileDescriptor.MODE_CREATE or
                    ParcelFileDescriptor.MODE_TRUNCATE or
                    ParcelFileDescriptor.MODE_READ_WRITE,
            )
        }

        RecordingStorageType.DOCUMENT,
        RecordingStorageType.MEDIASTORE,
        -> {
            context.contentResolver.openFileDescriptor(requireNotNull(target.uri), "rw")
                ?: throw IOException("Unable to open output document: ${target.id}")
        }
    }
}

fun resolveOutputTargetSize(
    context: Context,
    target: RecordingOutputTarget,
): Long {
    return when (target.storageType) {
        RecordingStorageType.FILE -> target.file?.length() ?: 0L
        RecordingStorageType.DOCUMENT,
        RecordingStorageType.MEDIASTORE,
        -> {
            val uri = requireNotNull(target.uri)
            val reportedSize = if (target.storageType == RecordingStorageType.DOCUMENT) {
                runCatching { DocumentFile.fromSingleUri(context, uri)?.length() ?: 0L }
                    .onFailure { Log.w(TAG, "Document size query failed for $uri", it) }
                    .getOrDefault(0L)
            } else {
                queryContentSize(context, uri)
            }
            if (reportedSize > 0L) reportedSize else {
                runCatching {
                    context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                        descriptor.statSize.coerceAtLeast(0L)
                    } ?: 0L
                }.onFailure { Log.w(TAG, "Unable to resolve output size for $uri", it) }
                    .getOrDefault(0L)
            }
        }
    }
}

@Throws(IOException::class)
internal fun finalizeOutputTarget(
    context: Context,
    target: RecordingOutputTarget,
    expectedFingerprint: StableOutputFingerprint,
): RecordingOutputTarget {
    return when (target.storageType) {
        RecordingStorageType.MEDIASTORE -> finalizeMediaStoreOutputTarget(context, target, expectedFingerprint)
        RecordingStorageType.FILE -> finalizeFileOutputTarget(context, target, expectedFingerprint)
        RecordingStorageType.DOCUMENT -> finalizeDocumentOutputTarget(context, target, expectedFingerprint)
    }
}

@Throws(IOException::class)
private fun requireCurrentOutputFingerprint(
    context: Context,
    target: RecordingOutputTarget,
    expectedFingerprint: StableOutputFingerprint,
) {
    val current = readStableOutputFingerprint(context, target.storageType, target.id)
        ?: throw IOException("Unable to bind output staging to a stable object")
    if (!stableOutputFingerprintMatches(target.storageType, expectedFingerprint, current)) {
        throw IOException("Output staging changed after verification")
    }
}

@Throws(IOException::class)
private fun finalizeMediaStoreOutputTarget(
    context: Context,
    target: RecordingOutputTarget,
    expectedFingerprint: StableOutputFingerprint,
): RecordingOutputTarget {
    val uri = requireNotNull(target.uri)
    if (!target.staging) {
        publishMediaStoreUri(context, uri)
        return target
    }
    requireCurrentOutputFingerprint(context, target, expectedFingerprint)
    val finalName = findAvailableDisplayName(target.displayName) { candidate ->
        mediaStoreNameExists(context, candidate)
    }
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, finalName)
        put(MediaStore.MediaColumns.IS_PENDING, 0)
    }
    if (context.contentResolver.update(uri, values, null, null) <= 0) {
        throw IOException("Unable to publish MediaStore recording: $uri")
    }
    val published = readStableOutputFingerprint(context, RecordingStorageType.MEDIASTORE, uri.toString())
    if (published == null || !verifiedProviderPublicationMatches(expectedFingerprint, published)) {
        // The provider already crossed the visibility boundary. Equal bytes are not proof that
        // the object at this URI is still the staging object we verified, so never manufacture
        // deletion authority from the post-publish observation. Suppression keeps the uncertain
        // result out of Reverb's Library while preserving the bytes for manual/provider recovery.
        val suppressed = suppressProviderOutputWithoutDeletion(
            context = context,
            storageType = RecordingStorageType.MEDIASTORE,
            id = uri.toString(),
            digest = expectedFingerprint.digest,
        )
        if (suppressed) {
            if (!removeVerifiedExportStaging(context, target.storageType, target.id)) {
                Log.w(TAG, "Unable to revoke recovery for unsafe MediaStore publish ${target.id}")
            }
        } else {
            Log.w(TAG, "Unable to durably suppress unsafe MediaStore publish $uri")
        }
        throw IOException(
            if (published == null) "Unable to verify published MediaStore recording"
            else "Published MediaStore recording no longer matches verified staging",
        )
    }
    val publishedIdentity = published.providerIdentity
        ?.takeIf { it.isNotBlank() }
        ?: throw IOException("Published MediaStore recording has no stable identity")
    val actualName = queryContentDisplayName(context, uri)?.takeIf { it.isNotBlank() } ?: finalName
    return target.copy(
        displayName = actualName,
        staging = false,
        publishedIdentity = publishedIdentity,
    )
}

@Throws(IOException::class)
private fun finalizeFileOutputTarget(
    context: Context,
    target: RecordingOutputTarget,
    expectedFingerprint: StableOutputFingerprint,
): RecordingOutputTarget {
    if (!target.staging) {
        target.file?.let { file ->
            if (target.directoryId == getSharedMusicRecordingsDirectory().absolutePath) {
                MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf(target.mimeType), null)
            }
        }
        return target
    }
    val source = requireNotNull(target.file)
    val current = readStableFileOutputFingerprint(source)
        ?: throw IOException("Unable to bind output staging to a stable file")
    if (!stableOutputFingerprintMatches(RecordingStorageType.FILE, expectedFingerprint, current)) {
        throw IOException("Output staging file changed after verification")
    }
    val published = publishStagedFileResult(
        source = source,
        finalDisplayName = target.displayName,
        expectedFingerprint = expectedFingerprint,
        onUnexpectedPublishedFile = { unexpected, digest ->
            suppressFileOutputWithoutDeletion(
                context = context,
                id = unexpected.absolutePath,
                digest = digest,
            )
        },
    )
    val destination = published.file
    if (target.directoryId == getSharedMusicRecordingsDirectory().absolutePath) {
        MediaScannerConnection.scanFile(context, arrayOf(destination.absolutePath), arrayOf(target.mimeType), null)
    }
    return target.copy(
        id = destination.absolutePath,
        displayName = destination.name,
        file = destination,
        staging = false,
        publishedIdentity = published.identity,
    )
}

private data class PublishedStagedFile(
    val file: File,
    val identity: String,
)

@Throws(IOException::class)
internal fun publishStagedFile(
    source: File,
    finalDisplayName: String,
    expectedFingerprint: StableOutputFingerprint? = null,
    onUnexpectedPublishedFile: ((File, CopyDigest) -> Boolean)? = null,
): File = publishStagedFileResult(
    source = source,
    finalDisplayName = finalDisplayName,
    expectedFingerprint = expectedFingerprint,
    onUnexpectedPublishedFile = onUnexpectedPublishedFile,
).file

@Throws(IOException::class)
private fun publishStagedFileResult(
    source: File,
    finalDisplayName: String,
    expectedFingerprint: StableOutputFingerprint? = null,
    onUnexpectedPublishedFile: ((File, CopyDigest) -> Boolean)? = null,
): PublishedStagedFile {
    val parent = source.parentFile ?: throw IOException("Output staging file has no parent")
    if (expectedFingerprint != null && expectedFingerprint.fileKey.isNullOrBlank()) {
        throw IOException("Verified file output has no stable object identity")
    }
    while (true) {
        val finalName = findAvailableDisplayName(finalDisplayName) { candidate -> File(parent, candidate).exists() }
        val destination = File(parent, finalName)
        try {
            // Same-directory move is the publish boundary. Do not use ATOMIC_MOVE here:
            // when a racing destination exists its replacement semantics are provider-specific.
            Files.move(source.toPath(), destination.toPath())
            val publishedIdentity = if (expectedFingerprint != null) {
                val published = readStableFileOutputFingerprint(destination)
                if (published == null || !verifiedFilePublishMatches(expectedFingerprint, published)) {
                    val suppressionDigest = published?.digest ?: runCatching {
                        FileInputStream(destination).use(::sha256)
                    }.getOrNull()
                    val cleanupSuppressed = suppressionDigest != null && onUnexpectedPublishedFile != null &&
                        runCatching { onUnexpectedPublishedFile(destination, suppressionDigest) }.getOrDefault(false)
                    val hiddenStateDurable = preserveUnexpectedPublishedFile(
                        source = source,
                        moved = destination,
                        finalDisplayName = finalDisplayName,
                    )
                    if (!cleanupSuppressed && !hiddenStateDurable) {
                        Log.e(
                            TAG,
                            "Unexpected published file could not be durably suppressed or hidden: $destination",
                        )
                    }
                    throw IOException("Published file was not the verified staging object")
                }
                published.fileKey?.takeIf { it.isNotBlank() }
                    ?: throw IOException("Published file has no stable identity")
            } else {
                ""
            }
            forceRecordingDirectoryDurable(parent)
            return PublishedStagedFile(destination, publishedIdentity)
        } catch (_: FileAlreadyExistsException) {
            continue
        }
    }
}

internal fun verifiedFilePublishMatches(
    expected: StableOutputFingerprint,
    published: StableOutputFingerprint,
): Boolean {
    val expectedIdentity = expected.fileKey ?: return false
    val publishedIdentity = published.fileKey ?: return false
    return copyDigestMatches(expected.digest, published.digest) &&
        sameFileObjectAcrossRename(expectedIdentity, publishedIdentity)
}

private fun preserveUnexpectedPublishedFile(source: File, moved: File, finalDisplayName: String): Boolean {
    val parent = moved.parentFile ?: return false
    try {
        Files.move(moved.toPath(), source.toPath())
        return runCatching {
            forceRecordingDirectoryDurable(parent)
            true
        }.getOrDefault(false)
    } catch (_: FileAlreadyExistsException) {
        // A new object owns the original staging path. Keep the moved object hidden too.
    } catch (_: IOException) {
        // Fall through to a hidden COPY staging name.
    } catch (_: SecurityException) {
        // Fall through to a hidden COPY staging name.
    }

    for (index in 0 until 10_000) {
        val token = "publish-race-${UUID.randomUUID()}-$index"
        val hidden = File(parent, stagingOutputName(finalDisplayName, token, kind = StagingOutputKind.COPY))
        try {
            Files.move(moved.toPath(), hidden.toPath())
            return runCatching {
                forceRecordingDirectoryDurable(parent)
                true
            }.getOrDefault(false)
        } catch (_: FileAlreadyExistsException) {
            continue
        } catch (_: Exception) {
            return false
        }
    }
    return false
}

@Throws(IOException::class)
private fun finalizeDocumentOutputTarget(
    context: Context,
    target: RecordingOutputTarget,
    expectedFingerprint: StableOutputFingerprint,
): RecordingOutputTarget {
    if (!target.staging) return target
    val sourceUri = requireNotNull(target.uri)
    requireCurrentOutputFingerprint(context, target, expectedFingerprint)
    val treeUri = target.directoryId.toUri()
    val finalName = findAvailableDocumentDisplayName(
        context = context,
        treeUri = treeUri,
        requestedDisplayName = target.displayName,
        excludedUri = sourceUri,
    )
    if (!documentSupportsRename(context, sourceUri)) {
        throw IOException("Output provider cannot safely publish verified staging without rename support")
    }
    val renamedUri = try {
        DocumentsContract.renameDocument(context.contentResolver, sourceUri, finalName)
    } catch (error: Exception) {
        throw IOException("Output provider failed to atomically publish recording", error)
    } ?: throw IOException("Output provider failed to atomically publish recording")
    val published = readStableOutputFingerprint(context, RecordingStorageType.DOCUMENT, renamedUri.toString())
        ?: throw IOException("Unable to verify published document recording")
    val sourceUriUnchanged = renamedUri == sourceUri
    val oldState = if (sourceUriUnchanged) {
        RecordingAssetState.PRESENT
    } else {
        queryUriAssetState(
            context = context,
            uri = sourceUri,
            projection = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
            logId = sourceUri.toString(),
        )
    }
    if (!documentRenameTransitionIsSafe(
            sourceUriUnchanged = sourceUriUnchanged,
            oldUriStateAfterRename = oldState,
            beforeIdentity = expectedFingerprint.providerIdentity.orEmpty(),
            afterIdentity = published.providerIdentity.orEmpty(),
            beforeDigest = expectedFingerprint.digest,
            afterDigest = published.digest,
        )
    ) {
        // The provider has already crossed its rename boundary. Do not trust the returned URI
        // enough to delete it, but do keep an unsafe/copy-like result out of Reverb's Library.
        // Suppress the original staging URI too when it survived so recovery cannot repeatedly
        // invoke the same broken rename and manufacture more final-name copies.
        val returnedSuppressed = suppressProviderOutputWithoutDeletion(
            context = context,
            storageType = RecordingStorageType.DOCUMENT,
            id = renamedUri.toString(),
            digest = published.digest,
        )
        val sourceSuppressed = sourceUriUnchanged || suppressProviderOutputWithoutDeletion(
            context = context,
            storageType = RecordingStorageType.DOCUMENT,
            id = sourceUri.toString(),
            digest = expectedFingerprint.digest,
        )
        if (!returnedSuppressed || !sourceSuppressed) {
            Log.w(TAG, "Unable to durably suppress unsafe document publish $sourceUri -> $renamedUri")
        }
        val recoveryRevoked = runCatching {
            removeVerifiedExportStaging(context, target.storageType, target.id)
        }.getOrDefault(false)
        if (!recoveryRevoked) {
            Log.w(TAG, "Unable to revoke recovery for unsafe document publish ${target.id}")
        }
        throw IOException("Published document no longer matches verified staging rename")
    }
    val publishedIdentity = published.providerIdentity
        ?.takeIf { it.isNotBlank() }
        ?: throw IOException("Published document recording has no stable identity")
    val actualName = DocumentFile.fromSingleUri(context, renamedUri)?.name
        ?.takeIf { it.isNotBlank() }
        ?: finalName
    return target.copy(
        id = renamedUri.toString(),
        displayName = actualName,
        uri = renamedUri,
        staging = false,
        publishedIdentity = publishedIdentity,
    )
}

private fun documentSupportsRename(context: Context, uri: Uri): Boolean = runCatching {
    context.contentResolver.query(
        uri,
        arrayOf(DocumentsContract.Document.COLUMN_FLAGS),
        null,
        null,
        null,
    )?.use { cursor ->
        cursor.moveToFirst() &&
            (cursor.getInt(0) and DocumentsContract.Document.FLAG_SUPPORTS_RENAME) != 0
    } == true
}.onFailure { Log.w(TAG, "Unable to inspect document publish capabilities for $uri", it) }
    .getOrDefault(false)


internal inline fun recordingIdentityForPublishedTarget(
    target: RecordingOutputTarget,
    resolveIdentity: () -> String,
): String = target.publishedIdentity.takeIf { it.isNotBlank() } ?: resolveIdentity()

fun buildRecordingEntity(
    context: Context,
    target: RecordingOutputTarget,
    durationMillis: Long,
    codecSummary: String,
    knownSizeBytes: Long? = null,
): RecordingEntity {
    val sizeBytes = knownSizeBytes?.takeIf { it > 0L } ?: resolveOutputTargetSize(context, target)
    // Publication already proved the exact object. A later path/URI lookup can race replacement;
    // never let that second observation retarget the just-published catalog row.
    val identity = recordingIdentityForPublishedTarget(target) {
        when (target.storageType) {
            RecordingStorageType.FILE -> target.file?.let(::resolveFileIdentity).orEmpty()
            RecordingStorageType.DOCUMENT,
            RecordingStorageType.MEDIASTORE,
            -> target.uri?.let { resolveProviderRecordingIdentity(context, target.storageType, it) }.orEmpty()
        }
    }
    return RecordingEntity(
        id = target.id,
        displayName = target.displayName,
        mimeType = target.mimeType,
        startedAtMillis = target.startedAtMillis,
        durationMillis = durationMillis,
        sizeBytes = sizeBytes,
        codecSummary = codecSummary,
        storageType = target.storageType,
        directoryId = target.directoryId,
        fileIdentity = identity,
    )
}

internal fun providerRecordingIdentity(
    storageType: RecordingStorageType,
    id: String,
    sizeBytes: Long,
    revisionToken: Long,
): String {
    if (storageType == RecordingStorageType.FILE || id.isBlank()) return ""
    if (revisionToken <= 0L) return ""
    val encodedId = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(id.toByteArray(Charsets.UTF_8))
    return "provider:${storageType.storageCode.toInt()}:$encodedId:${sizeBytes.coerceAtLeast(0L)}:$revisionToken"
}

internal fun resolveProviderRecordingIdentity(
    context: Context,
    storageType: RecordingStorageType,
    uri: Uri,
): String = when (storageType) {
    RecordingStorageType.FILE -> ""
    RecordingStorageType.DOCUMENT -> {
        val document = runCatching { DocumentFile.fromSingleUri(context, uri) }.getOrNull() ?: return ""
        providerRecordingIdentity(
            storageType = storageType,
            id = uri.toString(),
            sizeBytes = document.length().coerceAtLeast(0L),
            revisionToken = document.lastModified().coerceAtLeast(0L),
        )
    }
    RecordingStorageType.MEDIASTORE -> runCatching {
        val useGeneration = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        val projection = if (useGeneration) {
            arrayOf(
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_MODIFIED,
                MediaStore.MediaColumns.GENERATION_MODIFIED,
            )
        } else {
            arrayOf(MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED)
        }
        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use ""
            val sizeBytes = cursor.getLong(0).coerceAtLeast(0L)
            val modifiedSeconds = cursor.getLong(1).coerceAtLeast(0L)
            val revision = if (useGeneration) cursor.getLong(2).coerceAtLeast(0L) else 0L
            val fallbackRevision = if (modifiedSeconds > Long.MAX_VALUE / 1000L) Long.MAX_VALUE
            else modifiedSeconds * 1000L
            providerRecordingIdentity(
                storageType,
                uri.toString(),
                sizeBytes,
                revision.takeIf { it > 0L } ?: fallbackRevision,
            )
        } ?: ""
    }.getOrDefault("")
}

private data class ProviderRecordingIdentity(
    val storageType: RecordingStorageType,
    val encodedId: String,
    val sizeBytes: Long,
    val revisionToken: Long,
)

private fun parseProviderRecordingIdentity(value: String): ProviderRecordingIdentity? {
    val parts = value.split(':')
    if (parts.size != 5 || parts[0] != "provider") return null
    val storageType = parts[1].toIntOrNull()?.let(RecordingStorageType::fromStorageCode)
        ?: RecordingStorageType.fromLegacyName(parts[1])
        ?: return null
    if (storageType == RecordingStorageType.FILE || parts[2].isBlank()) return null
    val sizeBytes = parts[3].toLongOrNull()?.takeIf { it >= 0L } ?: return null
    val revisionToken = parts[4].toLongOrNull()?.takeIf { it > 0L } ?: return null
    return ProviderRecordingIdentity(storageType, parts[2], sizeBytes, revisionToken)
}

internal fun providerRecordingIdentityMatches(stored: String, current: String): Boolean {
    val storedIdentity = parseProviderRecordingIdentity(stored) ?: return false
    val currentIdentity = parseProviderRecordingIdentity(current) ?: return false
    return storedIdentity == currentIdentity
}

internal fun sameProviderObjectAcrossMutation(before: String?, after: String?): Boolean {
    val beforeIdentity = before?.let(::parseProviderRecordingIdentity) ?: return false
    val afterIdentity = after?.let(::parseProviderRecordingIdentity) ?: return false
    return beforeIdentity.storageType == afterIdentity.storageType &&
        beforeIdentity.encodedId == afterIdentity.encodedId
}

internal fun recordingContentIdentityMatches(context: Context, recording: RecordingEntity): Boolean {
    return when (val storageType = recording.storageType) {
        RecordingStorageType.FILE -> recordingFileIdentityMatches(recording)
        RecordingStorageType.DOCUMENT,
        RecordingStorageType.MEDIASTORE,
        -> {
            if (recording.fileIdentity.isBlank()) return true
            val current = resolveProviderRecordingIdentity(context, storageType, recording.id.toUri())
            providerRecordingIdentityMatches(recording.fileIdentity, current)
        }
    }
}

internal fun recordingDestructiveIdentityMatches(context: Context, recording: RecordingEntity): Boolean {
    if (recording.fileIdentity.isBlank()) return false
    return recordingContentIdentityMatches(context, recording)
}

internal fun recordingDeletionIdentityMatches(context: Context, recording: RecordingEntity): Boolean =
    recordingDestructiveIdentityMatches(context, recording)

internal fun providerDeletionCompleted(
    deleteReportedSuccess: Boolean,
    observedState: RecordingAssetState,
): Boolean = deleteReportedSuccess && observedState == RecordingAssetState.MISSING

internal fun resolveFileIdentity(file: File): String {
    val attributes = runCatching {
        Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
    }.getOrNull()
    val birthNanos = attributes?.creationTime()?.let { time ->
        runCatching { time.to(TimeUnit.NANOSECONDS) }.getOrDefault(0L)
    } ?: 0L

    val statIdentity = runCatching {
        val stat = Os.stat(file.absolutePath)
        if (stat.st_ino == 0L) "" else buildStatFileIdentity(
            stat.st_dev, stat.st_ino, stat.st_ctim.tv_sec, stat.st_ctim.tv_nsec, birthNanos,
        )
    }.getOrDefault("")
    if (statIdentity.isNotBlank()) return statIdentity

    return runCatching {
        val key = attributes?.fileKey()?.toString()?.takeIf { it.isNotBlank() } ?: return@runCatching ""
        val encodedKey = Base64.getUrlEncoder().withoutPadding().encodeToString(key.toByteArray(Charsets.UTF_8))
        "nio:$encodedKey:$birthNanos"
    }.getOrDefault("")
}

internal fun resolveFileDescriptorIdentity(descriptor: FileDescriptor): String = runCatching {
    val stat = Os.fstat(descriptor)
    if (stat.st_ino == 0L) "" else "statfd:${stat.st_dev}:${stat.st_ino}:${stat.st_ctim.tv_sec}:${stat.st_ctim.tv_nsec}"
}.getOrDefault("")

private fun buildStatFileIdentity(
    dev: Long,
    ino: Long,
    ctimeSeconds: Long,
    ctimeNanos: Long,
    birthNanos: Long,
): String = "stat:$dev:$ino:$ctimeSeconds:$ctimeNanos:$birthNanos"

internal fun fileIdentityMatches(storedIdentity: String, currentIdentity: String): Boolean =
    storedIdentity.isNotBlank() && currentIdentity.isNotBlank() && storedIdentity == currentIdentity

internal fun sameFileObjectAcrossRename(before: String, after: String): Boolean {
    if (before.isBlank() || after.isBlank()) return false
    val beforeParts = before.split(':')
    val afterParts = after.split(':')
    return when {
        beforeParts.size == 6 && afterParts.size == 6 && beforeParts[0] == "stat" && afterParts[0] == "stat" -> {
            val sameBase = beforeParts[1] == afterParts[1] && beforeParts[2] == afterParts[2]
            val beforeBirth = beforeParts[5].toLongOrNull() ?: 0L
            val afterBirth = afterParts[5].toLongOrNull() ?: 0L
            sameBase && (beforeBirth == 0L || afterBirth == 0L || beforeBirth == afterBirth)
        }
        beforeParts.size == 3 && afterParts.size == 3 && beforeParts[0] == "nio" && afterParts[0] == "nio" ->
            beforeParts[1] == afterParts[1] && beforeParts[2] == afterParts[2]
        else -> false
    }
}

internal fun fileDescriptorIdentityMatches(storedIdentity: String, descriptorIdentity: String): Boolean {
    val stored = storedIdentity.split(':')
    val descriptor = descriptorIdentity.split(':')
    return stored.size == 6 && descriptor.size == 5 &&
        stored[0] == "stat" && descriptor[0] == "statfd" &&
        stored[1] == descriptor[1] && stored[2] == descriptor[2] &&
        stored[3] == descriptor[3] && stored[4] == descriptor[4]
}

internal fun recordingFileIdentityMatches(recording: RecordingEntity): Boolean {
    if (recording.storageType != RecordingStorageType.FILE) return true
    return fileIdentityMatches(recording.fileIdentity, resolveFileIdentity(File(recording.id)))
}

internal fun fileRecordingAssetState(file: File): RecordingAssetState = try {
    val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
    if (attributes.isRegularFile) RecordingAssetState.PRESENT else RecordingAssetState.MISSING
} catch (_: NoSuchFileException) {
    RecordingAssetState.MISSING
} catch (error: IOException) {
    Log.w(TAG, "Unable to inspect recording $file", error)
    RecordingAssetState.UNAVAILABLE
} catch (error: SecurityException) {
    Log.w(TAG, "Unable to inspect recording $file", error)
    RecordingAssetState.UNAVAILABLE
}

internal fun recordingAssetState(
    context: Context,
    recording: RecordingEntity,
): RecordingAssetState {
    return when (recording.storageType) {
        RecordingStorageType.FILE -> fileRecordingAssetState(File(recording.id))

        RecordingStorageType.DOCUMENT -> {
            val uri = runCatching { recording.id.toUri() }.getOrNull()
                ?: return RecordingAssetState.MISSING
            queryUriAssetState(
                context = context,
                uri = uri,
                projection = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                logId = recording.id,
            )
        }

        RecordingStorageType.MEDIASTORE -> {
            val uri = runCatching { recording.id.toUri() }.getOrNull()
                ?: return RecordingAssetState.MISSING
            queryUriAssetState(
                context = context,
                uri = uri,
                projection = arrayOf(MediaStore.MediaColumns._ID),
                logId = recording.id,
            )
        }

    }
}

internal fun selectedRecordingAssetState(
    rawState: RecordingAssetState,
    storageType: RecordingStorageType,
    storedIdentity: String,
    currentIdentity: String,
): RecordingAssetState {
    if (rawState != RecordingAssetState.PRESENT || storedIdentity.isBlank()) return rawState
    if (currentIdentity.isBlank()) return RecordingAssetState.UNAVAILABLE
    val matches = when (storageType) {
        RecordingStorageType.FILE -> fileIdentityMatches(storedIdentity, currentIdentity)
        RecordingStorageType.DOCUMENT,
        RecordingStorageType.MEDIASTORE,
        -> providerRecordingIdentityMatches(storedIdentity, currentIdentity)
    }
    return if (matches) RecordingAssetState.PRESENT else RecordingAssetState.MISSING
}

internal fun selectedRecordingAssetState(
    context: Context,
    recording: RecordingEntity,
): RecordingAssetState {
    val rawState = recordingAssetState(context, recording)
    if (rawState != RecordingAssetState.PRESENT || recording.fileIdentity.isBlank()) return rawState
    val currentIdentity = when (val storageType = recording.storageType) {
        RecordingStorageType.FILE -> resolveFileIdentity(File(recording.id))
        RecordingStorageType.DOCUMENT,
        RecordingStorageType.MEDIASTORE,
        -> resolveProviderRecordingIdentity(context, storageType, recording.id.toUri())
    }
    return selectedRecordingAssetState(
        rawState = rawState,
        storageType = recording.storageType,
        storedIdentity = recording.fileIdentity,
        currentIdentity = currentIdentity,
    )
}

internal fun deleteVerifiedRecordingAsset(
    context: Context,
    recording: RecordingEntity,
): Boolean {
    // Callers must have content-fingerprinted this exact provider asset immediately before
    // reaching this function. A concrete provider identity, when available, must still match.
    if (!recordingDeletionIdentityMatches(context, recording)) return false
    when (recordingAssetState(context, recording)) {
        RecordingAssetState.MISSING,
        RecordingAssetState.UNAVAILABLE,
        -> return false
        RecordingAssetState.PRESENT -> Unit
    }
    return when (recording.storageType) {
        RecordingStorageType.FILE -> {
            // FILE deletion must go through RecordingRepository's journaled rename-to-claim
            // transaction. A raw path delete cannot close the check-to-delete reuse race.
            false
        }

        RecordingStorageType.DOCUMENT -> runCatching {
            val document = DocumentFile.fromSingleUri(context, recording.id.toUri())
            val deleted = document?.delete() == true
            providerDeletionCompleted(deleted, recordingAssetState(context, recording))
        }.onFailure { Log.w(TAG, "Unable to delete recording ${recording.id}", it) }.getOrDefault(false)
        RecordingStorageType.MEDIASTORE -> runCatching {
            val deleted = context.contentResolver.delete(recording.id.toUri(), null, null) > 0
            providerDeletionCompleted(deleted, recordingAssetState(context, recording))
        }.onFailure { Log.w(TAG, "Unable to delete recording ${recording.id}", it) }.getOrDefault(false)
    }
}

fun renameRecordingAsset(
    context: Context,
    recording: RecordingEntity,
    requestedBaseName: String,
): RecordingEntity? {
    if (!recordingDestructiveIdentityMatches(context, recording)) return null
    val extension = recording.displayName.substringAfterLast('.', "")
    var sanitized = sanitizeBaseName(requestedBaseName)
    if (sanitized.isBlank()) return null
    if (extension.isNotBlank()) {
        if (sanitized.endsWith(".$extension", ignoreCase = true)) {
            sanitized = sanitized.dropLast(extension.length + 1)
        }
        sanitized = "$sanitized.$extension"
    }
    if (sanitized == recording.displayName) return recording
    return when (recording.storageType) {
        RecordingStorageType.FILE -> renameFileRecording(context, recording, sanitized)
        RecordingStorageType.DOCUMENT -> renameDocumentRecording(context, recording, sanitized)
        RecordingStorageType.MEDIASTORE -> renameMediaStoreRecording(context, recording, sanitized)
    }
}

fun copyRecordingToDirectory(
    context: Context,
    recording: RecordingEntity,
    targetTreeUri: Uri?,
): RecordingEntity? {
    var target: RecordingOutputTarget? = null
    var cleanupFingerprint: StableOutputFingerprint? = null
    var preserveVerifiedCopyOnFailure = false
    return try {
        val resolvedTarget = createOutputTargetInDirectory(
            context = context,
            targetTreeUri = targetTreeUri,
            requestedDisplayName = recording.displayName,
            mimeType = recording.mimeType,
            startedAtMillis = recording.startedAtMillis,
        ).also { target = it }
        val sourceSize = when (recording.storageType) {
            RecordingStorageType.FILE -> runCatching { Files.size(File(recording.id).toPath()) }
                .getOrNull()
                ?.takeIf { it > 0L }
            // Document-provider size metadata may lag behind the stream contents.
            // The destination is verified against the bytes actually copied below.
            RecordingStorageType.DOCUMENT,
            RecordingStorageType.MEDIASTORE,
            -> null
        }
        val input = openRecordingInputStream(context, recording)
            ?: throw IOException("Unable to open source recording: ${recording.storageType}")
        lateinit var sourceDigest: CopyDigest
        input.use { source ->
            when (resolvedTarget.storageType) {
                RecordingStorageType.FILE -> {
                    FileOutputStream(requireNotNull(resolvedTarget.file)).use { output ->
                        sourceDigest = copyWithSha256(source, output)
                        output.fd.sync()
                    }
                }

                RecordingStorageType.DOCUMENT,
                RecordingStorageType.MEDIASTORE,
                -> {
                    openWritableParcelFileDescriptor(context, resolvedTarget).use { descriptor ->
                        FileOutputStream(descriptor.fileDescriptor).use { output ->
                            sourceDigest = copyWithSha256(source, output)
                            output.fd.sync()
                        }
                    }
                }
            }
        }
        val copiedBytes = sourceDigest.byteCount
        if (copiedBytes <= 0L) {
            throw IOException("Recording source was empty")
        }
        if (sourceSize != null && copiedBytes != sourceSize) {
            throw IOException("Recording copy was incomplete: expected=$sourceSize copied=$copiedBytes")
        }
        val targetSize = resolveOutputTargetSize(context, resolvedTarget)
        val verifiedTargetSize = if (targetSize == copiedBytes) {
            targetSize
        } else {
            countOutputTargetBytes(context, resolvedTarget, copiedBytes)
        }
        if (verifiedTargetSize != copiedBytes) {
            throw IOException("Recording copy size mismatch: copied=$copiedBytes target=$verifiedTargetSize")
        }
        val targetFingerprint = readStableOutputFingerprint(
            context,
            resolvedTarget.storageType,
            resolvedTarget.id,
        ) ?: throw IOException("Unable to bind copied recording to a stable output object")
        if (!copyDigestMatches(sourceDigest, targetFingerprint.digest)) {
            throw IOException("Recording copy content verification failed")
        }
        cleanupFingerprint = targetFingerprint
        val sourceAfterCopy = runCatching {
            sha256StableRecording(context, recording)
        }.onFailure {
            // The verified target may now be the only surviving copy. Do not turn source
            // disappearance/provider failure into target cleanup.
            Log.w(TAG, "Unable to recheck source after verified copy ${recording.id}", it)
        }.getOrNull()
        if (completedCopySourcePreservationRequired(sourceDigest, sourceAfterCopy)) {
            // The verified staging target may now be the only surviving version of the bytes
            // selected by the user. Keep it if any later step fails. A proven content change
            // aborts the move; an unavailable recheck may still safely publish the verified copy.
            preserveVerifiedCopyOnFailure = true
            if (sourceAfterCopy != null) {
                throw IOException("Recording source changed during copy")
            }
        }
        val finalizedTarget = finalizeOutputTarget(context, resolvedTarget, targetFingerprint)
        target = finalizedTarget
        cleanupFingerprint = outputCleanupFingerprintForTarget(finalizedTarget, targetFingerprint)

        // The finalizer already verified and pinned the published object. Only legacy/non-staging
        // callers may need a fresh lookup; never re-resolve a verified move target after publish.
        val copiedIdentity = recordingIdentityForPublishedTarget(finalizedTarget) {
            when (finalizedTarget.storageType) {
                RecordingStorageType.FILE -> finalizedTarget.file?.let(::resolveFileIdentity).orEmpty()
                RecordingStorageType.DOCUMENT,
                RecordingStorageType.MEDIASTORE,
                -> finalizedTarget.uri?.let { uri ->
                    resolveProviderRecordingIdentity(context, finalizedTarget.storageType, uri)
                }.orEmpty()
            }
        }
        rebindRecordingWaveformCache(
            source = recording,
            target = recording.copy(
                id = finalizedTarget.id,
                displayName = finalizedTarget.displayName,
                sizeBytes = verifiedTargetSize,
                storageType = finalizedTarget.storageType,
                directoryId = finalizedTarget.directoryId,
                fileIdentity = copiedIdentity,
                missingSinceMillis = null,
            ),
        )
    } catch (e: Exception) {
        Log.w(TAG, "exportToTarget failed for ${target?.displayName ?: recording.displayName}", e)
        target?.let { cleanupTarget ->
            if (preserveVerifiedCopyOnFailure) {
                Log.w(TAG, "Retaining verified copied recording after source changed: ${cleanupTarget.id}")
            } else {
                val expectedFingerprint = cleanupFingerprint
                if (expectedFingerprint == null) {
                    Log.w(TAG, "Retaining partial copied recording because cleanup identity is uncertain: ${cleanupTarget.id}")
                } else if (!suppressAndDeleteOutputTarget(
                        context,
                        cleanupTarget,
                        expectedFingerprint = expectedFingerprint,
                    )) {
                    Log.w(TAG, "Deferred cleanup for partial copied recording ${cleanupTarget.id}")
                }
            }
        }
        null
    }
}

internal data class CopyDigest(
    val byteCount: Long,
    val sha256: ByteArray,
)

internal fun completedCopySourcePreservationRequired(
    copiedDigest: CopyDigest,
    sourceAfterCopy: CopyDigest?,
): Boolean = sourceAfterCopy == null || !copyDigestMatches(copiedDigest, sourceAfterCopy)

internal fun copyWithSha256(
    input: InputStream,
    output: OutputStream,
    bufferSize: Int = FILE_COPY_BUFFER_BYTES,
): CopyDigest {
    require(bufferSize > 0) { "Copy buffer must be positive" }
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(bufferSize)
    var total = 0L
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        if (count == 0) {
            val value = input.read()
            if (value < 0) break
            output.write(value)
            digest.update(value.toByte())
            total++
            continue
        }
        output.write(buffer, 0, count)
        digest.update(buffer, 0, count)
        total += count.toLong()
    }
    return CopyDigest(total, digest.digest())
}

internal fun sha256(input: InputStream, bufferSize: Int = FILE_COPY_BUFFER_BYTES): CopyDigest {
    require(bufferSize > 0) { "Digest buffer must be positive" }
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(bufferSize)
    var total = 0L
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        if (count == 0) {
            val value = input.read()
            if (value < 0) break
            digest.update(value.toByte())
            total++
            continue
        }
        digest.update(buffer, 0, count)
        total += count.toLong()
    }
    return CopyDigest(total, digest.digest())
}

internal fun openRecordingInputStream(context: Context, recording: RecordingEntity): InputStream? =
    when (recording.storageType) {
        RecordingStorageType.FILE -> openVerifiedFileInputStream(recording)
        RecordingStorageType.DOCUMENT,
        RecordingStorageType.MEDIASTORE,
        -> if (recordingContentIdentityMatches(context, recording)) {
            context.contentResolver.openInputStream(recording.id.toUri())
        } else {
            null
        }
    }

internal fun openVerifiedFileInputStream(recording: RecordingEntity): FileInputStream? {
    if (recording.storageType != RecordingStorageType.FILE || recording.fileIdentity.isBlank()) return null
    val stream = try {
        FileInputStream(File(recording.id))
    } catch (_: Exception) {
        return null
    }
    val openedIdentity = resolveFileDescriptorIdentity(stream.fd)
    if (!fileDescriptorIdentityMatches(recording.fileIdentity, openedIdentity)) {
        runCatching { stream.close() }
        return null
    }
    return stream
}

internal fun sha256StableRecording(
    context: Context,
    recording: RecordingEntity,
): CopyDigest? {
    val digest = openRecordingInputStream(context, recording)?.use(::sha256) ?: return null
    return digest.takeIf { recordingContentIdentityMatches(context, recording) }
}

internal fun recordingsHaveSameContent(
    context: Context,
    first: RecordingEntity,
    second: RecordingEntity,
): Boolean {
    if (first.sizeBytes > 0L && second.sizeBytes > 0L && first.sizeBytes != second.sizeBytes) return false
    return runCatching {
        val firstDigest = sha256StableRecording(context, first) ?: return@runCatching false
        val secondDigest = sha256StableRecording(context, second) ?: return@runCatching false
        firstDigest.byteCount == secondDigest.byteCount &&
            firstDigest.sha256.contentEquals(secondDigest.sha256)
    }.onFailure { Log.w(TAG, "Unable to compare recordings ${first.id} and ${second.id}", it) }
        .getOrDefault(false)
}

@Throws(IOException::class)
internal fun verifyWavOutputStreamAndDigest(
    input: InputStream,
    expectedFileBytes: Long,
    expectedPrefix: ByteArray,
    payloadOffsetBytes: Long,
    payloadBytes: Long,
    expectedPayloadSha256: ByteArray,
    bufferSize: Int = FILE_COPY_BUFFER_BYTES,
): CopyDigest {
    require(expectedFileBytes > 0L)
    require(payloadOffsetBytes == expectedPrefix.size.toLong())
    require(payloadBytes >= 0L)
    require(bufferSize > 0)
    val paddingBytes = expectedFileBytes - payloadOffsetBytes - payloadBytes
    require(paddingBytes in 0L..1L)

    val observedPrefix = ByteArray(expectedPrefix.size)
    if (!input.readFully(observedPrefix) || !observedPrefix.contentEquals(expectedPrefix)) {
        throw IOException("Export header verification failed")
    }

    val fullDigest = MessageDigest.getInstance("SHA-256")
    fullDigest.update(observedPrefix)
    val payloadDigest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(bufferSize)
    var remaining = payloadBytes
    while (remaining > 0L) {
        val requested = minOf(buffer.size.toLong(), remaining).toInt()
        val count = input.read(buffer, 0, requested)
        if (count < 0) throw IOException("Unexpected EOF verifying recording payload")
        if (count == 0) {
            val value = input.read()
            if (value < 0) throw IOException("Unexpected EOF verifying recording payload")
            val byte = value.toByte()
            payloadDigest.update(byte)
            fullDigest.update(byte)
            remaining--
            continue
        }
        payloadDigest.update(buffer, 0, count)
        fullDigest.update(buffer, 0, count)
        remaining -= count.toLong()
    }
    if (!payloadDigest.digest().contentEquals(expectedPayloadSha256)) {
        throw IOException("Export payload verification failed")
    }
    if (paddingBytes == 1L) {
        val padding = input.read()
        if (padding != 0) throw IOException("Invalid WAV padding byte")
        fullDigest.update(0.toByte())
    }
    if (input.read() >= 0) throw IOException("Export contains unexpected trailing bytes")
    return CopyDigest(expectedFileBytes, fullDigest.digest())
}

@Throws(IOException::class)
internal fun verifyWavOutputTargetAndDigest(
    context: Context,
    target: RecordingOutputTarget,
    expectedFileBytes: Long,
    expectedPrefix: ByteArray,
    payloadOffsetBytes: Long,
    payloadBytes: Long,
    expectedPayloadSha256: ByteArray,
): StableOutputFingerprint {
    return when (target.storageType) {
        RecordingStorageType.FILE -> {
            val file = requireNotNull(target.file)
            FileInputStream(file).use { source ->
                val openedIdentity = resolveFileDescriptorIdentity(source.fd)
                    .takeIf { it.isNotBlank() }
                    ?: throw IOException("Unable to identify exported recording")
                val digest = verifyWavOutputStreamAndDigest(
                    input = source,
                    expectedFileBytes = expectedFileBytes,
                    expectedPrefix = expectedPrefix,
                    payloadOffsetBytes = payloadOffsetBytes,
                    payloadBytes = payloadBytes,
                    expectedPayloadSha256 = expectedPayloadSha256,
                )
                val closedIdentity = resolveFileDescriptorIdentity(source.fd)
                if (openedIdentity != closedIdentity) {
                    throw IOException("Exported recording changed while verifying")
                }
                val pathIdentity = resolveFileIdentity(file).takeIf { it.isNotBlank() }
                    ?: throw IOException("Unable to identify exported recording path")
                if (!fileDescriptorIdentityMatches(pathIdentity, closedIdentity)) {
                    throw IOException("Export staging path changed while verifying")
                }
                StableOutputFingerprint(digest, fileKey = pathIdentity, providerIdentity = null)
            }
        }
        RecordingStorageType.DOCUMENT,
        RecordingStorageType.MEDIASTORE,
        -> {
            val uri = requireNotNull(target.uri)
            val before = resolveProviderRecordingIdentity(context, target.storageType, uri)
                .takeIf { it.isNotBlank() }
                ?: throw IOException("Unable to identify exported provider object")
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IOException("Unable to reopen exported recording")
            val digest = input.use { source ->
                verifyWavOutputStreamAndDigest(
                    input = source,
                    expectedFileBytes = expectedFileBytes,
                    expectedPrefix = expectedPrefix,
                    payloadOffsetBytes = payloadOffsetBytes,
                    payloadBytes = payloadBytes,
                    expectedPayloadSha256 = expectedPayloadSha256,
                )
            }
            val after = resolveProviderRecordingIdentity(context, target.storageType, uri)
            if (!providerRecordingIdentityMatches(before, after)) {
                throw IOException("Exported provider object changed while verifying")
            }
            StableOutputFingerprint(digest, fileKey = null, providerIdentity = before)
        }
    }
}

internal fun listOutputDirectoryRecordings(
    context: Context,
    treeUri: Uri?,
    knownRecordings: Map<String, RecordingEntity> = emptyMap(),
): List<RecordingEntity> {
    val suppressedIds = pendingOutputCleanupIds(context)
    if (treeUri == null) {
        return if (usesMediaStoreDefaultStorage()) {
            listMediaStoreRecordings(context, knownRecordings, suppressedIds)
        } else {
            listFileDirectoryRecordings(
                context,
                getSharedMusicRecordingsDirectory(),
                knownRecordings,
                suppressedIds,
            )
        }
    }
    return listDocumentTreeRecordings(context, treeUri, knownRecordings, suppressedIds)
}

internal fun listLegacyAppStorageRecordings(
    context: Context,
    knownRecordings: Map<String, RecordingEntity> = emptyMap(),
): List<RecordingEntity> {
    return listFileDirectoryRecordings(
        context,
        getSavedRecordingsDirectory(context),
        knownRecordings,
        pendingOutputCleanupIds(context),
    )
}

private fun recoverStagedFileOutputs(
    context: Context,
    directory: File,
    files: Array<File>,
    suppressedIds: Set<String>,
): Boolean {
    var changed = false
    files.forEach { file ->
        if (file.absolutePath in suppressedIds) return@forEach
        val name = file.name
        if (!file.isFile || !isStagingOutputName(name)) return@forEach
        val metadata = parseStagingOutputMetadata(name) ?: return@forEach
        val trackedFingerprint = if (metadata.kind == StagingOutputKind.EXPORT_TRACKED) {
            verifiedExportStagingFingerprint(context, RecordingStorageType.FILE, file.absolutePath)
        } else {
            null
        }
        if (!shouldRecoverStagingOutput(metadata, verifiedTrackedExport = trackedFingerprint != null)) return@forEach
        if (!metadata.finalDisplayName.endsWith(".${ExportFormat.WAV.extension}", ignoreCase = true) || file.length() <= 0L) return@forEach
        val observation = runCatching { FileInputStream(file).use(::readRecoverableStagingWav) }
            .onFailure { Log.w(TAG, "Unable to inspect staging recording $file", it) }
            .getOrNull() ?: return@forEach
        val target = RecordingOutputTarget(
            id = file.absolutePath,
            displayName = metadata.finalDisplayName,
            mimeType = guessMimeType(metadata.finalDisplayName),
            storageType = RecordingStorageType.FILE,
            directoryId = directory.absolutePath,
            startedAtMillis = resolveRecordingStartTimeMillis(metadata.finalDisplayName, file.lastModified()),
            file = file,
            staging = true,
        )
        val publishFingerprint = trackedFingerprint ?: readStableFileOutputFingerprint(file) ?: return@forEach
        if (!copyDigestMatches(observation.digest, publishFingerprint.digest)) return@forEach
        val recovered = runCatching { finalizeOutputTarget(context, target, publishFingerprint) }
            .onFailure { Log.w(TAG, "Unable to publish recovered staging recording $file", it) }
            .isSuccess
        if (recovered) removeVerifiedExportStaging(context, target.storageType, target.id)
        changed = changed || recovered
    }
    return changed
}

private data class DocumentTreeEntry(
    val uri: Uri,
    val name: String?,
    val mimeType: String?,
    val sizeBytes: Long,
    val modifiedMillis: Long,
    val isFile: Boolean,
)

private fun queryDocumentTreeEntries(context: Context, treeUri: Uri): List<DocumentTreeEntry> {
    val treeDocumentId = try {
        DocumentsContract.getTreeDocumentId(treeUri)
    } catch (error: RuntimeException) {
        throw IOException("Unable to resolve document tree $treeUri", error)
    }
    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocumentId)
    val projection = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
    )
    val cursor = context.contentResolver.query(childrenUri, projection, null, null, null)
        ?: throw IOException("Document tree query returned no cursor: $treeUri")
    return cursor.use { rows ->
        val idIndex = rows.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
        val nameIndex = rows.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        val mimeIndex = rows.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
        val sizeIndex = rows.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
        val modifiedIndex = rows.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
        buildList {
            while (rows.moveToNext()) {
                val documentId = rows.getString(idIndex)
                    ?: throw IOException("Document tree row has no document id: $treeUri")
                val mimeType = if (rows.isNull(mimeIndex)) null else rows.getString(mimeIndex)
                add(
                    DocumentTreeEntry(
                        uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId),
                        name = if (rows.isNull(nameIndex)) null else rows.getString(nameIndex),
                        mimeType = mimeType,
                        sizeBytes = if (rows.isNull(sizeIndex)) 0L else rows.getLong(sizeIndex).coerceAtLeast(0L),
                        modifiedMillis = if (rows.isNull(modifiedIndex)) 0L else rows.getLong(modifiedIndex).coerceAtLeast(0L),
                        isFile = mimeType != null && mimeType != DocumentsContract.Document.MIME_TYPE_DIR,
                    ),
                )
            }
        }
    }
}

private fun recoverStagedDocumentOutputs(
    context: Context,
    treeUri: Uri,
    files: List<DocumentTreeEntry>,
    suppressedIds: Set<String>,
): Boolean {
    var changed = false
    files.forEach { file ->
        if (file.uri.toString() in suppressedIds) return@forEach
        val name = file.name ?: return@forEach
        if (!file.isFile || !isStagingOutputName(name)) return@forEach
        val metadata = parseStagingOutputMetadata(name) ?: return@forEach
        val trackedFingerprint = if (metadata.kind == StagingOutputKind.EXPORT_TRACKED) {
            verifiedExportStagingFingerprint(context, RecordingStorageType.DOCUMENT, file.uri.toString())
        } else {
            null
        }
        if (!shouldRecoverStagingOutput(metadata, verifiedTrackedExport = trackedFingerprint != null)) return@forEach
        if (!metadata.finalDisplayName.endsWith(".${ExportFormat.WAV.extension}", ignoreCase = true)) return@forEach
        val observation = runCatching {
            context.contentResolver.openInputStream(file.uri)?.use(::readRecoverableStagingWav)
        }.onFailure { Log.w(TAG, "Unable to inspect staging document ${file.uri}", it) }
            .getOrNull() ?: return@forEach
        val target = RecordingOutputTarget(
            id = file.uri.toString(),
            displayName = metadata.finalDisplayName,
            mimeType = file.mimeType ?: guessMimeType(metadata.finalDisplayName),
            storageType = RecordingStorageType.DOCUMENT,
            directoryId = treeUri.toString(),
            startedAtMillis = resolveRecordingStartTimeMillis(metadata.finalDisplayName, file.modifiedMillis),
            uri = file.uri,
            staging = true,
        )
        val publishFingerprint = trackedFingerprint ?: readStableOutputFingerprint(
            context,
            RecordingStorageType.DOCUMENT,
            file.uri.toString(),
        ) ?: return@forEach
        if (!copyDigestMatches(observation.digest, publishFingerprint.digest)) return@forEach
        val recovered = runCatching { finalizeOutputTarget(context, target, publishFingerprint) }
            .onFailure { Log.w(TAG, "Unable to publish recovered staging document ${file.uri}", it) }
            .isSuccess
        if (recovered) removeVerifiedExportStaging(context, target.storageType, target.id)
        changed = changed || recovered
    }
    return changed
}

internal fun fileDirectoryListingFailureIsAuthoritativeEmpty(
    directoryState: StoragePathState,
): Boolean = directoryState == StoragePathState.MISSING

internal enum class FileDirectoryEntryScanAction { SCAN, SKIP, FAIL_SCOPE }

internal fun fileDirectoryEntryScanAction(
    observation: StoragePathObservation,
): FileDirectoryEntryScanAction = when (observation.state) {
    StoragePathState.MISSING -> FileDirectoryEntryScanAction.SKIP
    StoragePathState.UNAVAILABLE -> FileDirectoryEntryScanAction.FAIL_SCOPE
    StoragePathState.PRESENT -> if (observation.isRegularFile) {
        FileDirectoryEntryScanAction.SCAN
    } else {
        FileDirectoryEntryScanAction.SKIP
    }
}

private fun listedRecordingFileSize(file: File): Long? = try {
    Files.size(file.toPath())
} catch (_: NoSuchFileException) {
    null
} catch (error: IOException) {
    throw IOException("Unable to inspect listed recording ${file.absolutePath}", error)
} catch (error: SecurityException) {
    throw IOException("Unable to inspect listed recording ${file.absolutePath}", error)
}

private fun listFileDirectoryRecordings(
    context: Context,
    directory: File,
    knownRecordings: Map<String, RecordingEntity>,
    suppressedIds: Set<String>,
): List<RecordingEntity> {
    var files = directory.listFiles() ?: if (
        fileDirectoryListingFailureIsAuthoritativeEmpty(storagePathState(directory))
    ) {
        emptyArray()
    } else {
        // A null listing on a present/unavailable path is not evidence that every recording
        // disappeared. Abort this reconciliation scope so existing catalog rows stay visible.
        throw IOException("Unable to list recordings directory: ${directory.absolutePath}")
    }
    if (recoverStagedFileOutputs(context, directory, files, suppressedIds)) {
        files = directory.listFiles() ?: throw IOException("Unable to relist recordings directory: ${directory.absolutePath}")
    }
    return files.asSequence()
        .filter { !it.isHidden }
        .filter { it.absolutePath !in suppressedIds }
        .filter { isSupportedRecordingName(it.name) }
        .mapNotNull { file ->
            when (fileDirectoryEntryScanAction(observeStoragePath(file))) {
                FileDirectoryEntryScanAction.SKIP -> return@mapNotNull null
                FileDirectoryEntryScanAction.FAIL_SCOPE -> throw IOException(
                    "Unable to inspect listed recording: ${file.absolutePath}",
                )
                FileDirectoryEntryScanAction.SCAN -> Unit
            }
            val id = file.absolutePath
            val size = listedRecordingFileSize(file) ?: return@mapNotNull null
            if (size <= 0L) return@mapNotNull null
            val identity = resolveFileIdentity(file)
            val existing = knownRecordings[id]
            if (
                existing != null && existing.durationMillis > 0L &&
                existing.displayName == file.name && existing.sizeBytes == size &&
                fileIdentityMatches(existing.fileIdentity, identity)
            ) {
                existing
            } else {
                val strictDuration = try {
                    FileInputStream(file).use { input ->
                        structurallyCompleteRecordingDurationMillis(file.name, input)
                    }
                } catch (_: NoSuchFileException) {
                    return@mapNotNull null
                } catch (error: Exception) {
                    throw IOException("Unable to validate discovered recording $file", error)
                }
                if (strictDuration <= 0L) return@mapNotNull null
                val media = inspectRecordingMedia(file)
                RecordingEntity(
                    id = id,
                    displayName = file.name,
                    mimeType = guessMimeType(file.name),
                    startedAtMillis = resolveRecordingStartTimeMillis(file),
                    durationMillis = strictDuration,
                    sizeBytes = size,
                    codecSummary = media.codecSummary,
                    storageType = RecordingStorageType.FILE,
                    directoryId = directory.absolutePath,
                    fileIdentity = identity,
                )
            }
        }
        .toList()
}

private fun listDocumentTreeRecordings(
    context: Context,
    treeUri: Uri,
    knownRecordings: Map<String, RecordingEntity>,
    suppressedIds: Set<String>,
): List<RecordingEntity> {
    var files = queryDocumentTreeEntries(context, treeUri)
    if (recoverStagedDocumentOutputs(context, treeUri, files, suppressedIds)) {
        files = queryDocumentTreeEntries(context, treeUri)
    }
    return files.asSequence()
        .filter { it.isFile }
        .filter { file -> file.uri.toString() !in suppressedIds }
        .filter { file -> isSupportedRecordingName(file.name.orEmpty()) }
        .mapNotNull { file ->
            val uri = file.uri
            val name = file.name ?: return@mapNotNull null
            val size = file.sizeBytes
            val modifiedMillis = file.modifiedMillis
            val identity = providerRecordingIdentity(
                RecordingStorageType.DOCUMENT, uri.toString(), size, modifiedMillis,
            )
            val existing = knownRecordings[uri.toString()]
            if (
                identity.isNotBlank() && existing != null && existing.durationMillis > 0L &&
                existing.displayName == name && (size == 0L || existing.sizeBytes == size) &&
                providerRecordingIdentityMatches(existing.fileIdentity, identity)
            ) {
                existing
            } else {
                val strictDuration = try {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        structurallyCompleteRecordingDurationMillis(name, input)
                    } ?: throw IOException("Unable to open discovered recording $uri")
                } catch (error: Exception) {
                    throw IOException("Unable to validate discovered recording $uri", error)
                }
                if (strictDuration <= 0L) return@mapNotNull null
                val media = inspectRecordingMedia(context, uri, name)
                RecordingEntity(
                    id = uri.toString(),
                    displayName = name,
                    mimeType = file.mimeType ?: guessMimeType(name),
                    startedAtMillis = resolveRecordingStartTimeMillis(name, modifiedMillis),
                    durationMillis = strictDuration,
                    sizeBytes = size,
                    codecSummary = media.codecSummary,
                    storageType = RecordingStorageType.DOCUMENT,
                    directoryId = treeUri.toString(),
                    fileIdentity = identity,
                )
            }
        }
        .toList()
}

private fun listMediaStoreRecordings(
    context: Context,
    knownRecordings: Map<String, RecordingEntity>,
    suppressedIds: Set<String>,
): List<RecordingEntity> {
    if (!usesMediaStoreDefaultStorage()) return emptyList()
    val resolver = context.contentResolver
    val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    val useGeneration = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
    val projection = buildList {
        add(MediaStore.MediaColumns._ID)
        add(MediaStore.MediaColumns.DISPLAY_NAME)
        add(MediaStore.MediaColumns.MIME_TYPE)
        add(MediaStore.MediaColumns.SIZE)
        add(MediaStore.MediaColumns.DATE_MODIFIED)
        add(MediaStore.Audio.AudioColumns.DURATION)
        if (useGeneration) add(MediaStore.MediaColumns.GENERATION_MODIFIED)
        add(MediaStore.MediaColumns.IS_PENDING)
    }.toTypedArray()
    val cursor = resolver.query(
            collection,
            projection,
            "${MediaStore.MediaColumns.RELATIVE_PATH}=?",
            arrayOf(MEDIA_STORE_RELATIVE_PATH),
            "${MediaStore.MediaColumns.DATE_MODIFIED} DESC",
        ) ?: throw IOException("MediaStore recording query returned no cursor")
    return cursor.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val mimeIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val modifiedIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val durationIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.AudioColumns.DURATION)
            val generationIndex = if (useGeneration) {
                cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.GENERATION_MODIFIED)
            } else -1
            val pendingIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.IS_PENDING)
            buildList {
                while (cursor.moveToNext()) {
                    val storedName = cursor.getString(nameIndex) ?: continue
                    val uri = ContentUris.withAppendedId(collection, cursor.getLong(idIndex))
                    if (uri.toString() in suppressedIds) continue
                    val size = cursor.getLong(sizeIndex).coerceAtLeast(0L)
                    val pending = cursor.getInt(pendingIndex) != 0
                    val reportedDuration = cursor.getLong(durationIndex).coerceAtLeast(0L)
                    val modifiedSeconds = cursor.getLong(modifiedIndex).coerceAtLeast(0L)
                    val modifiedMillis = if (modifiedSeconds > Long.MAX_VALUE / 1000L) Long.MAX_VALUE
                    else modifiedSeconds * 1000L
                    val generation = if (generationIndex >= 0) cursor.getLong(generationIndex).coerceAtLeast(0L) else 0L
                    val mimeType = cursor.getString(mimeIndex) ?: guessMimeType(storedName)

                    var name = storedName
                    var media: RecordingMediaMetadata? = null
                    var durationMillis = reportedDuration

                    if (pending) {
                        val metadata = parseStagingOutputMetadata(storedName)
                        if (metadata != null) {
                            val trackedFingerprint = if (metadata.kind == StagingOutputKind.EXPORT_TRACKED) {
                                verifiedExportStagingFingerprint(
                                    context,
                                    RecordingStorageType.MEDIASTORE,
                                    uri.toString(),
                                )
                            } else {
                                null
                            }
                            if (!shouldRecoverStagingOutput(metadata, verifiedTrackedExport = trackedFingerprint != null)) {
                                continue
                            }
                            val observation = runCatching {
                                context.contentResolver.openInputStream(uri)?.use(::readRecoverableStagingWav)
                            }.onFailure { Log.w(TAG, "Unable to inspect pending staged recording $uri", it) }
                                .getOrNull() ?: continue
                            if (!canRecoverPendingMedia(size, observation.durationMillis)) continue
                            val stagedTarget = RecordingOutputTarget(
                                id = uri.toString(),
                                displayName = metadata.finalDisplayName,
                                mimeType = mimeType,
                                storageType = RecordingStorageType.MEDIASTORE,
                                directoryId = MEDIA_STORE_DIRECTORY_ID,
                                startedAtMillis = resolveRecordingStartTimeMillis(metadata.finalDisplayName, modifiedMillis),
                                uri = uri,
                                staging = true,
                            )
                            val publishFingerprint = trackedFingerprint ?: readStableOutputFingerprint(
                                context,
                                RecordingStorageType.MEDIASTORE,
                                uri.toString(),
                            ) ?: continue
                            if (!copyDigestMatches(observation.digest, publishFingerprint.digest)) continue
                            val finalized = runCatching {
                                finalizeOutputTarget(context, stagedTarget, publishFingerprint)
                            }.onFailure { Log.w(TAG, "Unable to publish recovered pending recording $uri", it) }
                                .getOrNull() ?: continue
                            removeVerifiedExportStaging(context, stagedTarget.storageType, stagedTarget.id)
                            name = finalized.displayName
                            durationMillis = observation.durationMillis
                            media = inspectRecordingMedia(context, uri, name)
                        } else {
                            // Older rows predate operation-kind/session staging, so they may
                            // represent either an export or a copy/move. That ambiguity is not
                            // enough authority to publish them as finished user recordings.
                            continue
                        }
                    } else {
                        if (!isSupportedRecordingName(name)) continue
                        if (durationMillis <= 0L) media = inspectRecordingMedia(context, uri, name)
                    }

                    val id = uri.toString()
                    val identity = if (pending) {
                        resolveProviderRecordingIdentity(context, RecordingStorageType.MEDIASTORE, uri)
                    } else {
                        providerRecordingIdentity(
                            RecordingStorageType.MEDIASTORE, id, size, generation.takeIf { it > 0L } ?: modifiedMillis,
                        )
                    }
                    val existing = knownRecordings[id]
                    if (
                        identity.isNotBlank() && existing != null && existing.durationMillis > 0L &&
                        existing.displayName == name && (size == 0L || existing.sizeBytes == size) &&
                        providerRecordingIdentityMatches(existing.fileIdentity, identity)
                    ) {
                        add(existing)
                        continue
                    }
                    add(
                        RecordingEntity(
                            id = id,
                            displayName = name,
                            mimeType = mimeType,
                            startedAtMillis = resolveRecordingStartTimeMillis(name, modifiedMillis),
                            durationMillis = durationMillis.takeIf { it > 0L }
                                ?: media?.durationMillis?.coerceAtLeast(0L)
                                ?: 0L,
                            sizeBytes = size,
                            codecSummary = media?.codecSummary ?: resolveRecordingCodecInfo(
                                extension = name.substringAfterLast('.', ""),
                                bitrate = null,
                                sampleRate = null,
                            ),
                            storageType = RecordingStorageType.MEDIASTORE,
                            directoryId = MEDIA_STORE_DIRECTORY_ID,
                            fileIdentity = identity,
                        ),
                    )
                }
            }
        }
}


private fun queryUriAssetState(
    context: Context,
    uri: Uri,
    projection: Array<String>,
    logId: String,
): RecordingAssetState = try {
    context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) RecordingAssetState.PRESENT else RecordingAssetState.MISSING
    } ?: RecordingAssetState.UNAVAILABLE
} catch (error: Exception) {
    Log.w(TAG, "Unable to inspect recording $logId", error)
    RecordingAssetState.UNAVAILABLE
}

private fun queryContentSize(context: Context, uri: Uri): Long = runCatching {
    context.contentResolver.query(
        uri,
        arrayOf(MediaStore.MediaColumns.SIZE),
        null,
        null,
        null,
    )?.use { cursor ->
        if (!cursor.moveToFirst()) 0L else cursor.getLong(0).coerceAtLeast(0L)
    } ?: 0L
}.onFailure { Log.w(TAG, "Unable to query content size for $uri", it) }.getOrDefault(0L)


internal fun mediaStoreNameQueryOccupied(cursorAvailable: Boolean, hasMatchingRow: Boolean): Boolean {
    if (!cursorAvailable) throw IOException("MediaStore name query returned no cursor")
    return hasMatchingRow
}

private fun mediaStoreNameExists(context: Context, displayName: String): Boolean {
    if (!usesMediaStoreDefaultStorage()) return false
    val cursor = context.contentResolver.query(
        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
        arrayOf(MediaStore.MediaColumns._ID),
        "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?",
        arrayOf(MEDIA_STORE_RELATIVE_PATH, displayName),
        null,
    )
    return mediaStoreNameQueryOccupied(
        cursorAvailable = cursor != null,
        hasMatchingRow = cursor?.use { it.moveToFirst() } ?: false,
    )
}

private fun queryContentDisplayName(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(
        uri,
        arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
        null,
        null,
        null,
    )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
}.getOrNull()

private fun publishMediaStoreUri(context: Context, uri: Uri) {
    if (!usesMediaStoreDefaultStorage()) return
    val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
    if (context.contentResolver.update(uri, values, null, null) <= 0) {
        throw IOException("Unable to publish MediaStore recording: $uri")
    }
}

internal fun isStagingOutputName(name: String): Boolean = name.startsWith(STAGING_OUTPUT_PREFIX)

internal fun isSupportedRecordingName(name: String): Boolean =
    !isStagingOutputName(name) && name.substringAfterLast('.', "").lowercase() in SUPPORTED_RECORDING_EXTENSIONS

internal fun stagingOutputName(
    finalDisplayName: String,
    token: String,
    sessionId: String = OUTPUT_STAGING_SESSION_ID,
    kind: StagingOutputKind = StagingOutputKind.COPY,
): String {
    val extension = finalDisplayName.substringAfterLast('.', "tmp").lowercase().ifBlank { "tmp" }
    val encodedName = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(finalDisplayName.toByteArray(Charsets.UTF_8))
    return buildString {
        append(STAGING_OUTPUT_PREFIX)
        append(kind.storageCode.toInt())
        append(STAGING_SESSION_SEPARATOR)
        append(sessionId)
        append(STAGING_SESSION_SEPARATOR)
        append(token)
        append(STAGING_SESSION_SEPARATOR)
        append(encodedName)
        append('.')
        append(extension)
    }
}

internal fun parseStagingOutputMetadata(name: String): StagingOutputMetadata? = runCatching {
    if (!isStagingOutputName(name)) return@runCatching null
    val stem = name.substringBeforeLast('.', name).removePrefix(STAGING_OUTPUT_PREFIX)
    val parts = stem.split(STAGING_SESSION_SEPARATOR, limit = 4)
    if (parts.size != 4) return@runCatching null
    val kind = StagingOutputKind.fromWireValue(parts[0]) ?: return@runCatching null
    val sessionId = parts[1].takeIf { it.isNotBlank() } ?: return@runCatching null
    if (parts[2].isBlank()) return@runCatching null
    val finalDisplayName = Base64.getUrlDecoder().decode(parts[3]).toString(Charsets.UTF_8)
        .takeIf { it.isNotBlank() && isSupportedRecordingName(it) } ?: return@runCatching null
    StagingOutputMetadata(kind, sessionId, finalDisplayName)
}.getOrNull()

internal fun shouldRecoverStagingOutput(
    metadata: StagingOutputMetadata?,
    currentSessionId: String = OUTPUT_STAGING_SESSION_ID,
    verifiedTrackedExport: Boolean = false,
): Boolean {
    val resolved = metadata ?: return false
    if (resolved.sessionId == currentSessionId) return false
    return when (resolved.kind) {
        StagingOutputKind.EXPORT -> true // Legacy staging predates durable verification markers.
        StagingOutputKind.EXPORT_TRACKED -> verifiedTrackedExport
        StagingOutputKind.COPY -> false
    }
}

internal fun canRecoverPendingMedia(sizeBytes: Long, durationMillis: Long): Boolean =
    sizeBytes > 0L && durationMillis > 0L

private fun forceRecordingDirectoryDurable(directory: File) {
    FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { channel ->
        channel.force(true)
    }
}

internal fun confirmFileDirectoryStateDurable(file: File): Boolean = runCatching {
    val parent = file.parentFile ?: return@runCatching false
    forceRecordingDirectoryDurable(parent)
    true
}.onFailure { Log.w(TAG, "Unable to persist recording directory state for $file", it) }
    .getOrDefault(false)

internal fun confirmMissingFileRecordingDurable(file: File): Boolean =
    storagePathState(file) == StoragePathState.MISSING && confirmFileDirectoryStateDurable(file)

private fun createLocalOutputTarget(
    context: Context,
    requestedDisplayName: String,
    mimeType: String,
    startedAtMillis: Long,
    storageDir: File = getSavedRecordingsDirectory(context),
    stagingKind: StagingOutputKind = StagingOutputKind.COPY,
): RecordingOutputTarget {
    val storageDirectoryExisted = storageDir.exists()
    if (!storageDirectoryExisted && !storageDir.mkdirs() && !storageDir.exists()) {
        throw IOException("Unable to create recordings directory: ${storageDir.absolutePath}")
    }
    if (!storageDirectoryExisted) {
        val parent = storageDir.parentFile
            ?: throw IOException("Recordings directory has no parent: ${storageDir.absolutePath}")
        forceRecordingDirectoryDurable(parent)
    }

    // Document-provider display names are metadata, not trusted filesystem paths.
    // A name such as "../recording.wav" must never escape app-local storage when
    // recordings are moved from SAF back into the app directory.
    val safeDisplayName = sanitizeBaseName(requestedDisplayName)
    val uniqueName = findAvailableDisplayName(safeDisplayName) { candidate -> File(storageDir, candidate).exists() }
    var file: File
    while (true) {
        file = File(storageDir, stagingOutputName(uniqueName, UUID.randomUUID().toString(), kind = stagingKind))
        if (!file.createNewFile()) continue
        try {
            forceRecordingDirectoryDurable(storageDir)
        } catch (error: Exception) {
            runCatching {
                Files.deleteIfExists(file.toPath())
                forceRecordingDirectoryDurable(storageDir)
            }
            throw if (error is IOException) error else IOException("Unable to persist output staging entry", error)
        }
        break
    }
    return RecordingOutputTarget(
        id = file.absolutePath,
        displayName = uniqueName,
        mimeType = mimeType,
        storageType = RecordingStorageType.FILE,
        directoryId = storageDir.absolutePath,
        startedAtMillis = startedAtMillis,
        file = file,
        staging = true,
    )
}

private fun renameFileRecording(
    context: Context,
    recording: RecordingEntity,
    displayName: String,
): RecordingEntity? {
    val source = File(recording.id)
    val parent = source.parentFile ?: return null
    if (displayName == source.name) return recording

    val dotIndex = displayName.lastIndexOf('.')
    val name = if (dotIndex > 0) displayName.substring(0, dotIndex) else displayName
    val extension = if (dotIndex > 0) displayName.substring(dotIndex) else ""
    var suffix = 1
    while (suffix > 0) {
        val uniqueName = if (suffix == 1) displayName else "$name ($suffix)$extension"
        val target = File(parent, uniqueName)
        try {
            Files.move(source.toPath(), target.toPath())
            val renamedIdentity = resolveFileIdentity(target)
            if (!sameFileObjectAcrossRename(recording.fileIdentity, renamedIdentity)) {
                val protected = preserveUnexpectedRenameTarget(
                    source = source,
                    moved = target,
                    originalDisplayName = recording.displayName,
                    onUnexpectedMovedFile = { unexpected, digest ->
                        suppressFileOutputWithoutDeletion(
                            context = context,
                            id = unexpected.absolutePath,
                            digest = digest,
                        )
                    },
                )
                if (!protected) {
                    Log.e(TAG, "Unexpected rename target could not be durably suppressed or restored: $target")
                }
                throw IllegalStateException("Recording changed on disk during rename")
            }
            try {
                forceRecordingDirectoryDurable(parent)
            } catch (durabilityError: Exception) {
                var rollbackMoved = false
                var rollbackDurable = false
                try {
                    Files.move(target.toPath(), source.toPath())
                    rollbackMoved = true
                    rollbackDurable = runCatching {
                        forceRecordingDirectoryDurable(parent)
                        true
                    }.getOrDefault(false)
                } catch (_: Exception) {
                    // The renamed path remains the best current identity when rollback itself fails.
                }
                if (rollbackMoved) {
                    if (rollbackDurable) {
                        Log.w(TAG, "File rename durability failed; restored original name ${recording.id}", durabilityError)
                    } else {
                        Log.w(TAG, "File rename rollback is visible but not durably synced: ${recording.id}", durabilityError)
                    }
                    // Runtime/catalog state must follow the path that actually exists now. A
                    // later recovery scan can reconcile either outcome after sudden power loss.
                    return null
                }
                // The exact object was verified after the rename, but the directory entry is
                // not known durable and rollback failed. Keep the visible target identity so
                // reconciliation can recover either power-loss outcome without touching a
                // different object that later reuses the original path.
                Log.w(TAG, "File rename durability uncertain; retaining visible renamed asset $target", durabilityError)
            }
            if (parent.absolutePath == getSharedMusicRecordingsDirectory().absolutePath) {
                MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(recording.mimeType), null)
            }
            return rebindRecordingWaveformCache(
                source = recording,
                target = recording.copy(
                    id = target.absolutePath,
                    displayName = uniqueName,
                    fileIdentity = renamedIdentity,
                ),
            )
        } catch (_: FileAlreadyExistsException) {
            suffix++
        } catch (error: IllegalStateException) {
            throw error
        } catch (_: IOException) {
            return null
        } catch (_: SecurityException) {
            return null
        }
    }
    return null
}

internal fun preserveUnexpectedRenameTarget(
    source: File,
    moved: File,
    originalDisplayName: String,
    onUnexpectedMovedFile: ((File, CopyDigest) -> Boolean)? = null,
): Boolean {
    val suppressionDigest = readStableFileOutputFingerprint(moved)?.digest ?: runCatching {
        FileInputStream(moved).use(::sha256)
    }.getOrNull()
    val suppressed = suppressionDigest != null && onUnexpectedMovedFile != null &&
        runCatching { onUnexpectedMovedFile(moved, suppressionDigest) }.getOrDefault(false)

    try {
        Files.move(moved.toPath(), source.toPath())
        val restoredDurably = source.parentFile?.takeIf { it.isDirectory }?.let { parent ->
            runCatching {
                forceRecordingDirectoryDurable(parent)
                true
            }.getOrDefault(false)
        } == true
        return suppressed || restoredDurably
    } catch (_: FileAlreadyExistsException) {
        // A new file owns the original path. Preserve the object we accidentally moved
        // under a separate visible recovery name rather than overwrite either object.
    } catch (_: IOException) {
        // Fall through to recovery-name publication.
    } catch (_: SecurityException) {
        // Fall through to recovery-name publication.
    }

    val parent = moved.parentFile ?: return suppressed
    val extension = originalDisplayName.substringAfterLast('.', "").takeIf { it.isNotBlank() }
    val suffix = extension?.let { ".$it" }.orEmpty()
    val base = "recovered-rename-race-${System.currentTimeMillis()}"
    for (index in 0 until 10_000) {
        val name = if (index == 0) "$base$suffix" else "$base-$index$suffix"
        val recovery = File(parent, name)
        try {
            Files.move(moved.toPath(), recovery.toPath())
            val recoveryDurable = runCatching {
                forceRecordingDirectoryDurable(parent)
                true
            }.getOrDefault(false)
            return suppressed || recoveryDurable
        } catch (_: FileAlreadyExistsException) {
            continue
        } catch (_: Exception) {
            return suppressed
        }
    }
    return suppressed
}

private fun renameMediaStoreRecording(
    context: Context,
    recording: RecordingEntity,
    displayName: String,
): RecordingEntity? = runCatching {
    val uri = recording.id.toUri()
    val uniqueName = findAvailableDisplayName(displayName) { candidate ->
        candidate != recording.displayName && mediaStoreNameExists(context, candidate)
    }
    if (uniqueName == recording.displayName) return@runCatching recording
    val values = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, uniqueName) }
    if (context.contentResolver.update(uri, values, null, null) <= 0) return@runCatching null
    val renamedIdentity = resolveProviderRecordingIdentity(context, RecordingStorageType.MEDIASTORE, uri)
    if (!sameProviderObjectAcrossMutation(recording.fileIdentity, renamedIdentity)) {
        Log.w(TAG, "MediaStore recording identity changed during rename: ${recording.id}")
        return@runCatching null
    }
    val renamed = recording.copy(
        displayName = queryContentDisplayName(context, uri)?.takeIf { it.isNotBlank() } ?: uniqueName,
        fileIdentity = renamedIdentity,
    )
    rebindRecordingWaveformCache(recording, renamed)
}.onFailure { Log.w(TAG, "Unable to rename MediaStore recording ${recording.id}", it) }.getOrNull()

internal fun documentRenameTransitionIsSafe(
    sourceUriUnchanged: Boolean,
    oldUriStateAfterRename: RecordingAssetState,
    beforeIdentity: String,
    afterIdentity: String,
    beforeDigest: CopyDigest?,
    afterDigest: CopyDigest?,
): Boolean {
    if (afterIdentity.isBlank()) return false
    val before = beforeDigest ?: return false
    val after = afterDigest ?: return false
    if (!copyDigestMatches(before, after)) return false
    if (sourceUriUnchanged) {
        return sameProviderObjectAcrossMutation(beforeIdentity, afterIdentity)
    }
    return oldUriStateAfterRename == RecordingAssetState.MISSING
}

internal fun rejectedDocumentRenameShouldSuppressReturnedUri(sourceUriUnchanged: Boolean): Boolean =
    !sourceUriUnchanged

private fun renameDocumentRecording(
    context: Context,
    recording: RecordingEntity,
    displayName: String,
): RecordingEntity? {
    return runCatching {
        val sourceUri = recording.id.toUri()
        val document = DocumentFile.fromSingleUri(context, sourceUri) ?: return@runCatching null
        val uniqueName = findAvailableDocumentDisplayName(
            context = context,
            treeUri = recording.directoryId.toUri(),
            requestedDisplayName = displayName,
            excludedUri = sourceUri,
        )
        if (uniqueName == recording.displayName || uniqueName == document.name) {
            return@runCatching recording
        }
        // A provider is allowed to return a new URI when rename changes its document ID.
        // Pin the selected bytes before mutation so a URI-changing result can be proven to
        // represent the same recording rather than an unrelated equal-looking document.
        val beforeDigest = sha256StableRecording(context, recording) ?: return@runCatching null
        val renamedUri = DocumentsContract.renameDocument(context.contentResolver, sourceUri, uniqueName)
            ?: return@runCatching null
        val renamedIdentity = resolveProviderRecordingIdentity(context, RecordingStorageType.DOCUMENT, renamedUri)
        val renamed = recording.copy(
            id = renamedUri.toString(),
            displayName = DocumentFile.fromSingleUri(context, renamedUri)?.name ?: uniqueName,
            fileIdentity = renamedIdentity,
        )
        val sourceUriUnchanged = renamedUri == sourceUri
        val oldState = if (sourceUriUnchanged) RecordingAssetState.PRESENT
        else recordingAssetState(context, recording)
        // Rename is metadata, not a content mutation. Verify the selected bytes again even when
        // the provider keeps the same URI; document ID continuity alone cannot prove that a
        // buggy/provider-side rename did not rewrite or truncate the recording.
        val afterDigest = sha256StableRecording(context, renamed)
        if (!documentRenameTransitionIsSafe(
                sourceUriUnchanged = sourceUriUnchanged,
                oldUriStateAfterRename = oldState,
                beforeIdentity = recording.fileIdentity,
                afterIdentity = renamedIdentity,
                beforeDigest = beforeDigest,
                afterDigest = afterDigest,
            )
        ) {
            if (rejectedDocumentRenameShouldSuppressReturnedUri(sourceUriUnchanged) &&
                !suppressProviderOutputWithoutDeletion(
                    context = context,
                    storageType = RecordingStorageType.DOCUMENT,
                    id = renamedUri.toString(),
                    // Suppress only while the returned URI still contains the bytes the user
                    // selected. If it is unrelated/different content, cleanup replay drops the
                    // suppression without ever granting deletion authority.
                    digest = beforeDigest,
                )
            ) {
                Log.w(TAG, "Unable to suppress rejected document rename result $renamedUri")
            }
            Log.w(TAG, "Document recording identity changed during rename: ${recording.id} -> $renamedUri")
            return@runCatching null
        }
        rebindRecordingWaveformCache(recording, renamed)
    }.onFailure { Log.w(TAG, "Unable to rename recording ${recording.id}", it) }.getOrNull()
}

private fun createMediaStoreOutputTarget(
    context: Context,
    requestedDisplayName: String,
    mimeType: String,
    startedAtMillis: Long,
    stagingKind: StagingOutputKind,
): RecordingOutputTarget {
    check(usesMediaStoreDefaultStorage()) { "MediaStore output requires Android 10+" }
    val safeDisplayName = sanitizeBaseName(requestedDisplayName)
    val uniqueName = findAvailableDisplayName(safeDisplayName) { candidate ->
        mediaStoreNameExists(context, candidate)
    }
    val stagingName = stagingOutputName(uniqueName, UUID.randomUUID().toString(), kind = stagingKind)
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, stagingName)
        put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
        put(MediaStore.MediaColumns.RELATIVE_PATH, MEDIA_STORE_RELATIVE_PATH)
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }
    val uri = context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
        ?: throw IOException("Unable to create MediaStore recording")
    return RecordingOutputTarget(
        id = uri.toString(),
        displayName = uniqueName,
        mimeType = mimeType,
        storageType = RecordingStorageType.MEDIASTORE,
        directoryId = MEDIA_STORE_DIRECTORY_ID,
        startedAtMillis = startedAtMillis,
        uri = uri,
        staging = true,
    )
}

private fun createDocumentOutputTarget(
    context: Context,
    treeUri: Uri,
    requestedDisplayName: String,
    mimeType: String,
    startedAtMillis: Long,
    stagingKind: StagingOutputKind,
): RecordingOutputTarget {
    val safeDisplayName = sanitizeBaseName(requestedDisplayName)
    val uniqueName = findAvailableDocumentDisplayName(
        context = context,
        treeUri = treeUri,
        requestedDisplayName = safeDisplayName,
    )
    val stagingName = stagingOutputName(uniqueName, UUID.randomUUID().toString(), kind = stagingKind)
    val documentUri = DocumentsContract.createDocument(
        context.contentResolver,
        DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri)),
        mimeType,
        stagingName,
    ) ?: throw IOException("Unable to create output document")

    return RecordingOutputTarget(
        id = documentUri.toString(),
        displayName = uniqueName,
        mimeType = mimeType,
        storageType = RecordingStorageType.DOCUMENT,
        directoryId = treeUri.toString(),
        startedAtMillis = startedAtMillis,
        uri = documentUri,
        staging = true,
    )
}

private fun findAvailableDocumentDisplayName(
    context: Context,
    treeUri: Uri,
    requestedDisplayName: String,
    excludedUri: Uri? = null,
): String {
    val occupied = queryDocumentTreeEntries(context, treeUri)
        .asSequence()
        .filter { entry -> excludedUri == null || entry.uri != excludedUri }
        .mapNotNullTo(HashSet()) { entry -> entry.name?.takeIf { it.isNotBlank() } }
    return findAvailableDisplayName(requestedDisplayName) { candidate -> candidate in occupied }
}

internal fun findAvailableDisplayName(
    requestedDisplayName: String,
    exists: (String) -> Boolean,
): String {
    val dotIndex = requestedDisplayName.lastIndexOf('.')
    val name = if (dotIndex > 0) requestedDisplayName.substring(0, dotIndex) else requestedDisplayName
    val extension = if (dotIndex > 0) requestedDisplayName.substring(dotIndex) else ""
    var candidate = requestedDisplayName
    var suffix = 2
    while (exists(candidate)) {
        candidate = "$name ($suffix)$extension"
        suffix++
    }
    return candidate
}

private fun sanitizeBaseName(name: String): String {
    val sanitized = buildString {
        var prevWasSpace = false
        for (c in name) {
            val isWhitespace = c in ILLEGAL_FILENAME_CHARS || c == ' ' || c == '\t' || c == '\n' || c == '\r'
            if (!isWhitespace) {
                append(c)
                prevWasSpace = false
            } else if (!prevWasSpace) {
                append(' ')
                prevWasSpace = true
            }
        }
    }.trim()
    val nonEmpty = sanitized.ifEmpty { FALLBACK_DISPLAY_NAME }
    return if (nonEmpty.startsWith(STAGING_OUTPUT_PREFIX, ignoreCase = true)) {
        "${FALLBACK_DISPLAY_NAME} $nonEmpty"
    } else {
        nonEmpty
    }
}

private fun guessMimeType(displayName: String): String {
    val ext = displayName.substringAfterLast('.', "").lowercase()
    return ExportFormat.entries.firstOrNull { it.extension == ext }?.outputMimeType
        ?: FALLBACK_MIME_TYPE_AUDIO
}

private fun parseRecordingStartTimeMillis(value: String): Long? {
    val normalized = if (!value.endsWith(")")) value else {
        val openParen = value.lastIndexOf(" (")
        if (openParen < 0) value else {
            val suffix = value.substring(openParen + 2, value.length - 1)
            if (suffix.all { it in '0'..'9' }) value.substring(0, openParen) else value
        }
    }
    return normalized.toLongOrNull()
}

private fun readWavDurationMillis(file: File): Long {
    return runCatching {
        FileInputStream(file).use(::readWavDurationMillis)
    }.onFailure { Log.w(TAG, "readWavDurationMillis(file=$file) failed", it) }.getOrDefault(0L)
}

private fun readWavDurationMillis(input: InputStream): Long {
    return runCatching {
        val riffHeader = ByteArray(12)
        if (!input.readFully(riffHeader)) return@runCatching 0L
        if (!riffHeader.regionMatchesAscii(0, "RIFF") || !riffHeader.regionMatchesAscii(8, "WAVE")) {
            return@runCatching 0L
        }

        var byteRate = 0L
        var dataSize = -1L
        val chunkHeader = ByteArray(8)
        while (input.readFully(chunkHeader)) {
            val chunkSize = littleEndianUnsignedInt(chunkHeader, 4)
            when {
                chunkHeader.regionMatchesAscii(0, "fmt ") -> {
                    if (chunkSize < 16L) return@runCatching 0L
                    val format = ByteArray(16)
                    if (!input.readFully(format)) return@runCatching 0L
                    byteRate = littleEndianUnsignedInt(format, 8)
                    if (!input.skipFully(chunkSize - format.size.toLong())) return@runCatching 0L
                }

                chunkHeader.regionMatchesAscii(0, "data") -> {
                    dataSize = chunkSize
                    if (byteRate > 0L) {
                        return@runCatching dataSize * 1000L / byteRate
                    }
                    if (!input.skipFully(chunkSize)) return@runCatching 0L
                }

                else -> if (!input.skipFully(chunkSize)) return@runCatching 0L
            }
            if ((chunkSize and 1L) != 0L && !input.skipFully(1L)) return@runCatching 0L
            if (byteRate > 0L && dataSize >= 0L) {
                return@runCatching dataSize * 1000L / byteRate
            }
        }
        0L
    }.onFailure { Log.w(TAG, "readWavDurationMillis(input) failed", it) }.getOrDefault(0L)
}

internal fun structurallyCompleteRecordingDurationMillis(
    displayName: String,
    input: InputStream,
): Long = when (displayName.substringAfterLast('.', "").lowercase()) {
    ExportFormat.WAV.extension -> readRecoverableStagingWavDurationMillis(input)
    else -> 0L
}

internal data class RecoverableStagingWav(
    val durationMillis: Long,
    val digest: CopyDigest,
)

internal fun readRecoverableStagingWavDurationMillis(input: InputStream): Long =
    readRecoverableStagingWav(input)?.durationMillis ?: 0L

internal fun readRecoverableStagingWav(input: InputStream): RecoverableStagingWav? {
    val messageDigest = MessageDigest.getInstance("SHA-256")
    val source = DigestInputStream(input, messageDigest)
    val riffHeader = ByteArray(12)
    if (!source.readFully(riffHeader)) return null
    if (!riffHeader.regionMatchesAscii(0, "RIFF") || !riffHeader.regionMatchesAscii(8, "WAVE")) {
        return null
    }
    val expectedTotalBytes = littleEndianUnsignedInt(riffHeader, 4) + 8L
    if (expectedTotalBytes < 44L) return null

    var consumedBytes = 12L
    var byteRate = 0L
    var blockAlign = 0L
    var dataSize = -1L
    var sawFormat = false
    var sawData = false
    val chunkHeader = ByteArray(8)
    val discardBuffer = ByteArray(FILE_COPY_BUFFER_BYTES)
    while (consumedBytes < expectedTotalBytes) {
        if (expectedTotalBytes - consumedBytes < chunkHeader.size) return null
        if (!source.readFully(chunkHeader)) return null
        consumedBytes += chunkHeader.size
        val chunkSize = littleEndianUnsignedInt(chunkHeader, 4)
        val paddedChunkSize = chunkSize + (chunkSize and 1L)
        if (paddedChunkSize > expectedTotalBytes - consumedBytes) return null

        if (chunkHeader.regionMatchesAscii(0, "fmt ")) {
            if (sawFormat || chunkSize < 16L) return null
            val format = ByteArray(16)
            if (!source.readFully(format)) return null
            consumedBytes += format.size
            val formatTag = littleEndianUnsignedShort(format, 0)
            val channelCount = littleEndianUnsignedShort(format, 2)
            val sampleRate = littleEndianUnsignedInt(format, 4)
            val declaredByteRate = littleEndianUnsignedInt(format, 8)
            val declaredBlockAlign = littleEndianUnsignedShort(format, 12).toLong()
            val bitsPerSample = littleEndianUnsignedShort(format, 14)
            val sampleBytes = when {
                formatTag == 1 && bitsPerSample == 8 -> 1L
                formatTag == 1 && bitsPerSample == 16 -> 2L
                formatTag == 3 && bitsPerSample == 32 -> 4L
                else -> return null
            }
            if (channelCount !in 1..2 || sampleRate !in 1L..Int.MAX_VALUE.toLong()) return null
            val expectedBlockAlign = channelCount.toLong() * sampleBytes
            val expectedByteRate = sampleRate * expectedBlockAlign
            if (declaredBlockAlign != expectedBlockAlign || declaredByteRate != expectedByteRate) {
                return null
            }
            byteRate = declaredByteRate
            blockAlign = declaredBlockAlign
            sawFormat = true
            val remainder = chunkSize - format.size.toLong()
            if (!source.discardFully(remainder, discardBuffer)) return null
            consumedBytes += remainder
        } else {
            if (chunkHeader.regionMatchesAscii(0, "data")) {
                if (sawData) return null
                dataSize = chunkSize
                sawData = true
            }
            if (!source.discardFully(chunkSize, discardBuffer)) return null
            consumedBytes += chunkSize
        }
        if ((chunkSize and 1L) != 0L) {
            if (source.read() < 0) return null
            consumedBytes++
        }
    }
    if (consumedBytes != expectedTotalBytes || source.read() >= 0) return null
    if (!sawFormat || !sawData || byteRate <= 0L || blockAlign <= 0L || dataSize <= 0L) return null
    if (dataSize % blockAlign != 0L) return null
    val durationMillis = (dataSize * 1000L / byteRate).takeIf { it > 0L } ?: return null
    return RecoverableStagingWav(
        durationMillis = durationMillis,
        digest = CopyDigest(expectedTotalBytes, messageDigest.digest()),
    )
}

private fun InputStream.discardFully(byteCount: Long, buffer: ByteArray): Boolean {
    var remaining = byteCount
    while (remaining > 0L) {
        val requested = minOf(buffer.size.toLong(), remaining).toInt()
        val read = read(buffer, 0, requested)
        if (read < 0) return false
        if (read == 0) {
            if (read() < 0) return false
            remaining--
        } else {
            remaining -= read.toLong()
        }
    }
    return true
}

private fun countOutputTargetBytes(
    context: Context,
    target: RecordingOutputTarget,
    expectedBytes: Long,
): Long {
    val input = when (target.storageType) {
        RecordingStorageType.FILE -> FileInputStream(requireNotNull(target.file))
        RecordingStorageType.DOCUMENT,
        RecordingStorageType.MEDIASTORE,
        -> context.contentResolver.openInputStream(requireNotNull(target.uri))
    } ?: throw IOException("Unable to reopen copied recording")
    input.use { source ->
        val buffer = ByteArray(FILE_COPY_BUFFER_BYTES)
        var total = 0L
        val readLimit = if (expectedBytes == Long.MAX_VALUE) Long.MAX_VALUE else expectedBytes + 1L
        while (total < readLimit) {
            val maxRead = minOf(buffer.size.toLong(), readLimit - total).toInt()
            if (maxRead <= 0) break
            val read = source.read(buffer, 0, maxRead)
            if (read < 0) break
            if (read == 0) {
                if (source.read() < 0) break
                total++
            } else {
                total += read.toLong()
            }
        }
        return total
    }
}

private fun littleEndianInt(
    data: ByteArray,
    offset: Int,
): Int {
    return (data[offset].toInt() and 0xFF) or
        ((data[offset + 1].toInt() and 0xFF) shl 8) or
        ((data[offset + 2].toInt() and 0xFF) shl 16) or
        ((data[offset + 3].toInt() and 0xFF) shl 24)
}

private fun littleEndianUnsignedInt(data: ByteArray, offset: Int): Long {
    return littleEndianInt(data, offset).toLong() and 0xFFFF_FFFFL
}

private fun littleEndianUnsignedShort(data: ByteArray, offset: Int): Int =
    (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)

private fun ByteArray.regionMatchesAscii(offset: Int, expected: String): Boolean {
    if (offset < 0 || expected.length > size - offset) return false
    for (index in expected.indices) {
        if ((this[offset + index].toInt() and 0xFF) != expected[index].code) return false
    }
    return true
}

private fun InputStream.readFully(buffer: ByteArray): Boolean {
    var offset = 0
    while (offset < buffer.size) {
        val read = read(buffer, offset, buffer.size - offset)
        if (read < 0) return false
        if (read == 0) {
            val value = read()
            if (value < 0) return false
            buffer[offset] = value.toByte()
            offset++
        } else {
            offset += read
        }
    }
    return true
}

private fun InputStream.skipFully(byteCount: Long): Boolean {
    var remaining = byteCount
    while (remaining > 0L) {
        val skipped = skip(remaining)
        if (skipped > 0L) {
            remaining -= skipped
        } else {
            if (read() < 0) return false
            remaining--
        }
    }
    return true
}
