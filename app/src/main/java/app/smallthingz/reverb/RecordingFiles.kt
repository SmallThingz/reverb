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
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
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
import java.net.URI
import java.net.URLDecoder
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
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

internal fun mediaStoreRelativePathIsManaged(
    value: String?,
    managedRelativePath: String = MEDIA_STORE_RELATIVE_PATH,
): Boolean = value == managedRelativePath


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

internal fun recordingStorageIdIsValid(
    storageType: RecordingStorageType,
    id: String,
): Boolean {
    if (id.isBlank()) return false
    return when (storageType) {
        RecordingStorageType.FILE -> File(id).let { file -> file.isAbsolute && file.parentFile != null }
        RecordingStorageType.DOCUMENT -> documentRecordingIdIsValid(id)
        RecordingStorageType.MEDIASTORE -> mediaStoreRecordingIdIsValid(id)
    }
}

private data class DocumentStorageScope(
    val authority: String,
    val treeId: String,
    val documentId: String?,
)

private const val ANDROID_URI_UNRESERVED = "_-!.~'()*"
private val HEX_DIGITS = "0123456789ABCDEF"

private fun androidUriEncodeComponent(value: String): String = buildString {
    var current = 0
    while (current < value.length) {
        val allowedStart = current
        while (current < value.length) {
            val char = value[current]
            val allowed = char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' ||
                char in ANDROID_URI_UNRESERVED
            if (!allowed) break
            current++
        }
        if (current > allowedStart) append(value, allowedStart, current)
        if (current == value.length) break

        val encodedStart = current
        while (current < value.length) {
            val char = value[current]
            val allowed = char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' ||
                char in ANDROID_URI_UNRESERVED
            if (allowed) break
            current++
        }
        value.substring(encodedStart, current).toByteArray(StandardCharsets.UTF_8).forEach { byte ->
            val unsigned = byte.toInt() and 0xff
            append('%')
            append(HEX_DIGITS[unsigned ushr 4])
            append(HEX_DIGITS[unsigned and 0x0f])
        }
    }
}

private fun documentStorageSegmentIsCanonical(raw: String): Boolean {
    if (raw.isBlank()) return false
    return runCatching {
        val decoded = URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8")
        raw == androidUriEncodeComponent(decoded)
    }.getOrDefault(false)
}

private fun parseDocumentStorageScope(id: String): DocumentStorageScope? = runCatching {
    val uri = URI(id)
    if (uri.scheme != "content" || uri.authority.isNullOrBlank() ||
        uri.rawAuthority != uri.authority || uri.rawQuery != null || uri.rawFragment != null
    ) {
        return@runCatching null
    }
    val segments = uri.rawPath.orEmpty().split('/')
    when {
        segments.size == 3 && segments[0].isEmpty() &&
            segments[1] == "tree" && documentStorageSegmentIsCanonical(segments[2]) ->
            DocumentStorageScope(requireNotNull(uri.authority), segments[2], null)
        segments.size == 5 && segments[0].isEmpty() &&
            segments[1] == "tree" && documentStorageSegmentIsCanonical(segments[2]) &&
            segments[3] == "document" && documentStorageSegmentIsCanonical(segments[4]) ->
            DocumentStorageScope(requireNotNull(uri.authority), segments[2], segments[4])
        else -> null
    }
}.getOrNull()

internal fun documentTreeIdIsValid(id: String): Boolean {
    val tree = parseDocumentStorageScope(id) ?: return false
    return tree.documentId == null
}

internal fun documentRecordingIdIsValid(id: String): Boolean =
    parseDocumentStorageScope(id)?.documentId != null

internal fun documentRecordingBelongsToTree(
    recordingId: String,
    treeId: String,
): Boolean {
    val recording = parseDocumentStorageScope(recordingId) ?: return false
    val tree = parseDocumentStorageScope(treeId) ?: return false
    return recording.documentId != null && tree.documentId == null &&
        recording.authority == tree.authority && recording.treeId == tree.treeId
}

internal fun mediaStoreRecordingIdIsValid(id: String): Boolean = runCatching {
    val uri = URI(id)
    val segments = uri.rawPath.orEmpty().split('/')
    val rowIdText = segments.getOrNull(4).orEmpty()
    val rowId = rowIdText.toLongOrNull()
    uri.scheme == "content" &&
        uri.authority == MediaStore.AUTHORITY &&
        uri.rawAuthority == uri.authority &&
        uri.rawQuery == null &&
        uri.rawFragment == null &&
        uri.rawPath == uri.path &&
        segments.size == 5 &&
        segments[0].isEmpty() &&
        segments[1].isNotBlank() &&
        segments[2] == "audio" &&
        segments[3] == "media" &&
        rowId != null &&
        rowId.toString() == rowIdText
}.getOrDefault(false)

internal fun managedRecordingFileDirectoryIds(context: Context): Set<String> = setOf(
    getSavedRecordingsDirectory(context.applicationContext).absolutePath,
    getSharedMusicRecordingsDirectory().absolutePath,
)

internal fun recordingFileStorageIdIsManaged(
    id: String,
    managedDirectoryIds: Set<String>,
): Boolean {
    if (!recordingStorageIdIsValid(RecordingStorageType.FILE, id)) return false
    val parent = File(id).parentFile?.absolutePath ?: return false
    return parent in managedDirectoryIds
}

internal fun recordingFileStorageIdIsManaged(context: Context, id: String): Boolean =
    recordingFileStorageIdIsManaged(id, managedRecordingFileDirectoryIds(context))

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
    val staging: Boolean,
    val stagingDisplayName: String = "",
    val stagingIdentity: String = "",
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

internal fun configuredExportTreePreferenceIsUsable(raw: String?): Boolean =
    raw == null || documentTreeIdIsValid(raw)

private fun configuredExportTreePreferenceRaw(context: Context): String? {
    val preferences = getRecorderPreferences(context)
    return requireDurablePreference(
        present = preferences.contains(PrefKey.EXPORT_DIRECTORY_URI),
        absent = null,
        label = PrefKey.EXPORT_DIRECTORY_URI.name,
    ) {
        preferences.getString(PrefKey.EXPORT_DIRECTORY_URI, null)
    }
}

fun getConfiguredExportTreeUri(context: Context): Uri? {
    val raw = configuredExportTreePreferenceRaw(context) ?: return null
    if (!documentTreeIdIsValid(raw)) {
        throw IllegalStateException("Unreadable durable export tree authority")
    }
    return raw.toUri()
}

internal fun getConfiguredExportTreeUriForSettings(context: Context): Uri? {
    val raw = getRecorderPreferences(context).safeString(PrefKey.EXPORT_DIRECTORY_URI) ?: return null
    return raw.takeIf(::documentTreeIdIsValid)?.toUri()
}

internal inline fun commitConfiguredExportTreeUriChange(
    updatedValue: String?,
    write: (String?) -> Boolean,
    restoreInMemory: () -> Unit,
): Boolean = commitDurablePreferenceOrRestoreInMemory(
    commit = { write(updatedValue) },
    restoreInMemory = restoreInMemory,
)

fun setConfiguredExportTreeUri(
    context: Context,
    treeUri: Uri?,
): Boolean {
    val updatedValue = treeUri?.toString()
    if (updatedValue != null && !documentTreeIdIsValid(updatedValue)) return false
    val preferences = getRecorderPreferences(context)
    val previousValue = preferences.snapshotDurablePreferenceValue(PrefKey.EXPORT_DIRECTORY_URI)
    return commitConfiguredExportTreeUriChange(
        updatedValue = updatedValue,
        write = { value ->
            val editor = preferences.edit()
            if (value != null) editor.putString(PrefKey.EXPORT_DIRECTORY_URI, value)
            else editor.remove(PrefKey.EXPORT_DIRECTORY_URI)
            editor.commit()
        },
        restoreInMemory = {
            preferences.edit()
                .restoreDurablePreferenceValue(PrefKey.EXPORT_DIRECTORY_URI, previousValue)
                .commit()
        },
    )
}

fun getConfiguredOutputDirectoryId(context: Context): String {
    return getOutputDirectoryId(context, getConfiguredExportTreeUri(context))
}

fun getOutputDirectoryId(
    context: Context,
    treeUri: Uri?,
): String {
    if (treeUri != null) {
        val id = treeUri.toString()
        require(documentTreeIdIsValid(id)) { "Output directory is not a canonical document tree" }
        return id
    }
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

private fun inspectRecordingMedia(
    descriptor: FileDescriptor,
    displayName: String,
    fallbackDurationMillis: Long,
): RecordingMediaMetadata {
    val retriever = MediaMetadataRetriever()
    val metadata = try {
        withOwnedResource(
            owner = retriever,
            release = { it.release() },
        ) { configured ->
            configured.setDataSource(descriptor)
            RetrievedMediaMetadata(
                durationMillis = configured.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull(),
                bitrate = configured.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull(),
                sampleRate = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    configured.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)?.toIntOrNull()
                } else null,
            )
        }
    } catch (error: Exception) {
        Log.w(TAG, "Unable to inspect recording metadata for $displayName", error)
        null
    }

    val duration = metadata?.durationMillis?.takeIf { it > 0L }
        ?: fallbackDurationMillis.takeIf { it > 0L }
        ?: 0L
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
    if (targetTreeUri != null && !documentTreeIdIsValid(targetTreeUri.toString())) {
        throw IOException("Output destination is not a canonical document tree")
    }
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
    requireWritableStagingTargetStillEmpty(context, target)
    val descriptor = when (target.storageType) {
        RecordingStorageType.FILE -> ParcelFileDescriptor.open(
            requireNotNull(target.file),
            // The target was atomically created as an empty staging file. Never use CREATE or
            // TRUNCATE here: if the path was replaced after creation, opening it must not mutate
            // replacement bytes before identity/size verification below.
            ParcelFileDescriptor.MODE_READ_WRITE,
        )
        RecordingStorageType.DOCUMENT,
        RecordingStorageType.MEDIASTORE,
        -> context.contentResolver.openFileDescriptor(requireNotNull(target.uri), "rw")
            ?: throw IOException("Unable to open output document: ${target.id}")
    }
    try {
        if (descriptor.statSize != 0L) {
            throw IOException("Output staging descriptor is no longer empty: ${target.id}")
        }
        requireWritableStagingTargetStillEmpty(context, target)
        if (target.storageType == RecordingStorageType.FILE &&
            !stagingFileDescriptorMatchesCreation(
                expectedIdentity = target.stagingIdentity,
                currentPathIdentity = resolveFileIdentity(requireNotNull(target.file)),
                descriptorIdentity = resolveFileDescriptorIdentity(descriptor.fileDescriptor),
                descriptorSize = descriptor.statSize,
            )
        ) {
            throw IOException("Output staging file changed before write: ${target.id}")
        }
        return descriptor
    } catch (error: Throwable) {
        throw requireNotNull(closePreservingPrimaryFailure(error) { descriptor.close() })
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
    require(target.staging) { "Output finalization requires a staging target" }
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

internal data class MediaStorePublicationObservation(
    val displayName: String,
    val pending: Boolean,
    val fingerprint: StableOutputFingerprint,
)

internal fun mediaStorePublicationMatchesExpected(
    finalDisplayName: String,
    expectedFingerprint: StableOutputFingerprint,
    observation: MediaStorePublicationObservation?,
): Boolean {
    val current = observation ?: return false
    return !current.pending &&
        current.displayName == finalDisplayName &&
        verifiedProviderPublicationMatches(expectedFingerprint, current.fingerprint)
}

private data class MediaStorePublicationMetadata(
    val displayName: String,
    val pending: Boolean,
)

private fun queryMediaStorePublicationMetadata(
    context: Context,
    uri: Uri,
): MediaStorePublicationMetadata? = runCatching {
    context.contentResolver.query(
        uri,
        arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.IS_PENDING),
        null,
        null,
        null,
    )?.use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        val name = cursor.getString(0)?.takeIf { it.isNotBlank() } ?: return@use null
        MediaStorePublicationMetadata(name, cursor.getInt(1) != 0)
    }
}.getOrNull()

