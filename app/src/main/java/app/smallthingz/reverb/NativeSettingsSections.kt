package app.smallthingz.reverb

import android.content.Context
import android.view.View
import android.view.ViewGroup
import smallthingz.ui.runtime.MonotonicFrameClock
import androidx.lifecycle.Lifecycle
import app.smallthingz.reverb.nativeui.*
import kotlinx.coroutines.CoroutineScope
import kotlin.math.max
import kotlin.math.min
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
        val body = SettingsBackgroundWarningView(context, environment(), inputRoot) { owner.reviewBattery(activation) }
        return object : NativeSettingsSection {
            override val view = body
            override fun bind() { activation = owner.state.activation; body.bind(environment()) }
            override fun close() = body.close()
        }
    }

    private fun theme(): NativeSettingsSection {
        var activation = owner.state.activation
        val body = SettingsThemeSection(context, owner.snapshot.themeMode, environment(), inputRoot) {
            owner.themeChanged(activation, it)
        }
        return object : NativeSettingsSection {
            override val view = body
            override fun bind() { activation = owner.state.activation; body.bind(owner.snapshot.themeMode, environment()) }
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
            view.bind(owner.snapshot.format == ExportFormat.FLAC && owner.snapshot.sampleFormat != PcmSampleFormat.PCM_16, env)
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
        private fun compressionState() = SettingsOutputCompressionState(
            owner.snapshot.format == ExportFormat.FLAC, owner.snapshot.flacCompression,
            owner.state.interactionReady && !owner.state.persisting,
        )
        private fun silenceState(): SettingsSilenceState {
            val value = owner.snapshot
            if (silenceRaw != value.silenceGate) { silenceRaw = value.silenceGate; silence = decodeSilenceGate(value.silenceGate) }
            if (histogramRaw != value.silenceHistogram) {
                histogramRaw = value.silenceHistogram
                histogram = decodeSilenceCalibrationHistogram(value.silenceHistogram)
            }
            val input = value.silenceCalibrationInput()
            return SettingsSilenceState(
                config = silence,
                enabled = owner.state.interactionReady && !owner.state.persisting,
                maximumHz = minOf(SILENCE_MAX_HZ, value.sampleRate / 2),
                sampledPeaks = histogram?.peaksFor(input),
                calibrationAllowed = owner.state.baseline.silenceCalibrationInput() == input,
                calibrating = owner.calibration.running,
                holdingCalibration = owner.calibration.holding,
                calibrationProgress = owner.calibration.progress,
                calibrationError = owner.calibration.error,
                calibrationUnavailableReason = context.getString(R.string.silence_apply_input_first),
            )
        }
        private fun silenceActions(): SettingsSilenceActions {
            val token = owner.state.activation
            val revision = owner.state.editRevision
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
            val value = owner.snapshot
            return SettingsNotificationSaveState(
                value.quickSaveEnabled, value.quickSaveSeconds,
                exportDurationLimitExactSeconds(requireNotNull(value.format), ExportCodec.PCM_16,
                    value.sampleRate, requireNotNull(value.channelMode).channelCount, value.sampleFormat)
                    .toInt().coerceIn(1, 86_400),
                owner.state.interactionReady && !owner.state.persisting, owner.state.editRevision,
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
        fun inputs() = NativeQuickTilesInputs(owner.state.active,
            owner.state.interactionReady && !owner.state.persisting,
            owner.tileCapacityDraftReady(), owner.state.editRevision)
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
        var activation = owner.state.activation
        fun state() = SettingsStorageState(owner.exportPathText, owner.snapshot.exportDirectoryUri != null,
            owner.canMove, owner.state.persisting)
        val body = SettingsStorageSection(context, state(), environment(), inputRoot,
            { owner.useDefaultDirectory(activation) }, { owner.chooseDirectory(activation) },
            { owner.moveExistingRecordings(activation) })
        return object : NativeSettingsSection {
            override val view = body
            override fun bind() { activation = owner.state.activation; body.bind(state(), environment()); structureChanged() }
            override fun close() = body.close()
        }
    }

    private fun wakeLock(): NativeSettingsSection {
        var activation = owner.state.activation
        val body = SettingsWakeLockView(context, owner.snapshot.wakeLockEnabled, environment(), inputRoot) {
            owner.wakeLockChanged(activation, it)
        }
        return object : NativeSettingsSection {
            override val view = body
            override fun bind() { activation = owner.state.activation; body.bind(owner.snapshot.wakeLockEnabled, environment()) }
            override fun close() = body.close()
        }
    }
}

/** BatteryOptimizationUi's restricted branch; this item exists only while restricted. */
private class SettingsBackgroundWarningView(
    context: Context,
    private var environment: ReverbViewEnvironment,
    inputRoot: () -> View?,
    review: () -> Unit,
) : ViewGroup(context), AutoCloseable {
    private val icon = OnboardingIconView(context, ReverbIcon.BackgroundWarning, environment.palette.tertiary)
    private val text = environment.paragraph(context, context.getString(R.string.background_use_restricted),
        ReverbTextRole.BODY_MEDIUM, environment.palette.onSurface)
    private val action = SettingsCalibrationActionView(context, environment, inputRoot, action = review)
    private val row = Row(context)
    private val group = ReverbGroupLayout(context, 14f)
    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        icon.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        row.addView(icon); row.addView(text); row.addView(action)
        group.addRow(row, ReverbGroupLayout.LayoutParams(-1, -2))
        addView(group)
        bind(environment)
    }
    fun bind(next: ReverbViewEnvironment) {
        environment = next
        group.setPalette(next.palette.field, next.palette.border)
        icon.bind(ReverbIcon.BackgroundWarning, next.palette.tertiary)
        next.bindParagraphStyle(text, ReverbTextRole.BODY_MEDIUM, next.palette.onSurface)
        action.bind(context.getString(R.string.background_review), true, next)
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
    override fun close() = action.close()
    private fun dp(value: Int) = (value * environment.textDensity.density).roundToInt()
    private inner class Row(context: Context) : ViewGroup(context) {
        private var rowHeight = 0
        private var gap1 = 0
        private var gap2 = 0
        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
        override fun onMeasure(w: Int, h: Int) {
            val width = MeasureSpec.getSize(w)
            val available = (width - 2 * dp(15)).coerceAtLeast(0)
            icon.measure(exact(min(dp(22), available)), exact(dp(22)))
            gap1 = min(dp(12), (available - icon.measuredWidth).coerceAtLeast(0))
            action.measure(atMost((available - icon.measuredWidth - gap1).coerceAtLeast(0)), unspecified())
            gap2 = min(dp(12), (available - icon.measuredWidth - gap1 - action.measuredWidth).coerceAtLeast(0))
            text.measure(exact((available - icon.measuredWidth - gap1 - action.measuredWidth - gap2).coerceAtLeast(0)), unspecified())
            rowHeight = max(icon.measuredHeight, max(text.measuredHeight, action.measuredHeight))
            setMeasuredDimension(width, 2 * dp(13) + rowHeight)
        }
        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            var start = dp(15)
            fun place(child: View) {
                val x = if (layoutDirection == LAYOUT_DIRECTION_RTL) width - start - child.measuredWidth else start
                val y = dp(13) + ((rowHeight - child.measuredHeight) / 2f).roundToInt()
                child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
            }
            place(icon); start += icon.measuredWidth + gap1
            place(text); start += text.measuredWidth + gap2
            place(action)
        }
    }
}
