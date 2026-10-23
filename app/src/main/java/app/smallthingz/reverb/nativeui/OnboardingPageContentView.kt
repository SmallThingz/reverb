package app.smallthingz.reverb.nativeui

import android.content.Context
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import smallthingz.ui.text.TextStyle
import smallthingz.ui.runtime.MonotonicFrameClock
import kotlinx.coroutines.CoroutineScope
import smallthingz.ui.text.font.FontFamily
import smallthingz.ui.unit.Density
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import app.smallthingz.reverb.R
import app.smallthingz.reverb.DEFAULT_FLAC_COMPRESSION
import app.smallthingz.reverb.NativeDragPointerChange
import app.smallthingz.reverb.NativeViewPhasePointerNode
import app.smallthingz.reverb.SilenceGateConfig
import kotlin.math.roundToInt

internal data class OnboardingPageContentState(
    val permissions: OnboardingPermissionStatus,
    val oneShotEnabled: Boolean,
    val loopingEnabled: Boolean,
    val outputCompression: Boolean,
    val skipSilence: Boolean,
    val compressionAvailable: Boolean,
    val silenceValid: Boolean,
    /** Visual enabled state; predictive preview retains these exact colors/switch states. */
    val controlsEnabled: Boolean,
    /** False only for Finish/predictive preview, never merely because the pager is moving. */
    val interactionEnabled: Boolean,
    val compressionLevel: Int = DEFAULT_FLAC_COMPRESSION,
    val silenceState: SettingsSilenceState = SettingsSilenceState(SilenceGateConfig(enabled = skipSilence)),
    val calibrationRequired: Boolean = false,
)

internal data class OnboardingPageActions(
    val requestPermission: (OnboardingPermission) -> Unit,
    val oneShotChanged: (Boolean) -> Unit,
    val loopingChanged: (Boolean) -> Unit,
    val compressionChanged: (Boolean) -> Unit,
    val skipSilenceChanged: (Boolean) -> Unit,
    val compressionLevelChanged: (Int) -> Unit = {},
    val silenceActions: SettingsSilenceActions? = null,
    /** -1 means no settled visible page, including swipe, predictive Back and detach. */
    val pageChanged: (Int) -> Unit = {},
)

/**
 * One stable released page body. The owner retains one instance/scroll position per page and owns
 * pager/footer/permission launchers, drafts and Finish. No production Activity route is changed.
 * The outer screen supplies its existing safe-area and24/20dp padding, not this body.
 *
 * Typography/Density/resolver are immutable configuration inputs. Recreate with new inputs on
 * configuration replacement and restore the externally saved vertical scroll position.
 */
