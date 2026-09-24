@file:JvmName("RecorderPreferences")

package app.smallthingz.reverb

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.PowerManager
import androidx.annotation.StringRes
import androidx.core.content.edit
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

private const val PCM_WAV_HEADER_BYTES = 44L
private const val FLOAT_WAV_HEADER_BYTES = 58L
private const val WAV_MAX_RIFF_CHUNK_BYTES = 0xFFFF_FFFFL
private const val WAV_RIFF_SIZE_EXCLUDED_PREFIX_BYTES = 8L

private val STANDARD_SAMPLE_RATES =
    listOf(96_000, 88_200, 64_000, 48_000, 44_100, 32_000, 24_000, 22_050, 16_000, 12_000, 11_025, 8_000, 7_350)
private val SUPPORTED_EXPORT_FORMATS = listOf(ExportFormat.WAV)
private val SUPPORTED_WAV_CODECS = listOf(ExportCodec.PCM_16)
private val inputConfigCache = ConcurrentHashMap<InputConfigKey, Boolean>()

private data class InputConfigKey(
    val sampleRate: Int,
    val sourceMode: AudioSourceMode,
    val routeMode: InputRouteMode,
    val channelMode: ChannelMode,
    val sampleFormat: PcmSampleFormat,
)

internal val Enum<*>.legacyPrefValue: String get() = name.lowercase()

private inline fun <T> Iterable<T>.entryByStorageCode(value: Int, code: (T) -> Byte): T? =
    firstOrNull { code(it).toInt() == value }

private fun <T : Enum<T>> Iterable<T>.entryByLegacyValue(value: String?, default: T): T =
    firstOrNull { it.legacyPrefValue == value } ?: default

enum class RetentionMode(val storageCode: Byte) {
    SIZE(0),
    TIME(1),
    ;

    companion object {
        fun fromStorageOrNull(value: Int): RetentionMode? =
            entries.entryByStorageCode(value, RetentionMode::storageCode)
    }
}

enum class ExportFormat(
    @param:StringRes @field:StringRes val labelRes: Int,
    val storageCode: Byte,
) {
    WAV(R.string.format_wav, 1),
    ;

    val extension: String get() = "wav"
    val outputMimeType: String get() = "audio/wav"

    companion object {
        fun fromStorageCode(value: Int): ExportFormat? =
            entries.entryByStorageCode(value, ExportFormat::storageCode)

        fun fromLegacyPrefValue(value: String?): ExportFormat = entries.entryByLegacyValue(value, WAV)
    }
}

enum class ExportCodec(val storageCode: Byte) {
    PCM_16(1),
    ;

    companion object {
        fun fromStorageCode(value: Int): ExportCodec? =
            entries.entryByStorageCode(value, ExportCodec::storageCode)

        fun fromLegacyPrefValue(value: String?): ExportCodec = entries.entryByLegacyValue(value, PCM_16)
    }
}

private const val WAVE_FORMAT_PCM: Short = 1
private const val WAVE_FORMAT_IEEE_FLOAT: Short = 3

enum class PcmSampleFormat(
    @param:StringRes @field:StringRes val labelRes: Int,
    val bitsPerSample: Int,
    val bytesPerSample: Int,
    val audioEncoding: Int,
    val wavFormatTag: Short,
    val storageCode: Byte,
) {
    PCM_8(R.string.sample_format_pcm_8, 8, 1, AudioFormat.ENCODING_PCM_8BIT, WAVE_FORMAT_PCM, 1),
    PCM_16(R.string.sample_format_pcm_16, 16, 2, AudioFormat.ENCODING_PCM_16BIT, WAVE_FORMAT_PCM, 2),
    PCM_FLOAT(R.string.sample_format_float_32, 32, 4, AudioFormat.ENCODING_PCM_FLOAT, WAVE_FORMAT_IEEE_FLOAT, 3),
    ;

    companion object {
        fun fromStorageCode(value: Int): PcmSampleFormat? =
            entries.entryByStorageCode(value, PcmSampleFormat::storageCode)

        fun fromLegacyPrefValue(value: String?): PcmSampleFormat = entries.entryByLegacyValue(value, PCM_16)
    }
}

