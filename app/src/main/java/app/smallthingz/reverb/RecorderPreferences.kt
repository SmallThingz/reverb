@file:JvmName("RecorderPreferences")

package app.smallthingz.reverb

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.PowerManager
import androidx.annotation.StringRes
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

private const val WAV_HEADER_BYTES = 44L
private const val WAV_MAX_EXPORT_BYTES = 0xFFFF_FFFFL - WAV_HEADER_BYTES

private val STANDARD_SAMPLE_RATES =
    intArrayOf(96_000, 88_200, 64_000, 48_000, 44_100, 32_000, 24_000, 22_050, 16_000, 12_000, 11_025, 8_000, 7_350)
private val codecSupportCache = ConcurrentHashMap<CodecSupportKey, Boolean>()
private val inputConfigCache = ConcurrentHashMap<InputConfigKey, Boolean>()

private data class CodecSupportKey(
    val format: ExportFormat,
    val codec: ExportCodec,
    val sampleRate: Int,
    val channelMode: ChannelMode,
)

private data class InputConfigKey(
    val sampleRate: Int,
    val sourceMode: AudioSourceMode,
    val routeMode: InputRouteMode,
    val channelMode: ChannelMode,
    val sampleFormat: PcmSampleFormat,
)

private inline fun <K : Any, V : Any> ConcurrentHashMap<K, V>.cached(
    key: K,
    producer: () -> V,
): V {
    this[key]?.let { return it }
    val value = producer()
    val existing = putIfAbsent(key, value)
    return existing ?: value
}

enum class RetentionMode {
    SIZE,
    TIME,
    ;

    companion object {
        fun fromStorage(value: Int): RetentionMode = entries.getOrElse(value) { SIZE }
    }
}

enum class ExportFormat(
    @param:StringRes @field:StringRes val labelRes: Int,
) {
    WAV(R.string.format_wav),
    ;

    val prefValue: String get() = name.lowercase()
    val extension: String get() = "wav"
    val outputMimeType: String get() = "audio/wav"
    val isPcmContainer: Boolean get() = true

    companion object {
        fun fromPrefValue(value: String?): ExportFormat = WAV
    }
}

