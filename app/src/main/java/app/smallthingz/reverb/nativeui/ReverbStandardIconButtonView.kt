package app.smallthingz.reverb.nativeui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.accessibility.AccessibilityNodeInfo
import app.smallthingz.reverb.NativeClickPhaseEndpoint
import app.smallthingz.reverb.NativeClickPhaseHost
import app.smallthingz.reverb.ReverbClickPhaseEndpoint
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pinned M3 1.4.0 standard IconButton:48dp minimum layout,40dp circular action,24dp described Icon.
 * The source LocalContentColor (ripple) is independent from an explicitly tinted Icon.
 * This is a direct View leaf, not a themed platform widget or a second renderer.
 */
internal class ReverbStandardIconButtonView(
    context: Context,
    initialEnvironment: ReverbViewEnvironment,
    inputRoot: () -> View?,
    private val actionSizeDp: Float = 40f,
) : ViewGroup(context) {
    private var environment = initialEnvironment
    private var currentClick: () -> Unit = {}
    private val identity = Any()
    val visual = ActionVisual(context)
    private val indication = ReverbRowIndication.install(
        visual, initialEnvironment.palette.onSurface, ValueAnimator::getDurationScale)
    private val controller = ReverbClickController(visual, object : NativeClickEnvironment {
        override val nativeInputRoot: View? get() = inputRoot()
        override val minimumTouchWidthPx get() = 48f * environment.textDensity.density
        override val minimumTouchHeightPx get() = minimumTouchWidthPx
        override fun admitDown(event: MotionEvent): Boolean {
            val w = visual.width.toFloat(); val h = visual.height.toFloat()
            val dx = event.x - w * .5f; val dy = event.y - h * .5f
            val radius = min(w, h) * .5f
            if (dx * dx + dy * dy <= radius * radius) return true
            if (event.getToolType(0) != MotionEvent.TOOL_TYPE_FINGER) return false
            val x = max(0f, minimumTouchWidthPx - w) * .5f
            val y = max(0f, minimumTouchHeightPx - h) * .5f
            return event.x >= -x && event.x <= w + x && event.y >= -y && event.y <= h + y
        }
        override fun interestedParent(event: MotionEvent) = false
        override fun longPressOwnershipChanged(owns: Boolean) = Unit
    }, ReverbRowIndicationSink(indication) { x, y, point -> point.set(x, y) })
    private val endpoint: NativeClickPhaseEndpoint = ReverbClickPhaseEndpoint(controller)

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        clipChildren = false; clipToPadding = false
        visual.setOnClickListener { if (visual.isEnabled) currentClick() }
        addView(visual)
    }

    fun bind(
        value: ReverbViewEnvironment,
        icon: ReverbIcon,
        description: String,
        enabled: Boolean,
        iconTint: Int,
        inheritedContentColor: Int,
        onClick: () -> Unit,
    ) {
        environment = value; currentClick = onClick
        visual.isEnabled = enabled; visual.isFocusable = enabled
        visual.glyph.bind(icon, iconTint)
        visual.glyph.contentDescription = description
        // IconButton's LocalContentColor inherits the parent even when Icon overrides its tint.
        indication.updateColor(inheritedContentColor)
        controller.bind(NativeClickBinding(identity, enabled, { visual.performClick() }))
        requestLayout()
    }

    /** The host supplies its shared cursor and exact source presence/hidden/touch bounds facts. */
    fun bindAccessibility(
        window: PlainTextAccessibilityWindowScope,
        geometry: NativePlainTextViewGeometry,
        sourceNode: (View) -> NativeSourceAccessibilityNode,
    ): AutoCloseable {
        val parentLease = visual.mergedRowAccessibility.bindGeometry(
            geometry, { sourceNode(visual) }, { sourceNode(it).present }, { !sourceNode(visual).hidden })
        val childLease = visual.glyph.bindDescriptionAccessibility(
            window, { sourceNode(visual.glyph) }, { !sourceNode(visual.glyph).hidden })
        return AutoCloseable { childLease.close(); parentLease.close() }
    }

    fun cancelInput() = controller.cancel()
    val minimumTouchTargetPx get() = 48f * environment.textDensity.density

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val d = environment.textDensity.density
        val outer = (48f * d).roundToInt()
        // Released explicit Modifier.size(50.dp), used by Range Close, constrains M3's
        // later default40dp action to50. Other callers retain the default40dp visual.
        // A smaller actual parent bound still constrains both axes independently.
        val inner = (actionSizeDp * d).roundToInt()
        val w = resolveSize(outer, widthMeasureSpec); val h = resolveSize(outer, heightMeasureSpec)
        visual.measure(exact(min(w, inner)), exact(min(h, inner)))
        setMeasuredDimension(w, h)
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val x = ((width - visual.measuredWidth) * .5f).roundToInt()
        val y = ((height - visual.measuredHeight) * .5f).roundToInt()
        visual.layout(x, y, x + visual.measuredWidth, y + visual.measuredHeight)
        indication.onMeasured(visual.width, visual.height, environment.textDensity.density)
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        controller.attached()
        controller.bind(NativeClickBinding(identity, visual.isEnabled, { visual.performClick() }))
        controller.focusChanged(visual.isFocused)
    }
    override fun onDetachedFromWindow() { controller.detached(); super.onDetachedFromWindow() }
    override fun onCancelPendingInputEvents() { super.onCancelPendingInputEvents(); controller.cancel() }

    internal inner class ActionVisual(context: Context) : ViewGroup(context),
        NativeClickPhaseHost, NativeMergedRowAccessibilityHost {
        val glyph = OnboardingIconView(context, ReverbIcon.Back, environment.palette.onSurface)
        private val clip = Path()
        override val nativeClickEndpoint: NativeClickPhaseEndpoint get() = endpoint
        override val mergedRowAccessibility = ReverbMergedRowAccessibility(
            this, NativeMergedRowRole.BUTTON,
            sourceState = { NativeMergedRowAccessibilityState(isEnabled, null, null, listOf(glyph)) },
            invokeSourceClick = { if (isEnabled) { currentClick(); true } else false },
        )
        init {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
            isClickable = true; isFocusable = true
            glyph.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
            accessibilityDelegate = mergedRowAccessibility
            addView(glyph); addView(mergedRowAccessibility.roleView)
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, width, height, min(width, height) * .5f)
                }
            }
        }
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec); val h = MeasureSpec.getSize(heightMeasureSpec)
            val size = (24f * environment.textDensity.density).roundToInt()
            glyph.measure(exact(min(w, size)), exact(min(h, size)))
            mergedRowAccessibility.roleView.measure(exact(w), exact(h))
            setMeasuredDimension(w, h)
        }
        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val x = ((width - glyph.measuredWidth) * .5f).roundToInt()
            val y = ((height - glyph.measuredHeight) * .5f).roundToInt()
            glyph.layout(x, y, x + glyph.measuredWidth, y + glyph.measuredHeight)
            mergedRowAccessibility.roleView.layout(0, 0, width, height)
        }
        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            clip.rewind()
            clip.addRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), min(w, h) * .5f,
                min(w, h) * .5f, Path.Direction.CW)
            invalidateOutline()
        }
        override fun draw(canvas: Canvas) {
            if (canvas.isHardwareAccelerated) super.draw(canvas) else {
                val checkpoint = canvas.save()
                try { canvas.clipPath(clip); super.draw(canvas) }
                finally { canvas.restoreToCount(checkpoint) }
            }
        }
        override fun addChildrenForAccessibility(outChildren: ArrayList<View>) =
            mergedRowAccessibility.addChildrenForAccessibility(outChildren)
        override fun createAccessibilityNodeInfo(): AccessibilityNodeInfo? =
            if (mergedRowAccessibility.isNodePresent()) super.createAccessibilityNodeInfo() else null
        override fun onTouchEvent(event: MotionEvent) = controller.touch(event)
        override fun dispatchKeyEvent(event: KeyEvent): Boolean = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_SPACE -> controller.key(event)
            else -> super.dispatchKeyEvent(event)
        }
        override fun onHoverEvent(event: MotionEvent): Boolean {
            val native = super.onHoverEvent(event); return controller.hover(event) || native
        }
        override fun onFocusChanged(focused: Boolean, direction: Int, old: Rect?) {
            super.onFocusChanged(focused, direction, old); controller.focusChanged(focused)
        }
        override fun getAccessibilityClassName(): CharSequence = "android.view.View"
    }
    private fun exact(size: Int) = MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY)
}
