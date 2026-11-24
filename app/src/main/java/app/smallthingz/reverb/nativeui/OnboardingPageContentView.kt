package app.smallthingz.reverb.nativeui

import smallthingz.ui.reverb.onboarding.ReverbOnboardingPageLayout
import smallthingz.ui.scroll.NativeVerticalNestedHost
import smallthingz.ui.scroll.NativeVerticalScrollState
import smallthingz.ui.text.view.NativeSourceAccessibilityNode
import android.content.Context
import android.view.View
import android.view.KeyEvent
import smallthingz.ui.runtime.MonotonicFrameClock
import kotlinx.coroutines.CoroutineScope
import smallthingz.ui.text.font.FontFamily
import smallthingz.ui.unit.Density
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import app.smallthingz.reverb.DEFAULT_FLAC_COMPRESSION
import smallthingz.ui.input.phase.NativeDragPointerChange
import smallthingz.ui.input.phase.NativeViewPhasePointerNode
import app.smallthingz.reverb.SilenceGateConfig

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
    val silenceState: SettingsSilenceState? = null,
)

internal data class OnboardingPageActions(
    val requestPermission: (OnboardingPermission) -> Unit,
    val oneShotChanged: (Boolean) -> Unit,
    val loopingChanged: (Boolean) -> Unit,
    val compressionChanged: (Boolean) -> Unit,
    val skipSilenceChanged: (Boolean) -> Unit,
    /** -1 means no settled visible page, including swipe, predictive Back and detach. */
    val pageChanged: (Int) -> Unit = {},
    val compressionLevelChanged: (Int) -> Unit = {},
    val silenceActions: SettingsSilenceActions? = null,
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
    page: Int,
    typography: OnboardingPageTypography,
    density: Density,
    resolver: FontFamily.Resolver,
    initialState: OnboardingPageContentState,
    initialPalette: ReverbPalette,
    scrollState: NativeVerticalScrollState,
    scrollFrames: MonotonicFrameClock,
    scrollScope: CoroutineScope,
    scrollNested: NativeVerticalNestedHost,
    inputRoot: () -> View? = { null },
    parentInterested: (NativeDragPointerChange) -> Boolean = { false },
) : ReverbOnboardingPageLayout(context, page, typography, density, resolver, initialPalette,
    scrollState, scrollFrames, scrollScope, scrollNested, inputRoot), AutoCloseable {
    private var state = initialState
    private var boundPalette = initialPalette
    private var closed = false
    private var bindingInitialized = false
    private var pageInteractionAllowed = true
    private var owner: ActionOwner? = null
    private var textAccessibility: NativePageTextAccessibilityOwner? = null
    private var environment = ReverbViewEnvironment(density, resolver, initialPalette)
    private val silenceCallbacks = createSilenceCallbacks()
    private val compression = if (page == 2)
        SettingsOutputCompressionView(context, compressionState(initialState), environment, inputRoot,
            scrollScope, parentInterested,
            { checked -> activate { it.compressionChanged(checked) } },
            { level -> activate { it.compressionLevelChanged(level) } }) else null
    private val silence = if (page == 2)
        SettingsSilenceView(context, silenceState(initialState), environment, inputRoot,
            scrollScope, silenceCallbacks) else null
    init {
        onToggleOneShot = { activate { it.oneShotChanged(!state.oneShotEnabled) } }
        onToggleLooping = { activate { it.loopingChanged(!state.loopingEnabled) } }
        if (page == 2) installAudioRows(checkNotNull(compression), checkNotNull(silence))
        bind(initialState, initialPalette)
    }

    /** Current-owner callbacks only; no page-body callback persists or starts capture itself. */
    fun connect(lifecycleOwner: LifecycleOwner, actions: OnboardingPageActions): AutoCloseable {
        owner?.let(::releaseOwner)
        val connected = ActionOwner(lifecycleOwner, actions)
        connected.permissionLease = connectPermission(lifecycleOwner) { permission ->
            currentActions()?.requestPermission?.invoke(permission)
        }
        connected.observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY) releaseOwner(connected)
        }
        owner = connected
        lifecycleOwner.lifecycle.addObserver(connected.observer)
        return AutoCloseable { releaseOwner(connected) }
    }

    fun bind(next: OnboardingPageContentState, palette: ReverbPalette) {
        val previous = state
        val paletteChanged = boundPalette !== palette
        if (paletteChanged) {
            boundPalette = palette
            environment = environment.withPalette(palette)
        }
        state = next
        updateAudioExposure()
        viewport.interactionEnabled = next.interactionEnabled
        if (bindingInitialized && !paletteChanged && previous.controlsEnabled == next.controlsEnabled &&
            previous.interactionEnabled == next.interactionEnabled) {
            when (page) {
                0 -> if (previous.permissions == next.permissions) return
                1 -> if (previous.oneShotEnabled == next.oneShotEnabled && previous.loopingEnabled == next.loopingEnabled) return
                2 -> if (previous.outputCompression == next.outputCompression && previous.skipSilence == next.skipSilence &&
                    previous.compressionAvailable == next.compressionAvailable && previous.silenceValid == next.silenceValid &&
                    previous.compressionLevel == next.compressionLevel && previous.silenceState == next.silenceState) return
            }
        }
        bindingInitialized = true
        val permissionMembershipChanged = bindPageAppearance(palette, next.permissions,
            next.oneShotEnabled, next.loopingEnabled, next.controlsEnabled, next.interactionEnabled,
            next.compressionAvailable, next.silenceValid)
        compression?.bind(compressionState(next), environment)
        silence?.bind(silenceState(next), environment, silenceCallbacks)
        if (!next.interactionEnabled) cancelInputs()
        if (permissionMembershipChanged || page == 2) textAccessibility?.refresh()
    }

    /** Window-owned cursor/float geometry; retained across page detach and refreshed only as needed. */
    fun bindTextAccessibility(
        window: NativeWindowTextAccessibilityOwner,
        sourceNode: ((View) -> NativeSourceAccessibilityNode)? = null,
        sourceExposure: (View) -> Boolean,
    ): AutoCloseable {
        textAccessibility?.close()
        val connected = window.bindPage(this, viewport, sourceNode, sourceExposure)
        textAccessibility = connected
        return AutoCloseable {
            if (textAccessibility === connected) {
                textAccessibility = null
                connected.close()
            }
        }
    }

    fun verticalScrollPosition(): Int = viewport.saveScrollPosition()
    fun maximumVerticalScrollPosition(): Int = viewport.state.maxValue
    val acceptsPointerInput: Boolean get() = state.controlsEnabled && state.interactionEnabled &&
        pageInteractionAllowed

    fun pageInteractionAllowed(allowed: Boolean) {
        if (pageInteractionAllowed == allowed) return
        pageInteractionAllowed = allowed
        updateAudioExposure()
        if (allowed) textAccessibility?.refresh()
        if (!allowed) cancelInputs()
    }

    private fun updateAudioExposure() = setAudioSectionExposed(state.interactionEnabled && pageInteractionAllowed)

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // The pager can retain a focused page after it leaves placement. Its editor must not
        // consume keyboard input or mutate the draft while another page is displayed.
        if (!acceptsPointerInput) return false
        return super.dispatchKeyEvent(event)
    }

    fun pointerNodes(view: View): List<NativeViewPhasePointerNode> {
        compression?.currentSlider?.let { if (it.pointerView === view) return it.pointerNodes }
        silence?.calibrationPointerNode?.let { if (it.view === view) return listOf(it) }
        return emptyList()
    }

    private fun compressionState(value: OnboardingPageContentState) = SettingsOutputCompressionState(
        value.outputCompression, value.compressionLevel,
        value.controlsEnabled && (value.compressionAvailable || value.outputCompression),
    )

    private fun silenceState(value: OnboardingPageContentState): SettingsSilenceState =
        (value.silenceState ?: SettingsSilenceState(SilenceGateConfig(enabled = value.skipSilence))).copy(
            enabled = value.controlsEnabled && value.silenceState?.enabled != false,
        )

    private fun createSilenceCallbacks() = object : SettingsSilenceActions {
        override fun configChanged(config: SilenceGateConfig) {
            currentActions()?.let { actions ->
                actions.silenceActions?.configChanged(config) ?: actions.skipSilenceChanged(config.enabled)
            }
        }
        override fun calibrate() { currentActions()?.silenceActions?.calibrate() }
        override fun beginHeld(): Boolean = currentActions()?.silenceActions?.beginHeld() == true
        override fun completeTap() { currentActions()?.silenceActions?.completeTap() }
        override fun finishHeld() { currentActions()?.silenceActions?.finishHeld() }
        // Cancellation must reach the owner after visibility/interaction eligibility was revoked.
        override fun cancel() { owner?.actions?.silenceActions?.cancel() }
    }

    private fun currentActions(): OnboardingPageActions? = owner?.actions?.takeIf {
        !closed && isAttachedToWindow && acceptsPointerInput
    }

    private fun activate(action: (OnboardingPageActions) -> Unit) {
        currentActions()?.let(action)
    }
    private fun cancelInputs() {
        cancelPageInput()
        compression?.cancelPendingInputEvents()
        silence?.cancelPendingInputEvents()
        compression?.currentSlider?.pointerNodes?.forEach { it.cancel() }
        silence?.calibrationPointerNode?.cancel()
    }
    private fun releaseOwner(expected: ActionOwner) {
        if (owner !== expected) return
        owner = null
        if (page == 2) expected.actions.silenceActions?.cancel()
        expected.lifecycleOwner.lifecycle.removeObserver(expected.observer)
        expected.permissionLease?.close()
        cancelInputs()
    }
    override fun onDetachedFromWindow() {
        cancelInputs()
        super.onDetachedFromWindow()
    }
    override fun close() {
        if (closed) return
        closed = true
        try { owner?.let(::releaseOwner) } finally {
            textAccessibility?.close()
            textAccessibility = null
            cancelInputs()
            compression?.close()
            silence?.close()
        }
    }
    private class ActionOwner(val lifecycleOwner: LifecycleOwner, val actions: OnboardingPageActions) {
        lateinit var observer: LifecycleEventObserver
        var permissionLease: AutoCloseable? = null
    }
}
