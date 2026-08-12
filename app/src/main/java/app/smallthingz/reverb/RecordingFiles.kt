package app.smallthingz.reverb

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes

private val TAG = "RecordingFiles"
private val ILLEGAL_FILENAME_CHARS = setOf('\\', '/', '*', '?', '"', '<', '>', '|')
private val SUPPORTED_RECORDING_EXTENSIONS = ExportFormat.entries.map { it.extension }.toSet()
private const val FILE_COPY_BUFFER_BYTES = 128 * 1024


enum class RecordingStorageType {
    FILE,
    DOCUMENT,
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

fun getSavedRecordingsDirectory(context: Context): File {
    val baseDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
        ?: File(context.filesDir, "recordings")
    return File(baseDir, ReverbConfig.APP_STORAGE_FOLDER_NAME)
}

fun getConfiguredExportTreeUri(context: Context): Uri? {
    val raw = getRecorderPreferences(context).getString(PrefKey.EXPORT_DIRECTORY_URI, null) ?: return null
    return raw.takeIf { it.isNotBlank() }?.let(Uri::parse)
}

fun setConfiguredExportTreeUri(
    context: Context,
    treeUri: Uri?,
) {
    val editor = getRecorderPreferences(context).edit()
    if (treeUri != null) {
        editor.putString(PrefKey.EXPORT_DIRECTORY_URI, treeUri.toString())
    } else {
        editor.remove(PrefKey.EXPORT_DIRECTORY_URI)
    }
    editor.apply()
}

fun getConfiguredOutputDirectoryId(context: Context): String {
    return getOutputDirectoryId(context, getConfiguredExportTreeUri(context))
}

fun getOutputDirectoryId(
    context: Context,
    treeUri: Uri?,
): String {
    return treeUri?.toString() ?: getSavedRecordingsDirectory(context).absolutePath
}

fun describeConfiguredOutputDirectory(context: Context): String {
    return describeOutputDirectory(context, getConfiguredExportTreeUri(context))
}

fun describeOutputDirectory(
    context: Context,
    treeUri: Uri?,
): String {
    if (treeUri == null) {
        return "${context.getString(R.string.app_storage_label)}/${ReverbConfig.APP_STORAGE_FOLDER_NAME}"
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

        RecordingStorageType.DOCUMENT -> Uri.parse(recording.id)
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
            sampleRate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)?.toIntOrNull(),
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
            sampleRate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)?.toIntOrNull(),
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
    val documentUri = Uri.parse(recording.id)
    val directoryUri = recording.directoryId.takeIf { it.isNotBlank() }?.let(Uri::parse)

