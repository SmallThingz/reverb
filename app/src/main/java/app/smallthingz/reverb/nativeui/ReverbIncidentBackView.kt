package app.smallthingz.reverb.nativeui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import app.smallthingz.reverb.R
import app.smallthingz.reverb.NativeClickPhaseHost
import app.smallthingz.reverb.ReverbClickPhaseEndpoint
import kotlin.math.min
import kotlin.math.roundToInt

/** Released IconButton(size46dp), transparent CircleShape, explicit muted24dp Back glyph. */
internal class ReverbIncidentBackView(
    context: Context,
    palette: ReverbPalette,
    private var onBack: () -> Unit,
) : ViewGroup(context), NativeClickPhaseHost, NativePointerLayerShape, NativeMergedRowAccessibilityHost {
    var pointerInputRoot: View? = null
    private val ownerKey = Any()
    private val clip = Path()
    private val rect = RectF()
    private val icon = OnboardingIconView(context, ReverbIcon.Back, palette.muted).apply {
        contentDescription = context.getString(R.string.back)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }
    override val mergedRowAccessibility = run {
        val children = listOf(icon)
        ReverbMergedRowAccessibility(this, NativeMergedRowRole.BUTTON,
            { NativeMergedRowAccessibilityState(true, null, null, children) },
            invokeSourceClick = { onBack(); true })
    }
    private val indication = ReverbRowIndication.install(this, palette.ink, { reverbAnimatorDurationScale(context) })
    private val controller = ReverbClickController(this, object : NativeClickEnvironment {
        override val nativeInputRoot get() = pointerInputRoot
        override val minimumTouchWidthPx get() = 48f * resources.displayMetrics.density
        override val minimumTouchHeightPx get() = minimumTouchWidthPx
        override fun admitDown(event: MotionEvent): Boolean = reverbGroupGeometry(
            width.toFloat(), height.toFloat(), resources.displayMetrics.density,
            min(width, height) / (2f * resources.displayMetrics.density),
        ).acceptsPointer(event.x, event.y, event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER, minimumTouchWidthPx)
        override fun interestedParent(event: MotionEvent): Boolean = false
        override fun longPressOwnershipChanged(owns: Boolean) = Unit
    }, ReverbRowIndicationSink(indication) { x, y, target -> target.set(x, y) })

    override val nativeClickEndpoint = ReverbClickPhaseEndpoint(controller)

    override fun containsPointerLayer(x: Float, y: Float): Boolean = reverbGroupGeometry(
        width.toFloat(), height.toFloat(), resources.displayMetrics.density,
        min(width, height) / (2f * resources.displayMetrics.density),
    ).contains(x, y)

    init {
        isClickable = true
        isFocusable = true
        contentDescription = null
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        accessibilityDelegate = mergedRowAccessibility
        addView(icon)
        addView(mergedRowAccessibility.roleView)
        setOnClickListener { onBack() }
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, width, height, min(width, height) / 2f)
            }
        }
        if (android.os.Build.VERSION.SDK_INT >= 29) isForceDarkAllowed = false
    }

    fun bind(palette: ReverbPalette, onBack: () -> Unit) {
        this.onBack = onBack
        icon.bind(ReverbIcon.Back, palette.muted)
        indication.updateColor(palette.ink)
        bindInput()
        invalidate()
    }

    private fun bindInput() {
        controller.bind(NativeClickBinding(ownerKey, true, onClick = { performClick() }))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        controller.attached()
        bindInput()
    }

    override fun onDetachedFromWindow() {
        controller.detached()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        indication.onMeasured(w, h, resources.displayMetrics.density)
        rect.set(0f, 0f, w.toFloat(), h.toFloat())
        clip.rewind()
        clip.addRoundRect(rect, min(w, h) / 2f, min(w, h) / 2f, Path.Direction.CW)
        invalidateOutline()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        val glyph = (24f * resources.displayMetrics.density).roundToInt()
        icon.measure(MeasureSpec.makeMeasureSpec(glyph, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(glyph, MeasureSpec.EXACTLY))
        mergedRowAccessibility.roleView.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val x = ((width - icon.measuredWidth) / 2f).roundToInt()
        val y = ((height - icon.measuredHeight) / 2f).roundToInt()
        icon.layout(x, y, x + icon.measuredWidth, y + icon.measuredHeight)
        mergedRowAccessibility.roleView.layout(0, 0, width, height)
    }

    override fun draw(canvas: Canvas) {
        if (canvas.isHardwareAccelerated) super.draw(canvas) else {
            val checkpoint = canvas.save()
            canvas.clipPath(clip)
            super.draw(canvas)
            canvas.restoreToCount(checkpoint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = controller.touch(event)
    override fun dispatchKeyEvent(event: KeyEvent): Boolean = when (event.keyCode) {
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
        KeyEvent.KEYCODE_SPACE -> controller.key(event)
        else -> super.dispatchKeyEvent(event)
    }
    override fun onHoverEvent(event: MotionEvent): Boolean {
        val nativeHandled = super.onHoverEvent(event)
        return controller.hover(event) || nativeHandled
    }
    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        controller.focusChanged(gainFocus)
    }
    override fun createAccessibilityNodeInfo(): AccessibilityNodeInfo? =
        if (mergedRowAccessibility.isNodePresent()) super.createAccessibilityNodeInfo() else null

    override fun addChildrenForAccessibility(outChildren: ArrayList<View>) =
        mergedRowAccessibility.addChildrenForAccessibility(outChildren)

    override fun getAccessibilityClassName(): CharSequence = "android.view.View"
}