@SuppressLint("InlinedApi")
enum class AudioSourceMode(
    val storageCode: Byte,
    @param:StringRes @field:StringRes val labelRes: Int,
) {
    VOICE_RECOGNITION(MediaRecorder.AudioSource.VOICE_RECOGNITION.toByte(), R.string.audio_source_voice_recognition),
    VOICE_COMMUNICATION(MediaRecorder.AudioSource.VOICE_COMMUNICATION.toByte(), R.string.audio_source_voice_communication),
    VOICE_PERFORMANCE(MediaRecorder.AudioSource.VOICE_PERFORMANCE.toByte(), R.string.audio_source_voice_performance),
    CAMCORDER(MediaRecorder.AudioSource.CAMCORDER.toByte(), R.string.audio_source_camcorder),
    DEFAULT(MediaRecorder.AudioSource.DEFAULT.toByte(), R.string.audio_source_default),
    MIC(MediaRecorder.AudioSource.MIC.toByte(), R.string.audio_source_mic),
    UNPROCESSED(MediaRecorder.AudioSource.UNPROCESSED.toByte(), R.string.audio_source_unprocessed),
    VOICE_CALL(MediaRecorder.AudioSource.VOICE_CALL.toByte(), R.string.audio_source_voice_call),
    VOICE_UPLINK(MediaRecorder.AudioSource.VOICE_UPLINK.toByte(), R.string.audio_source_voice_uplink),
    VOICE_DOWNLINK(MediaRecorder.AudioSource.VOICE_DOWNLINK.toByte(), R.string.audio_source_voice_downlink),
    REMOTE_SUBMIX(MediaRecorder.AudioSource.REMOTE_SUBMIX.toByte(), R.string.audio_source_remote_submix),
    ;

    val sourceValue: Int get() = storageCode.toInt()

    companion object {
        private val preferredOrder = listOf(
            VOICE_RECOGNITION,
            VOICE_COMMUNICATION,
            VOICE_PERFORMANCE,
            CAMCORDER,
            DEFAULT,
            MIC,
            UNPROCESSED,
            VOICE_CALL,
            VOICE_UPLINK,
            VOICE_DOWNLINK,
            REMOTE_SUBMIX,
        )
        fun defaultMode(): AudioSourceMode = preferredOrder.first()

        fun fromStorageCode(value: Int): AudioSourceMode =
            entries.entryByStorageCode(value, AudioSourceMode::storageCode) ?: defaultMode()

        fun availableModes(): List<AudioSourceMode> = preferredOrder.filter { mode ->
            !mode.requiresPrivilegedCapturePermission &&
                (mode != VOICE_PERFORMANCE || Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        }
    }

    private val requiresPrivilegedCapturePermission: Boolean
        get() = this == VOICE_CALL || this == VOICE_UPLINK || this == VOICE_DOWNLINK || this == REMOTE_SUBMIX
}

enum class InputRouteMode(
    @param:StringRes @field:StringRes val labelRes: Int,
    val storageCode: Byte,
) {
    AUTO(R.string.input_route_auto, 0),
    BUILTIN_MIC(R.string.input_route_builtin_mic, 1),
    ;

    companion object {
        fun fromStorageCode(value: Int): InputRouteMode? =
            entries.entryByStorageCode(value, InputRouteMode::storageCode)

        fun fromLegacyPrefValue(value: String?): InputRouteMode = entries.entryByLegacyValue(value, AUTO)
    }
}

enum class ChannelMode(
    @param:StringRes @field:StringRes val labelRes: Int,
    val channelCount: Int,
    val inputChannelMask: Int,
    val storageCode: Byte,
) {
    MONO(R.string.channel_mode_mono, 1, AudioFormat.CHANNEL_IN_MONO, 1),
    STEREO(R.string.channel_mode_stereo, 2, AudioFormat.CHANNEL_IN_STEREO, 2),
    ;

    companion object {
        fun fromStorageCode(value: Int): ChannelMode? =
            entries.entryByStorageCode(value, ChannelMode::storageCode)

        fun fromLegacyPrefValue(value: String?): ChannelMode = entries.entryByLegacyValue(value, MONO)
    }
}

enum class AppThemeMode(
    @param:StringRes @field:StringRes val labelRes: Int,
    val storageCode: Byte,
) {
    SYSTEM(R.string.theme_system, 0),
    LIGHT(R.string.theme_light, 1),
    DARK(R.string.theme_dark, 2),
    ;

    companion object {
        fun fromStorageCode(value: Int): AppThemeMode? =
            entries.entryByStorageCode(value, AppThemeMode::storageCode)

        fun fromLegacyPrefValue(value: String?): AppThemeMode = entries.entryByLegacyValue(value, SYSTEM)
    }
}

internal inline fun <T> readByteBackedPreference(
    prefs: SharedPreferences,
    key: PrefKey,
    default: T,
    crossinline fromStorageCode: (Int) -> T?,
    crossinline fromLegacyPrefValue: (String?) -> T,
): T {
    val encoded = prefs.safeInt(key, Int.MIN_VALUE)
    if (encoded != Int.MIN_VALUE) return fromStorageCode(encoded) ?: default

    val legacy = prefs.safeString(key) ?: return default
    // A read may race a durable Settings save. Never write a sampled legacy value back:
    // it could overwrite the newer commit. Explicit saves persist canonical byte codes.
    return fromLegacyPrefValue(legacy)
}

fun getRecorderPreferences(context: Context): SharedPreferences =
    context.getSharedPreferences(context.packageName, Context.MODE_PRIVATE)

private fun rememberedRangeExportKeys(
    bufferSlot: ReverbService.BufferSlot,
): Pair<PrefKey, PrefKey> = when (bufferSlot) {
    ReverbService.BufferSlot.ONE_SHOT ->
        PrefKey.RANGE_EXPORT_ONE_SHOT_SELECTION_MILLIS to PrefKey.RANGE_EXPORT_ONE_SHOT_END_OFFSET_MILLIS
    ReverbService.BufferSlot.LOOPING ->
        PrefKey.RANGE_EXPORT_LOOPING_SELECTION_MILLIS to PrefKey.RANGE_EXPORT_LOOPING_END_OFFSET_MILLIS
}

internal fun getRememberedRangeExport(
    context: Context,
    bufferSlot: ReverbService.BufferSlot,
): RememberedRangeExport? {
    val (selectionKey, offsetKey) = rememberedRangeExportKeys(bufferSlot)
    val prefs = getRecorderPreferences(context)
    val selectionMillis = prefs.safeLong(selectionKey, -1L)
    val endOffsetMillis = prefs.safeLong(offsetKey, -1L)
    if (selectionMillis <= 0L || endOffsetMillis < 0L) return null
    return RememberedRangeExport(selectionMillis, endOffsetMillis)
}

internal fun rememberSuccessfulRangeExport(
    context: Context,
    bufferSlot: ReverbService.BufferSlot,
    availableSeconds: Double,
    startSeconds: Float,
    endSeconds: Float,
    actualSelectionMillis: Long? = null,
) {
    val remembered = rememberedRangeExportFromSavedRange(
        availableSeconds = availableSeconds,
        startSeconds = startSeconds,
        endSeconds = endSeconds,
        actualSelectionMillis = actualSelectionMillis,
    ) ?: return
    val (selectionKey, offsetKey) = rememberedRangeExportKeys(bufferSlot)
    // This is UI convenience state, not audio durability state. SaveResultReceiver runs on
    // the main thread, so commit() would put a synchronous filesystem write on export success.
    getRecorderPreferences(context).edit {
        putLong(selectionKey, remembered.selectionLengthMillis)
        putLong(offsetKey, remembered.endOffsetMillis)
    }
}

internal data class DurableCaptureIntentPreferences(
    val enabled: Boolean,
    val bufferSlot: ReverbService.BufferSlot?,
)

internal fun decodeDurableCaptureIntentPreferences(
    rawPreferences: Map<String, *>,
): DurableCaptureIntentPreferences {
    val enabled = when (val raw = rawPreferences[PrefKey.AUDIO_MEMORY_ENABLED.name]) {
        null -> {
            check(!rawPreferences.containsKey(PrefKey.AUDIO_MEMORY_ENABLED.name)) {
                "Null durable capture intent"
            }
            false
        }
        is Boolean -> raw
        else -> throw IllegalStateException(
            "Unreadable durable preference ${PrefKey.AUDIO_MEMORY_ENABLED.name}: ${raw::class.java.simpleName}",
        )
    }
    val bufferSlot = when (val raw = rawPreferences[PrefKey.CAPTURE_BUFFER_SLOT.name]) {
        null -> {
            check(!rawPreferences.containsKey(PrefKey.CAPTURE_BUFFER_SLOT.name)) {
                "Null durable capture buffer slot"
            }
            null
        }
        is Int -> ReverbService.BufferSlot.fromStorageCode(raw)
            ?: throw IllegalStateException("Unknown durable capture buffer slot code: $raw")
        is String -> ReverbService.BufferSlot.fromLegacyName(raw)
            ?: throw IllegalStateException("Unknown legacy durable capture buffer slot: $raw")
        else -> throw IllegalStateException(
            "Unreadable durable preference ${PrefKey.CAPTURE_BUFFER_SLOT.name}: ${raw::class.java.simpleName}",
        )
    }
    return DurableCaptureIntentPreferences(enabled = enabled, bufferSlot = bufferSlot)
}

internal fun readDurableCaptureIntentPreferences(
    prefs: SharedPreferences,
): DurableCaptureIntentPreferences = decodeDurableCaptureIntentPreferences(prefs.all)

internal fun readCaptureBufferSlotPreference(prefs: SharedPreferences): ReverbService.BufferSlot? {
    val encoded = prefs.safeInt(PrefKey.CAPTURE_BUFFER_SLOT, Int.MIN_VALUE)
    if (encoded != Int.MIN_VALUE) {
        return ReverbService.BufferSlot.fromStorageCode(encoded)
    }
    val legacy = prefs.safeString(PrefKey.CAPTURE_BUFFER_SLOT) ?: return null
    return ReverbService.BufferSlot.fromLegacyName(legacy)
}

fun isWakeLockEnabled(context: Context): Boolean =
    getRecorderPreferences(context).safeBoolean(PrefKey.WAKE_LOCK_ENABLED, false)

fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
    return powerManager.isIgnoringBatteryOptimizations(context.packageName)
}

