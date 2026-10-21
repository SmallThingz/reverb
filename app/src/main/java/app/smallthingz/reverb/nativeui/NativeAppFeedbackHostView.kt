package app.smallthingz.reverb.nativeui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.AccessibilityManager
import androidx.lifecycle.LifecycleOwner
import app.smallthingz.reverb.FeedbackEvent
import app.smallthingz.reverb.FeedbackTone
import app.smallthingz.reverb.NativeAppFeedbackOwner
import app.smallthingz.reverb.NativeDragPointerEvent
import app.smallthingz.reverb.NativePointerPass
import app.smallthingz.reverb.NativeViewPhasePointerNode
import app.smallthingz.reverb.NativeViewPointerHitLayout
import app.smallthingz.reverb.NativeWindowPointerCoordinates
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Released process-feedback host only. Main supplies its existing bottom-center placement,18dp
 * horizontal padding and branch-specific bottom inset/z; onboarding supplies safeDrawing+84dp.
 * This View owns no full-screen target and never accepts an action or replaces Library Undo.
 */
internal class NativeAppFeedbackHostView(
    context: Context,
    private var environment: ReverbViewEnvironment,
    private val root: View,
    lifecycleOwner: LifecycleOwner,
    parentScope: CoroutineScope,
    accessibilityManager: AccessibilityManager?,
    private val windowText: NativeWindowTextAccessibilityOwner,
    private val sourceNode: (View) -> NativeSourceAccessibilityNode,
) : ViewGroup(context), AutoCloseable {
    private val scope = CoroutineScope(parentScope.coroutineContext + Job(parentScope.coroutineContext[Job]))
    private val main = Handler(Looper.getMainLooper())
    private val motion = NativeFeedbackVisibilityMotion()
    private val coordinates = NativeWindowPointerCoordinates { root }
    private var pendingEvent: FeedbackEvent? = null
    private var renderedEvent: FeedbackEvent? = null
    private var card: PassiveFeedbackCard? = null
    private var textLease: NativePageTextAccessibilityOwner? = null
    private var renderPosted = false
    private var visibleAllowed = false
    private var closed = false
    private var frames: Job? = null
    private val render = Runnable {
        renderPosted = false
        if (!closed) renderCurrent()
    }
    private val owner: NativeAppFeedbackOwner

    /** Source Surface's inert pointer node shields the same rounded card, not the surrounding pad. */
    private val hitLayout = object : NativeViewPointerHitLayout {
        override val placed get() = !closed && visibleAllowed && card?.isAttachedToWindow == true
        override val size get() = card?.surface?.let { Size(it.width.toFloat(), it.height.toFloat()) } ?: Size.Zero
        override val minimumTouchTarget get() = Size(48f * environment.textDensity.density, 48f * environment.textDensity.density)
        override val nodes get() = card?.pointerNodes.orEmpty()
        override val childrenInHitOrder = emptyList<NativeViewPointerHitLayout>()
        override fun screenToLocal(screen: Offset) = coordinates.coordinates(card?.surface ?: this@NativeAppFeedbackHostView).local(screen)
        override fun withinLayerBounds(local: Offset) = card?.surface?.containsVisualPoint(local.x, local.y) == true
    }

    /** Join Main's current physical event coordinates; never retain matrices across movement. */
    fun parentPointerLayout(windowPositionInScreen: Offset): NativeViewPointerHitLayout {
        coordinates.beginFromParent(windowPositionInScreen)
        return hitLayout
    }

    init {
        requireNotNull(scope.coroutineContext[MonotonicFrameClock])
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        clipChildren = false
        clipToPadding = false
        if (android.os.Build.VERSION.SDK_INT >= 29) isForceDarkAllowed = false
        owner = NativeAppFeedbackOwner(root, lifecycleOwner, scope, accessibilityManager,
            display = {
                pendingEvent = it
                scheduleRender()
            },
            visibilityAllowed = { next ->
                if (visibleAllowed != next) {
                    visibleAllowed = next
                    if (!next) removeSourceHost() else scheduleRender()
                }
            })
    }

    fun bind(deliveryAllowed: Boolean, environment: ReverbViewEnvironment) {
        if (closed) return
        val changedEnvironment = this.environment !== environment
        this.environment = environment
        owner.bind(deliveryAllowed)
        if (changedEnvironment) renderedEvent?.let { card?.bind(it, environment) }
    }

    private fun scheduleRender() {
        if (!closed && !renderPosted) {
            renderPosted = true
            // Source state writes in one delivery turn coalesce: acknowledge/head replacement
            // must not manufacture a separate null-card frame and re-enter animation.
            main.post(render)
        }
    }

    private fun renderCurrent() {
        if (!visibleAllowed) return
        val event = pendingEvent
        if (event == null) {
            removeCard()
            // Released current=event check removes ink now; do not retain an exit ghost notice.
            motion.target(false, 0)
            requestLayout()
            startFrames()
            return
        }
        renderedEvent = event
        val shown = card ?: PassiveFeedbackCard(context, environment).also { created ->
            card = created
            addView(created)
            textLease = windowText.bindContent(created,
                NativeTextSemanticsClipPolicy { view ->
                    if (view === created.surface.pointerContent)
                        NativeTextSemanticsClip.Rectangle(Rect(0f, 0f, view.width.toFloat(), view.height.toFloat()))
                    else NativeTextSemanticsClip.Unclipped
                }, sourceNode) { view -> sourceNode(view).present }
        }
        shown.bind(event, environment)
        requestLayout()
    }

    private fun removeCard() {
        val lease = textLease
        val removed = card
        textLease = null
        card = null
        renderedEvent = null
        var failure: Throwable? = null
        try { lease?.close() } catch (error: Throwable) { failure = error }
        try { removed?.let(::removeView) } catch (error: Throwable) {
            val previous = failure
            if (previous == null) failure = error else previous.addSuppressed(error)
        }
        failure?.let { throw it }
    }

    private fun removeSourceHost() {
        frames?.cancel()
        frames = null
        motion.reset()
        removeCard()
        requestLayout()
    }

    override fun onMeasure(w: Int, h: Int) {
        val shown = card
        if (shown == null) {
            setMeasuredDimension(resolveSize(0, w), resolveSize(0, h))
            return
        }
        shown.measure(w, h)
        setMeasuredDimension(shown.measuredWidth, shown.measuredHeight)
        motion.target(true, shown.measuredHeight)
        applyMotion()
        startFrames()
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        card?.let { it.layout(0, 0, it.measuredWidth, it.measuredHeight) }
        applyMotion()
    }

    private fun applyMotion() {
        card?.let {
            it.alpha = motion.alpha
            it.translationY = motion.offset.y.toFloat()
        }
    }

    private fun startFrames() {
        if (closed || !visibleAllowed || !motion.running || frames?.isActive == true) return
        frames = scope.launch {
            while (isActive && visibleAllowed && motion.running) {
                withFrameNanos { time ->
                    motion.frame(time, scope.coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f)
                    applyMotion()
                }
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        var failure: Throwable? = null
        fun release(action: () -> Unit) {
            try { action() } catch (error: Throwable) {
                val previous = failure
                if (previous == null) failure = error else previous.addSuppressed(error)
            }
        }
        release(owner::close)
        release { main.removeCallbacks(render) }
        renderPosted = false
        release { scope.cancel() }
        frames = null
        release(::removeCard)
        motion.reset()
        failure?.let { throw it }
    }
}

private class PassiveFeedbackCard(context: Context, initial: ReverbViewEnvironment) : ViewGroup(context) {
    val surface = ReverbGroupLayout(context, 18f)
    val pointerNodes = listOf(object : NativeViewPhasePointerNode {
        override val view: View get() = surface
        override fun dispatch(event: NativeDragPointerEvent, pass: NativePointerPass) = Unit
        override fun cancel() = Unit
    })
    private val row = PassiveFeedbackRow(context, initial)
    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        clipChildren = false
        clipToPadding = false
        surface.addRow(row, ReverbGroupLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(surface)
    }
    fun bind(event: FeedbackEvent, environment: ReverbViewEnvironment) {
        val colors = passiveFeedbackColors(event.tone, environment.palette)
        surface.setPalette(colors.first, android.graphics.Color.TRANSPARENT)
        row.bind(event.message, environment, colors.second)
    }
    override fun onMeasure(w: Int, h: Int) {
        surface.measure(w, h)
        setMeasuredDimension(surface.measuredWidth, surface.measuredHeight)
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) { surface.layout(0, 0, width, height) }
}

private class PassiveFeedbackRow(context: Context, private var environment: ReverbViewEnvironment) : ViewGroup(context) {
    private val text = environment.paragraph(context, "", ReverbTextRole.BODY_MEDIUM)
    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        text.accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
        addView(text)
    }
    fun bind(message: String, next: ReverbViewEnvironment, color: Int) {
        environment = next
        next.bindParagraphStyle(text, ReverbTextRole.BODY_MEDIUM, color)
        text.setText(message)
        requestLayout()
    }
    private fun dp(value: Float) = (value * environment.textDensity.density).roundToInt()
    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w)
        val bounded = MeasureSpec.getMode(h) != MeasureSpec.UNSPECIFIED
        val available = if (bounded) max(0, MeasureSpec.getSize(h) - dp(8f) - dp(8f)) else 0
        text.measure(MeasureSpec.makeMeasureSpec(max(0, width - dp(16f) - dp(8f)), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(available, if (bounded) MeasureSpec.AT_MOST else MeasureSpec.UNSPECIFIED))
        setMeasuredDimension(width, resolveSize(text.measuredHeight + dp(8f) + dp(8f), h))
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val logical = dp(16f)
        val x = if (layoutDirection == LAYOUT_DIRECTION_RTL) width - logical - text.measuredWidth else logical
        val available = height - dp(8f) - dp(8f)
        val y = dp(8f) + ((available - text.measuredHeight) / 2f).roundToInt()
        text.layout(x, y, x + text.measuredWidth, y + text.measuredHeight)
    }
}

/** Material Surface applies its2dp tonal overlay only when the requested color equals surface. */
internal fun passiveFeedbackColors(tone: FeedbackTone, palette: ReverbPalette): Pair<Int, Int> {
    val colors = when (tone) {
        FeedbackTone.ERROR -> palette.errorContainer to palette.onErrorContainer
        FeedbackTone.SUCCESS -> palette.primaryContainer to palette.onPrimaryContainer
        FeedbackTone.INFO -> palette.surfaceContainerHigh to palette.onSurface
    }
    if (colors.first != palette.surface) return colors
    val alpha = ((4.5f * ln(2f + 1f)) + 2f) / 100f
    return Color(palette.surfaceTint).copy(alpha = alpha).compositeOver(Color(colors.first)).toArgb() to colors.second
}
