package app.smallthingz.reverb

/** Released option resolution; raw retention input is deliberately not normalized on restoration. */
internal data class NativeSettingsAudioOptions(
    val routes: List<InputRouteMode>,
    val sources: List<AudioSourceMode>,
    val channels: List<ChannelMode>,
    val rates: List<Int>,
)

internal fun nativeSettingsAudioOptions(
    snapshot: SettingsSnapshot,
    routes: List<InputRouteMode>,
    sources: List<AudioSourceMode> = AudioSourceMode.availableModes(),
    fallbackRate: Int = 48_000,
): NativeSettingsAudioOptions {
    val preferred = snapshot.sampleRate.takeIf { it > 0 } ?: fallbackRate
    return NativeSettingsAudioOptions(
        routes = routes,
        sources = sources,
        channels = ChannelMode.entries,
        rates = orderSampleRatesByPreference(
            buildList {
                if (preferred > 0) add(preferred)
                addAll(standardSampleRates())
            }.distinct(),
            preferred,
        ),
    )
}

internal fun resolveNativeSettingsRestoredDraft(
    draft: SettingsUiDraft,
    routes: List<InputRouteMode>,
    sources: List<AudioSourceMode> = AudioSourceMode.availableModes(),
    fallbackRate: Int = 48_000,
): SettingsUiDraft {
    val values = draft.snapshot
    val options = nativeSettingsAudioOptions(values, routes, sources, fallbackRate)
    val preferred = values.sampleRate.takeIf { it > 0 } ?: fallbackRate
    return draft.copy(snapshot = values.copy(
        format = values.format ?: ExportFormat.WAV,
        codec = ExportCodec.PCM_16,
        route = values.route ?: InputRouteMode.AUTO,
        source = values.source?.takeIf { it in options.sources } ?: options.sources.first(),
        channelMode = values.channelMode?.takeIf { it in options.channels } ?: options.channels.first(),
        sampleRate = preferred.takeIf { it in options.rates } ?: options.rates.first(),
        silenceGate = encodeSilenceGate(decodeSilenceGate(values.silenceGate)),
    ))
}

internal fun resolveNativeSettingsBaseline(
    stored: NativeSettingsStoredConfiguration,
    routes: List<InputRouteMode>,
    sources: List<AudioSourceMode> = AudioSourceMode.availableModes(),
    fallbackRate: Int = 48_000,
): SettingsDraftBaseline {
    val values = SettingsSnapshot(
        themeMode = stored.themeMode,
        retentionMode = stored.retention.mode,
        oneShotRetentionTime = stored.retention.oneShotSeconds.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
        oneShotRetentionSizeBytes = stored.retention.oneShotSizeBytes,
        loopingRetentionTime = stored.retention.loopingSeconds.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
        loopingRetentionSizeBytes = stored.retention.loopingSizeBytes,
        format = stored.format,
        codec = ExportCodec.PCM_16,
        sampleFormat = stored.sampleFormat,
        source = stored.source,
        channelMode = stored.channelMode,
        route = stored.route.takeIf { it in routes } ?: routes.first(),
        sampleRate = stored.sampleRate,
        exportDirectoryUri = stored.exportTreeUri?.toString(),
        wakeLockEnabled = stored.wakeLockEnabled,
        flacCompression = stored.flacCompression,
        quickSaveEnabled = stored.quickSaveEnabled,
        quickSaveSeconds = stored.quickSaveSeconds,
        silenceGate = encodeSilenceGate(stored.silence.config),
        silenceHistogram = stored.silenceHistogram,
    )
    val resolved = resolveNativeSettingsRestoredDraft(SettingsUiDraft.normalized(values), routes, sources, fallbackRate)
    return SettingsDraftBaseline(SettingsUiDraft.normalized(resolved.snapshot), stored.silence.raw)
}

internal data class NativeSettingsRetentionEstimates(
    val oneShotSizeMb: Double,
    val loopingSizeMb: Double,
    val oneShotSeconds: Long,
    val loopingSeconds: Long,
)

internal fun nativeSettingsRetentionEstimates(values: SettingsSnapshot): NativeSettingsRetentionEstimates {
    val rate = values.sampleRate
    val channels = values.channelMode?.channelCount ?: ChannelMode.MONO.channelCount
    if (rate <= 0) return NativeSettingsRetentionEstimates(0.0, 0.0, 0, 0)
    fun size(seconds: Int): Double =
        bytesForRetentionSeconds(seconds.toLong(), rate, channels, values.sampleFormat)
            .coerceAtLeast(0L) / (1024L * 1024L).toDouble()
    return NativeSettingsRetentionEstimates(
        size(values.oneShotRetentionTime),
        size(values.loopingRetentionTime),
        retentionSecondsForBytes(values.oneShotRetentionSizeBytes, rate, channels, values.sampleFormat),
        retentionSecondsForBytes(values.loopingRetentionSizeBytes, rate, channels, values.sampleFormat),
    )
}