private fun readStableMediaStorePublicationObservation(
    context: Context,
    uri: Uri,
): MediaStorePublicationObservation? {
    val before = queryMediaStorePublicationMetadata(context, uri) ?: return null
    val fingerprint = readStableOutputFingerprint(
        context,
        RecordingStorageType.MEDIASTORE,
        uri.toString(),
    ) ?: return null
    val after = queryMediaStorePublicationMetadata(context, uri) ?: return null
    if (before != after) return null
    return MediaStorePublicationObservation(
        displayName = after.displayName,
        pending = after.pending,
        fingerprint = fingerprint,
    )
}

@Throws(IOException::class)
private fun finalizeMediaStoreOutputTarget(
    context: Context,
    target: RecordingOutputTarget,
    expectedFingerprint: StableOutputFingerprint,
): RecordingOutputTarget {
    val uri = requireNotNull(target.uri)
    requireCurrentOutputFingerprint(context, target, expectedFingerprint)
    val finalName = findAvailableDisplayName(target.displayName) { candidate ->
        mediaStoreNameExists(context, candidate)
    }
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, finalName)
        put(MediaStore.MediaColumns.IS_PENDING, 0)
    }
    val updateFailure = try {
        if (context.contentResolver.update(uri, values, null, null) > 0) null
        else IOException("Unable to publish MediaStore recording: $uri")
    } catch (error: Exception) {
        IOException("MediaStore publication result is uncertain: $uri", error)
    }
    var recoveredPublication: MediaStorePublicationObservation? = null
    if (updateFailure != null) {
        val observation = readStableMediaStorePublicationObservation(context, uri)
        if (!mediaStorePublicationMatchesExpected(finalName, expectedFingerprint, observation)) {
            // Binder/provider calls can fail after committing an update. If the row is already
            // visible but cannot be proven to be the exact verified staging object under the
            // requested final name, keep that URI out of Reverb's Library without granting
            // deletion authority. A still-pending row remains hidden and keeps its recovery marker.
            if (observation?.pending == false) {
                val suppressed = suppressProviderOutputWithoutDeletion(
                    context = context,
                    storageType = RecordingStorageType.MEDIASTORE,
                    id = uri.toString(),
                    digest = expectedFingerprint.digest,
                )
                if (suppressed) {
                    if (!removeVerifiedExportStaging(context, target.storageType, target.id, expectedFingerprint)) {
                        Log.w(TAG, "Unable to revoke recovery for ambiguous MediaStore publish ${target.id}")
                    }
                } else {
                    Log.w(TAG, "Unable to durably suppress ambiguous MediaStore publish $uri")
                }
            }
            throw updateFailure
        }
        recoveredPublication = observation
    }
    val published = recoveredPublication?.fingerprint
        ?: readStableOutputFingerprint(context, RecordingStorageType.MEDIASTORE, uri.toString())
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
            if (!removeVerifiedExportStaging(context, target.storageType, target.id, expectedFingerprint)) {
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
    val actualName = recoveredPublication?.displayName
        ?: queryContentDisplayName(context, uri)?.takeIf { it.isNotBlank() }
        ?: finalName
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
    moveFile: (File, File) -> Unit = ::moveFileWithoutOverwrite,
    readFingerprint: (File) -> StableOutputFingerprint? = ::readStableFileOutputFingerprint,
): File = publishStagedFileResult(
    source = source,
    finalDisplayName = finalDisplayName,
    expectedFingerprint = expectedFingerprint,
    onUnexpectedPublishedFile = onUnexpectedPublishedFile,
    moveFile = moveFile,
    readFingerprint = readFingerprint,
).file

private fun moveFileWithoutOverwrite(source: File, destination: File) {
    Files.move(source.toPath(), destination.toPath())
}

@Throws(IOException::class)
private fun publishStagedFileResult(
    source: File,
    finalDisplayName: String,
    expectedFingerprint: StableOutputFingerprint? = null,
    onUnexpectedPublishedFile: ((File, CopyDigest) -> Boolean)? = null,
    moveFile: (File, File) -> Unit = ::moveFileWithoutOverwrite,
    readFingerprint: (File) -> StableOutputFingerprint? = ::readStableFileOutputFingerprint,
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
            moveFile(source, destination)
            val publishedIdentity = if (expectedFingerprint != null) {
                val published = readFingerprint(destination)
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
        } catch (error: IOException) {
            if (expectedFingerprint != null) {
                protectAmbiguousPublishedFile(
                    source = source,
                    destination = destination,
                    finalDisplayName = finalDisplayName,
                    expectedFingerprint = expectedFingerprint,
                    onUnexpectedPublishedFile = onUnexpectedPublishedFile,
                    readFingerprint = readFingerprint,
                )
            }
            throw error
        }
    }
}

