package app.smallthingz.reverb

import smallthingz.ui.input.phase.NativeDragPointerChange
import smallthingz.ui.input.phase.NativeViewPhasePointerNode

import android.content.Context
import android.view.View
import android.view.ViewGroup
import smallthingz.ui.runtime.MonotonicFrameClock
import androidx.lifecycle.Lifecycle
import app.smallthingz.reverb.nativeui.*
import smallthingz.ui.components.NativeThemePresentation
import smallthingz.ui.components.NativeSegmentedPresentation
import smallthingz.ui.components.NativeThemeSelector
import smallthingz.ui.controls.ControlPalette
import kotlinx.coroutines.CoroutineScope
import kotlin.math.roundToInt

/** Direct released section bindings; the list owns each keyed section's actual lifetime. */
internal class NativeSettingsSections(
    private val context: Context,
    private val owner: NativeSettingsOwner,
    private val environment: () -> ReverbViewEnvironment,
    private val lifecycle: Lifecycle,
    private val inputRoot: () -> View?,
    private val frames: MonotonicFrameClock,
    private val animationScope: CoroutineScope,
    private val parentInterested: (NativeDragPointerChange) -> Boolean,
    private val focusRetention: () -> ReverbService.BufferSlot?,
    private val deleteTargetChanged: (NativeQuickSettingsTilesOwner, QuickSettingsTileDeleteTarget?) -> Unit,
    private val structureChanged: () -> Unit,
) : SettingsSectionFactory {
    private val recordings = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Recording, Boolean>())
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
        SettingsSectionKey.WAKE_LOCK -> wakeLock()
    }

    private fun background(): NativeSettingsSection {
        var activation = owner.state.activation
        val body = SettingsBackgroundWarningView(context, environment(), inputRoot,
            owner.batteryOptimizationRestricted, owner.resumeNotificationBlocked, owner.restartAccessMissing,
            owner.restartTestArmed, owner.restartTestPending,
            { owner.reviewBattery(activation) }, { owner.reviewResumeNotifications(activation) },
            { owner.restartAction(activation) })
        return object : NativeSettingsSection {
            override val view = body
            override fun bind() {
                activation = owner.state.activation
                body.bind(environment(), owner.batteryOptimizationRestricted, owner.resumeNotificationBlocked,
                    owner.restartAccessMissing, owner.restartTestArmed, owner.restartTestPending)
            }
            override fun close() = body.close()
        }
    }

    private fun theme(): NativeSettingsSection {
        val initial = owner.state
        var activation = initial.activation
        var env = environment()
        fun palette() = segmentedPalette(env)
        lateinit var body: NativeThemeSelector
        val presentation = object : NativeThemePresentation,
            NativeSegmentedPresentation by ReverbSegmentedPresentation(context) {
            override val title = env.paragraph(context, "", ReverbTextRole.BODY_LARGE, env.palette.onSurface)
            override fun bindTitle(titleText: String, palette: ControlPalette, titleColor: Int) {
                title.setText(titleText)
                bindSectionTitle(title, env)
            }
        }
        body = NativeThemeSelector(context, palette(), initial.displayed.snapshot.themeMode.toNativeThemeMode(),
            env.palette.onSurface, presentation, env.textDensity.density, inputAllowed = {
                body.settingsInputAllowed(inputRoot)
            }, onSelectionRequest = {
                owner.themeChanged(activation, it.toAppThemeMode())
                true
            })
        return object : NativeSettingsSection {
            override val view = body
            override fun bind() {
                val state = owner.state
                activation = state.activation
                env = environment()
                body.bind(state.displayed.snapshot.themeMode.toNativeThemeMode(), palette(), env.palette.onSurface, env.textDensity.density)
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
        private var silenceRaw: String? = null
        private var histogramRaw: String? = null
        private var silence = decodeSilenceGate(owner.snapshot.silenceGate)
        private var histogram: SilenceCalibrationHistogram? = null
        private val choices = SettingsAudioChoicesSection(
            context,
            ReverbParagraphStyles[ReverbTextRole.BODY_SMALL],
            ReverbParagraphStyles[ReverbTextRole.BODY_LARGE],
            ReverbParagraphStyles[ReverbTextRole.LABEL_LARGE],
            environment().textDensity, environment().sharedFontResolver,
            audioState(), environment().palette, ::sampleRateLabel,
            SettingsAudioSelections(
                { owner.channelChanged(activation, it) },
                { owner.sampleFormatChanged(activation, it) },
                { owner.sampleRateChanged(activation, it) },
                { owner.sourceChanged(activation, it) },
                { owner.routeChanged(activation, it) },
            ), frames, animationScope, inputRoot = inputRoot,
        )
        private val compression = SettingsOutputCompressionView(
            context, compressionState(), environment(), inputRoot, animationScope, parentInterested,
            { owner.outputCompressionChanged(activation, it) }, { owner.compressionLevelChanged(activation, it) },
        )
        private val warning = environment().paragraph(context, context.getString(R.string.flac_pcm_required),
            ReverbTextRole.BODY_SMALL, environment().palette.error)
        private val skip = SettingsSilenceView(context, silenceState(), environment(), inputRoot,
            animationScope, silenceActions())
        private val notification = SettingsNotificationSaveView(
            context, notificationState(), environment(), inputRoot, lifecycle, owner.notificationDurationInteraction,
            { owner.notificationEnabledChanged(activation, it) }, owner::notificationSecondsChanged,
            owner::notificationEditRevision,
        )
        override val view = SettingsRecordingSection(context, choices, compression, warning, skip, notification, environment())

        override fun bind() {
            val state = owner.state
            activation = state.activation
            val env = environment()
            choices.active(state.active)
            choices.bind(audioState(), env.palette)
            compression.bind(compressionState(), env)
            env.bindParagraphStyle(warning, ReverbTextRole.BODY_SMALL, env.palette.error)
            skip.bind(silenceState(), env, silenceActions())
            notification.callbacks(owner::notificationSecondsChanged, owner::notificationEditRevision,
                owner::childInteractionChanged)
            notification.bind(notificationState(), env)
            val value = owner.snapshot
            view.bind(value.format == ExportFormat.FLAC && value.sampleFormat != PcmSampleFormat.PCM_16, env)
            structureChanged()
        }

        fun refreshCalibration() { skip.bind(silenceState(), environment(), silenceActions()) }
        fun pointerNodes(view: View): List<NativeViewPhasePointerNode> {
            compression.currentSlider?.let { if (it.pointerView === view) return it.pointerNodes }
            skip.calibrationPointerNode?.let { if (it.view === view) return listOf(it) }
            return emptyList()
        }

        private fun audioState(): SettingsAudioChoicesState {
            val value = owner.snapshot
            val options = owner.audioOptions
            return SettingsAudioChoicesState(
                requireNotNull(value.channelMode), options.channels, value.sampleFormat, PcmSampleFormat.entries,
                value.sampleRate, options.rates, requireNotNull(value.source), options.sources,
                requireNotNull(value.route), options.routes, emptySet(),
            )
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
            for (owned in listOf(choices, compression, skip, notification)) try { owned.close() }
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
        val body = SettingsQuickTilesView(context, controller, environment(), inputRoot, animationScope,
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

    private fun wakeLock(): NativeSettingsSection {
        val initial = owner.state
        var activation = initial.activation
        val body = SettingsWakeLockView(context, initial.displayed.snapshot.wakeLockEnabled, environment(), inputRoot) {
            owner.wakeLockChanged(activation, it)
        }
        return object : NativeSettingsSection {
            override val view = body
            override fun bind() {
                val state = owner.state
                activation = state.activation
                body.bind(state.displayed.snapshot.wakeLockEnabled, environment())
            }
            override fun close() = body.close()
        }
    }
}

/** Permission reviews and the optional restart test share one compact surface. */
private class SettingsBackgroundWarningView(
    context: Context,
    private var environment: ReverbViewEnvironment,
    inputRoot: () -> View?,
    batteryRestricted: Boolean,
    notificationBlocked: Boolean,
    restartAccessMissing: Boolean,
    restartTestArmed: Boolean,
    restartTestPending: Boolean,
    reviewBattery: () -> Unit,
    reviewNotifications: () -> Unit,
    reviewRestart: () -> Unit,
) : ViewGroup(context), AutoCloseable {
    private val battery = SettingsPermissionReviewRow(context, environment, inputRoot,
        ReverbIcon.BackgroundWarning, R.string.background_use_restricted, R.string.background_review,
        R.string.background_review, reviewBattery)
    private val notifications = SettingsPermissionReviewRow(context, environment, inputRoot,
        ReverbIcon.BackgroundWarning, R.string.settings_resume_notification_blocked, R.string.background_review,
        R.string.settings_notification_access, reviewNotifications)
    private val notificationLayout = ReverbGroupLayout.LayoutParams(-1, -2)
    private val restart = SettingsPermissionReviewRow(context, environment, inputRoot,
        ReverbIcon.BackgroundWarning, R.string.settings_restart_access_missing, R.string.background_review,
        R.string.settings_restart_access, reviewRestart)
    private val restartLayout = ReverbGroupLayout.LayoutParams(-1, -2)
    private val group = ReverbGroupLayout(context, 14f)
    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        group.addRow(battery, ReverbGroupLayout.LayoutParams(-1, -2))
        group.addRow(notifications, notificationLayout)
        group.addRow(restart, restartLayout)
        addView(group)
        bind(environment, batteryRestricted, notificationBlocked, restartAccessMissing, restartTestArmed, restartTestPending)
    }
    fun bind(next: ReverbViewEnvironment, batteryRestricted: Boolean, notificationBlocked: Boolean,
        restartAccessMissing: Boolean, restartTestArmed: Boolean, restartTestPending: Boolean) {
        environment = next
        group.setPalette(next.palette.field, next.palette.border)
        battery.visibility = if (batteryRestricted) VISIBLE else GONE
        notifications.visibility = if (notificationBlocked) VISIBLE else GONE
        restart.visibility = VISIBLE
        notificationLayout.dividerTop = batteryRestricted && notificationBlocked
        restartLayout.dividerTop = batteryRestricted || notificationBlocked
        battery.bind(next, batteryRestricted)
        notifications.bind(next, notificationBlocked)
        val action = when {
            restartTestArmed -> R.string.cancel
            restartAccessMissing -> R.string.background_review
            else -> R.string.settings_restart_test
        }
        val title = when {
            restartTestArmed -> R.string.settings_restart_test_armed
            restartAccessMissing -> R.string.settings_restart_access_missing
            else -> R.string.settings_restart_test_ready
        }
        restart.bind(next, !restartTestPending, title, action,
            if (restartAccessMissing && !restartTestArmed) R.string.settings_restart_access else action,
            if (restartAccessMissing) ReverbIcon.BackgroundWarning else ReverbIcon.Reset)
        requestLayout()
    }
    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w)
        group.measure(exact((width - 2 * dp(16)).coerceAtLeast(0)), unspecified())
        setMeasuredDimension(resolveSize(width, w), resolveSize(group.measuredHeight + 2 * dp(12), h))
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        group.layout(dp(16), dp(12), width - dp(16), dp(12) + group.measuredHeight)
    }
    override fun close() { battery.close(); notifications.close(); restart.close() }
    private fun dp(value: Int) = (value * environment.textDensity.density).roundToInt()
}
