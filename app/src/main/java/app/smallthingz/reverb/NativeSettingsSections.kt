package app.smallthingz.reverb

import smallthingz.ui.common.ToggleState
import smallthingz.ui.common.DropdownContent
import smallthingz.ui.input.phase.NativeDragPointerChange
import smallthingz.ui.input.phase.NativeViewPhasePointerNode
import android.content.Context
import android.os.LocaleList
import android.view.View
import androidx.lifecycle.Lifecycle
import app.smallthingz.reverb.nativeui.*
import smallthingz.ui.reverb.settings.ReverbAudioActions
import smallthingz.ui.reverb.settings.ReverbAudioChoices
import smallthingz.ui.reverb.settings.ReverbAudioOptions
import smallthingz.ui.reverb.settings.ReverbSettingsToggle
import smallthingz.ui.reverb.settings.ReverbSettingsToggleRole
import smallthingz.ui.reverb.settings.ReverbRecordingPresentation
import smallthingz.ui.reverb.settings.ReverbRecordingSettingsView
import smallthingz.ui.reverb.settings.ReverbSettingsBackgroundWarningView
import smallthingz.ui.reverb.settings.ReverbThemeSelector
import kotlinx.coroutines.CoroutineScope

/** Direct released section bindings; the list owns each keyed section's actual lifetime. */
internal class NativeSettingsSections(
    private val context: Context,
    private val owner: NativeSettingsOwner,
    private val environment: () -> ReverbViewEnvironment,
    private val lifecycle: Lifecycle,
    private val inputRoot: () -> View?,
    private val animationScope: CoroutineScope,
    private val parentInterested: (NativeDragPointerChange) -> Boolean,
    private val focusRetention: () -> ReverbService.BufferSlot?,
    private val deleteTargetChanged: (NativeQuickSettingsTilesOwner, QuickSettingsTileDeleteTarget?) -> Unit,
    private val structureChanged: () -> Unit,
) : SettingsSectionFactory {
    private val recordings = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Recording, Boolean>())
    fun dismissAudioChoices() = recordings.forEach { it.dismissAudioChoice() }

    fun bindDetachedRecordingSections() {
        recordings.forEach { if (!it.view.isAttachedToWindow) it.bind() }
    }

    fun refreshCalibration() { recordings.filter { it.view.isAttachedToWindow }.forEach { it.refreshCalibration() } }
    fun pointerNodes(view: View): List<NativeViewPhasePointerNode> {
        for (recording in recordings) {
            val nodes = recording.pointerNodes(view)
            if (nodes.isNotEmpty()) return nodes
        }
        return emptyList()
    }
    override fun create(key: SettingsSectionKey): NativeSettingsSection = when (key) {
        SettingsSectionKey.BACKGROUND -> background()
        SettingsSectionKey.THEME -> theme()
        SettingsSectionKey.RETENTION -> retention()
        SettingsSectionKey.RECORDING -> Recording().also { recordings.add(it) }
        SettingsSectionKey.QUICK_TILES -> tiles()
        SettingsSectionKey.STORAGE -> storage()
        SettingsSectionKey.AUTO_START -> error("Auto-start is owned by the Recording settings group")
    }

    private fun background(): NativeSettingsSection {
        var activation = owner.state.activation
        val body = ReverbSettingsBackgroundWarningView(context, environment(), inputRoot,
            owner.batteryOptimizationRestricted, owner.resumeNotificationBlocked, owner.restartAccessMissing,
            { owner.reviewBattery(activation) }, { owner.reviewResumeNotifications(activation) },
            { owner.reviewRestartAccess(activation) })
        return object : NativeSettingsSection {
            override val view = body
            override fun bind() {
                activation = owner.state.activation
                body.bind(environment(), owner.batteryOptimizationRestricted, owner.resumeNotificationBlocked,
                    owner.restartAccessMissing)
            }
            override fun close() = body.close()
        }
    }

    private fun theme(): NativeSettingsSection {
        val initial = owner.state
        var activation = initial.activation
        val body = ReverbThemeSelector(context, environment().theme,
            initial.displayed.snapshot.themeMode.toNativeThemeMode(),
            inputAllowed = { inputRoot() != null && owner.acceptsInput(activation) },
            onSelectionRequest = { owner.themeChanged(activation, it.toAppThemeMode()) })
        return object : NativeSettingsSection {
            override val view = body.view
            override fun bind() {
                val state = owner.state
                activation = state.activation
                body.bind(state.displayed.snapshot.themeMode.toNativeThemeMode(), environment().theme)
            }
            override fun close() = body.close()
        }
    }

    private fun retention(): NativeSettingsSection {
        var activation = owner.state.activation
        val firstEditor = SettingsRetentionInputView(context, environment())
        val secondEditor = SettingsRetentionInputView(context, environment())
        val firstLabel = context.getString(R.string.buffer_one_shot)
        val secondLabel = context.getString(R.string.retention_loop_label)
        val first = SettingsRetentionValueView(context, ReverbIcon.OneShot, firstLabel, firstEditor, environment())
        val second = SettingsRetentionValueView(context, ReverbIcon.Looping, secondLabel, secondEditor, environment())
        val body = SettingsRetentionSection(context, owner.snapshot.retentionMode, environment(), inputRoot,
            { owner.retentionModeChanged(activation, it) }, first, second)
        return object : NativeSettingsSection {
            override val view = body
            override fun bind() {
                val state = owner.state
                activation = state.activation
                val values = state.displayed
                val mode = values.snapshot.retentionMode
                val env = environment()
                val focus = focusRetention().takeIf { owner.acceptsInput(activation) }
                firstEditor.bind(
                    if (mode == RetentionMode.TIME) values.oneShotTimeText else values.oneShotSizeText,
                    mode, firstLabel, env, focus == ReverbService.BufferSlot.ONE_SHOT,
                    readCurrentRaw = {
                        val current = owner.state.displayed
                        if (current.snapshot.retentionMode == RetentionMode.TIME) current.oneShotTimeText else current.oneShotSizeText
                    },
                    onRawChanged = {
                        if (mode == RetentionMode.TIME) owner.oneShotTimeChanged(activation, it)
                        else owner.oneShotSizeChanged(activation, it)
                    },
                )
                secondEditor.bind(
                    if (mode == RetentionMode.TIME) values.loopingTimeText else values.loopingSizeText,
                    mode, secondLabel, env, focus == ReverbService.BufferSlot.LOOPING,
                    readCurrentRaw = {
                        val current = owner.state.displayed
                        if (current.snapshot.retentionMode == RetentionMode.TIME) current.loopingTimeText else current.loopingSizeText
                    },
                    onRawChanged = {
                        if (mode == RetentionMode.TIME) owner.loopingTimeChanged(activation, it)
                        else owner.loopingSizeChanged(activation, it)
                    },
                )
                val estimates = owner.estimates
                first.bind(mode, estimates.oneShotSizeMb, estimates.oneShotSeconds, env)
                second.bind(mode, estimates.loopingSizeMb, estimates.loopingSeconds, env)
                val errors = owner.errors
                val error = if (mode == RetentionMode.TIME) errors.oneShotTime ?: errors.loopingTime
                    else errors.oneShotSize ?: errors.loopingSize
                body.bind(mode, error?.let(context::getString), env)
            }
            override fun close() {
                firstEditor.releaseCallbacks(); secondEditor.releaseCallbacks(); body.close()
            }
        }
    }

    private inner class Recording : NativeSettingsSection {
        private var activation = owner.state.activation
        private var optionLocales: LocaleList? = null
        private val translatedAudioLabels = HashMap<Int, String>()
        private val sampleRateLabels = HashMap<Int, String>()
        private var silenceRaw: String? = null
        private var histogramRaw: String? = null
        private var silence = decodeSilenceGate(owner.snapshot.silenceGate)
        private var histogram: SilenceCalibrationHistogram? = null
        private val choices = ReverbAudioChoices(
            context,
            environment().theme, audioState(),
            ReverbAudioActions(
                { owner.channelChanged(activation, it) },
                { owner.sampleFormatChanged(activation, it) },
                { owner.sampleRateChanged(activation, it) },
                { owner.sourceChanged(activation, it) },
                { owner.routeChanged(activation, it) },
            ), inputRoot,
            inputAllowed = { owner.acceptsInput(activation) && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) },
        )
        private val compression = SettingsOutputCompressionView(
            context, compressionState(), environment(), inputRoot, animationScope, parentInterested,
            { owner.outputCompressionChanged(activation, it) }, { owner.compressionLevelChanged(activation, it) },
        )
        private val skip = SettingsSilenceView(context, silenceState(), environment(), inputRoot,
            animationScope, silenceActions())
        private val notification = SettingsNotificationSaveView(
            context, notificationState(), environment(), inputRoot, lifecycle, owner.notificationDurationInteraction,
            { owner.notificationEnabledChanged(activation, it) }, owner::notificationSecondsChanged,
            owner::notificationEditRevision,
        )
        private val autoStart = ReverbSettingsToggle(context, environment().theme, autoStartState(),
            ReverbSettingsToggleRole.AUTO_START, inputRoot) { owner.autoStartChanged(activation, it) }
        override val view = ReverbRecordingSettingsView(context, choices, compression, skip, notification, autoStart.view,
            recordingPresentation(), environment().theme)

        override fun bind() {
            val state = owner.state
            activation = state.activation
            val env = environment()
            choices.bind(audioState(), env.theme)
            compression.bind(compressionState(), env)
            skip.bind(silenceState(), env, silenceActions())
            notification.callbacks(owner::notificationSecondsChanged, owner::notificationEditRevision,
                owner::childInteractionChanged)
            notification.bind(notificationState(), env)
            autoStart.bind(autoStartState(), env.theme)
            view.bind(recordingPresentation(), env.theme)
            structureChanged()
        }

        fun dismissAudioChoice() = choices.dismissDropdown()
        fun refreshCalibration() { skip.bind(silenceState(), environment(), silenceActions()) }
        fun pointerNodes(view: View): List<NativeViewPhasePointerNode> {
            compression.currentSlider?.let { if (it.pointerView === view) return it.pointerNodes }
            skip.calibrationPointerNode?.let { if (it.view === view) return listOf(it) }
            return emptyList()
        }

        private fun audioLabel(resource: Int): String =
            translatedAudioLabels.getOrPut(resource) { context.getString(resource) }

        private fun audioState(): ReverbAudioOptions<ChannelMode, PcmSampleFormat, AudioSourceMode, InputRouteMode> {
            val locales = context.resources.configuration.locales
            if (optionLocales != locales) {
                optionLocales = locales
                translatedAudioLabels.clear()
            }
            val value = owner.snapshot
            val options = owner.audioOptions
            return ReverbAudioOptions(
                DropdownContent(audioLabel(R.string.channel_mode_label),
                    requireNotNull(value.channelMode), options.channels) { audioLabel(it.labelRes) },
                DropdownContent(audioLabel(R.string.sample_format_label),
                    value.sampleFormat, PcmSampleFormat.entries) { audioLabel(it.labelRes) },
                DropdownContent(audioLabel(R.string.sample_rate_label), value.sampleRate, options.rates) {
                    sampleRateLabels.getOrPut(it) { sampleRateLabel(it) }
                },
                DropdownContent(audioLabel(R.string.audio_source_label),
                    requireNotNull(value.source), options.sources) { audioLabel(it.labelRes) },
                DropdownContent(audioLabel(R.string.input_route_label),
                    requireNotNull(value.route), options.routes) { audioLabel(it.labelRes) },
            )
        }
        private fun recordingPresentation(): ReverbRecordingPresentation {
            val value = owner.snapshot
            return ReverbRecordingPresentation(context.getString(R.string.recording_settings_title),
                if (value.format == ExportFormat.FLAC && value.sampleFormat != PcmSampleFormat.PCM_16)
                    context.getString(R.string.flac_pcm_required) else null)
        }
        private fun compressionState(): SettingsOutputCompressionState {
            val state = owner.state
            val value = state.displayed.snapshot
            return SettingsOutputCompressionState(
                value.format == ExportFormat.FLAC, value.flacCompression,
                state.interactionReady && !state.persisting,
            )
        }
        private fun silenceState(): SettingsSilenceState {
            val state = owner.state
            val value = state.displayed.snapshot
            if (silenceRaw != value.silenceGate) { silenceRaw = value.silenceGate; silence = decodeSilenceGate(value.silenceGate) }
            if (histogramRaw != value.silenceHistogram) {
                histogramRaw = value.silenceHistogram
                histogram = decodeSilenceCalibrationHistogram(value.silenceHistogram)
            }
            val input = value.silenceCalibrationInput()
            return SettingsSilenceState(
                config = silence,
                enabled = state.interactionReady && !state.persisting,
                maximumHz = minOf(SILENCE_MAX_HZ,
                    (histogram?.takeIf { it.peaksFor(input) != null }?.measuredSampleRate ?: value.sampleRate) / 2),
                sampledPeaks = histogram?.peaksFor(input),
                calibrationAllowed = state.baseline.silenceCalibrationInput() == input,
                calibrating = owner.calibration.running,
                holdingCalibration = owner.calibration.holding,
                calibrationProgress = owner.calibration.progress,
                calibrationError = owner.calibration.error,
                calibrationUnavailableReason = context.getString(R.string.silence_apply_input_first),
            )
        }
        private fun silenceActions(): SettingsSilenceActions {
            val state = owner.state
            val token = state.activation
            val revision = state.editRevision
            val rendered = silence
            return object : SettingsSilenceActions {
                override fun configChanged(config: SilenceGateConfig) = owner.silenceChanged(token, rendered, revision, config)
                override fun calibrate() = owner.beginTimedCalibration()
                override fun beginHeld() = owner.beginHeldCalibration()
                override fun completeTap() = owner.calibration.completeTap()
                override fun finishHeld() = owner.calibration.finishHeld()
                override fun cancel() = owner.calibration.cancel()
            }
        }
        private fun notificationState(): SettingsNotificationSaveState {
            val state = owner.state
            val value = state.displayed.snapshot
            return SettingsNotificationSaveState(
                value.quickSaveEnabled, value.quickSaveSeconds,
                exportDurationLimitExactSeconds(requireNotNull(value.format), ExportCodec.PCM_16,
                    value.sampleRate, requireNotNull(value.channelMode).channelCount, value.sampleFormat)
                    .toInt().coerceIn(1, 86_400),
                state.interactionReady && !state.persisting, state.editRevision,
            )
        }
        override fun close() {
            recordings.remove(this)
            var failure: Throwable? = null
            for (owned in listOf(choices, compression, skip, notification, autoStart)) try { owned.close() }
            catch (error: Throwable) { if (failure == null) failure = error else if (failure !== error) failure.addSuppressed(error) }
            failure?.let { throw it }
        }
    }

    private fun tiles(): NativeSettingsSection {
        fun inputs(): NativeQuickTilesInputs {
            val state = owner.state
            return NativeQuickTilesInputs(state.active,
                state.interactionReady && !state.persisting,
                owner.tileCapacityDraftReady(), state.editRevision)
        }
        val controller = NativeQuickSettingsTilesOwner(context, lifecycle, animationScope, owner.quickTileEditorState,
            inputs(), owner::tileActionAdmission, owner::tileActionRevision, owner::tileDraftAdmission, owner::tileDraftRevision)
        val body = SettingsQuickTilesView(context, controller, environment(), inputRoot,
            owner::childInteractionChanged, { deleteTargetChanged(controller, it) })
        return object : NativeSettingsSection {
            override val view = body
            override fun bind() { body.bind(inputs(), environment()); structureChanged() }
            override fun close() = body.close()
        }
    }

    private fun storage(): NativeSettingsSection {
        val initial = owner.state
        var activation = initial.activation
        fun state(current: SettingsDraftPresentation) = SettingsStorageState(
            owner.exportPathText, current.displayed.snapshot.exportDirectoryUri != null,
            owner.canMove && !owner.settingsCommitBlocked, current.persisting,
            supportsAllFilesAccess(), owner.allFilesAllowed,
            recordingAudioReadPermission() != null && !owner.allFilesAllowed, owner.audioReadAllowed)
        val body = SettingsStorageSection(context, state(initial), environment(), inputRoot,
            { owner.useDefaultDirectory(activation) }, { owner.chooseDirectory(activation) },
            { owner.moveExistingRecordings(activation) }, { owner.reviewAllFilesAccess(activation) },
            { owner.reviewAudioReadAccess(activation) })
        return object : NativeSettingsSection {
            override val view = body
            override fun bind() {
                val current = owner.state
                activation = current.activation
                body.bind(state(current), environment())
                structureChanged()
            }
            override fun close() = body.close()
        }
    }

    private fun autoStartState(): ToggleState {
        val choice = owner.autoStartChoice
        val description = when {
            choice.unavailable -> R.string.auto_start_unavailable
            choice.permissionPending -> R.string.auto_start_permission_pending
            choice.enabled && owner.restartAccessMissing -> R.string.auto_start_permission_missing
            else -> R.string.auto_start_summary
        }
        return ToggleState(context.getString(R.string.auto_start_title), choice.permissionPending || choice.enabled,
            choice.loaded, context.getString(description))
    }

}
