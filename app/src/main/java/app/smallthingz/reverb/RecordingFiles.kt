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
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

private val TAG = "RecordingFiles"
private val ILLEGAL_FILENAME_CHARS = setOf('\\', '/', '*', '?', '"', '<', '>', '|')
private val SUPPORTED_RECORDING_EXTENSIONS = ExportFormat.entries.map { it.extension }.toSet()
private const val FILE_COPY_BUFFER_BYTES = 128 * 1024
internal const val MEDIA_STORE_DIRECTORY_ID = "mediastore:external:Music/Reverb"
private val MEDIA_STORE_RELATIVE_PATH = "${Environment.DIRECTORY_MUSIC}/${ReverbConfig.APP_STORAGE_FOLDER_NAME}/"


enum class RecordingStorageType {
    FILE,
    DOCUMENT,
    MEDIASTORE,
}

internal enum class RecordingAssetState {
    PRESENT,
    MISSING,
    UNAVAILABLE,
}

internal fun resolveRecordingStorageType(recording: RecordingEntity): RecordingStorageType? {
    return RecordingStorageType.entries.firstOrNull { it.name == recording.storageType }
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
)

/** Legacy app-private location used by older Reverb builds; always scanned for recovery. */
fun getSavedRecordingsDirectory(context: Context): File {
    val baseDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
        ?: File(context.filesDir, "recordings")
    return File(baseDir, ReverbConfig.APP_STORAGE_FOLDER_NAME)
}

@Suppress("DEPRECATION")
internal fun getSharedMusicRecordingsDirectory(): File =
    File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), ReverbConfig.APP_STORAGE_FOLDER_NAME)

