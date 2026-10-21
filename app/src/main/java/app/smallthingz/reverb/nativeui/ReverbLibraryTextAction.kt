package app.smallthingz.reverb.nativeui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Path
import android.graphics.Rect
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import androidx.core.view.ViewCompat
import app.smallthingz.reverb.NativeClickPhaseEndpoint
import app.smallthingz.reverb.NativeClickPhaseHost
import app.smallthingz.reverb.ReverbClickPhaseEndpoint
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Natural-width released TextButton used by Library Retry and Undo; no timer/operation owner. */
internal class ReverbLibraryTextAction(context: Context, environment: ReverbViewEnvironment, inputRoot: View) : ViewGroup(context) {
    private var density = environment.textDensity.density
    private val visual = LibraryTextActionSurface(context, environment, inputRoot)
    val textView: ReverbParagraphView get() = visual.label
    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        clipChildren = false
        clipToPadding = false
        addView(visual)
    }
    fun bind(environment: ReverbViewEnvironment, text: String, textColor: Int = environment.palette.primary, onClick: () -> Unit) {
        density = environment.textDensity.density
        visual.bind(environment, text, textColor, onClick)
        requestLayout()
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        visual.measure(widthMeasureSpec, heightMeasureSpec)
        val minimum = (48f * density).roundToInt()
        setMeasuredDimension(resolveSize(max(minimum, visual.measuredWidth), widthMeasureSpec),
            resolveSize(max(minimum, visual.measuredHeight), heightMeasureSpec))
    }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val x = ((width - visual.measuredWidth) / 2f).roundToInt()
        val y = ((height - visual.measuredHeight) / 2f).roundToInt()
        visual.layout(x, y, x + visual.measuredWidth, y + visual.measuredHeight)
    }
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val copy = MotionEvent.obtain(event)
        copy.offsetLocation(-visual.left.toFloat(), -visual.top.toFloat())
        return try { visual.dispatchTouchEvent(copy) } finally { copy.recycle() }
    }
}

private class LibraryTextActionSurface(context: Context, private var environment: ReverbViewEnvironment, inputRoot: View) :
    ViewGroup(context), NativeClickPhaseHost {
    private val identity = Any()
    private var action: (() -> Unit)? = null
    val label = environment.paragraph(context, "", ReverbTextRole.LABEL_LARGE, environment.palette.primary)
    private val path = Path()
    private val indication = ReverbRowIndication.install(this, environment.palette.primary, ValueAnimator::getDurationScale)
    private val controller = ReverbClickController(this, object : NativeClickEnvironment {
        override val nativeInputRoot: View = inputRoot
        override val minimumTouchWidthPx get() = 48f * environment.textDensity.density
        override val minimumTouchHeightPx get() = minimumTouchWidthPx
        override fun admitDown(event: MotionEvent) = reverbGroupGeometry(width.toFloat(), height.toFloat(),
            environment.textDensity.density, min(width, height) / (2f * environment.textDensity.density))
            .acceptsPointer(event.x, event.y, event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER, minimumTouchWidthPx)
        override fun interestedParent(event: MotionEvent): Boolean = false
        override fun longPressOwnershipChanged(owns: Boolean) = Unit
    }, ReverbRowIndicationSink(indication) { x, y, point -> point.set(x, y) })
    override val nativeClickEndpoint: NativeClickPhaseEndpoint = ReverbClickPhaseEndpoint(controller)
    init {
        isClickable = true
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        ViewCompat.setScreenReaderFocusable(this, true)
        ViewCompat.setScreenReaderFocusable(label, false)
        addView(label)
        setOnClickListener { action?.invoke() }
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, width, height, min(width, height) / 2f)
            }
        }
        if (android.os.Build.VERSION.SDK_INT >= 29) isForceDarkAllowed = false
    }
    fun bind(value: ReverbViewEnvironment, text: String, color: Int, callback: () -> Unit) {
        environment = value
        action = callback
        label.setText(text)
        value.bindParagraphStyle(label, ReverbTextRole.LABEL_LARGE, color)
        // An explicit Text color in FeedbackCard does not replace Button's LocalContentColor.
        indication.updateColor(value.palette.primary)
        bindInput()
        requestLayout()
    }
    private fun bindInput() {
        controller.bind(if (action == null) null else NativeClickBinding(identity, true, onClick = { performClick() }))
        controller.focusChanged(isFocused)
    }
    private fun dp(value: Float) = (value * environment.textDensity.density).roundToInt()
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val horizontal = dp(12f)
        val vertical = dp(8f)
        fun contentSpec(spec: Int, padding: Int): Int =
            if (MeasureSpec.getMode(spec) == MeasureSpec.UNSPECIFIED) spec
            else MeasureSpec.makeMeasureSpec(max(0, MeasureSpec.getSize(spec) - padding), MeasureSpec.AT_MOST)
        label.measure(contentSpec(widthMeasureSpec, horizontal + horizontal),
            contentSpec(heightMeasureSpec, vertical + vertical))
        setMeasuredDimension(resolveSize(max(dp(58f), label.measuredWidth + horizontal + horizontal), widthMeasureSpec),
            resolveSize(max(dp(40f), label.measuredHeight + vertical + vertical), heightMeasureSpec))
    }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val x = ((width - label.measuredWidth) / 2f).roundToInt()
        val y = ((height - label.measuredHeight) / 2f).roundToInt()
        label.layout(x, y, x + label.measuredWidth, y + label.measuredHeight)
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        indication.onMeasured(w, h, environment.textDensity.density)
        path.rewind()
        path.addRoundRect(0f, 0f, w.toFloat(), h.toFloat(), min(w, h) / 2f, min(w, h) / 2f, Path.Direction.CW)
        invalidateOutline()
    }
    override fun draw(canvas: Canvas) {
        if (canvas.isHardwareAccelerated) super.draw(canvas) else {
            val checkpoint = canvas.save()
            try { canvas.clipPath(path); super.draw(canvas) } finally { canvas.restoreToCount(checkpoint) }
        }
    }
    override fun onTouchEvent(event: MotionEvent) = controller.touch(event)
    override fun onKeyDown(code: Int, event: KeyEvent): Boolean =
        if (isActivationKey(code)) controller.key(event) else super.onKeyDown(code, event)
    override fun onKeyUp(code: Int, event: KeyEvent): Boolean =
        if (isActivationKey(code)) controller.key(event) else super.onKeyUp(code, event)
    override fun onHoverEvent(event: MotionEvent): Boolean { val handled = super.onHoverEvent(event); return controller.hover(event) || handled }
    override fun onFocusChanged(focused: Boolean, direction: Int, rect: Rect?) { super.onFocusChanged(focused, direction, rect); controller.focusChanged(focused) }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); controller.attached(); bindInput() }
    override fun onDetachedFromWindow() { controller.detached(); super.onDetachedFromWindow() }
    override fun getAccessibilityClassName(): CharSequence = "android.widget.Button"
}

private fun isActivationKey(code: Int): Boolean = when (code) {
    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
    KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_SPACE -> true
    else -> false
}