enum class ExportCodec(
    @param:StringRes @field:StringRes val labelRes: Int,
) {
    PCM_16(R.string.codec_pcm_16),
    ;

    val prefValue: String get() = name.lowercase()
    val isPcm: Boolean get() = true
    val supportedFormats: Set<ExportFormat> get() = setOf(ExportFormat.WAV)

    companion object {
        fun fromPrefValue(value: String?): ExportCodec = PCM_16
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
) {
    PCM_8(R.string.sample_format_pcm_8, 8, 1, AudioFormat.ENCODING_PCM_8BIT, WAVE_FORMAT_PCM),
    PCM_16(R.string.sample_format_pcm_16, 16, 2, AudioFormat.ENCODING_PCM_16BIT, WAVE_FORMAT_PCM),
    PCM_FLOAT(R.string.sample_format_float_32, 32, 4, AudioFormat.ENCODING_PCM_FLOAT, WAVE_FORMAT_IEEE_FLOAT),
    ;

    val prefValue: String get() = name.lowercase()

    companion object {
        private val byPrefValue = entries.associateBy { it.prefValue }

        fun fromPrefValue(value: String?): PcmSampleFormat {
            val v = value ?: return PCM_16
            return byPrefValue[v] ?: PCM_16
        }
    }
}

enum class AudioSourceMode(
    val sourceValue: Int,
    @param:StringRes @field:StringRes val labelRes: Int,
) {
    VOICE_RECOGNITION(MediaRecorder.AudioSource.VOICE_RECOGNITION, R.string.audio_source_voice_recognition),
    VOICE_COMMUNICATION(MediaRecorder.AudioSource.VOICE_COMMUNICATION, R.string.audio_source_voice_communication),
    VOICE_PERFORMANCE(MediaRecorder.AudioSource.VOICE_PERFORMANCE, R.string.audio_source_voice_performance),
    CAMCORDER(MediaRecorder.AudioSource.CAMCORDER, R.string.audio_source_camcorder),
    DEFAULT(MediaRecorder.AudioSource.DEFAULT, R.string.audio_source_default),
    MIC(MediaRecorder.AudioSource.MIC, R.string.audio_source_mic),
    UNPROCESSED(MediaRecorder.AudioSource.UNPROCESSED, R.string.audio_source_unprocessed),
    VOICE_CALL(MediaRecorder.AudioSource.VOICE_CALL, R.string.audio_source_voice_call),
    VOICE_UPLINK(MediaRecorder.AudioSource.VOICE_UPLINK, R.string.audio_source_voice_uplink),
    VOICE_DOWNLINK(MediaRecorder.AudioSource.VOICE_DOWNLINK, R.string.audio_source_voice_downlink),
    REMOTE_SUBMIX(MediaRecorder.AudioSource.REMOTE_SUBMIX, R.string.audio_source_remote_submix),
    ;

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
        private val bySourceValue = entries.associateBy { it.sourceValue }

        fun defaultMode(): AudioSourceMode = preferredOrder.first()

        fun fromSourceValue(value: Int): AudioSourceMode = bySourceValue[value] ?: defaultMode()

        fun availableModes(): List<AudioSourceMode> = preferredOrder.filter { mode ->
            !mode.requiresPrivilegedCapturePermission &&
                (mode != VOICE_PERFORMANCE || Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        }
    }

    private val requiresPrivilegedCapturePermission: Boolean
        get() = this == VOICE_CALL || this == VOICE_UPLINK || this == VOICE_DOWNLINK || this == REMOTE_SUBMIX
}

enum class InputRouteMode(@param:StringRes @field:StringRes val labelRes: Int) {
    AUTO(R.string.input_route_auto),
    BUILTIN_MIC(R.string.input_route_builtin_mic),
    ;

    val prefValue: String get() = name.lowercase()

    companion object {
        private val byPrefValue = entries.associateBy { it.prefValue }

        fun fromPrefValue(value: String?): InputRouteMode {
            val v = value ?: return AUTO
            return byPrefValue[v] ?: AUTO
        }
    }
}

enum class ChannelMode(
    @param:StringRes @field:StringRes val labelRes: Int,
    val channelCount: Int,
    val inputChannelMask: Int,
) {
    MONO(R.string.channel_mode_mono, 1, AudioFormat.CHANNEL_IN_MONO),
    STEREO(R.string.channel_mode_stereo, 2, AudioFormat.CHANNEL_IN_STEREO),
    ;

    val prefValue: String get() = name.lowercase()

    companion object {
        private val byPrefValue = entries.associateBy { it.prefValue }

        fun fromPrefValue(value: String?): ChannelMode {
            val v = value ?: return MONO
            return byPrefValue[v] ?: MONO
        }
    }
}

enum class AppThemeMode(
    @param:StringRes @field:StringRes val labelRes: Int,
) {
    SYSTEM(R.string.theme_system),
    LIGHT(R.string.theme_light),
    DARK(R.string.theme_dark),
    ;

    val prefValue: String get() = name.lowercase()

    companion object {
        private val byPrefValue = entries.associateBy { it.prefValue }

        fun fromPrefValue(value: String?): AppThemeMode {
            val v = value ?: return SYSTEM
            return byPrefValue[v] ?: SYSTEM
        }
    }
}

fun getRecorderPreferences(context: Context): SharedPreferences {
    return context.getSharedPreferences(context.packageName, Context.MODE_PRIVATE)
}

fun getConfiguredRetentionMode(context: Context): RetentionMode {
    return RetentionMode.fromStorage(
        getRecorderPreferences(context).getInt(PrefKey.RETENTION_MODE, RetentionMode.SIZE.ordinal),
    )
}

fun isWakeLockEnabled(context: Context): Boolean {
    return getRecorderPreferences(context).getBoolean(PrefKey.WAKE_LOCK_ENABLED, false)
}

fun isDebuggableBuild(context: Context): Boolean {
    return (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
}

fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
    return powerManager.isIgnoringBatteryOptimizations(context.packageName)
}

/**
 * Consumes the one-time startup battery-optimization prompt.
 *
 * The prompt is intentionally remembered independently of the live system exemption state:
 * users may change that state later, and Reverb should reflect it in Settings without nagging
 * them again on startup.
 *
 * @return true when the one-time prompt should be shown on this launch.
 */
fun consumeBatteryOptimizationStartupPrompt(context: Context): Boolean {
    val prefs = getRecorderPreferences(context)
    if (prefs.getBoolean(PrefKey.BATTERY_OPTIMIZATION_PROMPT_SHOWN, false)) return false

    // Commit before exposing the prompt so process death cannot turn a one-time prompt into a
    // recurring startup interruption.
    prefs.edit()
        .putBoolean(PrefKey.BATTERY_OPTIMIZATION_PROMPT_SHOWN, true)
        .commit()

    return !isIgnoringBatteryOptimizations(context)
}

fun getConfiguredThemeMode(context: Context): AppThemeMode {
    return AppThemeMode.fromPrefValue(
        getRecorderPreferences(context).getString(PrefKey.THEME_MODE, AppThemeMode.SYSTEM.prefValue),
    )
}

fun setConfiguredThemeMode(
    context: Context,
    mode: AppThemeMode,
) {
    getRecorderPreferences(context).edit().putString(PrefKey.THEME_MODE, mode.prefValue).apply()
}

fun getConfiguredRetentionSeconds(context: Context): Long {
    return getRecorderPreferences(context)
        .getLong(PrefKey.RETENTION_SECONDS, ReverbConfig.DEFAULT_RETENTION_SECONDS)
        .coerceAtLeast(1L)
}

fun getConfiguredRetentionSizeBytes(context: Context): Long {
    return getRecorderPreferences(context)
        .getLong(PrefKey.AUDIO_MEMORY_SIZE, ReverbConfig.DEFAULT_RETENTION_SIZE_BYTES)
        .coerceAtLeast(1L)
}

fun getConfiguredOutputFormat(context: Context): ExportFormat {
    return ExportFormat.WAV
}

fun getConfiguredOutputCodec(context: Context): ExportCodec {
    return ExportCodec.PCM_16
}

fun getConfiguredPcmSampleFormat(context: Context): PcmSampleFormat {
    return PcmSampleFormat.fromPrefValue(
        getRecorderPreferences(context).getString(PrefKey.PCM_SAMPLE_FORMAT, PcmSampleFormat.PCM_16.prefValue),
    )
}

fun isCodecCompatibleWithFormat(
    format: ExportFormat,
    codec: ExportCodec,
): Boolean = format in codec.supportedFormats

fun defaultCodecBitrateKbps(
    codec: ExportCodec,
    sampleRate: Int,
    channelCount: Int,
): Int? = null

fun getConfiguredCodecBitrateKbps(
    context: Context,
    codec: ExportCodec,
    sampleRate: Int,
    channelCount: Int,
): Int? = null

fun getConfiguredAudioSourceMode(context: Context): AudioSourceMode {
    return AudioSourceMode.fromSourceValue(
        getRecorderPreferences(context).getInt(
            PrefKey.AUDIO_SOURCE,
            AudioSourceMode.defaultMode().sourceValue,
        ),
    )
}

fun getConfiguredInputRouteMode(context: Context): InputRouteMode {
    return InputRouteMode.fromPrefValue(
        getRecorderPreferences(context).getString(PrefKey.INPUT_ROUTE, InputRouteMode.AUTO.prefValue),
    )
}

fun getConfiguredChannelMode(context: Context): ChannelMode {
    return ChannelMode.fromPrefValue(
        getRecorderPreferences(context).getString(
            PrefKey.CHANNEL_MODE,
            ReverbConfig.DEFAULT_CHANNEL_MODE.prefValue,
        ),
    )
}

fun getConfiguredSampleRate(context: Context): Int {
    val prefs = getRecorderPreferences(context)
    if (prefs.contains(PrefKey.SAMPLE_RATE)) {
        val requested = prefs.getInt(PrefKey.SAMPLE_RATE, 0)
        if (requested > 0) return requested
    }
    return ReverbConfig.PREFERRED_DEFAULT_SAMPLE_RATE
}

fun getConfiguredMemorySizeBytes(
    context: Context,
    sampleRate: Int,
    channelMode: ChannelMode = getConfiguredChannelMode(context),
    format: ExportFormat = getConfiguredOutputFormat(context),
    codec: ExportCodec = getConfiguredOutputCodec(context),
    bitrateKbps: Int? = getConfiguredCodecBitrateKbps(context, codec, sampleRate, channelMode.channelCount),
    sampleFormat: PcmSampleFormat = getConfiguredPcmSampleFormat(context),
): Long {
    return when (getConfiguredRetentionMode(context)) {
        RetentionMode.SIZE -> {
            val configuredSizeBytes = getConfiguredRetentionSizeBytes(context)
            val frameBytes = channelMode.channelCount.toLong() * sampleFormat.bytesPerSample.toLong()
            if (frameBytes <= 0L) 0L else {
                val rawBudget = (configuredSizeBytes - WAV_HEADER_BYTES).coerceAtLeast(frameBytes)
                (rawBudget / frameBytes) * frameBytes
            }
        }

        RetentionMode.TIME -> bytesForRetentionSeconds(
            getConfiguredRetentionSeconds(context), sampleRate,
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
    if (bytesPerSecond <= 0L) return 0
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

fun estimateExportSizeBytes(
    format: ExportFormat,
    codec: ExportCodec,
    sampleRate: Int,
    channelCount: Int,
    durationSeconds: Long,
    bitrateKbps: Int? = null,
    sampleFormat: PcmSampleFormat = PcmSampleFormat.PCM_16,
): Long {
    if (sampleRate <= 0 || channelCount <= 0 || durationSeconds <= 0L) {
        return 0L
    }
    if (!isExportConfigurationSupported(format, codec, sampleRate, channelCount)) return 0L
    val bps = bytesPerSecond(sampleRate, channelCount, sampleFormat)
    if (bps <= 0L) return 0L
    return if (durationSeconds > (Long.MAX_VALUE - WAV_HEADER_BYTES) / bps) {
        Long.MAX_VALUE
    } else {
        WAV_HEADER_BYTES + durationSeconds * bps
    }
}

fun estimateExportDurationSeconds(
    format: ExportFormat,
    codec: ExportCodec,
    sampleRate: Int,
    channelCount: Int,
    sizeBytes: Long,
    bitrateKbps: Int? = null,
    sampleFormat: PcmSampleFormat = PcmSampleFormat.PCM_16,
): Long {
    if (sampleRate <= 0 || channelCount <= 0 || sizeBytes <= 0L) {
        return 0L
    }
    if (!isExportConfigurationSupported(format, codec, sampleRate, channelCount)) return 0L
    val bps = bytesPerSecond(sampleRate, channelCount, sampleFormat)
    if (bps <= 0L) return 0L
    return ((sizeBytes - WAV_HEADER_BYTES).coerceAtLeast(0L)) / bps
}

fun exportFileSizeLimitBytes(format: ExportFormat): Long = WAV_MAX_EXPORT_BYTES

fun exportDurationLimitSeconds(
    format: ExportFormat,
    codec: ExportCodec,
    sampleRate: Int,
    channelCount: Int,
    bitrateKbps: Int? = null,
    sampleFormat: PcmSampleFormat = PcmSampleFormat.PCM_16,
): Long {
    return estimateExportDurationSeconds(
        format = format,
        codec = codec,
        sampleRate = sampleRate,
        channelCount = channelCount,
        sizeBytes = exportFileSizeLimitBytes(format),
        bitrateKbps = bitrateKbps,
        sampleFormat = sampleFormat,
    )
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

fun supportedInputRouteModes(context: Context): List<InputRouteMode> {
    return buildList {
        add(InputRouteMode.AUTO)
        if (hasBuiltInMicrophone(context)) {
            add(InputRouteMode.BUILTIN_MIC)
        }
    }
}

fun standardSampleRates(): List<Int> = STANDARD_SAMPLE_RATES.toList()

fun sampleRateLabel(sampleRate: Int): String {
    if (sampleRate % 1000 == 0) return "${sampleRate / 1000} kHz"
    val fracDigits = (sampleRate % 1000).toString().padStart(3, '0').dropLastWhile { it == '0' }
    return "${sampleRate / 1000}.${fracDigits} kHz"
}

fun hasBuiltInMicrophone(context: Context): Boolean = findBuiltInMicrophone(context) != null

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

        var record: AudioRecord? = null
        try {
            record = AudioRecord.Builder()
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
            val routeAccepted = preferredDevice == null || record.setPreferredDevice(preferredDevice)
            routeAccepted && record.state == AudioRecord.STATE_INITIALIZED
        } catch (_: Throwable) {
            false
        } finally {
            runCatching { record?.release() }
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
): Boolean {
    return codecSupportCache.cached(CodecSupportKey(format, codec, sampleRate, channelMode)) {
        isCodecCompatibleWithFormat(format, codec) &&
            isExportConfigurationSupported(format, codec, sampleRate, channelMode.channelCount)
    }
}

fun supportedFormats(): List<ExportFormat> {
    return listOf(ExportFormat.WAV)
}

fun supportedCodecs(format: ExportFormat): List<ExportCodec> {
    return if (format == ExportFormat.WAV) listOf(ExportCodec.PCM_16) else emptyList()
}

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
