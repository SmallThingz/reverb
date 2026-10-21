package app.smallthingz.reverb

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Mechanical durable IO/runtime slice of released SettingsScreen. UI admission and raw-field
 * validation remain with the draft owner; accepted commit/rollback/runtime ordering stays intact.
 */
internal class NativeSettingsPersistence(
    private val context:Context,
    private val readService:()->ReverbService?,
) {
    private val resources get()=context.resources
    private val service get()=readService()

    /** Called inside SettingsDraftController.hydrate's existing readSettled barrier. */
    suspend fun readStoredConfiguration():NativeSettingsStoredConfiguration=withContext(Dispatchers.IO) {
        withRetentionPersistenceLock {
            NativeSettingsStoredConfiguration(
                format = getConfiguredOutputFormat(context),
                themeMode = getConfiguredThemeMode(context),
                retention = retentionConfigurationForRead(context),
                sampleFormat = getConfiguredPcmSampleFormat(context),
                route = getConfiguredInputRouteMode(context),
                source = getConfiguredAudioSourceMode(context),
                channelMode = getConfiguredChannelMode(context),
                sampleRate = getConfiguredSampleRate(context),
                exportTreeUri = getConfiguredExportTreeUriForSettings(context),
                wakeLockEnabled = isWakeLockEnabled(context),
                flacCompression = getConfiguredFlacCompression(context),
                quickSaveEnabled = getConfiguredQuickSaveEnabled(context),
                quickSaveSeconds = getConfiguredQuickSaveSeconds(context),
                silence = SilenceGatePreferences.read(context),
                silenceHistogram = getRecorderPreferences(context)
                    .safeString(PrefKey.SILENCE_CALIBRATION_HISTOGRAM)
                    .takeIf { decodeSilenceCalibrationHistogram(it) != null },
            )
        }
    }

    /** Caller supplies the already validated submission; no editor normalization or UI save tail. */
    @SuppressLint("UseKtx")
    suspend fun persist(previous:SettingsSnapshot,submission:SettingsDraftController.Submission):ExportFormat? {
        val submittedSnapshot=submission.snapshot
        val submittedSilenceRaw=submission.originalSilenceRaw
        val originalSnapshot=previous
        val currentSnapshot=submittedSnapshot
        val activeRetentionMode=submittedSnapshot.retentionMode
        val oneShotRetentionTime=submittedSnapshot.oneShotRetentionTime
        val loopingRetentionTime=submittedSnapshot.loopingRetentionTime
        val requestedOneShotSizeBytes=submittedSnapshot.oneShotRetentionSizeBytes
        val requestedLoopingSizeBytes=submittedSnapshot.loopingRetentionSizeBytes
        val format=requireNotNull(submittedSnapshot.format)
        val codec=ExportCodec.PCM_16
        val sampleFormat=submittedSnapshot.sampleFormat
        val source=requireNotNull(submittedSnapshot.source)
        val channelMode=requireNotNull(submittedSnapshot.channelMode)
        val route=requireNotNull(submittedSnapshot.route)
        val sampleRate=submittedSnapshot.sampleRate
        val selectedTheme=submittedSnapshot.themeMode
        val selectedExportTreeUri=submittedSnapshot.exportDirectoryUri?.let(Uri::parse)
        val retentionConfiguration=RetentionConfiguration(activeRetentionMode,
            oneShotRetentionTime.toLong(),requestedOneShotSizeBytes,
            loopingRetentionTime.toLong(),requestedLoopingSizeBytes)
        var committedOutputFormat=format
        val preferences=getRecorderPreferences(context)
        val invalidateCachedOneShotFull = shouldInvalidateCachedOneShotFull(
            previousMode = originalSnapshot.retentionMode,
            newMode = activeRetentionMode,
            previousTimeSeconds = originalSnapshot.oneShotRetentionTime,
            newTimeSeconds = oneShotRetentionTime,
            previousSizeBytes = originalSnapshot.oneShotRetentionSizeBytes,
            newSizeBytes = requestedOneShotSizeBytes,
            previousSampleRate = originalSnapshot.sampleRate,
            newSampleRate = sampleRate,
            previousChannelMode = originalSnapshot.channelMode,
            newChannelMode = channelMode,
            previousSampleFormat = originalSnapshot.sampleFormat,
            newSampleFormat = sampleFormat,
        )
        val settingsEditor = preferences.edit()
            .putInt(PrefKey.RETENTION_MODE, activeRetentionMode.storageCode.toInt())
            .putLong(PrefKey.ONE_SHOT_RETENTION_SECONDS, oneShotRetentionTime.toLong())
            .putLong(PrefKey.ONE_SHOT_AUDIO_MEMORY_SIZE, requestedOneShotSizeBytes)
            .putLong(PrefKey.RETENTION_SECONDS, loopingRetentionTime.toLong())
            .putLong(PrefKey.AUDIO_MEMORY_SIZE, requestedLoopingSizeBytes)
            .putString(PrefKey.RETENTION_CONFIG_DIGEST, retentionConfigurationDigest(retentionConfiguration))
            .putInt(PrefKey.OUTPUT_CODEC, codec.storageCode.toInt())
            .putInt(PrefKey.FLAC_COMPRESSION_LEVEL, submittedSnapshot.flacCompression)
            .putBoolean(PrefKey.QUICK_SAVE_ENABLED, submittedSnapshot.quickSaveEnabled)
            .putInt(PrefKey.QUICK_SAVE_SECONDS, submittedSnapshot.quickSaveSeconds)
            .putString(PrefKey.SILENCE_GATE, submittedSnapshot.silenceGate)
            .putString(PrefKey.SILENCE_CALIBRATION_HISTOGRAM.name, submittedSnapshot.silenceHistogram)
            .putInt(PrefKey.PCM_SAMPLE_FORMAT, sampleFormat.storageCode.toInt())
            .putInt(PrefKey.AUDIO_SOURCE, source.storageCode.toInt())
            .putInt(PrefKey.CHANNEL_MODE, channelMode.storageCode.toInt())
            .putInt(PrefKey.INPUT_ROUTE, route.storageCode.toInt())
            .putInt(PrefKey.SAMPLE_RATE, sampleRate)
            .putBoolean(PrefKey.WAKE_LOCK_ENABLED, currentSnapshot.wakeLockEnabled)
            .putInt(PrefKey.THEME_MODE, selectedTheme.storageCode.toInt())
        if (invalidateCachedOneShotFull) {
            settingsEditor.putBoolean(PrefKey.QUICK_TILE_ONE_SHOT_FULL, false)
        }
        if (selectedExportTreeUri != null) {
            settingsEditor.putString(PrefKey.EXPORT_DIRECTORY_URI, selectedExportTreeUri.toString())
        } else {
            settingsEditor.remove(PrefKey.EXPORT_DIRECTORY_URI)
        }
        val transactionService = service
        // Once the recovery/preferences transaction starts, a configuration change or Activity
        // disposal may cancel only the UI tail. A successful durable commit must still reach the
        // surviving recorder (or stopped tile fallback) before this section can terminate.
        val persisted = runDurableUiBooleanAttempt {
            processSettingsCommitBarrier.runAcceptedSave {
                val committed = withContext(Dispatchers.IO) {
                withRetentionPersistenceLock {
                    val settingsCommitted = SilenceGatePreferences.withSettingsTransaction(context, submittedSilenceRaw) {
                    // Roll back to the state that was actually durable when this transaction started,
                    // not to the UI's older edit snapshot. Another writer may have committed since
                    // Settings opened.
                    val rollbackRetention = retentionConfigurationForRead(context)
                    val oneShotFullRollback = if (invalidateCachedOneShotFull) {
                        RecordingQuickTileStateCache.snapshotOneShotFullPreferenceRollback(preferences)
                    } else {
                        null
                    }
                    val rollbackPreferences = settingsPreferenceRollbackSnapshot(
                        rawPreferences = preferences.all,
                        invalidateCachedOneShotFull = invalidateCachedOneShotFull,
                        coordinatedOneShotFullSnapshot = oneShotFullRollback?.rawValue,
                    )

                    // A replacement can sample SharedPreferences' attempted value during commit.
                    // Retain the authoritative pre-write theme even if this first save rolls back.
                    processCommittedTheme.initializeBaseline(getConfiguredThemeMode(context))

                    var transactionCommitted = false
                    try {
                        transactionCommitted = persistRetentionTransaction(
                            // Recovery is the write-ahead side of the transaction. If the process dies before
                            // preferences commit, restart sees a mismatch and existing history fails closed.
                            writeNewRecovery = { writeRetentionRecoveryConfiguration(context, retentionConfiguration) },
                            commitNewPreferences = {
                                // An untouched control follows a newer durable output preference;
                                // an explicit selection owns its value in this atomic transaction.
                                commitSettingsForSelectedOutput(
                                    originalFormat = previous.format ?: ExportFormat.WAV,
                                    selectedFormat = format,
                                    readCurrentFormat = { getConfiguredOutputFormat(context) },
                                    codec = codec,
                                    sampleRate = sampleRate,
                                    channelMode = channelMode,
                                    sampleFormat = sampleFormat,
                                    commit = { output ->
                                        committedOutputFormat = output
                                        settingsEditor.putInt(PrefKey.OUTPUT_FORMAT, output.storageCode.toInt()).commit()
                                    },
                                )
                            },
                            restoreRecovery = { writeRetentionRecoveryConfiguration(context, rollbackRetention) },
                            restorePreferences = {
                                val editor = preferences.edit()
                                rollbackPreferences.forEach { (key, snapshot) ->
                                    editor.restoreDurablePreferenceValue(key, snapshot)
                                }
                                editor.commit()
                            },
                        )
                        transactionCommitted
                    } finally {
                        if (!transactionCommitted && oneShotFullRollback != null) {
                            RecordingQuickTileStateCache.reconcileOneShotFullAfterFailedSettings(
                                preferences = preferences,
                                token = oneShotFullRollback,
                            )
                        }
                    }
                    }
                    // Publish the exact durable value before releasing transaction ordering.
                    // Replacement Activities observe this independently of the old UI tail.
                    if (settingsCommitted) processCommittedTheme.publishCommitted(submittedSnapshot.themeMode)
                    settingsCommitted
                }
                }
                if (committed) applyCommittedSettingsToRuntime(transactionService)
                committed
            }
        }
        if(!persisted) {
            AppFeedbackCenter.post(resources.getString(R.string.recorder_state_persist_failed),FeedbackTone.ERROR)
            return null
        }
        return committedOutputFormat
    }

    fun refreshStoppedQuickTileFallback() {
        RecordingQuickTileStateCache.markRuntimeUnavailable(context)
        RecordingQuickTiles.refreshCachedSnapshot(
            context = context,
            requestSystemRefresh = true,
        )
    }

    fun applyCommittedSettingsToRuntime(transactionService: ReverbService?) {
        val currentService = service ?: transactionService
        if (currentService?.applyUpdatedPreferences() == true) return
        val appContext = context.applicationContext
        if (getRecorderPreferences(appContext).safeBoolean(PrefKey.AUDIO_MEMORY_ENABLED, false)) {
            runCatching {
                startCommittedSettingsRuntimeReload(appContext)
            }.onFailure {
                // Durable settings changed but no recorder accepted the reload. Replace stale
                // runtime tile state immediately with a fail-closed snapshot, then hydrate the
                // committed stopped settings on the tile IO worker.
                refreshStoppedQuickTileFallback()
                AppFeedbackCenter.post(
                    resources.getString(R.string.settings_apply_failed),
                    FeedbackTone.ERROR,
                )
            }
        } else {
            refreshStoppedQuickTileFallback()
        }
    }

}

internal data class NativeSettingsStoredConfiguration(
    val format: ExportFormat,
    val themeMode: AppThemeMode,
    val retention: RetentionConfiguration,
    val sampleFormat: PcmSampleFormat,
    val route: InputRouteMode,
    val source: AudioSourceMode,
    val channelMode: ChannelMode,
    val sampleRate: Int,
    val exportTreeUri: Uri?,
    val wakeLockEnabled: Boolean,
    val flacCompression: Int,
    val quickSaveEnabled: Boolean,
    val quickSaveSeconds: Int,
    val silence: SilenceGatePreferenceSnapshot,
    val silenceHistogram: String?,
)