fun getConfiguredThemeMode(context: Context): AppThemeMode = readByteBackedPreference(
    prefs = getRecorderPreferences(context),
    key = PrefKey.THEME_MODE,
    default = AppThemeMode.SYSTEM,
    fromStorageCode = AppThemeMode::fromStorageCode,
    fromLegacyPrefValue = AppThemeMode::fromLegacyPrefValue,
)

internal data class ConfiguredBufferAvailability(
    val oneShotEnabled: Boolean,
    val loopingEnabled: Boolean,
)

private fun configuredSizeHasWholeFrame(
    sizeBytes: Long,
    channelMode: ChannelMode,
    sampleFormat: PcmSampleFormat,
): Boolean {
    val frameBytes = channelMode.channelCount * sampleFormat.bytesPerSample
    return normalizeRetentionValue(RetentionMode.SIZE, sizeBytes, frameBytes) > 0L
}

internal fun configuredBufferAvailability(
    retention: RetentionConfiguration?,
    channelMode: ChannelMode,
    sampleFormat: PcmSampleFormat,
): ConfiguredBufferAvailability {
    if (retention == null) return ConfiguredBufferAvailability(false, false)
    fun enabled(seconds: Long, sizeBytes: Long): Boolean = when (retention.mode) {
        RetentionMode.SIZE -> configuredSizeHasWholeFrame(sizeBytes, channelMode, sampleFormat)
        RetentionMode.TIME -> seconds > 0L
    }
    return ConfiguredBufferAvailability(
        oneShotEnabled = enabled(retention.oneShotSeconds, retention.oneShotSizeBytes),
        loopingEnabled = enabled(retention.loopingSeconds, retention.loopingSizeBytes),
    )
}