    describeDocumentIdPath(context, runCatching {
        DocumentsContract.getDocumentId(documentUri)
    }.onFailure { Log.w(TAG, "getDocumentId failed for $documentUri", it) }.getOrNull())?.let {
        return it
    }
    describeDocumentIdPath(context, directoryUri?.let {
        runCatching { DocumentsContract.getTreeDocumentId(it) }
            .onFailure { Log.w(TAG, "getTreeDocumentId failed for $it", it) }.getOrNull()
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

fun createOutputTarget(
    context: Context,
    requestedName: String?,
    startedAtMillis: Long,
    format: ExportFormat,
    codec: ExportCodec,
): RecordingOutputTarget {
    val baseName = sanitizeBaseName(
        if (requestedName.isNullOrBlank()) startedAtMillis.toString() else requestedName.trim(),
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
        createLocalOutputTarget(context, requestedDisplayName, mimeType, startedAtMillis)
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

        RecordingStorageType.DOCUMENT -> {
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
        RecordingStorageType.DOCUMENT -> {
            val uri = requireNotNull(target.uri)
            val documentSize = runCatching { DocumentFile.fromSingleUri(context, uri)?.length() ?: 0L }
                .onFailure { Log.w(TAG, "Document size query failed for $uri", it) }
                .getOrDefault(0L)
            if (documentSize > 0L) {
                documentSize
            } else {
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
    if (reportedSize > 0L) return reportedSize
    return countOutputTargetBytes(context, target, expectedBytes)
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
            val uri = runCatching { Uri.parse(recording.id) }.getOrNull()
                ?: return RecordingAssetState.MISSING
            try {
                context.contentResolver.query(
                    uri,
                    arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    if (cursor.moveToFirst()) RecordingAssetState.PRESENT else RecordingAssetState.MISSING
                } ?: RecordingAssetState.UNAVAILABLE
            } catch (error: Exception) {
                Log.w(TAG, "Unable to inspect recording ${recording.id}", error)
                RecordingAssetState.UNAVAILABLE
            }
        }

        null -> RecordingAssetState.MISSING
    }
}

fun deleteRecordingAsset(
    context: Context,
    recording: RecordingEntity,
): Boolean {
    when (recordingAssetState(context, recording)) {
        RecordingAssetState.MISSING -> return true
        RecordingAssetState.UNAVAILABLE -> return false
        RecordingAssetState.PRESENT -> Unit
    }
    return when (resolveRecordingStorageType(recording)) {
        RecordingStorageType.FILE -> {
            val file = File(recording.id)
            runCatching { file.delete() || !file.exists() }
                .onFailure { Log.w(TAG, "Unable to delete recording ${recording.id}", it) }
                .getOrDefault(false)
        }

        RecordingStorageType.DOCUMENT -> runCatching {
            val document = DocumentFile.fromSingleUri(context, Uri.parse(recording.id))
            document?.delete() == true || recordingAssetState(context, recording) == RecordingAssetState.MISSING
        }.onFailure { Log.w(TAG, "Unable to delete recording ${recording.id}", it) }.getOrDefault(false)
        null -> true
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
        RecordingStorageType.FILE -> renameFileRecording(recording, sanitized)
        RecordingStorageType.DOCUMENT -> renameDocumentRecording(context, recording, sanitized)
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
            RecordingStorageType.DOCUMENT -> {
                val uri = Uri.parse(recording.id)
                runCatching {
                    context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                        descriptor.statSize.takeIf { it > 0L }
                    }
                }.getOrNull() ?: DocumentFile.fromSingleUri(context, uri)?.length()?.takeIf { it > 0L }
            }
            null -> null
        }
        val input = when (resolveRecordingStorageType(recording)) {
            RecordingStorageType.FILE -> FileInputStream(File(recording.id))
            RecordingStorageType.DOCUMENT -> context.contentResolver.openInputStream(Uri.parse(recording.id))
            null -> throw IOException("Unknown recording storage type: ${recording.storageType}")
        } ?: throw IOException("Unable to open source recording")
        var copiedBytes = 0L
        input.use { source ->
            when (resolvedTarget.storageType) {
                RecordingStorageType.FILE -> {
                    FileOutputStream(requireNotNull(resolvedTarget.file)).use { output ->
                        copiedBytes = source.copyTo(output, FILE_COPY_BUFFER_BYTES)
                        output.fd.sync()
                    }
                }

                RecordingStorageType.DOCUMENT -> {
                    context.contentResolver.openOutputStream(requireNotNull(resolvedTarget.uri), "w")?.use { output ->
                        copiedBytes = source.copyTo(output, FILE_COPY_BUFFER_BYTES)
                        output.flush()
                    } ?: throw IOException("Unable to open target output stream")
                }
            }
        }
        if (copiedBytes <= 0L) {
            throw IOException("Recording source was empty")
        }
        if (sourceSize != null && copiedBytes != sourceSize) {
            throw IOException("Recording copy was incomplete: expected=$sourceSize copied=$copiedBytes")
        }
        val targetSize = resolveOutputTargetSize(context, resolvedTarget)
        val verifiedTargetSize = if (targetSize > 0L) {
            targetSize
        } else {
            countOutputTargetBytes(context, resolvedTarget, copiedBytes)
        }
        if (verifiedTargetSize != copiedBytes) {
            throw IOException("Recording copy size mismatch: copied=$copiedBytes target=$verifiedTargetSize")
        }

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
                RecordingStorageType.FILE -> target?.file?.delete()
                RecordingStorageType.DOCUMENT -> {
                    target?.uri?.let { DocumentFile.fromSingleUri(context, it)?.delete() }
                }
                null -> Unit
            }
        }.onFailure { Log.w(TAG, "Failed to clean up partial export for ${target?.displayName}", it) }
        null
    }
}

fun listCurrentOutputDirectoryRecordings(
    context: Context,
    knownRecordings: Map<String, RecordingEntity> = emptyMap(),
): List<RecordingEntity> {
    val treeUri = getConfiguredExportTreeUri(context)
    return if (treeUri == null) {
        val directory = getSavedRecordingsDirectory(context)
        val files = directory.listFiles() ?: if (!directory.exists()) {
            emptyArray()
        } else {
            throw IOException("Unable to list recordings directory: ${directory.absolutePath}")
        }
        files.asSequence()
            .filter { it.isFile && it.length() > 0L && !it.isHidden }
            .filter { it.extension.lowercase() in SUPPORTED_RECORDING_EXTENSIONS }
            .mapNotNull { file ->
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
                    if (media.durationMillis <= 0L) return@mapNotNull null
                    RecordingEntity(
                        id = id,
                        displayName = file.name,
                        mimeType = guessMimeType(file.name),
                        startedAtMillis = resolveRecordingStartTimeMillis(file),
                        durationMillis = media.durationMillis,
                        sizeBytes = size,
                        codecSummary = media.codecSummary,
                        storageType = RecordingStorageType.FILE.name,
                        directoryId = directory.absolutePath,
                    )
                }
            }
            .toList()
    } else {
        runCatching {
            val tree = DocumentFile.fromTreeUri(context, treeUri) ?: return@runCatching emptyList()
            tree.listFiles()
                .asSequence()
                .filter { it.isFile }
                .filter { file -> file.name?.substringAfterLast('.', "")?.lowercase() in SUPPORTED_RECORDING_EXTENSIONS }
                .mapNotNull { file ->
                    val uri = file.uri
                    val name = file.name ?: return@mapNotNull null
                    val id = uri.toString()
                    // A number of SAF providers use zero to mean "unknown size". Do not
                    // discard a readable recording just because metadata is incomplete.
                    val size = file.length().coerceAtLeast(0L)
                    val existing = knownRecordings[id]
                    if (
                        existing != null && existing.durationMillis > 0L && existing.displayName == name &&
                        (size == 0L || existing.sizeBytes == size)
                    ) {
                        existing
                    } else {
                        val media = inspectRecordingMedia(context, uri, name)
                        if (media.durationMillis <= 0L) return@mapNotNull null
                        RecordingEntity(
                            id = id,
                            displayName = name,
                            mimeType = file.type ?: guessMimeType(name),
                            startedAtMillis = resolveRecordingStartTimeMillis(name, file.lastModified()),
                            durationMillis = media.durationMillis,
                            sizeBytes = size,
                            codecSummary = media.codecSummary,
                            storageType = RecordingStorageType.DOCUMENT.name,
                            directoryId = treeUri.toString(),
                        )
                    }
                }
                .toList()
        }.onFailure { Log.w(TAG, "Unable to list configured output directory $treeUri", it) }.getOrDefault(emptyList())
    }
}