private fun protectAmbiguousPublishedFile(
    source: File,
    destination: File,
    finalDisplayName: String,
    expectedFingerprint: StableOutputFingerprint,
    onUnexpectedPublishedFile: ((File, CopyDigest) -> Boolean)?,
    readFingerprint: (File) -> StableOutputFingerprint?,
) {
    val expectedIdentity = expectedFingerprint.fileKey?.takeIf { it.isNotBlank() } ?: return
    val published = readFingerprint(destination) ?: return
    val publishedIdentity = published.fileKey?.takeIf { it.isNotBlank() } ?: return
    if (!sameFileObjectAcrossRename(expectedIdentity, publishedIdentity)) return

    // Files.move() may become visible before surfacing an I/O failure. Once the exact staging
    // object is proven at the final path, suppress it before rollback so a failed directory
    // durability barrier cannot expose a failed publication as a normal Library recording.
    val suppressed = onUnexpectedPublishedFile != null &&
        runCatching { onUnexpectedPublishedFile(destination, published.digest) }.getOrDefault(false)
    val hiddenStateDurable = preserveUnexpectedPublishedFile(
        source = source,
        moved = destination,
        finalDisplayName = finalDisplayName,
    )
    if (!suppressed && !hiddenStateDurable) {
        Log.e(TAG, "Ambiguous published file could not be durably suppressed or hidden: $destination")
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

internal data class DocumentPublicationObservation(
    val displayName: String,
    val sourceUriUnchanged: Boolean,
    val oldSourceState: RecordingAssetState,
    val fingerprint: StableOutputFingerprint,
)

internal fun documentPublicationMatchesExpected(
    finalDisplayName: String,
    expectedFingerprint: StableOutputFingerprint,
    observation: DocumentPublicationObservation?,
): Boolean {
    val current = observation ?: return false
    if (current.displayName != finalDisplayName) return false
    return documentRenameTransitionIsSafe(
        sourceUriUnchanged = current.sourceUriUnchanged,
        oldUriStateAfterRename = current.oldSourceState,
        beforeIdentity = expectedFingerprint.providerIdentity.orEmpty(),
        afterIdentity = current.fingerprint.providerIdentity.orEmpty(),
        beforeDigest = expectedFingerprint.digest,
        afterDigest = current.fingerprint.digest,
    )
}

private data class ObservedDocumentPublication(
    val uri: Uri,
    val state: DocumentPublicationObservation,
)

private fun readStableDocumentPublicationObservation(
    context: Context,
    treeUri: Uri,
    sourceUri: Uri,
    finalDisplayName: String,
): ObservedDocumentPublication? {
    fun candidate(): DocumentTreeEntry? = queryDocumentTreeEntries(context, treeUri)
        .filter { entry -> entry.isFile && entry.name == finalDisplayName }
        .singleOrNull()

    val before = candidate() ?: return null
    val fingerprint = readStableOutputFingerprint(
        context,
        RecordingStorageType.DOCUMENT,
        before.uri.toString(),
    ) ?: return null
    val after = candidate() ?: return null
    if (before.uri != after.uri) return null
    val sourceUriUnchanged = after.uri == sourceUri
    val oldSourceState = if (sourceUriUnchanged) {
        RecordingAssetState.PRESENT
    } else {
        queryUriAssetState(
            context = context,
            uri = sourceUri,
            projection = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
            logId = sourceUri.toString(),
        )
    }
    return ObservedDocumentPublication(
        uri = after.uri,
        state = DocumentPublicationObservation(
            displayName = finalDisplayName,
            sourceUriUnchanged = sourceUriUnchanged,
            oldSourceState = oldSourceState,
            fingerprint = fingerprint,
        ),
    )
}

private fun suppressUnsafeDocumentPublication(
    context: Context,
    target: RecordingOutputTarget,
    sourceUri: Uri,
    publishedUri: Uri,
    expectedFingerprint: StableOutputFingerprint,
    publishedDigest: CopyDigest,
): Boolean {
    val sourceUriUnchanged = publishedUri == sourceUri
    val returnedSuppressed = suppressProviderOutputWithoutDeletion(
        context = context,
        storageType = RecordingStorageType.DOCUMENT,
        id = publishedUri.toString(),
        digest = publishedDigest,
    )
    val sourceSuppressed = sourceUriUnchanged || suppressProviderOutputWithoutDeletion(
        context = context,
        storageType = RecordingStorageType.DOCUMENT,
        id = sourceUri.toString(),
        digest = expectedFingerprint.digest,
    )
    if (!returnedSuppressed || !sourceSuppressed) {
        Log.w(TAG, "Unable to durably suppress unsafe document publish $sourceUri -> $publishedUri")
    }
    val recoveryRevoked = runCatching {
        removeVerifiedExportStaging(context, target.storageType, target.id, expectedFingerprint)
    }.getOrDefault(false)
    if (!recoveryRevoked) {
        Log.w(TAG, "Unable to revoke recovery for unsafe document publish ${target.id}")
    }
    return returnedSuppressed && sourceSuppressed && recoveryRevoked
}

@Throws(IOException::class)
private fun finalizeDocumentOutputTarget(
    context: Context,
    target: RecordingOutputTarget,
    expectedFingerprint: StableOutputFingerprint,
): RecordingOutputTarget {
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
    var renameFailure: IOException? = null
    val directRenamedUri = try {
        DocumentsContract.renameDocument(context.contentResolver, sourceUri, finalName)
            ?: run {
                renameFailure = IOException("Output provider failed to atomically publish recording")
                null
            }
    } catch (error: Exception) {
        renameFailure = IOException("Document publication result is uncertain", error)
        null
    }
    var recoveredPublication: ObservedDocumentPublication? = null
    val treeScopedDirectRenamedUri = directRenamedUri?.takeIf { returned ->
        documentRecordingBelongsToTree(returned.toString(), target.directoryId)
    }
    if (directRenamedUri != null && treeScopedDirectRenamedUri == null) {
        renameFailure = IOException("Document publication returned URI outside configured tree: $directRenamedUri")
    }
    val renamedUri = treeScopedDirectRenamedUri ?: run {
        val failure = requireNotNull(renameFailure)
        val observed = runCatching {
            readStableDocumentPublicationObservation(context, treeUri, sourceUri, finalName)
        }.onFailure { error ->
            Log.w(TAG, "Unable to resolve ambiguous document publication $sourceUri", error)
        }.getOrNull()
        if (!documentPublicationMatchesExpected(finalName, expectedFingerprint, observed?.state)) {
            // Only suppress a discovered visible candidate when it still contains the exact
            // verified bytes. A same-name different recording must remain untouched.
            if (observed != null &&
                copyDigestMatches(expectedFingerprint.digest, observed.state.fingerprint.digest)
            ) {
                suppressUnsafeDocumentPublication(
                    context = context,
                    target = target,
                    sourceUri = sourceUri,
                    publishedUri = observed.uri,
                    expectedFingerprint = expectedFingerprint,
                    publishedDigest = observed.state.fingerprint.digest,
                )
            }
            throw failure
        }
        recoveredPublication = observed
        requireNotNull(observed).uri
    }
    val published = recoveredPublication?.state?.fingerprint
        ?: readStableOutputFingerprint(context, RecordingStorageType.DOCUMENT, renamedUri.toString())
    if (published == null) {
        // renameDocument() already returned a publication URI. If later provider reads lose
        // identity/content proof, keep that visible candidate out of Library without deriving
        // deletion authority from the failed observation.
        suppressUnsafeDocumentPublication(
            context = context,
            target = target,
            sourceUri = sourceUri,
            publishedUri = renamedUri,
            expectedFingerprint = expectedFingerprint,
            publishedDigest = expectedFingerprint.digest,
        )
        throw IOException("Unable to verify published document recording")
    }
    val sourceUriUnchanged = recoveredPublication?.state?.sourceUriUnchanged ?: (renamedUri == sourceUri)
    val oldState = recoveredPublication?.state?.oldSourceState ?: if (sourceUriUnchanged) {
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
        suppressUnsafeDocumentPublication(
            context = context,
            target = target,
            sourceUri = sourceUri,
            publishedUri = renamedUri,
            expectedFingerprint = expectedFingerprint,
            publishedDigest = published.digest,
        )
        throw IOException("Published document no longer matches verified staging rename")
    }
    val publishedIdentity = published.providerIdentity
        ?.takeIf { it.isNotBlank() }
        ?: throw IOException("Published document recording has no stable identity")
    val actualName = recoveredPublication?.state?.displayName
        ?: DocumentFile.fromSingleUri(context, renamedUri)?.name?.takeIf { it.isNotBlank() }
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

internal fun documentProviderIdentityFromMetadata(
    id: String,
    sizeKnown: Boolean,
    sizeBytes: Long,
    modifiedKnown: Boolean,
    modifiedMillis: Long,
): String {
    if (!sizeKnown || !modifiedKnown) return ""
    return providerRecordingIdentity(
        storageType = RecordingStorageType.DOCUMENT,
        id = id,
        sizeBytes = sizeBytes,
        revisionToken = modifiedMillis,
    )
}

internal fun mediaStoreProviderIdentityFromMetadata(
    id: String,
    sizeKnown: Boolean,
    sizeBytes: Long,
    modifiedKnown: Boolean,
    modifiedSeconds: Long,
    generationKnown: Boolean,
    generationModified: Long,
): String {
    if (!sizeKnown) return ""
    val revision = generationModified.takeIf { generationKnown && it > 0L }
        ?: modifiedSeconds.takeIf { modifiedKnown && it > 0L }?.let { seconds ->
            if (seconds > Long.MAX_VALUE / 1000L) Long.MAX_VALUE else seconds * 1000L
        }
        ?: return ""
    return providerRecordingIdentity(
        storageType = RecordingStorageType.MEDIASTORE,
        id = id,
        sizeBytes = sizeBytes,
        revisionToken = revision,
    )
}

internal data class ProviderCatalogObservation(
    val displayName: String,
    val identity: String,
)

internal fun resolveProviderCatalogObservation(
    context: Context,
    storageType: RecordingStorageType,
    uri: Uri,
): ProviderCatalogObservation? = when (storageType) {
    RecordingStorageType.FILE -> null
    RecordingStorageType.DOCUMENT -> runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst() || cursor.isNull(0)) return@use null
            ProviderCatalogObservation(
                displayName = cursor.getString(0),
                identity = documentProviderIdentityFromMetadata(
                    id = uri.toString(),
                    sizeKnown = !cursor.isNull(1),
                    sizeBytes = if (cursor.isNull(1)) 0L else cursor.getLong(1).coerceAtLeast(0L),
                    modifiedKnown = !cursor.isNull(2),
                    modifiedMillis = if (cursor.isNull(2)) 0L else cursor.getLong(2).coerceAtLeast(0L),
                ),
            )
        }
    }.getOrNull()
    RecordingStorageType.MEDIASTORE -> runCatching {
        val useGeneration = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        val projection = if (useGeneration) {
            arrayOf(
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_MODIFIED,
                MediaStore.MediaColumns.GENERATION_MODIFIED,
                MediaStore.MediaColumns.RELATIVE_PATH,
            )
        } else {
            arrayOf(
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_MODIFIED,
                MediaStore.MediaColumns.RELATIVE_PATH,
            )
        }
        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst() || cursor.isNull(0)) return@use null
            val relativePathIndex = if (useGeneration) 4 else 3
            if (cursor.isNull(relativePathIndex) ||
                !mediaStoreRelativePathIsManaged(cursor.getString(relativePathIndex))
            ) {
                return@use null
            }
            ProviderCatalogObservation(
                displayName = cursor.getString(0),
                identity = mediaStoreProviderIdentityFromMetadata(
                    id = uri.toString(),
                    sizeKnown = !cursor.isNull(1),
                    sizeBytes = if (cursor.isNull(1)) 0L else cursor.getLong(1).coerceAtLeast(0L),
                    modifiedKnown = !cursor.isNull(2),
                    modifiedSeconds = if (cursor.isNull(2)) 0L else cursor.getLong(2).coerceAtLeast(0L),
                    generationKnown = useGeneration && !cursor.isNull(3),
                    generationModified = if (useGeneration && !cursor.isNull(3)) {
                        cursor.getLong(3).coerceAtLeast(0L)
                    } else {
                        0L
                    },
                ),
            )
        }
    }.getOrNull()
}