internal class OnboardingPageContentView(
    context: Context,
    val page: Int,
    typography: OnboardingPageTypography,
    density: Density,
    resolver: FontFamily.Resolver,
    initialState: OnboardingPageContentState,
    initialPalette: ReverbPalette,
    scrollState: NativeVerticalScrollState,
    scrollFrames: MonotonicFrameClock,
    scrollScope: CoroutineScope,
    scrollNested: NativeVerticalNestedHost,
    interactions: OnboardingNativeInteractionsFactory = OnboardingNativeInteractionsFactory(),
    inputRoot: () -> View? = { null },
    parentInterested: (NativeDragPointerChange) -> Boolean = { false },
    private val audioStructureChanged: () -> Unit = {},
) : FrameLayout(context), AutoCloseable {
    private var state = initialState
    private var environment = ReverbViewEnvironment(density, resolver, initialPalette)
    private var closed = false
    private var bindingInitialized = false
    private var audioStructureObserved = false
    private var audioSliderView: View? = null
    private var audioCurveView: View? = null
    private var audioCalibrationView: View? = null
    private var owner: ActionOwner? = null
    private var textAccessibility: NativePageTextAccessibilityOwner? = null
    private var accessibilityWindow: NativeWindowTextAccessibilityOwner? = null
    private var accessibilitySourceNode: ((View) -> NativeSourceAccessibilityNode)? = null
    private var accessibilityExposure: ((View) -> Boolean)? = null
    private val progressAccessibility = java.util.IdentityHashMap<SettingsCalibrationProgressView, AutoCloseable>()
    private val accessibilityClips = NativeTextSemanticsClipPolicy { view ->
        if (view === viewport || view is SettingsExpansionView || view.parent is ReverbGroupLayout)
            NativeTextSemanticsClip.Rectangle(smallthingz.ui.geometry.Rect(0f, 0f, view.width.toFloat(), view.height.toFloat()))
        else NativeTextSemanticsClip.Unclipped
    }
    private val viewport = NativeVerticalViewport(context, scrollState, density, scrollFrames, scrollScope, scrollNested)
    val sourceVerticalViewport: NativeVerticalViewport get() = viewport
    private val column = OnboardingPageColumn(context)
    private val headerIcon = OnboardingIconView(context, when (page) {
        0 -> ReverbIcon.BackgroundWarning
        1 -> ReverbIcon.OneShot
        else -> ReverbIcon.Capture
    }, initialPalette.primary)
    private val header = ReverbParagraphView(context, typography.headlineMedium, density, resolver, initialPalette.onSurface)
    private val permissions = if (page == 0)
        OnboardingPermissionGroup(context, typography, density, resolver, interactions) else null
    private val bufferGroup = if (page == 1) ReverbGroupLayout(context, cornerRadiusDp = 20f) else null
    private val oneShot = if (page == 1)
        OnboardingBufferRow(context, typography, density, resolver, ReverbIcon.OneShot, initialPalette, interactions) else null
    private val looping = if (page == 1)
        OnboardingBufferRow(context, typography, density, resolver, ReverbIcon.Looping, initialPalette, interactions) else null
    private val bufferOrder = if (page == 1) label(context, typography.bodyMedium, density, resolver,
        R.string.onboarding_native_order, initialPalette.onSurfaceVariant) else null
    private val audioGroup = if (page == 2) ReverbGroupLayout(context, cornerRadiusDp = 20f) else null
    private val silenceActions = object : SettingsSilenceActions {
        override fun configChanged(config: SilenceGateConfig) = activate {
            val callbacks = it.silenceActions
            if (callbacks != null) callbacks.configChanged(config) else it.skipSilenceChanged(config.enabled)
        }
        override fun calibrate() = activate { it.silenceActions?.calibrate() }
        override fun beginHeld(): Boolean = currentActions()?.silenceActions?.beginHeld() == true
        override fun completeTap() = activate { it.silenceActions?.completeTap() }
        override fun finishHeld() = activate { it.silenceActions?.finishHeld() }
        // Cancellation must release an accepted sample even after interaction is disabled/detached.
        override fun cancel() { owner?.actions?.silenceActions?.cancel() }
    }
    private val compression = if (page == 2)
        SettingsOutputCompressionView(context, compressionState(initialState), environment, inputRoot,
            scrollScope, parentInterested,
            { checked -> activate { it.compressionChanged(checked) } },
            { level -> activate { it.compressionLevelChanged(level) } }) else null
    private val silence = if (page == 2)
        SettingsSilenceView(context, silenceState(initialState), environment, inputRoot, scrollScope, silenceActions) else null
    private val compressionWarning = if (page == 2) OnboardingPaddedLabel(context,
        label(context, typography.bodySmall, density, resolver,
            R.string.flac_pcm_required, initialPalette.onSurfaceVariant), density) else null
    private val silenceWarning = if (page == 2) OnboardingPaddedLabel(context,
        label(context, typography.bodySmall, density, resolver,
            R.string.silence_invalid_settings, initialPalette.error), density) else null

    init {
        require(page in 0..2)
        clipChildren = false
        clipToPadding = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        if (Build.VERSION.SDK_INT >= 29) isForceDarkAllowed = false
        header.setText(context.getString(when (page) {
            0 -> R.string.onboarding_permissions_title
            1 -> R.string.onboarding_title
            else -> R.string.recording_settings_title
        }))
        column.add(headerIcon, widthDp = 40, heightDp = 40)
        column.add(header, beforeDp = 16)
        when (page) {
            0 -> {
                column.add(checkNotNull(permissions).view, beforeDp = 24, fillWidth = true)
            }
            1 -> {
                val group = checkNotNull(bufferGroup)
                group.addRow(checkNotNull(oneShot), ReverbGroupLayout.LayoutParams(-1, -2))
                group.addRow(checkNotNull(looping), ReverbGroupLayout.LayoutParams(-1, -2).apply { dividerTop = true })
                column.add(group, beforeDp = 24, fillWidth = true)
                column.add(checkNotNull(bufferOrder), beforeDp = 18)
                oneShot.onActivate = { activate { it.oneShotChanged(!state.oneShotEnabled) } }
                looping.onActivate = { activate { it.loopingChanged(!state.loopingEnabled) } }
            }
            2 -> {
                val group = checkNotNull(audioGroup)
                group.addRow(checkNotNull(compression), ReverbGroupLayout.LayoutParams(-1, -2))
                group.addRow(checkNotNull(compressionWarning), ReverbGroupLayout.LayoutParams(-2, -2))
                group.addRow(checkNotNull(silence), ReverbGroupLayout.LayoutParams(-1, -2).apply { dividerTop = true })
                group.addRow(checkNotNull(silenceWarning), ReverbGroupLayout.LayoutParams(-2, -2))
                column.add(group, beforeDp = 24, fillWidth = true)
            }
        }
        viewport.setContent(column)
        addView(viewport, LayoutParams(-1, -1))
        bind(initialState, initialPalette)
    }

    /** Current-owner callbacks only; no page-body callback persists or starts capture itself. */
    fun connect(lifecycleOwner: LifecycleOwner, actions: OnboardingPageActions): AutoCloseable {
        owner?.let(::releaseOwner)
        val connected = ActionOwner(lifecycleOwner, actions)
        connected.permissionLease = permissions?.connect(lifecycleOwner, actions.requestPermission)
        connected.observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY) releaseOwner(connected)
        }
        owner = connected
        lifecycleOwner.lifecycle.addObserver(connected.observer)
        return AutoCloseable { releaseOwner(connected) }
    }

    fun bind(next: OnboardingPageContentState, palette: ReverbPalette) {
        val previous = state
        val paletteChanged = environment.palette !== palette
        if (paletteChanged) environment = ReverbViewEnvironment(environment.textDensity, environment.sharedFontResolver, palette)
        val permissionMembershipChanged = state.permissions.storageRequired != next.permissions.storageRequired ||
            state.permissions.recoveryRequired != next.permissions.recoveryRequired
        state = next
        viewport.interactionEnabled = next.interactionEnabled
        if (bindingInitialized && !paletteChanged && previous.controlsEnabled == next.controlsEnabled &&
            previous.interactionEnabled == next.interactionEnabled) {
            when (page) {
                0 -> if (previous.permissions == next.permissions) return
                1 -> if (previous.oneShotEnabled == next.oneShotEnabled && previous.loopingEnabled == next.loopingEnabled) return
                2 -> if (previous.outputCompression == next.outputCompression && previous.skipSilence == next.skipSilence &&
                    previous.compressionLevel == next.compressionLevel && previous.compressionAvailable == next.compressionAvailable &&
                    previous.silenceValid == next.silenceValid) {
                    // Calibration progress keeps every other page/control stable. The reused silence
                    // component already updates only its progress leaf when its presentation is equal.
                    if (previous.silenceState !== next.silenceState) silence?.bind(silenceState(next), environment, silenceActions)
                    return
                }
            }
        }
        bindingInitialized = true
        headerIcon.bind(when (page) {
            0 -> ReverbIcon.BackgroundWarning
            1 -> ReverbIcon.OneShot
            else -> ReverbIcon.Capture
        }, palette.primary)
        header.setTextColor(palette.onSurface)
        permissions?.bind(next.permissions.copy(interactionEnabled = next.interactionEnabled), palette)
        bufferGroup?.setPalette(palette.field, palette.border)
        oneShot?.bind(context.getString(R.string.onboarding_one_shot_title),
            context.getString(R.string.onboarding_native_one_shot_body), next.oneShotEnabled,
            next.controlsEnabled && (!next.oneShotEnabled || next.loopingEnabled), next.interactionEnabled, palette)
        looping?.bind(context.getString(R.string.onboarding_looping_title),
            context.getString(R.string.onboarding_native_looping_body), next.loopingEnabled,
            next.controlsEnabled && (!next.loopingEnabled || next.oneShotEnabled), next.interactionEnabled, palette)
        bufferOrder?.setTextColor(palette.onSurfaceVariant)
        audioGroup?.setPalette(palette.field, palette.border)
        if (paletteChanged || previous.outputCompression != next.outputCompression ||
            previous.compressionLevel != next.compressionLevel || previous.controlsEnabled != next.controlsEnabled ||
            previous.interactionEnabled != next.interactionEnabled || previous.compressionAvailable != next.compressionAvailable)
            compression?.bind(compressionState(next), environment)
        silence?.bind(silenceState(next), environment, silenceActions)
        compressionWarning?.apply {
            visibility = if (next.compressionAvailable) GONE else VISIBLE
            setTextColor(palette.onSurfaceVariant)
        }
        silenceWarning?.apply {
            visibility = if (next.silenceValid) GONE else VISIBLE
            setTextColor(palette.error)
        }
        if (!next.interactionEnabled) cancelInputs()
        if (permissionMembershipChanged || previous.outputCompression != next.outputCompression ||
            previous.skipSilence != next.skipSilence) {
            textAccessibility?.refresh()
            refreshProgressAccessibility()
        }
    }

    /** Window-owned cursor/float geometry; retained across page detach and refreshed only as needed. */
    fun bindTextAccessibility(
        window: NativeWindowTextAccessibilityOwner,
        sourceNode: ((View) -> NativeSourceAccessibilityNode)? = null,
        sourceExposure: (View) -> Boolean,
    ): AutoCloseable {
        textAccessibility?.close()
        closeProgressAccessibility()
        val connected = if (page == 2) window.bindContent(this, accessibilityClips, sourceNode, sourceExposure)
            else window.bindPage(this, viewport, sourceNode, sourceExposure)
        textAccessibility = connected
        accessibilityWindow = window
        accessibilitySourceNode = sourceNode
        accessibilityExposure = sourceExposure
        refreshProgressAccessibility()
        return AutoCloseable {
            if (textAccessibility === connected) {
                textAccessibility = null
                connected.close()
                closeProgressAccessibility()
                accessibilityWindow = null
                accessibilitySourceNode = null
                accessibilityExposure = null
            }
        }
    }

    private fun refreshProgressAccessibility() {
        val window = accessibilityWindow ?: return
        val present = java.util.IdentityHashMap<SettingsCalibrationProgressView, Boolean>()
        fun visit(view: View) {
            if (view is SettingsCalibrationProgressView) present[view] = true
            if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index))
        }
        silence?.let(::visit)
        progressAccessibility.keys.filterNot(present::containsKey).forEach { progressAccessibility.remove(it)?.close() }
        for (view in present.keys) if (!progressAccessibility.containsKey(view))
            progressAccessibility[view] = window.bindSettingsProgress(view, this, accessibilityClips) {
                !closed && state.interactionEnabled && accessibilityExposure?.invoke(view) == true &&
                    accessibilitySourceNode?.invoke(view)?.hidden != true
            }
    }

    private fun closeProgressAccessibility() {
        var failure: Throwable? = null
        progressAccessibility.values.forEach { lease ->
            try { lease.close() } catch (error: Throwable) {
                if (failure == null) failure = error else if (failure !== error) failure!!.addSuppressed(error)
            }
        }
        progressAccessibility.clear()
        failure?.let { throw it }
    }

    fun verticalScrollPosition(): Int = viewport.saveScrollPosition()
    fun maximumVerticalScrollPosition(): Int = viewport.state.maxValue

    /** The screen's existing pointer phase tree owns these exact reused child nodes. */
    fun pointerNodes(view: View): List<NativeViewPhasePointerNode> {
        compression?.currentSlider?.let { if (it.pointerView === view) return it.pointerNodes }
        silence?.calibrationPointerNode?.let { if (it.view === view) return listOf(it) }
        return emptyList()
    }

    private fun compressionState(value: OnboardingPageContentState) = SettingsOutputCompressionState(
        value.outputCompression, value.compressionLevel,
        value.controlsEnabled && value.interactionEnabled && (value.compressionAvailable || value.outputCompression),
    )
    private fun silenceState(value: OnboardingPageContentState): SettingsSilenceState {
        val source = value.silenceState
        val enabled = source.enabled && value.controlsEnabled && value.interactionEnabled
        if (source.config.enabled == value.skipSilence && source.enabled == enabled) return source
        return source.copy(config = if (source.config.enabled == value.skipSilence) source.config
            else source.config.copy(enabled = value.skipSilence), enabled = enabled)
    }

    private fun currentActions(): OnboardingPageActions? = owner?.actions?.takeIf {
        !closed && isAttachedToWindow && state.controlsEnabled && state.interactionEnabled
    }

    private fun activate(action: (OnboardingPageActions) -> Unit) {
        currentActions()?.let(action)
    }
    private fun cancelInputs() {
        permissions?.cancelPendingRowInput()
        listOfNotNull(oneShot, looping).forEach(View::cancelPendingInputEvents)
        compression?.currentSlider?.pointerNodes?.forEach { it.cancel() }
        silence?.calibrationPointerNode?.cancel()
    }
    private fun releaseOwner(expected: ActionOwner) {
        if (owner !== expected) return
        owner = null
        expected.lifecycleOwner.lifecycle.removeObserver(expected.observer)
        expected.permissionLease?.close()
        cancelInputs()
    }
    override fun onDetachedFromWindow() {
        cancelInputs()
        super.onDetachedFromWindow()
    }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (page != 2 || closed) return
        // Animated expansion owns disposal after the final frame. Observe only actual leaf identity
        // changes so retired gesture/semantic leases close even when no new draft bind follows.
        val slider = compression?.currentSlider?.pointerView
        val curve = silence?.curveView
        val calibration = silence?.calibrationPointerNode?.view
        if (audioStructureObserved && slider === audioSliderView && curve === audioCurveView &&
            calibration === audioCalibrationView) return
        audioStructureObserved = true
        audioSliderView = slider
        audioCurveView = curve
        audioCalibrationView = calibration
        textAccessibility?.refresh()
        refreshProgressAccessibility()
        audioStructureChanged()
    }
    override fun close() {
        if (closed) return
        closed = true
        audioSliderView = null
        audioCurveView = null
        audioCalibrationView = null
        try { cancelInputs() } finally {
            try { compression?.close() } finally {
                try { silence?.close() } finally { closeProgressAccessibility() }
            }
        }
    }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
    private class ActionOwner(val lifecycleOwner: LifecycleOwner, val actions: OnboardingPageActions) {
        lateinit var observer: LifecycleEventObserver
        var permissionLease: AutoCloseable? = null
    }
}

