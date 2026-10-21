package app.smallthingz.reverb.nativeui

import android.content.Context
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.ui.text.TextStyle
import androidx.compose.runtime.MonotonicFrameClock
import kotlinx.coroutines.CoroutineScope
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import app.smallthingz.reverb.R
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
)

internal data class OnboardingPageActions(
    val requestPermission: (OnboardingPermission) -> Unit,
    val oneShotChanged: (Boolean) -> Unit,
    val loopingChanged: (Boolean) -> Unit,
    val compressionChanged: (Boolean) -> Unit,
    val skipSilenceChanged: (Boolean) -> Unit,
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
) : FrameLayout(context) {
    private var state = initialState
    private var owner: ActionOwner? = null
    private var textAccessibility: NativePageTextAccessibilityOwner? = null
    private val viewport = NativeVerticalViewport(context, scrollState, density, scrollFrames, scrollScope, scrollNested)
    val sourceVerticalViewport: NativeVerticalViewport get() = viewport
    private val column = OnboardingPageColumn(context)
    private val headerIcon = OnboardingIconView(context, when (page) {
        0 -> ReverbIcon.BackgroundWarning
        1 -> ReverbIcon.OneShot
        else -> ReverbIcon.Capture
    }, initialPalette.primary)
    private val header = ReverbParagraphView(context, typography.headlineMedium, density, resolver, initialPalette.onSurface)
    private val permissionBody = if (page == 0) label(context, typography.bodyMedium, density, resolver,
        R.string.onboarding_permissions_body, initialPalette.onSurfaceVariant) else null
    private val permissions = if (page == 0)
        OnboardingPermissionGroup(context, typography, density, resolver, interactions) else null
    private val bufferGroup = if (page == 1) ReverbGroupLayout(context, cornerRadiusDp = 20f) else null
    private val oneShot = if (page == 1)
        OnboardingBufferRow(context, typography, density, resolver, ReverbIcon.OneShot, initialPalette, interactions) else null
    private val looping = if (page == 1)
        OnboardingBufferRow(context, typography, density, resolver, ReverbIcon.Looping, initialPalette, interactions) else null
    private val bufferOrder = if (page == 1) label(context, typography.bodyMedium, density, resolver,
        R.string.onboarding_order, initialPalette.onSurfaceVariant) else null
    private val audioGroup = if (page == 2) ReverbGroupLayout(context, cornerRadiusDp = 20f) else null
    private val compression = if (page == 2)
        OnboardingAudioRow(context, typography, density, resolver, initialPalette, interactions) else null
    private val silence = if (page == 2)
        OnboardingAudioRow(context, typography, density, resolver, initialPalette, interactions) else null
    private val compressionWarning = if (page == 2) label(context, typography.bodySmall, density, resolver,
        R.string.flac_pcm_required, initialPalette.onSurfaceVariant).apply {
            setPadding(dp(14), dp(8), dp(14), dp(8))
        } else null
    private val silenceWarning = if (page == 2) label(context, typography.bodySmall, density, resolver,
        R.string.silence_invalid_settings, initialPalette.error).apply {
            setPadding(dp(14), dp(8), dp(14), dp(8))
        } else null

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
                column.add(checkNotNull(permissionBody), beforeDp = 24)
                column.add(checkNotNull(permissions).view, beforeDp = 16, fillWidth = true)
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
                compression.onActivate = { activate { it.compressionChanged(!state.outputCompression) } }
                silence.onActivate = { activate { it.skipSilenceChanged(!state.skipSilence) } }
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
        val permissionMembershipChanged = state.permissions.storageRequired != next.permissions.storageRequired ||
            state.permissions.recoveryRequired != next.permissions.recoveryRequired
        state = next
        viewport.interactionEnabled = next.interactionEnabled
        headerIcon.bind(when (page) {
            0 -> ReverbIcon.BackgroundWarning
            1 -> ReverbIcon.OneShot
            else -> ReverbIcon.Capture
        }, palette.primary)
        header.setTextColor(palette.onSurface)
        permissionBody?.setTextColor(palette.onSurfaceVariant)
        permissions?.bind(next.permissions.copy(interactionEnabled = next.interactionEnabled), palette)
        bufferGroup?.setPalette(palette.field, palette.border)
        oneShot?.bind(context.getString(R.string.onboarding_one_shot_title),
            context.getString(R.string.onboarding_one_shot_body), next.oneShotEnabled,
            next.controlsEnabled && (!next.oneShotEnabled || next.loopingEnabled), next.interactionEnabled, palette)
        looping?.bind(context.getString(R.string.onboarding_looping_title),
            context.getString(R.string.onboarding_looping_body), next.loopingEnabled,
            next.controlsEnabled && (!next.loopingEnabled || next.oneShotEnabled), next.interactionEnabled, palette)
        bufferOrder?.setTextColor(palette.onSurfaceVariant)
        audioGroup?.setPalette(palette.field, palette.border)
        compression?.bind(context.getString(R.string.output_compression), next.outputCompression,
            next.controlsEnabled && (next.compressionAvailable || next.outputCompression), next.interactionEnabled, palette)
        silence?.bind(context.getString(R.string.silence_title), next.skipSilence,
            next.controlsEnabled, next.interactionEnabled, palette)
        compressionWarning?.apply {
            visibility = if (next.compressionAvailable) GONE else VISIBLE
            setTextColor(palette.onSurfaceVariant)
        }
        silenceWarning?.apply {
            visibility = if (next.silenceValid) GONE else VISIBLE
            setTextColor(palette.error)
        }
        if (!next.interactionEnabled) cancelInputs()
        if (permissionMembershipChanged) textAccessibility?.refresh()
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

    private fun activate(action: (OnboardingPageActions) -> Unit) {
        val current = owner ?: return
        if (!isAttachedToWindow || !state.controlsEnabled || !state.interactionEnabled) return
        action(current.actions)
    }
    private fun cancelInputs() {
        permissions?.cancelPendingRowInput()
        listOfNotNull(oneShot, looping, compression, silence).forEach(View::cancelPendingInputEvents)
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