internal fun resolveProviderRecordingIdentity(
    context: Context,
    storageType: RecordingStorageType,
    uri: Uri,
): String = when (storageType) {
    RecordingStorageType.FILE -> ""
    RecordingStorageType.DOCUMENT -> runCatching {
        // Length and revision must come from one provider row observation. Separate DocumentFile
        // calls can straddle a replacement and synthesize an identity tuple that never belonged
        // to one object, which is unsafe when this identity later authorizes rename/delete/copy.
        context.contentResolver.query(
            uri,
            arrayOf(
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use ""
            documentProviderIdentityFromMetadata(
                id = uri.toString(),
                sizeKnown = !cursor.isNull(0),
                sizeBytes = if (cursor.isNull(0)) 0L else cursor.getLong(0).coerceAtLeast(0L),
                modifiedKnown = !cursor.isNull(1),
                modifiedMillis = if (cursor.isNull(1)) 0L else cursor.getLong(1).coerceAtLeast(0L),
            )
        } ?: ""
    }.getOrDefault("")
    RecordingStorageType.MEDIASTORE -> runCatching {
        val useGeneration = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        val projection = if (useGeneration) {
            arrayOf(
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_MODIFIED,
                MediaStore.MediaColumns.GENERATION_MODIFIED,
                MediaStore.MediaColumns.RELATIVE_PATH,
            )
        } else {
            arrayOf(
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_MODIFIED,
                MediaStore.MediaColumns.RELATIVE_PATH,
            )
        }
        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use ""
            val relativePathIndex = if (useGeneration) 3 else 2
            if (cursor.isNull(relativePathIndex) ||
                !mediaStoreRelativePathIsManaged(cursor.getString(relativePathIndex))
            ) {
                return@use ""
            }
            mediaStoreProviderIdentityFromMetadata(
                id = uri.toString(),
                sizeKnown = !cursor.isNull(0),
                sizeBytes = if (cursor.isNull(0)) 0L else cursor.getLong(0).coerceAtLeast(0L),
                modifiedKnown = !cursor.isNull(1),
                modifiedSeconds = if (cursor.isNull(1)) 0L else cursor.getLong(1).coerceAtLeast(0L),
                generationKnown = useGeneration && !cursor.isNull(2),
                generationModified = if (useGeneration && !cursor.isNull(2)) {
                    cursor.getLong(2).coerceAtLeast(0L)
                } else {
                    0L
                },
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

private fun providerRecordingIdentityStorageId(identity: ProviderRecordingIdentity): String? =
    runCatching {
        String(Base64.getUrlDecoder().decode(identity.encodedId), Charsets.UTF_8)
    }.getOrNull()?.takeIf { it.isNotBlank() }

internal fun documentProviderIdentitiesShareTree(before: String, after: String): Boolean {
    val beforeIdentity = parseProviderRecordingIdentity(before) ?: return false
    val afterIdentity = parseProviderRecordingIdentity(after) ?: return false
    if (beforeIdentity.storageType != RecordingStorageType.DOCUMENT ||
        afterIdentity.storageType != RecordingStorageType.DOCUMENT
    ) {
        return false
    }
    val beforeId = providerRecordingIdentityStorageId(beforeIdentity) ?: return false
    val afterId = providerRecordingIdentityStorageId(afterIdentity) ?: return false
    val beforeScope = parseDocumentStorageScope(beforeId) ?: return false
    val afterScope = parseDocumentStorageScope(afterId) ?: return false
    return beforeScope.documentId != null && afterScope.documentId != null &&
        beforeScope.authority == afterScope.authority && beforeScope.treeId == afterScope.treeId
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

internal fun providerDeletionCompleted(observedState: RecordingAssetState): Boolean =
    observedState == RecordingAssetState.MISSING

internal fun resolveFileIdentity(file: File): String {
    fun readRegularAttributes(): BasicFileAttributes? = runCatching {
        Files.readAttributes(
            file.toPath(),
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
    }.getOrNull()?.takeIf(BasicFileAttributes::isRegularFile)

    val attributes = readRegularAttributes() ?: return ""
    val birthNanos = attributes.creationTime().let { time ->
        runCatching { time.to(TimeUnit.NANOSECONDS) }.getOrDefault(0L)
    }

    val statIdentity = runCatching {
        val stat = Os.lstat(file.absolutePath)
        if (!OsConstants.S_ISREG(stat.st_mode) || stat.st_ino == 0L) return@runCatching ""
        val confirmed = readRegularAttributes() ?: return@runCatching ""
        val initialKey = attributes.fileKey()?.toString().orEmpty()
        val confirmedKey = confirmed.fileKey()?.toString().orEmpty()
        if (initialKey.isNotBlank() && confirmedKey.isNotBlank() && initialKey != confirmedKey) {
            return@runCatching ""
        }
        val confirmedBirthNanos = confirmed.creationTime().let { time ->
            runCatching { time.to(TimeUnit.NANOSECONDS) }.getOrDefault(0L)
        }
        if (birthNanos != 0L && confirmedBirthNanos != 0L && birthNanos != confirmedBirthNanos) {
            return@runCatching ""
        }
        buildStatFileIdentity(
            stat.st_dev, stat.st_ino, stat.st_ctim.tv_sec, stat.st_ctim.tv_nsec, confirmedBirthNanos,
        )
    }.getOrDefault("")
    if (statIdentity.isNotBlank()) return statIdentity

    // Android/JVM environments without a usable lstat inode fall back to NIO. Re-sample the
    // path entry immediately before deriving that identity so a symlink swap after the first
    // observation cannot inherit the previous regular file's key.
    val fallbackAttributes = readRegularAttributes() ?: return ""
    val fallbackBirthNanos = fallbackAttributes.creationTime().let { time ->
        runCatching { time.to(TimeUnit.NANOSECONDS) }.getOrDefault(0L)
    }
    return runCatching {
        val key = fallbackAttributes.fileKey()?.toString()?.takeIf { it.isNotBlank() } ?: return@runCatching ""
        val encodedKey = Base64.getUrlEncoder().withoutPadding().encodeToString(key.toByteArray(Charsets.UTF_8))
        "nio:$encodedKey:$fallbackBirthNanos"
    }.getOrDefault("")
}

internal fun resolveDirectoryIdentity(directory: File): String {
    fun readDirectoryAttributes(): BasicFileAttributes? = runCatching {
        Files.readAttributes(
            directory.toPath(),
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
    }.getOrNull()?.takeIf(BasicFileAttributes::isDirectory)

    val attributes = readDirectoryAttributes() ?: return ""
    val birthNanos = attributes.creationTime().let { time ->
        runCatching { time.to(TimeUnit.NANOSECONDS) }.getOrDefault(0L)
    }
    val statIdentity = runCatching {
        val stat = Os.lstat(directory.absolutePath)
        if (!OsConstants.S_ISDIR(stat.st_mode) || stat.st_ino == 0L) return@runCatching ""
        val confirmed = readDirectoryAttributes() ?: return@runCatching ""
        val initialKey = attributes.fileKey()?.toString().orEmpty()
        val confirmedKey = confirmed.fileKey()?.toString().orEmpty()
        if (initialKey.isNotBlank() && confirmedKey.isNotBlank() && initialKey != confirmedKey) {
            return@runCatching ""
        }
        val confirmedBirthNanos = confirmed.creationTime().let { time ->
            runCatching { time.to(TimeUnit.NANOSECONDS) }.getOrDefault(0L)
        }
        if (birthNanos != 0L && confirmedBirthNanos != 0L && birthNanos != confirmedBirthNanos) {
            return@runCatching ""
        }
        buildStatFileIdentity(
            stat.st_dev, stat.st_ino, stat.st_ctim.tv_sec, stat.st_ctim.tv_nsec, confirmedBirthNanos,
        )
    }.getOrDefault("")
    if (statIdentity.isNotBlank()) return statIdentity

    val fallback = readDirectoryAttributes() ?: return ""
    val fallbackBirthNanos = fallback.creationTime().let { time ->
        runCatching { time.to(TimeUnit.NANOSECONDS) }.getOrDefault(0L)
    }
    return runCatching {
        val key = fallback.fileKey()?.toString()?.takeIf { it.isNotBlank() } ?: return@runCatching ""
        val encodedKey = Base64.getUrlEncoder().withoutPadding().encodeToString(key.toByteArray(Charsets.UTF_8))
        "nio:$encodedKey:$fallbackBirthNanos"
    }.getOrDefault("")
}

internal fun resolveFileDescriptorIdentity(descriptor: FileDescriptor): String = runCatching {
    val stat = Os.fstat(descriptor)
    if (stat.st_ino == 0L) "" else "statfd:${stat.st_dev}:${stat.st_ino}:${stat.st_ctim.tv_sec}:${stat.st_ctim.tv_nsec}"
}.getOrDefault("")

internal fun resolveFileDescriptorModifiedTimeMillis(descriptor: FileDescriptor): Long = runCatching {
    val stat = Os.fstat(descriptor)
    Math.addExact(
        Math.multiplyExact(stat.st_mtim.tv_sec, 1_000L),
        stat.st_mtim.tv_nsec / 1_000_000L,
    )
}.getOrDefault(0L)

private fun buildStatFileIdentity(
    dev: Long,
    ino: Long,
    ctimeSeconds: Long,
    ctimeNanos: Long,
    birthNanos: Long,
): String = "stat:$dev:$ino:$ctimeSeconds:$ctimeNanos:$birthNanos"

internal fun fileIdentityMatches(storedIdentity: String, currentIdentity: String): Boolean =
    storedIdentity.isNotBlank() && currentIdentity.isNotBlank() && storedIdentity == currentIdentity

internal fun scannedRecordingIdentityRemainsCurrent(
    storageType: RecordingStorageType,
    beforeValidation: String,
    afterValidation: String,
): Boolean {
    if (beforeValidation.isBlank()) return true
    if (afterValidation.isBlank()) return false
    return when (storageType) {
        RecordingStorageType.FILE -> fileIdentityMatches(beforeValidation, afterValidation)
        RecordingStorageType.DOCUMENT,
        RecordingStorageType.MEDIASTORE,
        -> providerRecordingIdentityMatches(beforeValidation, afterValidation)
    }
}

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

internal fun scannedFileRecordingIdentityRemainsCurrent(
    beforePathIdentity: String,
    openedDescriptorIdentity: String,
    afterReadDescriptorIdentity: String,
    afterPathIdentity: String,
): Boolean {
    if (beforePathIdentity.isBlank()) {
        // Identity-less rows remain non-authoritative, but the bytes/metadata sampled from the
        // pinned descriptor still have to belong to one stable descriptor revision. If path
        // identity becomes available by the end of the scan, bind that path to the same revision.
        if (openedDescriptorIdentity.isBlank() != afterReadDescriptorIdentity.isBlank()) return false
        if (openedDescriptorIdentity.isNotBlank() && openedDescriptorIdentity != afterReadDescriptorIdentity) {
            return false
        }
        if (afterPathIdentity.isNotBlank()) {
            return afterReadDescriptorIdentity.isNotBlank() &&
                fileDescriptorIdentityMatches(afterPathIdentity, afterReadDescriptorIdentity)
        }
        return true
    }
    return afterPathIdentity.isNotBlank() &&
        fileDescriptorIdentityMatches(beforePathIdentity, openedDescriptorIdentity) &&
        openedDescriptorIdentity == afterReadDescriptorIdentity &&
        fileIdentityMatches(beforePathIdentity, afterPathIdentity) &&
        fileDescriptorIdentityMatches(afterPathIdentity, afterReadDescriptorIdentity)
}

internal fun recordingFileIdentityMatches(recording: RecordingEntity): Boolean {
    if (recording.storageType != RecordingStorageType.FILE) return true
    return fileIdentityMatches(recording.fileIdentity, resolveFileIdentity(File(recording.id)))
}

internal fun fileRecordingAssetState(file: File): RecordingAssetState = when (val observation = observeStoragePath(file)) {
    StoragePathObservation(StoragePathState.MISSING, false) -> RecordingAssetState.MISSING
    StoragePathObservation(StoragePathState.UNAVAILABLE, false) -> RecordingAssetState.UNAVAILABLE
    else -> if (observation.state == StoragePathState.PRESENT && observation.isRegularFile) {
        RecordingAssetState.PRESENT
    } else {
        RecordingAssetState.MISSING
    }
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

        RecordingStorageType.DOCUMENT -> {
            val deleteFailure = runCatching {
                val document = DocumentFile.fromSingleUri(context, recording.id.toUri())
                    ?: throw IOException("Unable to resolve recording for deletion")
                document.delete()
            }.exceptionOrNull()
            val observedState = recordingAssetState(context, recording)
            deleteFailure?.let {
                Log.w(TAG, "Document delete result was uncertain; observed $observedState for ${recording.id}", it)
            }
            providerDeletionCompleted(observedState)
        }
        RecordingStorageType.MEDIASTORE -> {
            val deleteFailure = runCatching {
                context.contentResolver.delete(recording.id.toUri(), null, null)
            }.exceptionOrNull()
            val observedState = recordingAssetState(context, recording)
            deleteFailure?.let {
                Log.w(TAG, "MediaStore delete result was uncertain; observed $observedState for ${recording.id}", it)
            }
            providerDeletionCompleted(observedState)
        }
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
            val output = openChildOrCloseOwner(
                openWritableParcelFileDescriptor(context, resolvedTarget),
            ) { descriptor ->
                ParcelFileDescriptor.AutoCloseOutputStream(descriptor)
            }
            output.use { ownedOutput ->
                sourceDigest = copyWithSha256(source, ownedOutput)
                ownedOutput.fd.sync()
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
        if (!stagingFingerprintMatchesCreatedObject(resolvedTarget, targetFingerprint)) {
            throw IOException("Copied recording no longer matches the created staging object")
        }
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

internal fun providerReadHandoffMatchesExpected(
    expectedIdentity: String,
    beforeOpenIdentity: String,
    afterOpenIdentity: String,
): Boolean = expectedIdentity.isNotBlank() &&
    providerRecordingIdentityMatches(expectedIdentity, beforeOpenIdentity) &&
    providerRecordingIdentityMatches(expectedIdentity, afterOpenIdentity)

internal fun providerReadRemainsStable(
    beforeOpenIdentity: String,
    afterOpenIdentity: String,
    afterReadIdentity: String,
): Boolean = beforeOpenIdentity.isNotBlank() &&
    providerRecordingIdentityMatches(beforeOpenIdentity, afterOpenIdentity) &&
    providerRecordingIdentityMatches(beforeOpenIdentity, afterReadIdentity)

internal fun scannedProviderRecordingIdentityRemainsCurrent(
    beforeOpenIdentity: String,
    afterOpenIdentity: String,
    afterReadIdentity: String,
): Boolean {
    if (beforeOpenIdentity.isBlank()) {
        // Identity-less rows remain non-authoritative, but any identity that becomes available
        // after descriptor acquisition must stay bound to one provider revision through the read.
        if (afterOpenIdentity.isBlank() != afterReadIdentity.isBlank()) return false
        return afterOpenIdentity.isBlank() ||
            providerRecordingIdentityMatches(afterOpenIdentity, afterReadIdentity)
    }
    return providerReadRemainsStable(beforeOpenIdentity, afterOpenIdentity, afterReadIdentity)
}

internal fun stagingFingerprintMatchesCreatedObject(
    target: RecordingOutputTarget,
    fingerprint: StableOutputFingerprint,
): Boolean {
    if (!target.staging) return false
    return when (target.storageType) {
        RecordingStorageType.FILE -> sameFileObjectAcrossRename(
            target.stagingIdentity,
            fingerprint.fileKey.orEmpty(),
        )
        RecordingStorageType.DOCUMENT,
        RecordingStorageType.MEDIASTORE,
        -> target.stagingIdentity.isBlank() || sameProviderObjectAcrossMutation(
            target.stagingIdentity,
            fingerprint.providerIdentity,
        )
    }
}

private fun openVerifiedProviderInputStream(
    context: Context,
    recording: RecordingEntity,
): InputStream? {
    val expectedIdentity = recording.fileIdentity.takeIf { it.isNotBlank() } ?: return null
    val uri = recording.id.toUri()
    val before = resolveProviderRecordingIdentity(context, recording.storageType, uri)
    if (!providerRecordingIdentityMatches(expectedIdentity, before)) return null

    val descriptor = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
    val input = openChildOrCloseOwner(descriptor) { opened ->
        ParcelFileDescriptor.AutoCloseInputStream(opened)
    }
    val after = resolveProviderRecordingIdentity(context, recording.storageType, uri)
    if (!providerReadHandoffMatchesExpected(expectedIdentity, before, after)) {
        closeRejectedOwnerOrThrow { input.close() }
        return null
    }
    return input
}

internal fun openRecordingInputStream(context: Context, recording: RecordingEntity): InputStream? =
    when (recording.storageType) {
        RecordingStorageType.FILE -> openVerifiedFileInputStream(recording)
        RecordingStorageType.DOCUMENT,
        RecordingStorageType.MEDIASTORE,
        -> openVerifiedProviderInputStream(context, recording)
    }

internal fun openVerifiedFileInputStream(recording: RecordingEntity): FileInputStream? {
    if (recording.storageType != RecordingStorageType.FILE || recording.fileIdentity.isBlank()) return null
    val file = File(recording.id)
    val beforeObservation = observeStoragePath(file)
    if (beforeObservation.state != StoragePathState.PRESENT || !beforeObservation.isRegularFile) return null
    val beforeIdentity = resolveFileIdentity(file)
    if (!fileIdentityMatches(recording.fileIdentity, beforeIdentity)) return null
    val stream = try {
        FileInputStream(file)
    } catch (_: Exception) {
        return null
    }
    val openedIdentity = resolveFileDescriptorIdentity(stream.fd)
    val afterObservation = observeStoragePath(file)
    val afterIdentity = if (afterObservation.state == StoragePathState.PRESENT && afterObservation.isRegularFile) {
        resolveFileIdentity(file)
    } else {
        ""
    }
    if (!verifiedFileReadHandoffMatchesExpected(
            expectedIdentity = recording.fileIdentity,
            beforePathIdentity = beforeIdentity,
            descriptorIdentity = openedIdentity,
            afterPathIdentity = afterIdentity,
        )
    ) {
        closeRejectedOwnerOrThrow { stream.close() }
        return null
    }
    return stream
}

internal fun verifiedFileReadHandoffMatchesExpected(
    expectedIdentity: String,
    beforePathIdentity: String,
    descriptorIdentity: String,
    afterPathIdentity: String,
): Boolean = fileIdentityMatches(expectedIdentity, beforePathIdentity) &&
    fileDescriptorIdentityMatches(expectedIdentity, descriptorIdentity) &&
    fileIdentityMatches(expectedIdentity, afterPathIdentity) &&
    fileDescriptorIdentityMatches(afterPathIdentity, descriptorIdentity)

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
    val fingerprint = when (target.storageType) {
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
            val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
                ?: throw IOException("Unable to reopen exported recording")
            val input = openChildOrCloseOwner(descriptor) { opened ->
                ParcelFileDescriptor.AutoCloseInputStream(opened)
            }
            input.use { source ->
                val afterOpen = resolveProviderRecordingIdentity(context, target.storageType, uri)
                if (!providerRecordingIdentityMatches(before, afterOpen)) {
                    throw IOException("Exported provider object changed while opening verification")
                }
                val digest = verifyWavOutputStreamAndDigest(
                    input = source,
                    expectedFileBytes = expectedFileBytes,
                    expectedPrefix = expectedPrefix,
                    payloadOffsetBytes = payloadOffsetBytes,
                    payloadBytes = payloadBytes,
                    expectedPayloadSha256 = expectedPayloadSha256,
                )
                val afterRead = resolveProviderRecordingIdentity(context, target.storageType, uri)
                if (!providerReadRemainsStable(before, afterOpen, afterRead)) {
                    throw IOException("Exported provider object changed while verifying")
                }
                StableOutputFingerprint(digest, fileKey = null, providerIdentity = before)
            }
        }
    }
    if (!stagingFingerprintMatchesCreatedObject(target, fingerprint)) {
        throw IOException("Verified output no longer matches the created staging object")
    }
    return fingerprint
}

internal fun listOutputDirectoryRecordings(
    context: Context,
    treeUri: Uri?,
    knownRecordings: Map<String, RecordingEntity> = emptyMap(),
): List<RecordingEntity> {
    if (treeUri != null && !documentTreeIdIsValid(treeUri.toString())) {
        throw IOException("Output destination is not a canonical document tree")
    }
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

internal fun readRecoverableStagingFile(file: File): RecoverableStagingWav? {
    val beforeObservation = observeStoragePath(file)
    if (beforeObservation.state != StoragePathState.PRESENT || !beforeObservation.isRegularFile) return null
    val beforeIdentity = resolveFileIdentity(file).takeIf { it.isNotBlank() } ?: return null
    return FileInputStream(file).use { input ->
        val openedIdentity = resolveFileDescriptorIdentity(input.fd).takeIf { it.isNotBlank() } ?: return@use null
        if (!fileDescriptorIdentityMatches(beforeIdentity, openedIdentity)) return@use null
        val observation = readRecoverableStagingWav(input) ?: return@use null
        val afterReadIdentity = resolveFileDescriptorIdentity(input.fd)
        if (openedIdentity != afterReadIdentity) return@use null
        val afterObservation = observeStoragePath(file)
        if (afterObservation.state != StoragePathState.PRESENT || !afterObservation.isRegularFile) return@use null
        val afterIdentity = resolveFileIdentity(file).takeIf { it.isNotBlank() } ?: return@use null
        if (!fileIdentityMatches(beforeIdentity, afterIdentity) ||
            !fileDescriptorIdentityMatches(afterIdentity, afterReadIdentity)
        ) {
            return@use null
        }
        observation
    }
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
        val pathObservation = observeStoragePath(file)
        if (pathObservation.state != StoragePathState.PRESENT || !pathObservation.isRegularFile ||
            !isStagingOutputName(name)
        ) return@forEach
        val metadata = parseStagingOutputMetadata(name) ?: return@forEach
        val trackedFingerprint = if (metadata.kind == StagingOutputKind.EXPORT_TRACKED) {
            verifiedExportStagingFingerprint(context, RecordingStorageType.FILE, file.absolutePath)
        } else {
            null
        }
        if (!shouldRecoverStagingOutput(metadata, verifiedTrackedExport = trackedFingerprint != null)) return@forEach
        if (!metadata.finalDisplayName.endsWith(".${ExportFormat.WAV.extension}", ignoreCase = true)) return@forEach
        val observation = runCatching { readRecoverableStagingFile(file) }
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
        if (recovered) removeVerifiedExportStaging(context, target.storageType, target.id, publishFingerprint)
        changed = changed || recovered
    }
    return changed
}

private data class DocumentTreeEntry(
    val uri: Uri,
    val name: String?,
    val mimeType: String?,
    val sizeBytes: Long,
    val sizeKnown: Boolean,
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
                        sizeKnown = !rows.isNull(sizeIndex),
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
        if (recovered) removeVerifiedExportStaging(context, target.storageType, target.id, publishFingerprint)
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
    when (storageDirectoryState(directory)) {
        StoragePathState.MISSING -> return emptyList()
        StoragePathState.UNAVAILABLE -> throw IOException(
            "Recording directory is not a trustworthy directory entry: ${directory.absolutePath}",
        )
        StoragePathState.PRESENT -> Unit
    }
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
            val listedSize = listedRecordingFileSize(file) ?: return@mapNotNull null
            val identity = resolveFileIdentity(file)
            val existing = knownRecordings[id]
            if (
                existing != null && existing.durationMillis > 0L &&
                existing.displayName == file.name && existing.sizeBytes == listedSize &&
                fileIdentityMatches(existing.fileIdentity, identity)
            ) {
                existing
            } else {
                val scan = try {
                    FileInputStream(file).use { input ->
                        val openedDescriptorIdentity = resolveFileDescriptorIdentity(input.fd)
                        if (
                            identity.isNotBlank() &&
                            !fileDescriptorIdentityMatches(identity, openedDescriptorIdentity)
                        ) {
                            throw IOException("Recording path changed before descriptor scan: $file")
                        }
                        val descriptorSize = input.channel.size()
                        val strictDuration = if (descriptorSize > 0L) {
                            structurallyCompleteRecordingDurationMillis(file.name, input)
                        } else {
                            0L
                        }
                        val media = if (strictDuration > 0L) {
                            inspectRecordingMedia(
                                descriptor = input.fd,
                                displayName = file.name,
                                fallbackDurationMillis = strictDuration,
                            )
                        } else {
                            RecordingMediaMetadata(durationMillis = 0L, codecSummary = "")
                        }
                        val startedAtMillis = resolveRecordingStartTimeMillis(
                            displayName = file.name,
                            fallbackMillis = resolveFileDescriptorModifiedTimeMillis(input.fd),
                        )
                        val afterReadDescriptorIdentity = resolveFileDescriptorIdentity(input.fd)
                        ScannedFileRecording(
                            strictDurationMillis = strictDuration,
                            sizeBytes = descriptorSize,
                            codecSummary = media.codecSummary,
                            startedAtMillis = startedAtMillis,
                            openedDescriptorIdentity = openedDescriptorIdentity,
                            afterReadDescriptorIdentity = afterReadDescriptorIdentity,
                        )
                    }
                } catch (_: NoSuchFileException) {
                    return@mapNotNull null
                } catch (error: Exception) {
                    throw IOException("Unable to validate discovered recording $file", error)
                }
                if (!scannedFileRecordingIdentityRemainsCurrent(
                        beforePathIdentity = identity,
                        openedDescriptorIdentity = scan.openedDescriptorIdentity,
                        afterReadDescriptorIdentity = scan.afterReadDescriptorIdentity,
                        afterPathIdentity = resolveFileIdentity(file),
                    )
                ) {
                    throw IOException("Recording changed while scanning $file")
                }
                if (scan.strictDurationMillis <= 0L || scan.sizeBytes <= 0L) return@mapNotNull null
                RecordingEntity(
                    id = id,
                    displayName = file.name,
                    mimeType = guessMimeType(file.name),
                    startedAtMillis = scan.startedAtMillis,
                    durationMillis = scan.strictDurationMillis,
                    sizeBytes = scan.sizeBytes,
                    codecSummary = scan.codecSummary,
                    storageType = RecordingStorageType.FILE,
                    directoryId = directory.absolutePath,
                    fileIdentity = identity,
                )
            }
        }
        .toList()
}

private data class ScannedFileRecording(
    val strictDurationMillis: Long,
    val sizeBytes: Long,
    val codecSummary: String,
    val startedAtMillis: Long,
    val openedDescriptorIdentity: String,
    val afterReadDescriptorIdentity: String,
)

private data class ScannedProviderRecording(
    val strictDurationMillis: Long,
    val media: RecordingMediaMetadata,
    val afterReadIdentity: String,
)

internal fun recoveredProviderCatalogIdentity(
    publishedIdentity: String,
    scannedIdentity: String,
): String? = publishedIdentity.takeIf {
    it.isNotBlank() && providerRecordingIdentityMatches(it, scannedIdentity)
}

private fun scanProviderRecording(
    context: Context,
    storageType: RecordingStorageType,
    uri: Uri,
    displayName: String,
    beforeIdentity: String,
): ScannedProviderRecording {
    val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
        ?: throw IOException("Unable to open discovered recording $uri")
    val input = openChildOrCloseOwner(descriptor) { opened ->
        ParcelFileDescriptor.AutoCloseInputStream(opened)
    }
    return input.use { source ->
        val afterOpenIdentity = resolveProviderRecordingIdentity(context, storageType, uri)
        if (
            beforeIdentity.isNotBlank() &&
            !providerRecordingIdentityMatches(beforeIdentity, afterOpenIdentity)
        ) {
            throw IOException("Recording changed while opening scan descriptor $uri")
        }
        val strictDuration = structurallyCompleteRecordingDurationMillis(displayName, source)
        val media = if (strictDuration > 0L) {
            inspectRecordingMedia(
                descriptor = source.fd,
                displayName = displayName,
                fallbackDurationMillis = strictDuration,
            )
        } else {
            RecordingMediaMetadata(durationMillis = 0L, codecSummary = "")
        }
        val afterReadIdentity = resolveProviderRecordingIdentity(context, storageType, uri)
        if (!scannedProviderRecordingIdentityRemainsCurrent(
                beforeOpenIdentity = beforeIdentity,
                afterOpenIdentity = afterOpenIdentity,
                afterReadIdentity = afterReadIdentity,
            )
        ) {
            throw IOException("Recording changed while scanning $uri")
        }
        ScannedProviderRecording(
            strictDurationMillis = strictDuration,
            media = media,
            afterReadIdentity = afterReadIdentity,
        )
    }
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
                val scan = try {
                    scanProviderRecording(
                        context = context,
                        storageType = RecordingStorageType.DOCUMENT,
                        uri = uri,
                        displayName = name,
                        beforeIdentity = identity,
                    )
                } catch (error: Exception) {
                    throw IOException("Unable to validate discovered recording $uri", error)
                }
                if (scan.strictDurationMillis <= 0L) return@mapNotNull null
                val media = scan.media
                RecordingEntity(
                    id = uri.toString(),
                    displayName = name,
                    mimeType = file.mimeType ?: guessMimeType(name),
                    startedAtMillis = resolveRecordingStartTimeMillis(name, modifiedMillis),
                    durationMillis = scan.strictDurationMillis,
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

internal fun canReuseKnownMediaStoreRecording(
    existing: RecordingEntity?,
    displayName: String,
    sizeBytes: Long,
    identity: String,
): Boolean = identity.isNotBlank() &&
    existing != null &&
    existing.durationMillis > 0L &&
    existing.displayName == displayName &&
    (sizeBytes == 0L || existing.sizeBytes == sizeBytes) &&
    providerRecordingIdentityMatches(existing.fileIdentity, identity)

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
                    val modifiedSeconds = cursor.getLong(modifiedIndex).coerceAtLeast(0L)
                    val modifiedMillis = if (modifiedSeconds > Long.MAX_VALUE / 1000L) Long.MAX_VALUE
                    else modifiedSeconds * 1000L
                    val generation = if (generationIndex >= 0) cursor.getLong(generationIndex).coerceAtLeast(0L) else 0L
                    val mimeType = cursor.getString(mimeIndex) ?: guessMimeType(storedName)

                    var name = storedName
                    var media: RecordingMediaMetadata? = null
                    var durationMillis = 0L
                    var recoveredPublishedIdentity: String? = null

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
                            removeVerifiedExportStaging(context, stagedTarget.storageType, stagedTarget.id, publishFingerprint)
                            name = finalized.displayName
                            val publishedScan = runCatching {
                                scanProviderRecording(
                                    context = context,
                                    storageType = RecordingStorageType.MEDIASTORE,
                                    uri = uri,
                                    displayName = name,
                                    beforeIdentity = finalized.publishedIdentity,
                                )
                            }.onFailure {
                                Log.w(TAG, "Unable to inspect published recovered recording $uri", it)
                            }.getOrNull() ?: continue
                            if (publishedScan.strictDurationMillis <= 0L) continue
                            recoveredPublishedIdentity = recoveredProviderCatalogIdentity(
                                publishedIdentity = finalized.publishedIdentity,
                                scannedIdentity = publishedScan.afterReadIdentity,
                            ) ?: continue
                            durationMillis = publishedScan.strictDurationMillis
                            media = publishedScan.media
                        } else {
                            // Older rows predate operation-kind/session staging, so they may
                            // represent either an export or a copy/move. That ambiguity is not
                            // enough authority to publish them as finished user recordings.
                            continue
                        }
                    } else {
                        if (!isSupportedRecordingName(name)) continue
                    }

                    val id = uri.toString()
                    val identity = if (pending) {
                        recoveredPublishedIdentity ?: continue
                    } else {
                        providerRecordingIdentity(
                            RecordingStorageType.MEDIASTORE, id, size, generation.takeIf { it > 0L } ?: modifiedMillis,
                        )
                    }
                    val existing = knownRecordings[id]
                    if (canReuseKnownMediaStoreRecording(existing, name, size, identity)) {
                        add(requireNotNull(existing))
                        continue
                    }
                    if (!pending) {
                        // MediaStore duration/name metadata does not prove that the underlying
                        // bytes are a complete recording. Match FILE/SAF reconciliation: only a
                        // new or changed row pays this read, while identity-stable rows above stay
                        // on the no-I/O fast path. Transport/read uncertainty aborts this scan
                        // scope so existing catalog state is preserved rather than treated empty.
                        val scan = try {
                            scanProviderRecording(
                                context = context,
                                storageType = RecordingStorageType.MEDIASTORE,
                                uri = uri,
                                displayName = name,
                                beforeIdentity = identity,
                            )
                        } catch (error: Exception) {
                            throw IOException("Unable to validate discovered MediaStore recording $uri", error)
                        }
                        if (scan.strictDurationMillis <= 0L) continue
                        durationMillis = scan.strictDurationMillis
                        media = scan.media
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
    val beforeIdentity = resolveDirectoryIdentity(directory).takeIf { it.isNotBlank() }
        ?: throw IOException("Recording directory is not a trustworthy directory: ${directory.absolutePath}")
    FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { channel ->
        channel.force(true)
    }
    val afterIdentity = resolveDirectoryIdentity(directory)
    if (!fileIdentityMatches(beforeIdentity, afterIdentity)) {
        throw IOException("Recording directory changed across durability barrier: ${directory.absolutePath}")
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
    val storageDirectoryCreated = ensureDirectoryEntryNoFollow(storageDir)
    if (storageDirectoryCreated) {
        val parent = storageDir.parentFile
            ?: throw IOException("Recordings directory has no parent: ${storageDir.absolutePath}")
        forceRecordingDirectoryDurable(parent)
    }

    // Document-provider display names are metadata, not trusted filesystem paths.
    // A name such as "../recording.wav" must never escape app-local storage when
    // recordings are moved from SAF back into the app directory.
    val safeDisplayName = sanitizeBaseName(requestedDisplayName)
    val uniqueName = findAvailableDisplayName(safeDisplayName) { candidate -> File(storageDir, candidate).exists() }
    val created = createLocalStagingFile(
        storageDir = storageDir,
        finalDisplayName = uniqueName,
        stagingKind = stagingKind,
    )
    val file = created.file
    val stagingIdentity = created.identity
    return RecordingOutputTarget(
        id = file.absolutePath,
        displayName = uniqueName,
        mimeType = mimeType,
        storageType = RecordingStorageType.FILE,
        directoryId = storageDir.absolutePath,
        startedAtMillis = startedAtMillis,
        file = file,
        staging = true,
        stagingDisplayName = file.name,
        stagingIdentity = stagingIdentity,
    )
}

internal data class CreatedLocalStagingFile(
    val file: File,
    val identity: String,
)

internal interface LocalStagingCreationHandle {
    fun descriptorIdentity(): String
    fun close()
}

internal interface LocalStagingCreationIo {
    fun openExclusive(file: File): LocalStagingCreationHandle?
    fun forceDirectory(directory: File)
    fun resolveIdentity(file: File): String
}

private object DefaultLocalStagingCreationIo : LocalStagingCreationIo {
    override fun openExclusive(file: File): LocalStagingCreationHandle? {
        val descriptor = try {
            Os.open(
                file.absolutePath,
                OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_RDWR or OsConstants.O_NOFOLLOW,
                0x1B6,
            )
        } catch (error: ErrnoException) {
            if (error.errno == OsConstants.EEXIST) return null
            throw IOException("Unable to create output staging entry", error)
        }
        return object : LocalStagingCreationHandle {
            override fun descriptorIdentity(): String = resolveFileDescriptorIdentity(descriptor)
            override fun close() {
                try {
                    Os.close(descriptor)
                } catch (error: ErrnoException) {
                    throw IOException("Unable to close output staging creation descriptor", error)
                }
            }
        }
    }

    override fun forceDirectory(directory: File) = forceRecordingDirectoryDurable(directory)
    override fun resolveIdentity(file: File): String = resolveFileIdentity(file)
}

internal fun createLocalStagingFile(
    storageDir: File,
    finalDisplayName: String,
    stagingKind: StagingOutputKind,
    token: () -> String = { UUID.randomUUID().toString() },
    io: LocalStagingCreationIo = DefaultLocalStagingCreationIo,
): CreatedLocalStagingFile {
    while (true) {
        val file = File(storageDir, stagingOutputName(finalDisplayName, token(), kind = stagingKind))
        val handle = io.openExclusive(file) ?: continue
        var failure: Throwable? = null
        var created: CreatedLocalStagingFile? = null
        try {
            val descriptorIdentity = handle.descriptorIdentity().takeIf { it.isNotBlank() }
                ?: throw IOException("Unable to bind new output staging descriptor to a stable identity")
            val beforeIdentity = io.resolveIdentity(file).takeIf { it.isNotBlank() }
                ?: throw IOException("Unable to bind new output staging path to a stable identity")
            if (!fileDescriptorIdentityMatches(beforeIdentity, descriptorIdentity)) {
                throw IOException("Output staging path changed during creation")
            }
            io.forceDirectory(storageDir)
            val afterIdentity = io.resolveIdentity(file).takeIf { it.isNotBlank() }
                ?: throw IOException("Output staging path became unavailable during creation")
            if (!fileDescriptorIdentityMatches(afterIdentity, descriptorIdentity)) {
                throw IOException("Output staging path changed across creation durability barrier")
            }
            created = CreatedLocalStagingFile(file, afterIdentity)
        } catch (error: Throwable) {
            failure = if (error is IOException) error
            else IOException("Unable to persist output staging entry", error)
        }
        failure = closePreservingPrimaryFailure(failure) { handle.close() }
        failure?.let { error ->
            throw if (error is IOException) error else IOException("Unable to close output staging creation", error)
        }
        return requireNotNull(created)
    }
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
    val beforeDigest = sha256StableRecording(context, recording) ?: return null
    var suffix = 1
    while (suffix > 0) {
        val uniqueName = if (suffix == 1) displayName else "$name ($suffix)$extension"
        val target = File(parent, uniqueName)
        try {
            Files.move(source.toPath(), target.toPath())
            val renamedIdentity = resolveFileIdentity(target)
            val sameObject = sameFileObjectAcrossRename(recording.fileIdentity, renamedIdentity)
            if (!sameObject) {
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
            val renamed = recording.copy(
                id = target.absolutePath,
                displayName = uniqueName,
                fileIdentity = renamedIdentity,
            )
            val afterDigest = sha256StableRecording(context, renamed)
            if (!renameContentContinuityIsSafe(sameObject, beforeDigest, afterDigest)) {
                val protected = preserveUnexpectedRenameTarget(
                    source = source,
                    moved = target,
                    originalDisplayName = recording.displayName,
                )
                if (!protected) {
                    Log.e(TAG, "Content-changed rename target could not be durably restored: $target")
                }
                throw IllegalStateException("Recording content changed during rename")
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
                target = renamed,
            )
        } catch (_: FileAlreadyExistsException) {
            suffix++
        } catch (error: IllegalStateException) {
            throw error
        } catch (error: IOException) {
            if (fileRenameMoveFailureIsUncertain(error)) {
                throw RecordingRenameStateUncertainException(
                    "File rename result is uncertain: ${recording.id}",
                    error,
                )
            }
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

internal class RecordingRenameStateUncertainException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

internal fun providerRenameStateIsUncertain(
    mutationAccepted: Boolean,
    contentContinuityVerified: Boolean,
    displayNameVerified: Boolean = true,
): Boolean = mutationAccepted && (!contentContinuityVerified || !displayNameVerified)

internal fun fileRenameMoveFailureIsUncertain(error: Throwable): Boolean =
    error is IOException && error !is FileAlreadyExistsException

internal data class MediaStoreRenameObservation(
    val displayName: String,
    val fingerprint: StableOutputFingerprint,
)

internal fun mediaStoreRenameObservationMatchesExpected(
    expectedDisplayName: String,
    beforeIdentity: String,
    beforeDigest: CopyDigest,
    observation: MediaStoreRenameObservation?,
): Boolean {
    val current = observation ?: return false
    if (current.displayName != expectedDisplayName) return false
    return sameProviderObjectAcrossMutation(
        beforeIdentity,
        current.fingerprint.providerIdentity.orEmpty(),
    ) && copyDigestMatches(beforeDigest, current.fingerprint.digest)
}

private fun readStableMediaStoreRenameObservation(
    context: Context,
    uri: Uri,
): MediaStoreRenameObservation? {
    val beforeName = queryContentDisplayName(context, uri)?.takeIf { it.isNotBlank() } ?: return null
    val fingerprint = readStableOutputFingerprint(
        context,
        RecordingStorageType.MEDIASTORE,
        uri.toString(),
    ) ?: return null
    val afterName = queryContentDisplayName(context, uri)?.takeIf { it.isNotBlank() } ?: return null
    if (beforeName != afterName) return null
    return MediaStoreRenameObservation(afterName, fingerprint)
}

private fun renameMediaStoreRecording(
    context: Context,
    recording: RecordingEntity,
    displayName: String,
): RecordingEntity? {
    val uri = recording.id.toUri()
    return try {
        val uniqueName = findAvailableDisplayName(displayName) { candidate ->
            candidate != recording.displayName && mediaStoreNameExists(context, candidate)
        }
        if (uniqueName == recording.displayName) return recording
        val beforeDigest = sha256StableRecording(context, recording) ?: return null
        val values = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, uniqueName) }
        var updateFailure: Throwable? = null
        val updated = try {
            context.contentResolver.update(uri, values, null, null)
        } catch (error: Exception) {
            updateFailure = error
            -1
        }
        if (updated <= 0) {
            // A remote provider may commit metadata before binder/transport failure, and a buggy
            // provider may even report zero rows after doing so. Re-observe the exact selected
            // object and accept only a byte-identical same-object result under the requested name.
            val observed = readStableMediaStoreRenameObservation(context, uri)
            if (mediaStoreRenameObservationMatchesExpected(
                    expectedDisplayName = uniqueName,
                    beforeIdentity = recording.fileIdentity,
                    beforeDigest = beforeDigest,
                    observation = observed,
                )
            ) {
                val recovered = recording.copy(
                    displayName = uniqueName,
                    fileIdentity = requireNotNull(observed).fingerprint.providerIdentity.orEmpty(),
                )
                return rebindRecordingWaveformCache(recording, recovered)
            }
            if (mediaStoreRenameObservationMatchesExpected(
                    expectedDisplayName = recording.displayName,
                    beforeIdentity = recording.fileIdentity,
                    beforeDigest = beforeDigest,
                    observation = observed,
                )
            ) {
                // The selected object is positively unchanged. This was a definite no-op, not an
                // uncertain mutation, even if the transport itself threw.
                return null
            }
            throw RecordingRenameStateUncertainException(
                "MediaStore rename result is uncertain: ${recording.id}",
                updateFailure,
            )
        }

        val renamedIdentity = resolveProviderRecordingIdentity(context, RecordingStorageType.MEDIASTORE, uri)
        val renamedDisplayName = queryContentDisplayName(context, uri)?.takeIf { it.isNotBlank() }
        val sameObject = sameProviderObjectAcrossMutation(recording.fileIdentity, renamedIdentity)
        val renamed = recording.copy(
            displayName = renamedDisplayName ?: uniqueName,
            fileIdentity = renamedIdentity,
        )
        val afterDigest = if (sameObject) sha256StableRecording(context, renamed) else null
        val continuityVerified = renameContentContinuityIsSafe(sameObject, beforeDigest, afterDigest)
        if (providerRenameStateIsUncertain(
                mutationAccepted = true,
                contentContinuityVerified = continuityVerified,
                displayNameVerified = renamedDisplayName == uniqueName,
            )
        ) {
            throw RecordingRenameStateUncertainException(
                "MediaStore rename postcondition is uncertain: ${recording.id}",
            )
        }
        rebindRecordingWaveformCache(recording, renamed)
    } catch (uncertain: RecordingRenameStateUncertainException) {
        Log.w(TAG, uncertain.message.orEmpty(), uncertain)
        throw uncertain
    } catch (error: Exception) {
        Log.w(TAG, "Unable to rename MediaStore recording ${recording.id}", error)
        null
    }
}

internal fun renameContentContinuityIsSafe(
    sameObject: Boolean,
    beforeDigest: CopyDigest?,
    afterDigest: CopyDigest?,
): Boolean {
    if (!sameObject) return false
    val before = beforeDigest ?: return false
    val after = afterDigest ?: return false
    return copyDigestMatches(before, after)
}

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
    return oldUriStateAfterRename == RecordingAssetState.MISSING &&
        documentProviderIdentitiesShareTree(beforeIdentity, afterIdentity)
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
        var renameFailure: Throwable? = null
        val returnedRenamedUri = try {
            DocumentsContract.renameDocument(context.contentResolver, sourceUri, uniqueName)
        } catch (error: Exception) {
            renameFailure = error
            null
        }
        val directRenamedUri = returnedRenamedUri?.takeIf { returned ->
            documentRecordingBelongsToTree(returned.toString(), recording.directoryId)
        }
        if (returnedRenamedUri != null && directRenamedUri == null) {
            renameFailure = IOException(
                "Document rename returned URI outside configured tree: $returnedRenamedUri",
            )
        }
        val renamedUri = directRenamedUri ?: run {
            val expectedFingerprint = StableOutputFingerprint(
                digest = beforeDigest,
                fileKey = null,
                providerIdentity = recording.fileIdentity,
            )
            val observed = runCatching {
                readStableDocumentPublicationObservation(
                    context = context,
                    treeUri = recording.directoryId.toUri(),
                    sourceUri = sourceUri,
                    finalDisplayName = uniqueName,
                )
            }.onFailure { error ->
                Log.w(TAG, "Unable to resolve ambiguous document rename $sourceUri", error)
            }.getOrNull()
            if (documentPublicationMatchesExpected(uniqueName, expectedFingerprint, observed?.state)) {
                val recovered = recording.copy(
                    id = requireNotNull(observed).uri.toString(),
                    displayName = uniqueName,
                    fileIdentity = observed.state.fingerprint.providerIdentity.orEmpty(),
                )
                return@runCatching rebindRecordingWaveformCache(recording, recovered)
            }
            if (observed != null &&
                observed.uri != sourceUri &&
                copyDigestMatches(beforeDigest, observed.state.fingerprint.digest) &&
                !suppressProviderOutputWithoutDeletion(
                    context = context,
                    storageType = RecordingStorageType.DOCUMENT,
                    id = observed.uri.toString(),
                    digest = beforeDigest,
                )
            ) {
                Log.w(TAG, "Unable to suppress rejected ambiguous document rename result ${observed.uri}")
            }
            if (renameFailure != null || observed != null) {
                throw RecordingRenameStateUncertainException(
                    "Document rename result is uncertain: ${recording.id}",
                    renameFailure,
                )
            }
            return@runCatching null
        }
        val renamedIdentity = resolveProviderRecordingIdentity(context, RecordingStorageType.DOCUMENT, renamedUri)
        val renamedDisplayName = DocumentFile.fromSingleUri(context, renamedUri)?.name?.takeIf { it.isNotBlank() }
        val renamed = recording.copy(
            id = renamedUri.toString(),
            displayName = renamedDisplayName ?: uniqueName,
            fileIdentity = renamedIdentity,
        )
        val sourceUriUnchanged = renamedUri == sourceUri
        val oldState = if (sourceUriUnchanged) RecordingAssetState.PRESENT
        else recordingAssetState(context, recording)
        // Rename is metadata, not a content mutation. Verify the selected bytes again even when
        // the provider keeps the same URI; document ID continuity alone cannot prove that a
        // buggy/provider-side rename did not rewrite or truncate the recording.
        val afterDigest = sha256StableRecording(context, renamed)
        val continuityVerified = documentRenameTransitionIsSafe(
            sourceUriUnchanged = sourceUriUnchanged,
            oldUriStateAfterRename = oldState,
            beforeIdentity = recording.fileIdentity,
            afterIdentity = renamedIdentity,
            beforeDigest = beforeDigest,
            afterDigest = afterDigest,
        )
        if (providerRenameStateIsUncertain(
                mutationAccepted = true,
                contentContinuityVerified = continuityVerified,
                displayNameVerified = renamedDisplayName == uniqueName,
            )
        ) {
            if (!continuityVerified && rejectedDocumentRenameShouldSuppressReturnedUri(sourceUriUnchanged) &&
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
            throw RecordingRenameStateUncertainException(
                "Document recording changed during rename: ${recording.id} -> $renamedUri",
            )
        }
        rebindRecordingWaveformCache(recording, renamed)
    }.fold(
        onSuccess = { it },
        onFailure = { error ->
            Log.w(TAG, "Unable to rename recording ${recording.id}", error)
            if (error is RecordingRenameStateUncertainException) throw error
            null
        },
    )
}

internal data class NewlyCreatedOutputObservation(
    val displayName: String?,
    val sizeBytes: Long,
    val sizeKnown: Boolean,
    val isFile: Boolean,
    val pending: Boolean?,
    val directoryMatches: Boolean,
)

internal fun newlyCreatedOutputMayBeWritten(
    expectedDisplayName: String,
    requirePending: Boolean,
    observation: NewlyCreatedOutputObservation?,
): Boolean {
    val current = observation ?: return false
    return current.displayName == expectedDisplayName &&
        current.sizeKnown &&
        current.sizeBytes == 0L &&
        current.isFile &&
        current.directoryMatches &&
        (!requirePending || current.pending == true)
}

internal fun stagingFileDescriptorMatchesCreation(
    expectedIdentity: String,
    currentPathIdentity: String,
    descriptorIdentity: String,
    descriptorSize: Long,
): Boolean = descriptorSize == 0L &&
    fileIdentityMatches(expectedIdentity, currentPathIdentity) &&
    fileDescriptorIdentityMatches(expectedIdentity, descriptorIdentity)

private fun queryCreatedFileOutput(target: RecordingOutputTarget): NewlyCreatedOutputObservation? {
    val file = target.file ?: return null
    val attributes = try {
        Files.readAttributes(
            file.toPath(),
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
    } catch (_: Exception) {
        return null
    }
    return NewlyCreatedOutputObservation(
        displayName = file.name,
        sizeBytes = attributes.size().coerceAtLeast(0L),
        sizeKnown = true,
        isFile = attributes.isRegularFile,
        pending = null,
        directoryMatches = file.parentFile?.absolutePath == target.directoryId,
    )
}

private fun queryCreatedDocumentOutput(
    context: Context,
    treeUri: Uri,
    documentUri: Uri,
): NewlyCreatedOutputObservation? {
    val entry = queryDocumentTreeEntries(context, treeUri)
        .singleOrNull { candidate -> candidate.uri == documentUri }
        ?: return null
    return NewlyCreatedOutputObservation(
        displayName = entry.name,
        sizeBytes = entry.sizeBytes,
        sizeKnown = entry.sizeKnown,
        isFile = entry.isFile,
        pending = null,
        directoryMatches = true,
    )
}

private fun requireWritableStagingTargetStillEmpty(
    context: Context,
    target: RecordingOutputTarget,
) {
    if (!target.staging || target.stagingDisplayName.isBlank()) {
        throw IOException("Output write requires owned staging metadata: ${target.id}")
    }
    val observation = when (target.storageType) {
        RecordingStorageType.FILE -> queryCreatedFileOutput(target)
        RecordingStorageType.DOCUMENT -> queryCreatedDocumentOutput(
            context,
            target.directoryId.toUri(),
            requireNotNull(target.uri),
        )
        RecordingStorageType.MEDIASTORE -> queryCreatedMediaStoreOutput(
            context,
            requireNotNull(target.uri),
        )
    }
    if (!newlyCreatedOutputMayBeWritten(
            expectedDisplayName = target.stagingDisplayName,
            requirePending = target.storageType == RecordingStorageType.MEDIASTORE,
            observation = observation,
        )
    ) {
        throw IOException("Output staging changed before write: ${target.id}")
    }
    when (target.storageType) {
        RecordingStorageType.FILE -> {
            if (target.stagingIdentity.isBlank() ||
                !fileIdentityMatches(target.stagingIdentity, resolveFileIdentity(requireNotNull(target.file)))
            ) {
                throw IOException("Output staging file identity changed before write: ${target.id}")
            }
        }
        RecordingStorageType.DOCUMENT,
        RecordingStorageType.MEDIASTORE,
        -> if (target.stagingIdentity.isNotBlank()) {
            val current = resolveProviderRecordingIdentity(
                context,
                target.storageType,
                requireNotNull(target.uri),
            )
            if (!sameProviderObjectAcrossMutation(target.stagingIdentity, current)) {
                throw IOException("Output staging provider identity changed before write: ${target.id}")
            }
        }
    }
}

private fun queryCreatedMediaStoreOutput(
    context: Context,
    uri: Uri,
): NewlyCreatedOutputObservation? {
    val projection = arrayOf(
        MediaStore.MediaColumns.DISPLAY_NAME,
        MediaStore.MediaColumns.MIME_TYPE,
        MediaStore.MediaColumns.SIZE,
        MediaStore.MediaColumns.IS_PENDING,
        MediaStore.MediaColumns.RELATIVE_PATH,
    )
    val cursor = try {
        context.contentResolver.query(uri, projection, null, null, null)
    } catch (error: Exception) {
        throw IOException("Unable to verify created MediaStore output $uri", error)
    } ?: throw IOException("Created MediaStore output returned no verification cursor: $uri")
    return cursor.use { rows ->
        if (!rows.moveToFirst()) return@use null
        val nameIndex = rows.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
        val mimeIndex = rows.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
        val sizeIndex = rows.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
        val pendingIndex = rows.getColumnIndexOrThrow(MediaStore.MediaColumns.IS_PENDING)
        val pathIndex = rows.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
        val mimeType = if (rows.isNull(mimeIndex)) null else rows.getString(mimeIndex)
        NewlyCreatedOutputObservation(
            displayName = if (rows.isNull(nameIndex)) null else rows.getString(nameIndex),
            sizeBytes = if (rows.isNull(sizeIndex)) 0L else rows.getLong(sizeIndex).coerceAtLeast(0L),
            sizeKnown = !rows.isNull(sizeIndex),
            isFile = !mimeType.isNullOrBlank(),
            pending = if (rows.isNull(pendingIndex)) null else rows.getInt(pendingIndex) != 0,
            directoryMatches = !rows.isNull(pathIndex) && rows.getString(pathIndex) == MEDIA_STORE_RELATIVE_PATH,
        )
    }
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
    val observation = queryCreatedMediaStoreOutput(context, uri)
    if (!newlyCreatedOutputMayBeWritten(stagingName, requirePending = true, observation)) {
        // The returned URI is not enough authority to truncate/write an arbitrary provider row.
        // Preserve an unproven object untouched; pending/staging naming keeps a genuine partial
        // creation out of Library recovery until a later pass can prove its ownership/content.
        throw IOException("MediaStore returned an unverified output row: $uri")
    }
    return RecordingOutputTarget(
        id = uri.toString(),
        displayName = uniqueName,
        mimeType = mimeType,
        storageType = RecordingStorageType.MEDIASTORE,
        directoryId = MEDIA_STORE_DIRECTORY_ID,
        startedAtMillis = startedAtMillis,
        uri = uri,
        staging = true,
        stagingDisplayName = stagingName,
        stagingIdentity = resolveProviderRecordingIdentity(context, RecordingStorageType.MEDIASTORE, uri),
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
    val observation = queryCreatedDocumentOutput(context, treeUri, documentUri)
    if (!newlyCreatedOutputMayBeWritten(stagingName, requirePending = false, observation)) {
        // Never open a provider-returned URI with truncating write mode until the strict tree
        // listing proves that it is the exact new, empty high-entropy staging child we requested.
        throw IOException("Document provider returned an unverified output document: $documentUri")
    }

    return RecordingOutputTarget(
        id = documentUri.toString(),
        displayName = uniqueName,
        mimeType = mimeType,
        storageType = RecordingStorageType.DOCUMENT,
        directoryId = treeUri.toString(),
        startedAtMillis = startedAtMillis,
        uri = documentUri,
        staging = true,
        stagingDisplayName = stagingName,
        stagingIdentity = resolveProviderRecordingIdentity(context, RecordingStorageType.DOCUMENT, documentUri),
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