/** Source Column geometry: each Spacer rounds separately, children align to logical Start. */
private class OnboardingPageColumn(context: Context) : ViewGroup(context) {
    private class Item(val beforeDp: Int, val widthDp: Int?, val heightDp: Int?, val fillWidth: Boolean) :
        LayoutParams(-2, -2)
    init { clipChildren = false; clipToPadding = false }
    fun add(view: View, beforeDp: Int = 0, widthDp: Int? = null, heightDp: Int? = null, fillWidth: Boolean = false) {
        addView(view, Item(beforeDp, widthDp, heightDp, fillWidth))
    }
    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w)
        var height = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            val item = child.layoutParams as Item
            height += dp(item.beforeDp)
            val childWidth = item.widthDp?.let { exact(dp(it).coerceAtMost(width)) }
                ?: if (item.fillWidth) exact(width) else atMost(width)
            child.measure(childWidth, item.heightDp?.let { exact(dp(it)) } ?: unspecified())
            height += child.measuredHeight
        }
        setMeasuredDimension(resolveSize(width, w), resolveSize(height, h))
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        var y = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            y += dp((child.layoutParams as Item).beforeDp)
            val x = if (layoutDirection == LAYOUT_DIRECTION_RTL) width - child.measuredWidth else 0
            child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
            y += child.measuredHeight
        }
    }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
}