internal fun getConfiguredBufferAvailability(context: Context): ConfiguredBufferAvailability =
    configuredBufferAvailability(
        retention = retentionConfigurationForOperationalRead(context),
        channelMode = getConfiguredChannelMode(context),
        sampleFormat = getConfiguredPcmSampleFormat(context),
    )

fun isOnboardingPending(context: Context): Boolean =
    !getRecorderPreferences(context).safeBoolean(PrefKey.ONBOARDING_SHOWN, false)

private val ONBOARDING_TRANSACTION_PREFERENCE_KEYS = listOf(
    PrefKey.ONBOARDING_SHOWN,
    PrefKey.RETENTION_MODE,
    PrefKey.ONE_SHOT_RETENTION_SECONDS,
    PrefKey.ONE_SHOT_AUDIO_MEMORY_SIZE,
    PrefKey.RETENTION_SECONDS,
    PrefKey.AUDIO_MEMORY_SIZE,
    PrefKey.RETENTION_CONFIG_DIGEST,
)

internal fun onboardingPreferenceRollbackSnapshot(
    rawPreferences: Map<String, *>,
): Map<PrefKey, DurablePreferenceValueSnapshot> =
    ONBOARDING_TRANSACTION_PREFERENCE_KEYS.associateWith { key ->
        durablePreferenceValueSnapshot(rawPreferences, key)
    }

