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
import android.view.ViewOutlineProvider
import app.smallthingz.reverb.R
import kotlin.math.min
import kotlin.math.roundToInt

/** Released IconButton(size46dp), transparent CircleShape, explicit muted24dp Back glyph. */
internal class ReverbIncidentBackView(
    context: Context,
    palette: ReverbPalette,
    private var onBack: () -> Unit,
) : View(context) {
    private val ownerKey = Any()
    private val clip = Path()
    private val rect = RectF()
    private val icon = ReverbIconDrawable(ReverbIcon.Back, resources.displayMetrics.density, palette.muted)
    private val indication = ReverbRowIndication.install(this, palette.ink, ValueAnimator::getDurationScale)
    private val controller = ReverbClickController(this, object : NativeClickEnvironment {
        override val minimumTouchWidthPx get() = 48f * resources.displayMetrics.density
        override val minimumTouchHeightPx get() = minimumTouchWidthPx
        override fun admitDown(event: MotionEvent): Boolean = reverbGroupGeometry(
            width.toFloat(), height.toFloat(), resources.displayMetrics.density,
            min(width, height) / (2f * resources.displayMetrics.density),
        ).acceptsPointer(event.x, event.y, event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER, minimumTouchWidthPx)
        override fun interestedParent(event: MotionEvent): Boolean = false
        override fun longPressOwnershipChanged(owns: Boolean) = Unit
    }, ReverbRowIndicationSink(indication) { x, y, target -> target.set(x, y) })

    init {
        isClickable = true
        isFocusable = true
        contentDescription = context.getString(R.string.back)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
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
        icon.setTint(palette.muted)
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
        val glyph = (24f * resources.displayMetrics.density).roundToInt()
        val left = ((w - glyph) / 2f).roundToInt()
        val top = ((h - glyph) / 2f).roundToInt()
        icon.setBounds(left, top, left + glyph, top + glyph)
        indication.onMeasured(w, h, resources.displayMetrics.density)
        rect.set(0f, 0f, w.toFloat(), h.toFloat())
        clip.rewind()
        clip.addRoundRect(rect, min(w, h) / 2f, min(w, h) / 2f, Path.Direction.CW)
        invalidateOutline()
    }

    override fun onRtlPropertiesChanged(layoutDirection: Int) {
        super.onRtlPropertiesChanged(layoutDirection)
        icon.layoutDirection = layoutDirection
        invalidate()
    }

    override fun draw(canvas: Canvas) {
        if (canvas.isHardwareAccelerated) super.draw(canvas) else {
            val checkpoint = canvas.save()
            canvas.clipPath(clip)
            super.draw(canvas)
            canvas.restoreToCount(checkpoint)
        }
    }

    override fun onDraw(canvas: Canvas) { super.onDraw(canvas); icon.draw(canvas) }
    override fun onTouchEvent(event: MotionEvent): Boolean = controller.touch(event)
    override fun dispatchKeyEvent(event: KeyEvent): Boolean = controller.key(event) || super.dispatchKeyEvent(event)
    override fun onHoverEvent(event: MotionEvent): Boolean {
        val nativeHandled = super.onHoverEvent(event)
        return controller.hover(event) || nativeHandled
    }
    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        controller.focusChanged(gainFocus)
    }
    override fun getAccessibilityClassName(): CharSequence = "android.widget.Button"
}