private fun label(context: Context, style: TextStyle, density: Density, resolver: FontFamily.Resolver,
                  text: Int, color: Int): ReverbParagraphView =
    ReverbParagraphView(context, style, density, resolver, color).apply { setText(context.getString(text)) }

/**
 * Source Modifier.padding sits outside Text semantics. Keeping that inset on the paragraph itself
 * draws correctly but incorrectly enlarges the exposed Text bounds. This wrapper owns only the
 * original14/8dp layout inset; the real original label/cursor remains the child node.
 */
private class OnboardingPaddedLabel(
    context: Context,
    private val label: ReverbParagraphView,
    density: Density,
) : ViewGroup(context) {
    private val horizontalInset = (14f * density.density).roundToInt()
    private val verticalInset = (8f * density.density).roundToInt()
    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        clipChildren = false
        clipToPadding = false
        addView(label)
    }
    fun setTextColor(color: Int) = label.setTextColor(color)
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        fun inset(spec: Int, amount: Int): Int =
            if (MeasureSpec.getMode(spec) == MeasureSpec.UNSPECIFIED) unspecified()
            else MeasureSpec.makeMeasureSpec((MeasureSpec.getSize(spec) - amount).coerceAtLeast(0),
                MeasureSpec.getMode(spec))
        label.measure(inset(widthMeasureSpec, horizontalInset * 2), inset(heightMeasureSpec, verticalInset * 2))
        setMeasuredDimension(resolveSize(label.measuredWidth + horizontalInset * 2, widthMeasureSpec),
            resolveSize(label.measuredHeight + verticalInset * 2, heightMeasureSpec))
    }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        label.layout(horizontalInset, verticalInset,
            horizontalInset + label.measuredWidth, verticalInset + label.measuredHeight)
    }
}