@SuppressLint("UseKtx") // commit() Boolean is required by the retention transaction.
fun finishOnboarding(
    context: Context,
    oneShotEnabled: Boolean,
    loopingEnabled: Boolean,
): Boolean {
    if (!oneShotEnabled && !loopingEnabled) return false
    return withRetentionPersistenceLock {
        if (!retentionMutationIsSafe(context)) return@withRetentionPersistenceLock false
        val current = retentionConfigurationForRead(context)
        val defaults = defaultRetentionConfiguration()
        val configuredChannelMode = getConfiguredChannelMode(context)
        val configuredSampleFormat = getConfiguredPcmSampleFormat(context)
        val updated = when (current.mode) {
            RetentionMode.TIME -> current.copy(
                oneShotSeconds = if (oneShotEnabled) {
                    current.oneShotSeconds.takeIf { it > 0L } ?: defaults.oneShotSeconds
                } else {
                    0L
                },
                loopingSeconds = if (loopingEnabled) {
                    current.loopingSeconds.takeIf { it > 0L } ?: defaults.loopingSeconds
                } else {
                    0L
                },
            )
            RetentionMode.SIZE -> current.copy(
                oneShotSizeBytes = if (oneShotEnabled) {
                    current.oneShotSizeBytes.takeIf {
                        configuredSizeHasWholeFrame(it, configuredChannelMode, configuredSampleFormat)
                    }
                        ?: defaults.oneShotSizeBytes
                } else {
                    0L
                },
                loopingSizeBytes = if (loopingEnabled) {
                    current.loopingSizeBytes.takeIf {
                        configuredSizeHasWholeFrame(it, configuredChannelMode, configuredSampleFormat)
                    }
                        ?: defaults.loopingSizeBytes
                } else {
                    0L
                },
            )
        }
        val prefs = getRecorderPreferences(context)
        val rollbackPreferences = onboardingPreferenceRollbackSnapshot(prefs.all)
        persistRetentionTransaction(
            writeNewRecovery = { writeRetentionRecoveryConfiguration(context, updated) },
            commitNewPreferences = {
                prefs.edit()
                    .putBoolean(PrefKey.ONBOARDING_SHOWN, true)
                    .putInt(PrefKey.RETENTION_MODE, updated.mode.storageCode.toInt())
                    .putLong(PrefKey.ONE_SHOT_RETENTION_SECONDS, updated.oneShotSeconds)
                    .putLong(PrefKey.ONE_SHOT_AUDIO_MEMORY_SIZE, updated.oneShotSizeBytes)
                    .putLong(PrefKey.RETENTION_SECONDS, updated.loopingSeconds)
                    .putLong(PrefKey.AUDIO_MEMORY_SIZE, updated.loopingSizeBytes)
                    .putString(PrefKey.RETENTION_CONFIG_DIGEST, retentionConfigurationDigest(updated))
                    .commit()
            },
            restoreRecovery = { writeRetentionRecoveryConfiguration(context, current) },
            restorePreferences = {
                val editor = prefs.edit()
                rollbackPreferences.forEach { (key, snapshot) ->
                    editor.restoreDurablePreferenceValue(key, snapshot)
                }
                editor.commit()
            },
        )
    }
}

fun getConfiguredOutputFormat(context: Context): ExportFormat = readByteBackedPreference(
    prefs = getRecorderPreferences(context),
    key = PrefKey.OUTPUT_FORMAT,
    default = ExportFormat.WAV,
    fromStorageCode = ExportFormat::fromStorageCode,
    fromLegacyPrefValue = ExportFormat::fromLegacyPrefValue,
)

fun getConfiguredOutputCodec(context: Context): ExportCodec = readByteBackedPreference(
    prefs = getRecorderPreferences(context),
    key = PrefKey.OUTPUT_CODEC,
    default = ExportCodec.PCM_16,
    fromStorageCode = ExportCodec::fromStorageCode,
    fromLegacyPrefValue = ExportCodec::fromLegacyPrefValue,
)

fun getConfiguredPcmSampleFormat(context: Context): PcmSampleFormat = readByteBackedPreference(
    prefs = getRecorderPreferences(context),
    key = PrefKey.PCM_SAMPLE_FORMAT,
    default = PcmSampleFormat.PCM_16,
    fromStorageCode = PcmSampleFormat::fromStorageCode,
    fromLegacyPrefValue = PcmSampleFormat::fromLegacyPrefValue,
)

fun isCodecCompatibleWithFormat(
    format: ExportFormat,
    codec: ExportCodec,
): Boolean = format == ExportFormat.WAV && codec == ExportCodec.PCM_16

fun getConfiguredAudioSourceMode(context: Context): AudioSourceMode =
    AudioSourceMode.fromStorageCode(
        getRecorderPreferences(context).safeInt(PrefKey.AUDIO_SOURCE, AudioSourceMode.defaultMode().storageCode.toInt()),
    )

