package app.smallthingz.reverb

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.util.AtomicFile
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.CRC32

internal data class RetentionConfiguration(
    val mode: RetentionMode,
    val oneShotSeconds: Long,
    val oneShotSizeBytes: Long,
    val loopingSeconds: Long,
    val loopingSizeBytes: Long,
)

internal data class RetentionPreferenceValues(
    val modePresent: Boolean,
    val modeCode: Int?,
    val oneShotSeconds: Long?,
    val oneShotSizeBytes: Long?,
    val loopingSeconds: Long?,
    val loopingSizeBytes: Long?,
    val digestPresent: Boolean = false,
    val digest: String? = null,
)

internal enum class RetentionConfigurationSource {
    PREFERENCES,
    RECOVERY,
    DEFAULTS,
}

internal data class ResolvedRetentionConfiguration(
    val configuration: RetentionConfiguration,
    val source: RetentionConfigurationSource,
)

internal enum class RetentionRecoveryReadState {
    MISSING,
    VALID,
    INVALID,
}

internal data class RetentionRecoveryRead(
    val state: RetentionRecoveryReadState,
    val configuration: RetentionConfiguration? = null,
)

internal fun legacyRetentionPreferencesAllowed(recoveryState: RetentionRecoveryReadState): Boolean =
    recoveryState == RetentionRecoveryReadState.MISSING

private val retentionPersistenceLock = Any()

internal fun <T> withRetentionPersistenceLock(block: () -> T): T =
    synchronized(retentionPersistenceLock) { block() }

internal fun persistRetentionTransaction(
    writeNewRecovery: () -> Boolean,
    commitNewPreferences: () -> Boolean,
    restoreRecovery: () -> Boolean,
    restorePreferences: () -> Boolean,
): Boolean = withRetentionPersistenceLock {
    val recoveryWritten = try {
        writeNewRecovery()
    } catch (error: Throwable) {
        restoreRetentionTransactionAfterException(
            primary = error,
            restoreRecovery = restoreRecovery,
            restorePreferences = null,
        )
        throw error
    }
    if (!recoveryWritten) {
        restoreRetentionTransactionAfterFailure(
            restoreRecovery = restoreRecovery,
            restorePreferences = null,
        )?.let { throw it }
        return@withRetentionPersistenceLock false
    }

    val preferencesCommitted = try {
        commitNewPreferences()
    } catch (error: Throwable) {
        restoreRetentionTransactionAfterException(
            primary = error,
            restoreRecovery = restoreRecovery,
            restorePreferences = restorePreferences,
        )
        throw error
    }
    if (!preferencesCommitted) {
        restoreRetentionTransactionAfterFailure(
            restoreRecovery = restoreRecovery,
            restorePreferences = restorePreferences,
        )?.let { throw it }
        return@withRetentionPersistenceLock false
    }
    true
}

private fun restoreRetentionTransactionAfterFailure(
    restoreRecovery: () -> Boolean,
    restorePreferences: (() -> Boolean)?,
): Throwable? {
    var failure: Throwable? = null

    fun attempt(label: String, restore: () -> Boolean) {
        try {
            if (!restore()) {
                throw IllegalStateException("$label rollback returned false")
            }
        } catch (restoreError: Throwable) {
            val primary = failure
            if (primary == null) {
                failure = restoreError
            } else if (restoreError !== primary) {
                primary.addSuppressed(restoreError)
            }
        }
    }

    attempt("Retention recovery", restoreRecovery)
    restorePreferences?.let { restore -> attempt("Retention preferences", restore) }
    return failure
}

private fun restoreRetentionTransactionAfterException(
    primary: Throwable,
    restoreRecovery: () -> Boolean,
    restorePreferences: (() -> Boolean)?,
) {
    val rollbackFailure = restoreRetentionTransactionAfterFailure(
        restoreRecovery = restoreRecovery,
        restorePreferences = restorePreferences,
    ) ?: return
    if (rollbackFailure !== primary) {
        primary.addSuppressed(rollbackFailure)
        rollbackFailure.suppressed.forEach { suppressed ->
            if (suppressed !== primary && suppressed !== rollbackFailure) primary.addSuppressed(suppressed)
        }
    }
}

internal fun defaultRetentionConfiguration(): RetentionConfiguration = RetentionConfiguration(
    mode = RetentionMode.SIZE,
    oneShotSeconds = DEFAULT_RETENTION_SECONDS,
    oneShotSizeBytes = DEFAULT_RETENTION_SIZE_BYTES,
    loopingSeconds = DEFAULT_RETENTION_SECONDS,
    loopingSizeBytes = DEFAULT_RETENTION_SIZE_BYTES,
)