internal fun usesMediaStoreDefaultStorage(sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
    sdkInt >= Build.VERSION_CODES.Q

internal fun requiresLegacyPublicStoragePermission(sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
    sdkInt < Build.VERSION_CODES.Q

fun getConfiguredExportTreeUri(context: Context): Uri? {
    val raw = getRecorderPreferences(context).getString(PrefKey.EXPORT_DIRECTORY_URI, null) ?: return null
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
        return "${Environment.DIRECTORY_MUSIC}/${ReverbConfig.APP_STORAGE_FOLDER_NAME}"
    }
    val documentId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
    return documentId
        ?.substringAfterLast(':')
        ?.trimEnd('/')
        ?.substringAfterLast('/')
        ?.takeIf { it.isNotBlank() }
        ?: treeUri.toString()
}

fun buildRecordingUri(
    context: Context,
    recording: RecordingEntity,
): Uri {
    return when (resolveRecordingStorageType(recording)) {
        RecordingStorageType.FILE -> {
            val file = File(recording.id)
            FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        }

        RecordingStorageType.DOCUMENT,
        RecordingStorageType.MEDIASTORE,
        -> recording.id.toUri()
        null -> throw IllegalArgumentException("Unknown recording storage type: ${recording.storageType}")
    }
}

fun buildOpenRecordingIntent(
    context: Context,
    recording: RecordingEntity,
): Intent {
    return Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(
            buildRecordingUri(context, recording),
            recording.mimeType.ifBlank { ReverbConfig.FALLBACK_MIME_TYPE_AUDIO },
        )
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
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
        .map { it.mimeType.ifBlank { ReverbConfig.FALLBACK_MIME_TYPE_AUDIO } }
        .distinct()
    val mimeType = mimeTypes.singleOrNull() ?: ReverbConfig.FALLBACK_MIME_TYPE_AUDIO
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

fun buildPlayerCodecSummary(codecSummary: String): String {
    val trimmed = codecSummary.trim()
    if (trimmed.isEmpty()) return codecSummary
    val sep = ReverbConfig.CODEC_SUMMARY_SEPARATOR
    val parts = mutableListOf<String>()
    var start = 0
    while (true) {
        val idx = trimmed.indexOf(sep, start)
        val part = if (idx < 0) trimmed.substring(start).trim() else trimmed.substring(start, idx).trim()
        if (part.isNotEmpty()) parts.add(part)
        if (idx < 0) break
        start = idx + sep.length
    }
    if (parts.isEmpty()) return codecSummary
    val first = parts[0]
    var sampleRate: String? = null
    var bitrate: String? = null
    for (i in 1 until parts.size) {
        val p = parts[i]
        if (p.contains("kHz", ignoreCase = true)) sampleRate = p
        else if (p.contains("kbps", ignoreCase = true)) bitrate = p
    }
    return buildString {
        append(first)
        if (sampleRate != null && sampleRate != first) { append(sep); append(sampleRate) }
        if (bitrate != null && bitrate != first) { append(sep); append(bitrate) }
    }
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
            append(ReverbConfig.CODEC_SUMMARY_SEPARATOR)
            append(sampleRateLabel(it))
        }
        bitrate?.takeIf { it > 0 }?.let {
            append(ReverbConfig.CODEC_SUMMARY_SEPARATOR)
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
            "${context.getString(R.string.app_storage_label)}/${ReverbConfig.APP_STORAGE_FOLDER_NAME}",
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
        "${Environment.DIRECTORY_MUSIC}/${ReverbConfig.APP_STORAGE_FOLDER_NAME}"
    if (normalizedPath == appStorageRelativeRoot || normalizedPath.startsWith("$appStorageRelativeRoot/")) {
        val tail = normalizedPath.removePrefix(appStorageRelativeRoot).trimStart('/')
        return appendRelativePath(
            "${context.getString(R.string.app_storage_label)}/${ReverbConfig.APP_STORAGE_FOLDER_NAME}",
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
        append(ReverbConfig.CODEC_SUMMARY_SEPARATOR)
        append(context.getString(format.labelRes))
        append(ReverbConfig.CODEC_SUMMARY_SEPARATOR)
        append(sampleRateLabel(sampleRate))
        append(ReverbConfig.CODEC_SUMMARY_SEPARATOR)
        append(channelLabel)
    }
}

fun describeRecordingLocation(
    context: Context,
    recording: RecordingEntity,
): String {
    return when (resolveRecordingStorageType(recording)) {
        RecordingStorageType.FILE -> describeFileRecordingLocation(context, File(recording.id))
        RecordingStorageType.DOCUMENT -> describeDocumentRecordingLocation(context, recording)
        RecordingStorageType.MEDIASTORE -> "${Environment.DIRECTORY_MUSIC}/${ReverbConfig.APP_STORAGE_FOLDER_NAME}/${recording.displayName}"
        null -> recording.directoryId
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
    return createOutputTarget(context, displayName, mimeType, startedAtMillis)
}

fun createOutputTarget(
    context: Context,
    requestedDisplayName: String,
    mimeType: String,
    startedAtMillis: Long,
): RecordingOutputTarget {
    val treeUri = getConfiguredExportTreeUri(context)
    return if (treeUri == null) {
        if (usesMediaStoreDefaultStorage()) {
            createMediaStoreOutputTarget(context, requestedDisplayName, mimeType, startedAtMillis)
        } else {
            createLocalOutputTarget(
                context, requestedDisplayName, mimeType, startedAtMillis,
                storageDir = getSharedMusicRecordingsDirectory(),
            )
        }
    } else {
        createDocumentOutputTarget(context, treeUri, requestedDisplayName, mimeType, startedAtMillis)
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
internal fun verifyOutputTargetSize(
    context: Context,
    target: RecordingOutputTarget,
    expectedBytes: Long,
): Long {
    require(expectedBytes > 0L) { "Expected output size must be positive" }
    val reportedSize = resolveOutputTargetSize(context, target)
    if (reportedSize == expectedBytes) return reportedSize
    return countOutputTargetBytes(context, target, expectedBytes)
}

@Throws(IOException::class)
fun finalizeOutputTarget(context: Context, target: RecordingOutputTarget) {
    when (target.storageType) {
        RecordingStorageType.MEDIASTORE -> publishMediaStoreUri(context, requireNotNull(target.uri))
        RecordingStorageType.FILE -> {
            val file = target.file
            if (file != null && target.directoryId == getSharedMusicRecordingsDirectory().absolutePath) {
                MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf(target.mimeType), null)
            }
        }
        RecordingStorageType.DOCUMENT -> Unit
    }
}

fun buildRecordingEntity(
    context: Context,
    target: RecordingOutputTarget,
    durationMillis: Long,
    codecSummary: String,
    knownSizeBytes: Long? = null,
): RecordingEntity {
    return RecordingEntity(
        id = target.id,
        displayName = target.displayName,
        mimeType = target.mimeType,
        startedAtMillis = target.startedAtMillis,
        durationMillis = durationMillis,
        sizeBytes = knownSizeBytes?.takeIf { it > 0L } ?: resolveOutputTargetSize(context, target),
        codecSummary = codecSummary,
        storageType = target.storageType.name,
        directoryId = target.directoryId,
    )
}

internal fun recordingAssetState(
    context: Context,
    recording: RecordingEntity,
): RecordingAssetState {
    return when (resolveRecordingStorageType(recording)) {
        RecordingStorageType.FILE -> try {
            val attributes = Files.readAttributes(
                File(recording.id).toPath(),
                BasicFileAttributes::class.java,
            )
            if (attributes.isRegularFile) RecordingAssetState.PRESENT else RecordingAssetState.MISSING
        } catch (_: NoSuchFileException) {
            RecordingAssetState.MISSING
        } catch (error: IOException) {
            Log.w(TAG, "Unable to inspect recording ${recording.id}", error)
            RecordingAssetState.UNAVAILABLE
        } catch (error: SecurityException) {
            Log.w(TAG, "Unable to inspect recording ${recording.id}", error)
            RecordingAssetState.UNAVAILABLE
        }

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

        null -> RecordingAssetState.UNAVAILABLE
    }
}

fun deleteRecordingAsset(
    context: Context,
    recording: RecordingEntity,
): Boolean {
    when (recordingAssetState(context, recording)) {
        RecordingAssetState.MISSING,
        RecordingAssetState.UNAVAILABLE,
        -> return false
        RecordingAssetState.PRESENT -> Unit
    }
    return when (resolveRecordingStorageType(recording)) {
        RecordingStorageType.FILE -> {
            val file = File(recording.id)
            runCatching { file.delete() }
                .onFailure { Log.w(TAG, "Unable to delete recording ${recording.id}", it) }
                .getOrDefault(false)
        }

        RecordingStorageType.DOCUMENT -> runCatching {
            val document = DocumentFile.fromSingleUri(context, recording.id.toUri())
            document?.delete() == true
        }.onFailure { Log.w(TAG, "Unable to delete recording ${recording.id}", it) }.getOrDefault(false)
        RecordingStorageType.MEDIASTORE -> runCatching {
            context.contentResolver.delete(recording.id.toUri(), null, null) > 0
        }.onFailure { Log.w(TAG, "Unable to delete recording ${recording.id}", it) }.getOrDefault(false)
        null -> false
    }
}

fun renameRecordingAsset(
    context: Context,
    recording: RecordingEntity,
    requestedBaseName: String,
): RecordingEntity? {
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
    return when (resolveRecordingStorageType(recording)) {
        RecordingStorageType.FILE -> renameFileRecording(context, recording, sanitized)
        RecordingStorageType.DOCUMENT -> renameDocumentRecording(context, recording, sanitized)
        RecordingStorageType.MEDIASTORE -> renameMediaStoreRecording(context, recording, sanitized)
        null -> null
    }
}

fun copyRecordingToConfiguredDirectory(
    context: Context,
    recording: RecordingEntity,
): RecordingEntity? {
    var target: RecordingOutputTarget? = null
    return try {
        val resolvedTarget = createOutputTarget(
            context = context,
            requestedDisplayName = recording.displayName,
            mimeType = recording.mimeType,
            startedAtMillis = recording.startedAtMillis,
        ).also { target = it }
        val sourceSize = when (resolveRecordingStorageType(recording)) {
            RecordingStorageType.FILE -> runCatching { Files.size(File(recording.id).toPath()) }
                .getOrNull()
                ?.takeIf { it > 0L }
            // Document-provider size metadata may lag behind the stream contents.
            // The destination is verified against the bytes actually copied below.
            RecordingStorageType.DOCUMENT,
            RecordingStorageType.MEDIASTORE,
            -> null
            null -> null
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
        val targetInput = when (resolvedTarget.storageType) {
            RecordingStorageType.FILE -> FileInputStream(requireNotNull(resolvedTarget.file))
            RecordingStorageType.DOCUMENT,
            RecordingStorageType.MEDIASTORE,
            -> context.contentResolver.openInputStream(requireNotNull(resolvedTarget.uri))
                ?: throw IOException("Unable to reopen copied recording")
        }
        val targetDigest = targetInput.use(::sha256)
        if (targetDigest.byteCount != copiedBytes || !targetDigest.sha256.contentEquals(sourceDigest.sha256)) {
            throw IOException("Recording copy content verification failed")
        }
        val sourceAfterCopy = runCatching {
            openRecordingInputStream(context, recording)?.use(::sha256)
        }.onFailure {
            // The verified target may now be the only surviving copy. Do not turn source
            // disappearance/provider failure into target cleanup.
            Log.w(TAG, "Unable to recheck source after verified copy ${recording.id}", it)
        }.getOrNull()
        if (
            sourceAfterCopy != null &&
            (sourceAfterCopy.byteCount != sourceDigest.byteCount ||
                !sourceAfterCopy.sha256.contentEquals(sourceDigest.sha256))
        ) {
            throw IOException("Recording source changed during copy")
        }
        finalizeOutputTarget(context, resolvedTarget)

        recording.copy(
            id = resolvedTarget.id,
            displayName = resolvedTarget.displayName,
            sizeBytes = verifiedTargetSize,
            storageType = resolvedTarget.storageType.name,
            directoryId = resolvedTarget.directoryId,
        )
    } catch (e: Exception) {
        Log.w(TAG, "exportToTarget failed for ${target?.displayName ?: recording.displayName}", e)
        runCatching {
            when (target?.storageType) {
                RecordingStorageType.FILE -> target.file?.delete()
                RecordingStorageType.DOCUMENT -> {
                    target.uri?.let { DocumentFile.fromSingleUri(context, it)?.delete() }
                }
                RecordingStorageType.MEDIASTORE -> {
                    target.uri?.let { context.contentResolver.delete(it, null, null) }
                }
                null -> Unit
            }
        }.onFailure { Log.w(TAG, "Failed to clean up partial export for ${target?.displayName}", it) }
        null
    }
}

internal data class CopyDigest(
    val byteCount: Long,
    val sha256: ByteArray,
)

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

internal fun sha256Range(
    input: InputStream,
    offsetBytes: Long,
    byteCount: Long,
    bufferSize: Int = FILE_COPY_BUFFER_BYTES,
): CopyDigest {
    require(offsetBytes >= 0L && byteCount >= 0L)
    require(bufferSize > 0) { "Digest buffer must be positive" }
    if (!input.skipFully(offsetBytes)) throw IOException("Unable to reach recording payload")
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(bufferSize)
    var remaining = byteCount
    var total = 0L
    while (remaining > 0L) {
        val requested = minOf(buffer.size.toLong(), remaining).toInt()
        val count = input.read(buffer, 0, requested)
        if (count < 0) throw IOException("Unexpected EOF verifying recording payload")
        if (count == 0) {
            val value = input.read()
            if (value < 0) throw IOException("Unexpected EOF verifying recording payload")
            digest.update(value.toByte())
            total++
            remaining--
            continue
        }
        digest.update(buffer, 0, count)
        total += count.toLong()
        remaining -= count.toLong()
    }
    return CopyDigest(total, digest.digest())
}

internal fun openRecordingInputStream(context: Context, recording: RecordingEntity): InputStream? =
    when (resolveRecordingStorageType(recording)) {
        RecordingStorageType.FILE -> FileInputStream(File(recording.id))
        RecordingStorageType.DOCUMENT,
        RecordingStorageType.MEDIASTORE,
        -> context.contentResolver.openInputStream(recording.id.toUri())
        null -> null
    }

internal fun recordingsHaveSameContent(
    context: Context,
    first: RecordingEntity,
    second: RecordingEntity,
): Boolean {
    if (first.sizeBytes > 0L && second.sizeBytes > 0L && first.sizeBytes != second.sizeBytes) return false
    return runCatching {
        val firstDigest = openRecordingInputStream(context, first)?.use(::sha256) ?: return@runCatching false
        val secondDigest = openRecordingInputStream(context, second)?.use(::sha256) ?: return@runCatching false
        firstDigest.byteCount == secondDigest.byteCount &&
            firstDigest.sha256.contentEquals(secondDigest.sha256)
    }.onFailure { Log.w(TAG, "Unable to compare recordings ${first.id} and ${second.id}", it) }
        .getOrDefault(false)
}

@Throws(IOException::class)
internal fun verifyOutputTargetPrefix(
    context: Context,
    target: RecordingOutputTarget,
    expectedPrefix: ByteArray,
) {
    val input = when (target.storageType) {
        RecordingStorageType.FILE -> FileInputStream(requireNotNull(target.file))
        RecordingStorageType.DOCUMENT,
        RecordingStorageType.MEDIASTORE,
        -> context.contentResolver.openInputStream(requireNotNull(target.uri))
    } ?: throw IOException("Unable to reopen exported recording")
    val observed = ByteArray(expectedPrefix.size)
    input.use { source ->
        if (!source.readFully(observed)) throw IOException("Unexpected EOF verifying recording header")
    }
    if (!observed.contentEquals(expectedPrefix)) {
        throw IOException("Export header verification failed: ${target.displayName}")
    }
}

@Throws(IOException::class)
internal fun verifyOutputTargetPayloadDigest(
    context: Context,
    target: RecordingOutputTarget,
    payloadOffsetBytes: Long,
    payloadBytes: Long,
    expectedSha256: ByteArray,
) {
    val input = when (target.storageType) {
        RecordingStorageType.FILE -> FileInputStream(requireNotNull(target.file))
        RecordingStorageType.DOCUMENT,
        RecordingStorageType.MEDIASTORE,
        -> context.contentResolver.openInputStream(requireNotNull(target.uri))
    } ?: throw IOException("Unable to reopen exported recording")
    val observed = input.use { sha256Range(it, payloadOffsetBytes, payloadBytes) }
    if (observed.byteCount != payloadBytes || !observed.sha256.contentEquals(expectedSha256)) {
        throw IOException("Export payload verification failed: ${target.displayName}")
    }
}

fun listCurrentOutputDirectoryRecordings(
    context: Context,
    knownRecordings: Map<String, RecordingEntity> = emptyMap(),
): List<RecordingEntity> = listOutputDirectoryRecordings(
    context = context,
    treeUri = getConfiguredExportTreeUri(context),
    knownRecordings = knownRecordings,
)

internal fun listOutputDirectoryRecordings(
    context: Context,
    treeUri: Uri?,
    knownRecordings: Map<String, RecordingEntity> = emptyMap(),
): List<RecordingEntity> {
    if (treeUri == null) {
        return if (usesMediaStoreDefaultStorage()) {
            listMediaStoreRecordings(context, knownRecordings)
        } else {
            listFileDirectoryRecordings(context, getSharedMusicRecordingsDirectory(), knownRecordings)
        }
    }
    return listDocumentTreeRecordings(context, treeUri, knownRecordings)
}

internal fun listLegacyAppStorageRecordings(
    context: Context,
    knownRecordings: Map<String, RecordingEntity> = emptyMap(),
): List<RecordingEntity> = listFileDirectoryRecordings(
    context,
    getSavedRecordingsDirectory(context),
    knownRecordings,
)

private fun listFileDirectoryRecordings(
    context: Context,
    directory: File,
    knownRecordings: Map<String, RecordingEntity>,
): List<RecordingEntity> {
    val files = directory.listFiles() ?: if (!directory.exists()) {
        emptyArray()
    } else {
        throw IOException("Unable to list recordings directory: ${directory.absolutePath}")
    }
    return files.asSequence()
        .filter { it.isFile && it.length() > 0L && !it.isHidden }
        .filter { isSupportedRecordingName(it.name) }
        .map { file ->
            val id = file.absolutePath
            val size = file.length()
            val existing = knownRecordings[id]
            if (
                existing != null && existing.durationMillis > 0L &&
                existing.displayName == file.name && existing.sizeBytes == size
            ) {
                existing
            } else {
                val media = inspectRecordingMedia(file)
                RecordingEntity(
                    id = id,
                    displayName = file.name,
                    mimeType = guessMimeType(file.name),
                    startedAtMillis = resolveRecordingStartTimeMillis(file),
                    durationMillis = media.durationMillis.coerceAtLeast(0L),
                    sizeBytes = size,
                    codecSummary = media.codecSummary,
                    storageType = RecordingStorageType.FILE.name,
                    directoryId = directory.absolutePath,
                )
            }
        }
        .toList()
}

private fun listDocumentTreeRecordings(
    context: Context,
    treeUri: Uri,
    knownRecordings: Map<String, RecordingEntity>,
): List<RecordingEntity> = runCatching {
    val tree = DocumentFile.fromTreeUri(context, treeUri)
        ?: throw IOException("Unable to access output directory $treeUri")
    tree.listFiles()
        .asSequence()
        .filter { it.isFile }
        .filter { file -> isSupportedRecordingName(file.name.orEmpty()) }
        .mapNotNull { file ->
            val uri = file.uri
            val name = file.name ?: return@mapNotNull null
            val size = file.length().coerceAtLeast(0L)
            val existing = knownRecordings[uri.toString()]
            if (
                existing != null && existing.durationMillis > 0L && existing.displayName == name &&
                (size == 0L || existing.sizeBytes == size)
            ) {
                existing
            } else {
                val media = inspectRecordingMedia(context, uri, name)
                RecordingEntity(
                    id = uri.toString(),
                    displayName = name,
                    mimeType = file.type ?: guessMimeType(name),
                    startedAtMillis = resolveRecordingStartTimeMillis(name, file.lastModified()),
                    durationMillis = media.durationMillis.coerceAtLeast(0L),
                    sizeBytes = size,
                    codecSummary = media.codecSummary,
                    storageType = RecordingStorageType.DOCUMENT.name,
                    directoryId = treeUri.toString(),
                )
            }
        }
        .toList()
}.onFailure { Log.w(TAG, "Unable to list recording directory $treeUri", it) }.getOrDefault(emptyList())

private fun listMediaStoreRecordings(
    context: Context,
    knownRecordings: Map<String, RecordingEntity>,
): List<RecordingEntity> {
    if (!usesMediaStoreDefaultStorage()) return emptyList()
    val resolver = context.contentResolver
    val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    val projection = arrayOf(
        MediaStore.MediaColumns._ID,
        MediaStore.MediaColumns.DISPLAY_NAME,
        MediaStore.MediaColumns.MIME_TYPE,
        MediaStore.MediaColumns.SIZE,
        MediaStore.MediaColumns.DATE_MODIFIED,
        MediaStore.Audio.AudioColumns.DURATION,
        MediaStore.MediaColumns.IS_PENDING,
    )
    return runCatching {
        resolver.query(
            collection,
            projection,
            "${MediaStore.MediaColumns.RELATIVE_PATH}=?",
            arrayOf(MEDIA_STORE_RELATIVE_PATH),
            "${MediaStore.MediaColumns.DATE_MODIFIED} DESC",
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val mimeIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val modifiedIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val durationIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.AudioColumns.DURATION)
            val pendingIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.IS_PENDING)
            buildList {
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameIndex) ?: continue
                    if (!isSupportedRecordingName(name)) continue
                    val uri = ContentUris.withAppendedId(collection, cursor.getLong(idIndex))
                    val size = cursor.getLong(sizeIndex).coerceAtLeast(0L)
                    val pending = cursor.getInt(pendingIndex) != 0
                    val reportedDuration = cursor.getLong(durationIndex).coerceAtLeast(0L)
                    // Pending rows are crash artifacts until the audio itself proves complete.
                    // Never publish a merely non-empty partial file.
                    val media = if (pending || reportedDuration <= 0L) {
                        inspectRecordingMedia(context, uri, name)
                    } else {
                        null
                    }
                    if (pending) {
                        if (!canRecoverPendingMedia(size, media?.durationMillis ?: 0L)) continue
                        val published = runCatching { publishMediaStoreUri(context, uri) }
                            .onFailure { Log.w(TAG, "Unable to republish recovered recording $uri", it) }
                            .isSuccess
                        if (!published) continue
                    }
                    val id = uri.toString()
                    val existing = knownRecordings[id]
                    if (
                        existing != null && existing.durationMillis > 0L &&
                        existing.displayName == name && (size == 0L || existing.sizeBytes == size)
                    ) {
                        add(existing)
                        continue
                    }
                    add(
                        RecordingEntity(
                            id = id,
                            displayName = name,
                            mimeType = cursor.getString(mimeIndex) ?: guessMimeType(name),
                            startedAtMillis = resolveRecordingStartTimeMillis(
                                name,
                                cursor.getLong(modifiedIndex).coerceAtLeast(0L) * 1000L,
                            ),
                            durationMillis = reportedDuration.takeIf { it > 0L }
                                ?: media?.durationMillis?.coerceAtLeast(0L)
                                ?: 0L,
                            sizeBytes = size,
                            codecSummary = media?.codecSummary ?: resolveRecordingCodecInfo(
                                extension = name.substringAfterLast('.', ""),
                                bitrate = null,
                                sampleRate = null,
                            ),
                            storageType = RecordingStorageType.MEDIASTORE.name,
                            directoryId = MEDIA_STORE_DIRECTORY_ID,
                        ),
                    )
                }
            }
        } ?: emptyList()
    }.onFailure { Log.w(TAG, "Unable to list MediaStore recordings", it) }.getOrDefault(emptyList())
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


private fun mediaStoreNameExists(context: Context, displayName: String): Boolean {
    if (!usesMediaStoreDefaultStorage()) return false
    return context.contentResolver.query(
        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
        arrayOf(MediaStore.MediaColumns._ID),
        "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?",
        arrayOf(MEDIA_STORE_RELATIVE_PATH, displayName),
        null,
    )?.use { it.moveToFirst() } == true
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

internal enum class RecoverableDirectoryState {
    HAS_RECORDINGS,
    EMPTY_OF_RECORDINGS,
    UNAVAILABLE,
}

internal fun inspectRecoverableDocumentDirectory(
    context: Context,
    treeUri: Uri,
): RecoverableDirectoryState {
    return try {
        val tree = DocumentFile.fromTreeUri(context, treeUri) ?: return RecoverableDirectoryState.UNAVAILABLE
        if (tree.listFiles().any { it.isFile && isSupportedRecordingName(it.name.orEmpty()) }) {
            RecoverableDirectoryState.HAS_RECORDINGS
        } else {
            RecoverableDirectoryState.EMPTY_OF_RECORDINGS
        }
    } catch (error: Exception) {
        Log.w(TAG, "Unable to inspect recording directory $treeUri", error)
        RecoverableDirectoryState.UNAVAILABLE
    }
}

internal fun isSupportedRecordingName(name: String): Boolean =
    name.substringAfterLast('.', "").lowercase() in SUPPORTED_RECORDING_EXTENSIONS

internal fun canRecoverPendingMedia(sizeBytes: Long, durationMillis: Long): Boolean =
    sizeBytes > 0L && durationMillis > 0L

private fun createLocalOutputTarget(
    context: Context,
    requestedDisplayName: String,
    mimeType: String,
    startedAtMillis: Long,
    storageDir: File = getSavedRecordingsDirectory(context),
): RecordingOutputTarget {
    if (!storageDir.exists() && !storageDir.mkdirs() && !storageDir.exists()) {
        throw IOException("Unable to create recordings directory: ${storageDir.absolutePath}")
    }

    // Document-provider display names are metadata, not trusted filesystem paths.
    // A name such as "../recording.wav" must never escape app-local storage when
    // recordings are moved from SAF back into the app directory.
    val safeDisplayName = sanitizeBaseName(requestedDisplayName)
    val dotIndex = safeDisplayName.lastIndexOf('.')
    val name = if (dotIndex > 0) safeDisplayName.substring(0, dotIndex) else safeDisplayName
    val extension = if (dotIndex > 0) safeDisplayName.substring(dotIndex) else ""
    var suffix = 1
    var uniqueName: String
    var file: File
    while (true) {
        uniqueName = if (suffix == 1) safeDisplayName else "$name ($suffix)$extension"
        file = File(storageDir, uniqueName)
        if (file.createNewFile()) break
        suffix++
    }
    return RecordingOutputTarget(
        id = file.absolutePath,
        displayName = uniqueName,
        mimeType = mimeType,
        storageType = RecordingStorageType.FILE,
        directoryId = storageDir.absolutePath,
        startedAtMillis = startedAtMillis,
        file = file,
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
            if (parent.absolutePath == getSharedMusicRecordingsDirectory().absolutePath) {
                MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(recording.mimeType), null)
            }
            return recording.copy(
                id = target.absolutePath,
                displayName = uniqueName,
            )
        } catch (_: FileAlreadyExistsException) {
            suffix++
        } catch (_: IOException) {
            return null
        } catch (_: SecurityException) {
            return null
        }
    }
    return null
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
    recording.copy(
        displayName = queryContentDisplayName(context, uri)?.takeIf { it.isNotBlank() } ?: uniqueName,
    )
}.onFailure { Log.w(TAG, "Unable to rename MediaStore recording ${recording.id}", it) }.getOrNull()

private fun renameDocumentRecording(
    context: Context,
    recording: RecordingEntity,
    displayName: String,
): RecordingEntity? {
    return runCatching {
        val document = DocumentFile.fromSingleUri(context, recording.id.toUri()) ?: return@runCatching null
        val tree = DocumentFile.fromTreeUri(context, recording.directoryId.toUri()) ?: return@runCatching null
        val uniqueName = findAvailableDisplayName(displayName) { candidate ->
            tree.findFile(candidate)?.uri?.let { it != document.uri } == true
        }
        if (uniqueName == document.name) {
            return@runCatching recording
        }
        val renamedUri = DocumentsContract.renameDocument(context.contentResolver, document.uri, uniqueName)
            ?: return@runCatching null
        recording.copy(
            id = renamedUri.toString(),
            displayName = DocumentFile.fromSingleUri(context, renamedUri)?.name ?: uniqueName,
        )
    }.onFailure { Log.w(TAG, "Unable to rename recording ${recording.id}", it) }.getOrNull()
}

private fun createMediaStoreOutputTarget(
    context: Context,
    requestedDisplayName: String,
    mimeType: String,
    startedAtMillis: Long,
): RecordingOutputTarget {
    check(usesMediaStoreDefaultStorage()) { "MediaStore output requires Android 10+" }
    val safeDisplayName = sanitizeBaseName(requestedDisplayName)
    val uniqueName = findAvailableDisplayName(safeDisplayName) { candidate ->
        mediaStoreNameExists(context, candidate)
    }
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, uniqueName)
        put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
        put(MediaStore.MediaColumns.RELATIVE_PATH, MEDIA_STORE_RELATIVE_PATH)
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }
    val uri = context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
        ?: throw IOException("Unable to create MediaStore recording")
    return RecordingOutputTarget(
        id = uri.toString(),
        displayName = queryContentDisplayName(context, uri)?.takeIf { it.isNotBlank() } ?: uniqueName,
        mimeType = mimeType,
        storageType = RecordingStorageType.MEDIASTORE,
        directoryId = MEDIA_STORE_DIRECTORY_ID,
        startedAtMillis = startedAtMillis,
        uri = uri,
    )
}