fun getConfiguredInputRouteMode(context: Context): InputRouteMode = readByteBackedPreference(
    prefs = getRecorderPreferences(context),
    key = PrefKey.INPUT_ROUTE,
    default = InputRouteMode.AUTO,
    fromStorageCode = InputRouteMode::fromStorageCode,
    fromLegacyPrefValue = InputRouteMode::fromLegacyPrefValue,
)

fun getConfiguredChannelMode(context: Context): ChannelMode = readByteBackedPreference(
    prefs = getRecorderPreferences(context),
    key = PrefKey.CHANNEL_MODE,
    default = DEFAULT_CHANNEL_MODE,
    fromStorageCode = ChannelMode::fromStorageCode,
    fromLegacyPrefValue = ChannelMode::fromLegacyPrefValue,
)

fun getConfiguredSampleRate(context: Context): Int {
    val prefs = getRecorderPreferences(context)
    if (prefs.contains(PrefKey.SAMPLE_RATE)) {
        val requested = prefs.safeInt(PrefKey.SAMPLE_RATE, 0)
        if (requested in STANDARD_SAMPLE_RATES) return requested
    }
    return PREFERRED_DEFAULT_SAMPLE_RATE
}

fun getConfiguredMemorySizeBytes(
    context: Context,
    sampleRate: Int,
    channelMode: ChannelMode = getConfiguredChannelMode(context),
    sampleFormat: PcmSampleFormat = getConfiguredPcmSampleFormat(context),
): Long {
    val retention = retentionConfigurationForOperationalRead(context) ?: return 0L
    return when (retention.mode) {
        RetentionMode.SIZE -> {
            val frameBytes = channelMode.channelCount.toLong() * sampleFormat.bytesPerSample.toLong()
            if (frameBytes <= 0L || retention.loopingSizeBytes <= 0L) 0L else {
                (retention.loopingSizeBytes / frameBytes) * frameBytes
            }
        }

        RetentionMode.TIME -> bytesForRetentionSeconds(
            retention.loopingSeconds, sampleRate,
            channelMode.channelCount, sampleFormat,
        )
    }
}

fun bytesForRetentionSeconds(
    seconds: Long,
    sampleRate: Int,
    channelCount: Int,
    sampleFormat: PcmSampleFormat = PcmSampleFormat.PCM_16,
): Long {
    if (sampleRate <= 0 || channelCount <= 0) return 0
    val bytesPerSecond = bytesPerSecond(sampleRate, channelCount, sampleFormat)
    if (bytesPerSecond <= 0L || seconds <= 0L) return 0L
    if (seconds > Long.MAX_VALUE / bytesPerSecond) {
        return Long.MAX_VALUE
    }
    return seconds * bytesPerSecond
}

fun retentionSecondsForBytes(
    bytes: Long,
    sampleRate: Int,
    channelCount: Int,
    sampleFormat: PcmSampleFormat = PcmSampleFormat.PCM_16,
): Long {
    val bytesPerSecond = bytesPerSecond(sampleRate, channelCount, sampleFormat)
    if (bytesPerSecond <= 0L || bytes <= 0L) return 0
    return bytes / bytesPerSecond
}

fun parseDurationInput(value: String): Int? {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return null
    val parts = trimmed.split(":")
    if (parts.size == 1) {
        val minutes = parts[0].toIntOrNull() ?: return null
        if (minutes < 0) return null
        val product = minutes.toLong() * 60L
        return if (product > Int.MAX_VALUE.toLong()) null else product.toInt()
    }
    if (parts.size !in 2..3) return null

    var seconds = 0L
    for ((index, part) in parts.withIndex()) {
        val unit = part.toLongOrNull() ?: return null
        if (unit < 0) return null
        if (index > 0 && unit >= 60) return null
        seconds = seconds * 60L + unit
        if (seconds > Int.MAX_VALUE.toLong()) return null
    }
    val result = seconds.toInt()
    return if (result >= 0) result else null
}

internal val DIGIT_0 = charArrayOf('0', '1', '2', '3', '4', '5', '6', '7', '8', '9')

fun formatDurationInput(seconds: Int): String = formatDurationInput(seconds.toLong())

