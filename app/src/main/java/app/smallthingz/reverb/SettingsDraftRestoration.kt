package app.smallthingz.reverb

/** Values only: Activity recreation must not restore jobs, readiness or transaction ownership. */
internal data class SettingsUiDraft(
    val snapshot: SettingsSnapshot,
    val oneShotTimeText: String,
    val oneShotSizeText: String,
    val loopingTimeText: String,
    val loopingSizeText: String,
) {
    fun hasUnsavedChanges(baseline: SettingsSnapshot): Boolean {
        if (snapshot != baseline) return true
        return when (snapshot.retentionMode) {
            RetentionMode.TIME -> parseRetentionTimeSeconds(oneShotTimeText) == null ||
                parseRetentionTimeSeconds(loopingTimeText) == null
            RetentionMode.SIZE -> parseRetentionSizeMib(oneShotSizeText)?.takeIf { it >= 0.0 } == null ||
                parseRetentionSizeMib(loopingSizeText)?.takeIf { it >= 0.0 } == null
        }
    }

    // Primitive saveable values keep byte counts exact and enum identity independent of labels.
    fun save(): List<Any> = listOf(
        2,
        snapshot.themeMode.storageCode.toInt(),
        snapshot.retentionMode.storageCode.toInt(),
        snapshot.oneShotRetentionTime,
        snapshot.oneShotRetentionSizeBytes,
        snapshot.loopingRetentionTime,
        snapshot.loopingRetentionSizeBytes,
        snapshot.format?.storageCode?.toInt() ?: -1,
        snapshot.codec?.storageCode?.toInt() ?: -1,
        snapshot.sampleFormat.storageCode.toInt(),
        snapshot.source?.storageCode?.toInt() ?: -1,
        snapshot.channelMode?.storageCode?.toInt() ?: -1,
        snapshot.route?.storageCode?.toInt() ?: -1,
        snapshot.sampleRate,
        snapshot.exportDirectoryUri.orEmpty(),
        snapshot.wakeLockEnabled,
        snapshot.flacCompression,
        snapshot.quickSaveEnabled,
        snapshot.quickSaveSeconds,
        snapshot.silenceGate,
        oneShotTimeText,
        oneShotSizeText,
        loopingTimeText,
        loopingSizeText,
        snapshot.silenceHistogram.orEmpty(),
    )

    companion object {
        fun normalized(snapshot: SettingsSnapshot) = SettingsUiDraft(
            snapshot = snapshot,
            oneShotTimeText = formatRetentionTimeInput(snapshot.oneShotRetentionTime.toLong()),
            oneShotSizeText = formatRetentionSizeBytes(snapshot.oneShotRetentionSizeBytes),
            loopingTimeText = formatRetentionTimeInput(snapshot.loopingRetentionTime.toLong()),
            loopingSizeText = formatRetentionSizeBytes(snapshot.loopingRetentionSizeBytes),
        )

        fun restore(saved: List<Any>): SettingsUiDraft? = runCatching {
            require((saved.size == 24 && saved[0] == 1) || (saved.size == 25 && saved[0] == 2))
            fun <T> nullableCode(index: Int, decode: (Int) -> T?): T? {
                val code = saved[index] as Int
                return if (code == -1) null else requireNotNull(decode(code))
            }
            SettingsUiDraft(
                snapshot = SettingsSnapshot(
                    themeMode = requireNotNull(AppThemeMode.fromStorageCode(saved[1] as Int)),
                    retentionMode = requireNotNull(RetentionMode.fromStorageOrNull(saved[2] as Int)),
                    oneShotRetentionTime = saved[3] as Int,
                    oneShotRetentionSizeBytes = saved[4] as Long,
                    loopingRetentionTime = saved[5] as Int,
                    loopingRetentionSizeBytes = saved[6] as Long,
                    format = nullableCode(7, ExportFormat::fromStorageCode),
                    codec = nullableCode(8, ExportCodec::fromStorageCode),
                    sampleFormat = requireNotNull(PcmSampleFormat.fromStorageCode(saved[9] as Int)),
                    source = nullableCode(10) { code ->
                        AudioSourceMode.entries.firstOrNull { it.storageCode.toInt() == code }
                    },
                    channelMode = nullableCode(11, ChannelMode::fromStorageCode),
                    route = nullableCode(12, InputRouteMode::fromStorageCode),
                    sampleRate = saved[13] as Int,
                    exportDirectoryUri = (saved[14] as String).takeIf { it.isNotEmpty() },
                    wakeLockEnabled = saved[15] as Boolean,
                    flacCompression = saved[16] as Int,
                    quickSaveEnabled = saved[17] as Boolean,
                    quickSaveSeconds = saved[18] as Int,
                    silenceGate = saved[19] as String,
                    silenceHistogram = if (saved[0] == 2) (saved[24] as? String)
                        ?.takeIf { decodeSilenceCalibrationHistogram(it) != null } else null,
                ),
                oneShotTimeText = saved[20] as String,
                oneShotSizeText = saved[21] as String,
                loopingTimeText = saved[22] as String,
                loopingSizeText = saved[23] as String,
            )
        }.getOrNull()
    }
}

internal data class SettingsDraftHydration(
    val baseline: SettingsSnapshot,
    val displayed: SettingsUiDraft,
    val restored: Boolean,
) {
    val hasUnsavedChanges: Boolean get() = displayed.hasUnsavedChanges(baseline)
    val themePreview: AppThemeMode? get() = displayed.snapshot.themeMode.takeIf { it != baseline.themeMode }
}

/** Pending restored values remain inert until a complete, current durable hydration succeeds. */
internal class SettingsDraftRestoration(private var pending: SettingsUiDraft? = null) {
    fun save(
        panelActive: Boolean,
        hydrated: Boolean,
        persisting: Boolean,
        baseline: SettingsSnapshot,
        currentDraft: () -> SettingsUiDraft,
    ): List<Any> {
        if (!panelActive) return emptyList()
        if (!hydrated) return pending?.save().orEmpty()
        val draft = currentDraft()
        // Preserve text-only/inactive edits too. During Save, even a reversal to the old
        // baseline can be a newer draft relative to the value the transaction will commit.
        return if (persisting || draft.snapshot != baseline || draft != SettingsUiDraft.normalized(draft.snapshot)) {
            draft.save()
        } else emptyList()
    }

    fun hydrate(
        expectedEditRevision: Long,
        currentEditRevision: Long,
        readBaseline: () -> SettingsUiDraft,
    ): SettingsDraftHydration? {
        if (!settingsHydrationMayApply(expectedEditRevision, currentEditRevision)) return null
        val baseline = readBaseline()
        val restored = pending
        pending = null
        return SettingsDraftHydration(baseline.snapshot, restored ?: baseline, restored != null)
    }

    fun discard() {
        pending = null
    }

    companion object {
        fun restore(saved: List<Any>) = SettingsDraftRestoration(SettingsUiDraft.restore(saved))
    }
}