private fun createDocumentOutputTarget(
    context: Context,
    treeUri: Uri,
    requestedDisplayName: String,
    mimeType: String,
    startedAtMillis: Long,
): RecordingOutputTarget {
    val tree = DocumentFile.fromTreeUri(context, treeUri)
        ?: throw IOException("Unable to access output directory")
    val uniqueName = findAvailableDisplayName(requestedDisplayName) { candidate ->
        tree.findFile(candidate) != null
    }
    val documentUri = DocumentsContract.createDocument(
        context.contentResolver,
        DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri)),
        mimeType,
        uniqueName,
    ) ?: throw IOException("Unable to create output document")
    val actualDisplayName = DocumentFile.fromSingleUri(context, documentUri)?.name
        ?.takeIf { it.isNotBlank() }
        ?: uniqueName

    return RecordingOutputTarget(
        id = documentUri.toString(),
        displayName = actualDisplayName,
        mimeType = mimeType,
        storageType = RecordingStorageType.DOCUMENT,
        directoryId = treeUri.toString(),
        startedAtMillis = startedAtMillis,
        uri = documentUri,
    )
}

private fun findAvailableDisplayName(
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
    return sanitized.ifEmpty { ReverbConfig.FALLBACK_DISPLAY_NAME }
}

private fun guessMimeType(displayName: String): String {
    val ext = displayName.substringAfterLast('.', "").lowercase()
    return ExportFormat.entries.firstOrNull { it.extension == ext }?.outputMimeType
        ?: ReverbConfig.FALLBACK_MIME_TYPE_AUDIO
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