fun formatDurationInput(seconds: Long): String {
    val total = max(0, seconds)
    val hours = total / 3600
    val minutes = total % 3600 / 60
    val secs = total % 60
    return if (hours > 0) {
        val hs = hours.toString()
        val chars = CharArray(hs.length + 6)
        var i = 0
        for (c in hs) chars[i++] = c
        chars[i++] = ':'; chars[i++] = DIGIT_0[minutes.toInt() / 10]; chars[i++] = DIGIT_0[minutes.toInt() % 10]
        chars[i++] = ':'; chars[i++] = DIGIT_0[secs.toInt() / 10]; chars[i] = DIGIT_0[secs.toInt() % 10]
        String(chars)
    } else {
        val m = minutes.toInt()
        val s = secs.toInt()
        if (m >= 10) {
            val chars = CharArray(5)
            chars[0] = DIGIT_0[m / 10]; chars[1] = DIGIT_0[m % 10]
            chars[2] = ':'; chars[3] = DIGIT_0[s / 10]; chars[4] = DIGIT_0[s % 10]
            String(chars)
        } else {
            val chars = CharArray(4)
            chars[0] = DIGIT_0[m]; chars[1] = ':'; chars[2] = DIGIT_0[s / 10]; chars[3] = DIGIT_0[s % 10]
            String(chars)
        }
    }
}

fun exportFileSizeLimitBytes(format: ExportFormat): Long =
    // RIFF ChunkSize is fileSize - 8, so a max unsigned-32 ChunkSize permits eight
    // additional physical file bytes beyond 0xFFFF_FFFF.
    WAV_MAX_RIFF_CHUNK_BYTES + WAV_RIFF_SIZE_EXCLUDED_PREFIX_BYTES

fun exportPayloadLimitBytes(
    format: ExportFormat,
    sampleFormat: PcmSampleFormat = PcmSampleFormat.PCM_16,
): Long {
    val headerBytes = if (sampleFormat == PcmSampleFormat.PCM_FLOAT) FLOAT_WAV_HEADER_BYTES else PCM_WAV_HEADER_BYTES
    val budget = (exportFileSizeLimitBytes(format) - headerBytes).coerceAtLeast(0L)
    return if (sampleFormat == PcmSampleFormat.PCM_8) (budget - 1L).coerceAtLeast(0L) else budget
}

fun exportDurationLimitExactSeconds(
    format: ExportFormat,
    codec: ExportCodec,
    sampleRate: Int,
    channelCount: Int,
    sampleFormat: PcmSampleFormat = PcmSampleFormat.PCM_16,
): Double {
    if (sampleRate <= 0 || channelCount <= 0) return 0.0
    if (!isExportConfigurationSupported(format, codec, sampleRate, channelCount)) return 0.0
    val bytesPerSecond = bytesPerSecond(sampleRate, channelCount, sampleFormat)
    if (bytesPerSecond <= 0L) return 0.0
    val frameBytes = channelCount.toLong() * sampleFormat.bytesPerSample.toLong()
    if (frameBytes <= 0L) return 0.0
    // WavAudioFileWriter accepts only complete PCM frames. Keep the UI/service limit on the
    // largest whole-frame payload that fits the container so a duration cannot render safe
    // and then fail merely because the raw byte budget ends in the middle of a frame.
    val payloadBudget = exportPayloadLimitBytes(format, sampleFormat)
    val frameAlignedPayloadBudget = payloadBudget - payloadBudget % frameBytes
    return frameAlignedPayloadBudget.toDouble() / bytesPerSecond.toDouble()
}

fun resolveOperationalSampleRate(
    context: Context,
    requestedRate: Int,
    sourceMode: AudioSourceMode,
    routeMode: InputRouteMode,
    format: ExportFormat,
    codec: ExportCodec,
    channelMode: ChannelMode,
    sampleFormat: PcmSampleFormat = PcmSampleFormat.PCM_16,
): Int {
    if (requestedRate > 0 &&
        isCodecSupported(format, codec, requestedRate, channelMode) &&
        isInputConfigSupported(context, requestedRate, sourceMode, routeMode, channelMode, sampleFormat)
    ) {
        return requestedRate
    }
    return orderSampleRatesByPreference(
        standardSampleRates().filter { it != requestedRate },
        requestedRate,
    ).firstOrNull { rate ->
        isCodecSupported(format, codec, rate, channelMode) &&
            isInputConfigSupported(context, rate, sourceMode, routeMode, channelMode, sampleFormat)
    } ?: 0
}

fun supportedInputRouteModes(context: Context): List<InputRouteMode> = buildList {
    add(InputRouteMode.AUTO)
    if (findBuiltInMicrophone(context) != null) add(InputRouteMode.BUILTIN_MIC)
}

fun standardSampleRates(): List<Int> = STANDARD_SAMPLE_RATES

fun sampleRateLabel(sampleRate: Int): String {
    if (sampleRate % 1000 == 0) return "${sampleRate / 1000} kHz"
    val fracDigits = (sampleRate % 1000).toString().padStart(3, '0').dropLastWhile { it == '0' }
    return "${sampleRate / 1000}.${fracDigits} kHz"
}