internal fun readRetentionPreferenceValues(prefs: SharedPreferences): RetentionPreferenceValues =
    RetentionPreferenceValues(
        modePresent = prefs.contains(PrefKey.RETENTION_MODE),
        modeCode = if (prefs.contains(PrefKey.RETENTION_MODE)) {
            prefs.safeInt(PrefKey.RETENTION_MODE, Int.MIN_VALUE).takeUnless { it == Int.MIN_VALUE }
        } else null,
        oneShotSeconds = safePreferenceLong(prefs, PrefKey.ONE_SHOT_RETENTION_SECONDS),
        oneShotSizeBytes = safePreferenceLong(prefs, PrefKey.ONE_SHOT_AUDIO_MEMORY_SIZE),
        loopingSeconds = safePreferenceLong(prefs, PrefKey.RETENTION_SECONDS),
        loopingSizeBytes = safePreferenceLong(prefs, PrefKey.AUDIO_MEMORY_SIZE),
        digestPresent = prefs.contains(PrefKey.RETENTION_CONFIG_DIGEST),
        digest = if (prefs.contains(PrefKey.RETENTION_CONFIG_DIGEST)) {
            prefs.safeString(PrefKey.RETENTION_CONFIG_DIGEST)
        } else null,
    )

private fun safePreferenceLong(prefs: SharedPreferences, key: PrefKey): Long? {
    if (!prefs.contains(key)) return null
    return prefs.safeLong(key, Long.MIN_VALUE).takeIf { it >= 0L }
}

internal fun retentionConfigurationFromPreferences(
    values: RetentionPreferenceValues,
    recoveryFallback: RetentionConfiguration? = null,
    allowLegacyWithoutDigest: Boolean = true,
): RetentionConfiguration? {
    if (listOf(
            values.oneShotSeconds,
            values.oneShotSizeBytes,
            values.loopingSeconds,
            values.loopingSizeBytes,
        ).any { value -> value != null && value < 0L }
    ) {
        return null
    }
    val mode = if (values.modePresent) {
        values.modeCode?.let(RetentionMode::fromStorageOrNull) ?: return null
    } else {
        val completeSize = values.oneShotSizeBytes != null && values.loopingSizeBytes != null
        val completeTime = values.oneShotSeconds != null && values.loopingSeconds != null
        when {
            completeSize && !completeTime -> RetentionMode.SIZE
            completeTime && !completeSize -> RetentionMode.TIME
            else -> return null
        }
    }

    when (mode) {
        RetentionMode.SIZE -> if (values.oneShotSizeBytes == null || values.loopingSizeBytes == null) return null
        RetentionMode.TIME -> if (values.oneShotSeconds == null || values.loopingSeconds == null) return null
    }

    val defaults = defaultRetentionConfiguration()
    val configuration = RetentionConfiguration(
        mode = mode,
        oneShotSeconds = values.oneShotSeconds
            ?: recoveryFallback?.oneShotSeconds
            ?: defaults.oneShotSeconds,
        oneShotSizeBytes = values.oneShotSizeBytes
            ?: recoveryFallback?.oneShotSizeBytes
            ?: defaults.oneShotSizeBytes,
        loopingSeconds = values.loopingSeconds
            ?: recoveryFallback?.loopingSeconds
            ?: defaults.loopingSeconds,
        loopingSizeBytes = values.loopingSizeBytes
            ?: recoveryFallback?.loopingSizeBytes
            ?: defaults.loopingSizeBytes,
    )
    if (values.digestPresent) {
        val observed = values.digest?.lowercase()?.takeIf(::isSha256Hex) ?: return null
        if (observed != retentionConfigurationDigest(configuration)) return null
    } else if (!allowLegacyWithoutDigest) {
        return null
    }
    return configuration
}

private data class RetentionReadSnapshot(
    val values: RetentionPreferenceValues,
    val recoveryRead: RetentionRecoveryRead,
) {
    val recovery: RetentionConfiguration? get() = recoveryRead.configuration

    fun primaryWithRecoveryFallback(): RetentionConfiguration? = retentionConfigurationFromPreferences(
        values = values,
        recoveryFallback = recovery,
        allowLegacyWithoutDigest = legacyRetentionPreferencesAllowed(recoveryRead.state),
    )
}