private fun createLocalOutputTarget(
    context: Context,
    requestedDisplayName: String,
    mimeType: String,
    startedAtMillis: Long,
): RecordingOutputTarget {
    val storageDir = getSavedRecordingsDirectory(context)
    if (!storageDir.exists() && !storageDir.mkdirs() && !storageDir.exists()) {
        throw IOException("Unable to create recordings directory: ${storageDir.absolutePath}")
    }

    val dotIndex = requestedDisplayName.lastIndexOf('.')
    val name = if (dotIndex > 0) requestedDisplayName.substring(0, dotIndex) else requestedDisplayName
    val extension = if (dotIndex > 0) requestedDisplayName.substring(dotIndex) else ""
    var suffix = 1
    var uniqueName: String
    var file: File
    while (true) {
        uniqueName = if (suffix == 1) requestedDisplayName else "$name ($suffix)$extension"
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

private fun renameDocumentRecording(
    context: Context,
    recording: RecordingEntity,
    displayName: String,
): RecordingEntity? {
    return runCatching {
        val document = DocumentFile.fromSingleUri(context, Uri.parse(recording.id)) ?: return@runCatching null
        val tree = DocumentFile.fromTreeUri(context, Uri.parse(recording.directoryId)) ?: return@runCatching null
        val uniqueName = findAvailableDisplayName(displayName) { candidate ->
            candidate != document.name && tree.findFile(candidate) != null
        }
        if (uniqueName == document.name) {
            return@runCatching recording
        }
        if (!document.renameTo(uniqueName)) {
            return@runCatching null
        }
        recording.copy(
            id = document.uri.toString(),
            displayName = document.name ?: uniqueName,
        )
    }.onFailure { Log.w(TAG, "Unable to rename recording ${recording.id}", it) }.getOrNull()
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
        RecordingStorageType.DOCUMENT -> context.contentResolver.openInputStream(requireNotNull(target.uri))
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