fun findBuiltInMicrophone(context: Context): AudioDeviceInfo? = runCatching {
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
}.getOrNull()

fun isInputConfigSupported(
    context: Context,
    sampleRate: Int,
    sourceMode: AudioSourceMode,
    routeMode: InputRouteMode,
    channelMode: ChannelMode,
    sampleFormat: PcmSampleFormat = PcmSampleFormat.PCM_16,
): Boolean {
    if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
        return false
    }
    val key = InputConfigKey(
        sampleRate = sampleRate,
        sourceMode = sourceMode,
        routeMode = routeMode,
        channelMode = channelMode,
        sampleFormat = sampleFormat,
    )
    if (inputConfigCache[key] == true) return true
    val supported = run {
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            channelMode.inputChannelMask,
            sampleFormat.audioEncoding,
        )
        if (minBuffer <= 0) {
            return@run false
        }

        val preferredDevice = if (routeMode == InputRouteMode.BUILTIN_MIC) findBuiltInMicrophone(context) else null
        if (routeMode == InputRouteMode.BUILTIN_MIC && preferredDevice == null) {
            return@run false
        }

        try {
            val record = AudioRecord.Builder()
                .setAudioSource(sourceMode.sourceValue)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(sampleFormat.audioEncoding)
                        .setChannelMask(channelMode.inputChannelMask)
                        .setSampleRate(sampleRate)
                        .build(),
                )
                .setBufferSizeInBytes(max(minBuffer * 2, 16 * 1024))
                .build()
            withOwnedResource(
                owner = record,
                release = { it.release() },
            ) { configured ->
                val routeAccepted = preferredDevice == null || configured.setPreferredDevice(preferredDevice)
                routeAccepted && configured.state == AudioRecord.STATE_INITIALIZED
            }
        } catch (_: Exception) {
            false
        }
    }
    if (supported) inputConfigCache.putIfAbsent(key, true)
    return supported
}

fun isCodecSupported(
    format: ExportFormat,
    codec: ExportCodec,
    sampleRate: Int,
    channelMode: ChannelMode,
): Boolean = isCodecCompatibleWithFormat(format, codec) &&
    isExportConfigurationSupported(format, codec, sampleRate, channelMode.channelCount)

fun supportedFormats(): List<ExportFormat> = SUPPORTED_EXPORT_FORMATS

fun supportedCodecs(format: ExportFormat): List<ExportCodec> =
    if (format == ExportFormat.WAV) SUPPORTED_WAV_CODECS else emptyList()

private fun bytesPerSecond(
    sampleRate: Int,
    channelCount: Int,
    sampleFormat: PcmSampleFormat = PcmSampleFormat.PCM_16,
): Long {
    if (sampleRate <= 0 || channelCount <= 0) return 0L
    return sampleRate.toLong() * channelCount.toLong() * sampleFormat.bytesPerSample.toLong()
}

internal fun isExportConfigurationSupported(
    format: ExportFormat,
    codec: ExportCodec,
    sampleRate: Int,
    channelCount: Int,
): Boolean {
    return format == ExportFormat.WAV && codec == ExportCodec.PCM_16 && sampleRate > 0 && channelCount in 1..2
}

fun orderSampleRatesByPreference(
    sampleRates: List<Int>,
    requestedRate: Int,
): List<Int> {
    if (sampleRates.isEmpty()) return emptyList()
    if (requestedRate <= 0) return sampleRates.sortedDescending()
    val exact = mutableListOf<Int>()
    val higher = mutableListOf<Int>()
    val lower = mutableListOf<Int>()
    val seen = HashSet<Int>(sampleRates.size)
    for (rate in sampleRates) {
        if (!seen.add(rate)) continue
        when {
            rate == requestedRate -> exact.add(rate)
            rate > requestedRate -> higher.add(rate)
            else -> lower.add(rate)
        }
    }
    higher.sortBy { it - requestedRate }
    lower.sortByDescending { it }
    return exact + higher + lower
}

internal fun captureIntentPersistenceRequired(
    authorityValid: Boolean,
    previousEnabled: Boolean,
    requestedEnabled: Boolean,
    previousStoredSlot: ReverbService.BufferSlot?,
    requestedSlot: ReverbService.BufferSlot,
): Boolean = !authorityValid || captureIntentNeedsPersistence(
    previousEnabled = previousEnabled,
    requestedEnabled = requestedEnabled,
    previousStoredSlot = previousStoredSlot,
    requestedSlot = requestedSlot,
)