private fun retentionReadSnapshot(context: Context): RetentionReadSnapshot = RetentionReadSnapshot(
    values = readRetentionPreferenceValues(getRecorderPreferences(context)),
    recoveryRead = readRetentionRecovery(context),
)

internal fun retentionConfigurationForOperationalRead(context: Context): RetentionConfiguration? =
    withRetentionPersistenceLock {
        val snapshot = retentionReadSnapshot(context)
        resolveRetentionConfiguration(
            primary = snapshot.primaryWithRecoveryFallback(),
            recovery = snapshot.recovery,
            historyExists = hasPersistedBufferHistoryArtifacts(context),
        )?.configuration
    }

internal fun retentionConfigurationForRead(context: Context): RetentionConfiguration =
    withRetentionPersistenceLock {
        val snapshot = retentionReadSnapshot(context)
        val verifiedPrimary = retentionConfigurationFromPreferences(
            values = snapshot.values,
            recoveryFallback = null,
            allowLegacyWithoutDigest = false,
        )
        val historyExists = verifiedPrimary != null && snapshot.recovery != null &&
            verifiedPrimary != snapshot.recovery && hasPersistedBufferHistoryArtifacts(context)
        preferredRetentionConfigurationForRead(verifiedPrimary, snapshot.recovery, historyExists)
            ?: snapshot.primaryWithRecoveryFallback()
            ?: snapshot.recovery
            ?: defaultRetentionConfiguration()
    }

internal fun preferredRetentionConfigurationForRead(
    verifiedPrimary: RetentionConfiguration?,
    recovery: RetentionConfiguration?,
    historyExists: Boolean,
): RetentionConfiguration? = when {
    verifiedPrimary != null && recovery != null && verifiedPrimary != recovery ->
        if (historyExists) recovery else verifiedPrimary
    verifiedPrimary != null -> verifiedPrimary
    recovery != null -> recovery
    else -> null
}

internal fun retentionMutationIsSafe(context: Context): Boolean =
    withRetentionPersistenceLock {
        val snapshot = retentionReadSnapshot(context)
        snapshot.primaryWithRecoveryFallback() != null || snapshot.recovery != null ||
            !hasPersistedBufferHistoryArtifacts(context)
    }

internal fun persistedBufferHistoryRootMayContainData(root: File): Boolean {
    val chunks = File(root, BUFFER_CHUNKS_FOLDER_NAME)
    return when (storageDirectoryState(chunks)) {
        StoragePathState.MISSING -> false
        StoragePathState.UNAVAILABLE -> true
        StoragePathState.PRESENT -> chunks.listFiles()
            ?.any { child -> storagePathMayContainData(storagePathState(child)) }
            ?: true
    }
}

internal fun hasPersistedBufferHistoryArtifacts(context: Context): Boolean {
    val roots = listOf(
        File(context.noBackupFilesDir, BUFFER_CACHE_FOLDER_NAME),
        File(context.noBackupFilesDir, ONE_SHOT_BUFFER_CACHE_FOLDER_NAME),
    )
    return roots.any(::persistedBufferHistoryRootMayContainData)
}

internal fun resolveRetentionConfiguration(
    primary: RetentionConfiguration?,
    recovery: RetentionConfiguration?,
    historyExists: Boolean,
): ResolvedRetentionConfiguration? = when {
    primary != null && recovery != null && primary != recovery && historyExists -> null
    primary != null -> ResolvedRetentionConfiguration(primary, RetentionConfigurationSource.PREFERENCES)
    recovery != null -> ResolvedRetentionConfiguration(recovery, RetentionConfigurationSource.RECOVERY)
    historyExists -> null
    else -> ResolvedRetentionConfiguration(defaultRetentionConfiguration(), RetentionConfigurationSource.DEFAULTS)
}

internal fun retentionConfigurationDigest(configuration: RetentionConfiguration): String =
    MessageDigest.getInstance("SHA-256")
        .digest(canonicalRetentionConfigurationBytes(configuration))
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

internal fun retentionPreferenceDigestMatches(
    values: RetentionPreferenceValues,
    configuration: RetentionConfiguration,
): Boolean {
    if (!values.digestPresent) return false
    val digest = values.digest?.lowercase()?.takeIf(::isSha256Hex) ?: return false
    return digest == retentionConfigurationDigest(configuration)
}

private fun isSha256Hex(value: String): Boolean =
    value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }

private fun canonicalRetentionConfigurationBytes(configuration: RetentionConfiguration): ByteArray {
    require(configuration.oneShotSeconds >= 0L)
    require(configuration.oneShotSizeBytes >= 0L)
    require(configuration.loopingSeconds >= 0L)
    require(configuration.loopingSizeBytes >= 0L)
    return ByteArray(RETENTION_RECOVERY_CRC_OFFSET).also { bytes ->
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(RETENTION_RECOVERY_MAGIC)
            putInt(RETENTION_RECOVERY_VERSION)
            putInt(configuration.mode.storageCode.toInt())
            putLong(configuration.oneShotSeconds)
            putLong(configuration.oneShotSizeBytes)
            putLong(configuration.loopingSeconds)
            putLong(configuration.loopingSizeBytes)
        }
    }
}

internal fun encodeRetentionRecoveryConfiguration(configuration: RetentionConfiguration): ByteArray {
    val bytes = canonicalRetentionConfigurationBytes(configuration).copyOf(RETENTION_RECOVERY_FILE_BYTES)
    ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        .putInt(RETENTION_RECOVERY_CRC_OFFSET, crc32(bytes, 0, RETENTION_RECOVERY_CRC_OFFSET))
    return bytes
}

internal fun decodeRetentionRecoveryConfiguration(bytes: ByteArray): RetentionConfiguration? {
    if (bytes.size != RETENTION_RECOVERY_FILE_BYTES) return null
    if (readIntLittleEndian(bytes, 0) != RETENTION_RECOVERY_MAGIC) return null
    if (readIntLittleEndian(bytes, 4) != RETENTION_RECOVERY_VERSION) return null
    if (readIntLittleEndian(bytes, RETENTION_RECOVERY_CRC_OFFSET) != crc32(bytes, 0, RETENTION_RECOVERY_CRC_OFFSET)) {
        return null
    }
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    buffer.position(8)
    val mode = RetentionMode.fromStorageOrNull(buffer.int) ?: return null
    val oneShotSeconds = buffer.long
    val oneShotSizeBytes = buffer.long
    val loopingSeconds = buffer.long
    val loopingSizeBytes = buffer.long
    if (oneShotSeconds < 0L || oneShotSizeBytes < 0L || loopingSeconds < 0L || loopingSizeBytes < 0L) return null
    return RetentionConfiguration(
        mode = mode,
        oneShotSeconds = oneShotSeconds,
        oneShotSizeBytes = oneShotSizeBytes,
        loopingSeconds = loopingSeconds,
        loopingSizeBytes = loopingSizeBytes,
    )
}

internal fun readBoundedRetentionRecoveryBytes(input: InputStream): ByteArray? {
    val bytes = ByteArray(RETENTION_RECOVERY_FILE_BYTES + 1)
    var offset = 0
    while (offset < bytes.size) {
        val count = input.read(bytes, offset, bytes.size - offset)
        when {
            count < 0 -> break
            count > 0 -> offset += count
            else -> {
                // InputStream permits a zero-length progress result. Force one-byte progress so
                // a malformed/custom stream cannot spin startup recovery forever.
                val value = input.read()
                if (value < 0) break
                bytes[offset++] = value.toByte()
            }
        }
    }
    if (offset > RETENTION_RECOVERY_FILE_BYTES) return null
    return bytes.copyOf(offset)
}

internal fun readRetentionRecovery(context: Context): RetentionRecoveryRead {
    val baseFile = retentionRecoveryFile(context)
    val atomicFile = AtomicFile(baseFile)
    when (atomicFileRegularBackingState(baseFile)) {
        StoragePathState.MISSING -> return RetentionRecoveryRead(RetentionRecoveryReadState.MISSING)
        StoragePathState.UNAVAILABLE -> return RetentionRecoveryRead(RetentionRecoveryReadState.INVALID)
        StoragePathState.PRESENT -> Unit
    }
    val bytes = try {
        // Let AtomicFile recover a regular backup/new-file state, then bind the descriptor it
        // actually opened back to the recovered regular base before consuming durable authority.
        atomicFile.openRead().use { input ->
            val descriptorIdentity = bindAtomicFileReadDescriptor(baseFile, input.fd)
                ?: return RetentionRecoveryRead(RetentionRecoveryReadState.INVALID)
            val value = readBoundedRetentionRecoveryBytes(input)
                ?: return RetentionRecoveryRead(RetentionRecoveryReadState.INVALID)
            if (!atomicFileReadDescriptorRemainsCurrent(baseFile, descriptorIdentity)) {
                return RetentionRecoveryRead(RetentionRecoveryReadState.INVALID)
            }
            value
        }
    } catch (_: FileNotFoundException) {
        return RetentionRecoveryRead(
            if (atomicFileRegularBackingState(baseFile) == StoragePathState.MISSING) {
                RetentionRecoveryReadState.MISSING
            } else {
                RetentionRecoveryReadState.INVALID
            },
        )
    } catch (_: Exception) {
        return RetentionRecoveryRead(RetentionRecoveryReadState.INVALID)
    }
    val configuration = decodeRetentionRecoveryConfiguration(bytes)
        ?: return RetentionRecoveryRead(RetentionRecoveryReadState.INVALID)
    return RetentionRecoveryRead(RetentionRecoveryReadState.VALID, configuration)
}

internal inline fun atomicWriteFailureResult(
    primary: Exception,
    rollback: () -> Unit,
): Boolean {
    try {
        rollback()
    } catch (rollbackError: Exception) {
        if (rollbackError !== primary) primary.addSuppressed(rollbackError)
        throw primary
    }
    return false
}

internal fun writeRetentionRecoveryConfiguration(
    context: Context,
    configuration: RetentionConfiguration,
): Boolean {
    val baseFile = retentionRecoveryFile(context)
    if (atomicFileRegularBackingState(baseFile) == StoragePathState.UNAVAILABLE) return false
    val atomicFile = AtomicFile(baseFile)
    val bytes = runCatching { encodeRetentionRecoveryConfiguration(configuration) }.getOrNull() ?: return false
    var output: java.io.FileOutputStream? = null
    return try {
        output = atomicFile.startWrite()
        output.write(bytes)
        output.fd.sync()
        atomicFile.finishWrite(output)
        output = null
        atomicFileRegularBackingState(baseFile) == StoragePathState.PRESENT &&
            syncRetentionRecoveryDirectory(context)
    } catch (error: Exception) {
        val failedOutput = output
        atomicWriteFailureResult(error) {
            if (failedOutput != null) atomicFile.failWrite(failedOutput)
        }
    }
}

@SuppressLint("UseKtx") // commit() Boolean is required by the retention transaction.
internal fun restoreRetentionConfigurationToPreferences(
    prefs: SharedPreferences,
    configuration: RetentionConfiguration,
): Boolean = prefs.edit()
    .putInt(PrefKey.RETENTION_MODE, configuration.mode.storageCode.toInt())
    .putLong(PrefKey.ONE_SHOT_RETENTION_SECONDS, configuration.oneShotSeconds)
    .putLong(PrefKey.ONE_SHOT_AUDIO_MEMORY_SIZE, configuration.oneShotSizeBytes)
    .putLong(PrefKey.RETENTION_SECONDS, configuration.loopingSeconds)
    .putLong(PrefKey.AUDIO_MEMORY_SIZE, configuration.loopingSizeBytes)
    .putString(PrefKey.RETENTION_CONFIG_DIGEST, retentionConfigurationDigest(configuration))
    .commit()

private fun retentionRecoveryFile(context: Context): File =
    File(context.noBackupFilesDir, RETENTION_RECOVERY_FILE_NAME)

internal inline fun durableBarrierAndCloseSucceeded(
    barrier: () -> Unit,
    close: () -> Unit,
): Boolean {
    var failure: Throwable? = null
    try {
        barrier()
    } catch (error: Throwable) {
        failure = error
    }
    failure = closePreservingPrimaryFailure(failure, close)
    return failure == null
}

private fun syncRetentionRecoveryDirectory(context: Context): Boolean {
    val directory = context.noBackupFilesDir
    val descriptor = runCatching {
        Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
    }.getOrNull() ?: return false
    return durableBarrierAndCloseSucceeded(
        barrier = { Os.fsync(descriptor) },
        close = { Os.close(descriptor) },
    )
}

private fun crc32(bytes: ByteArray, offset: Int, count: Int): Int = CRC32().run {
    update(bytes, offset, count)
    value.toInt()
}

private fun readIntLittleEndian(bytes: ByteArray, offset: Int): Int =
    (bytes[offset].toInt() and 0xff) or
        ((bytes[offset + 1].toInt() and 0xff) shl 8) or
        ((bytes[offset + 2].toInt() and 0xff) shl 16) or
        (bytes[offset + 3].toInt() shl 24)

private const val RETENTION_RECOVERY_FILE_NAME = "retention-config.v1"
private const val RETENTION_RECOVERY_MAGIC = 0x5254524e // RTRN
private const val RETENTION_RECOVERY_VERSION = 1
private const val RETENTION_RECOVERY_CRC_OFFSET = 44
private const val RETENTION_RECOVERY_FILE_BYTES = 48
