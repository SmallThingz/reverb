package app.smallthingz.reverb.nativeui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import smallthingz.ui.runtime.MonotonicFrameClock
import smallthingz.ui.graphics.Color
import smallthingz.ui.graphics.compositeOver
import smallthingz.ui.graphics.toArgb
import smallthingz.ui.unit.Density
import app.smallthingz.reverb.NativeClickPhaseNode
import app.smallthingz.reverb.NativeDragPointerChange
import app.smallthingz.reverb.NativeDragPointerEvent
import app.smallthingz.reverb.NativePointerPass
import app.smallthingz.reverb.NativeViewPhasePointerNode
import app.smallthingz.reverb.ReverbClickPhaseEndpoint
import app.smallthingz.reverb.R
import app.smallthingz.reverb.rangeFineTuneConstrainedY
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

internal data class SpringFineSeekEnvironment(
    val palette: ReverbPalette,
    val density: Density,
    val touchSlop: Float,
    val frameClock: MonotonicFrameClock,
    val scope: CoroutineScope,
    val inputRoot: View,
    val parentInterested: (NativeDragPointerChange) -> Boolean,
)

/**
 * The released SpringFineSeekControl as direct ordinary Views, including Range's optional tap owner.
 *
 * Parent supplies source104/120dp bounds and external trim alpha. Place fineSeekNode on this full
 * rectangle only while fineSeekInputEnabled, then puckClickNode on puckClickView's live64dp rectangle in the shared phase hit tree.
 * Neither parent nor this leaf must also run a stock Android gesture recognizer for that stream.
 * Offsets update child placement directly, not a second virtual/layout tree or a recomposition.
 */
internal class ReverbSpringFineSeekView(
    context: Context,
    private val environment: SpringFineSeekEnvironment,
) : ViewGroup(context) {
    private var palette = environment.palette
    private val colors = FineSeekColorPalette(palette.primary, palette.secondary, palette.tertiary)
    private var binding: SpringFineSeekBinding? = null
    private var inputActive = false
    private var attachedScope: CoroutineScope? = null
    private var frameJob: Job? = null
    private val field = Path()
    private val paint = Paint(PAINT_FLAGS)
    private val dotPaint = Paint(PAINT_FLAGS)
    private val model = NativeSpringFineSeekState(::visualChanged, ::requestFrames)
    private val puck = PuckView(context)
    private var geometry = SpringFineSeekGeometry(0, 0, environment.density.density)
    private var geometryLease: AutoCloseable? = null
    private var descriptionLease: AutoCloseable? = null
    val puckClickView: View get() = puck
    /** Remove the source parent pointer modifier when disabled; the disabled child clickable remains. */
    val fineSeekInputEnabled: Boolean get() = binding?.enabled == true

    val fineSeekNode: NativeViewPhasePointerNode = object : NativeViewPhasePointerNode {
        override val view: View get() = this@ReverbSpringFineSeekView
        override fun dispatch(event: NativeDragPointerEvent, pass: NativePointerPass) {
            model.pointer(event, pass, geometry)
        }
        override fun cancel() { model.cancel() }
    }
    val puckClickNode = NativeClickPhaseNode(puck, ReverbClickPhaseEndpoint(puck.controller),
        environment.parentInterested)

    init {
        setWillNotDraw(false)
        clipChildren = false
        clipToPadding = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        if (Build.VERSION.SDK_INT >= 29) isForceDarkAllowed = false
        addView(puck)
    }

    fun bind(value: SpringFineSeekBinding) {
        binding = value
        model.bind(value, environment.touchSlop)
        puck.bind(value)
    }

    fun setPalette(value: ReverbPalette) {
        if (palette == value) return
        palette = value
        colors.update(value.primary, value.secondary, value.tertiary)
        binding?.let(puck::bind)
        invalidate()
    }

    /** Reuse the screen/window's existing cursor and geometry; never create another cursor. */
    fun bindAccessibility(
        window: PlainTextAccessibilityWindowScope,
        geometry: NativePlainTextViewGeometry,
        exposed: () -> Boolean,
    ): AutoCloseable {
        geometryLease?.close(); descriptionLease?.close()
        val localGeometry = puck.mergedRowAccessibility.bindGeometry(geometry, exposed)
        val localDescription = puck.glyph.bindDescriptionAccessibility(window, exposed)
        geometryLease = localGeometry; descriptionLease = localDescription
        return AutoCloseable {
            localGeometry.close(); localDescription.close()
            if (geometryLease === localGeometry) geometryLease = null
            if (descriptionLease === localDescription) descriptionLease = null
        }
    }

    fun cancelInput() { model.cancel(); puck.controller.cancel() }

    /** Recycler/presentation owner retires callbacks before binding a different recording. */
    fun release() {
        model.release()
        binding = null
        puck.controller.bind(null)
        frameJob?.cancel(); frameJob = null
        geometryLease?.close(); geometryLease = null
        descriptionLease?.close(); descriptionLease = null
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        inputActive = true
        attachedScope = CoroutineScope(environment.scope.coroutineContext +
            SupervisorJob(environment.scope.coroutineContext[Job]))
        requestFrames()
    }

    override fun onDetachedFromWindow() {
        inputActive = false
        model.cancel()
        frameJob?.cancel(); frameJob = null
        attachedScope?.cancel(); attachedScope = null
        super.onDetachedFromWindow()
    }

    private fun requestFrames() {
        if (!inputActive || !model.needsFrames || frameJob?.isActive == true) return
        frameJob = attachedScope?.launch {
            // Source uses raw withFrameNanos and210ms cosh return, not animator duration scaling.
            while (model.needsFrames && inputActive) {
                environment.frameClock.withFrameNanos(model::frame)
            }
        }
    }

    private fun visualChanged() {
        if (isLaidOut) placePuck()
        invalidate()
        puck.invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Released callers provide bounded fillMaxWidth + explicit104/120dp height.
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        geometry = SpringFineSeekGeometry(w, h, environment.density.density)
        puck.measure(MeasureSpec.makeMeasureSpec(geometry.hitWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(geometry.hitHeight, MeasureSpec.EXACTLY))
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) = placePuck()
    private fun placePuck() {
        val x = geometry.puckLeft(model.horizontalPull, layoutDirection == LAYOUT_DIRECTION_RTL)
        val y = geometry.puckTop(model.horizontalPull, model.rawVerticalPull)
        puck.layout(x, y, x + puck.measuredWidth, y + puck.measuredHeight)
    }

    override fun onRtlPropertiesChanged(layoutDirection: Int) {
        super.onRtlPropertiesChanged(layoutDirection)
        if (isLaidOut) placePuck()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val density = environment.density.density
        val liveX = model.horizontalPull
        val liveY = rangeFineTuneConstrainedY(model.rawVerticalPull, liveX)
        val centerX = width * .5f
        val centerY = height * .5f
        val radius = 24f * density
        val puckX = centerX + liveX * geometry.drawHorizontalTravel
        val puckY = centerY + liveY * geometry.drawVerticalTravel
        val left = 10f * density
        val right = width - left
        val leftSpan = (puckX - radius - left).coerceAtLeast(1f)
        val rightSpan = (right - puckX - radius).coerceAtLeast(1f)
        val top = puckY - radius
        val bottom = puckY + radius
        field.rewind()
        field.moveTo(left, centerY)
        field.cubicTo(left + leftSpan * .30f, centerY,
            (puckX - radius - leftSpan * .28f).coerceAtLeast(left), top, puckX, top)
        field.cubicTo((puckX + radius + rightSpan * .28f).coerceAtMost(right), top,
            right - rightSpan * .30f, centerY, right, centerY)
        field.cubicTo(right - rightSpan * .30f, centerY,
            (puckX + radius + rightSpan * .28f).coerceAtMost(right), bottom, puckX, bottom)
        field.cubicTo((puckX - radius - leftSpan * .28f).coerceAtLeast(left), bottom,
            left + leftSpan * .30f, centerY, left, centerY)
        field.close()

        val power = abs(liveX).pow(.72f)
        val yMagnitude = (abs(liveY) / .72f).coerceIn(0f, 1f)
        // Keep packed Oklab endpoint quantization; only the palette-dependent work is cached.
        val color = colors.color(liveY, yMagnitude)
        val alpha = .12f + .13f * power + .06f * yMagnitude
        paint.color = android.graphics.Color.BLACK
        paint.alpha = 255
        paint.shader = LinearGradient(left, 0f, right, 0f, intArrayOf(
            color.copy(alpha = .018f).toArgb(), color.copy(alpha = alpha).toArgb(),
            color.copy(alpha = .018f).toArgb()), null, Shader.TileMode.CLAMP)
        canvas.drawPath(field, paint)
        paint.shader = null
        paint.color = color.copy(alpha = .035f + .045f * yMagnitude).toArgb()
        canvas.drawPath(field, paint)
        dotPaint.color = Color(palette.onSurfaceVariant).copy(alpha = if (model.dragging) .20f else .13f).toArgb()
        canvas.drawCircle(centerX, centerY, 1.6f * density, dotPaint)
        if (model.dragging) {
            dotPaint.color = color.copy(alpha = .055f + .055f * power).toArgb()
            canvas.drawCircle(puckX, puckY, radius * 1.28f, dotPaint)
        }
    }

    private inner class PuckView(context: Context) : ViewGroup(context), NativeMergedRowAccessibilityHost {
        val glyph = OnboardingIconView(context, ReverbIcon.Play, palette.surface)
        private val surfaceBounds = RectF()
        private val surfacePaint = Paint(PAINT_FLAGS)
        val controller = ReverbClickController(this, object : NativeClickEnvironment {
            override val nativeInputRoot get() = environment.inputRoot
            override fun admitDown(event: MotionEvent) =
                event.x >= 0f && event.y >= 0f && event.x <= width && event.y <= height
            override fun interestedParent(event: MotionEvent) = false // Shared phase node supplies actual connection.
            override fun longPressOwnershipChanged(owns: Boolean) = Unit // Source has no long-click.
            override val minimumTouchWidthPx get() = 48f * environment.density.density
            override val minimumTouchHeightPx get() = minimumTouchWidthPx
        }, NO_INDICATION)
        override val mergedRowAccessibility = ReverbMergedRowAccessibility(
            this, NativeMergedRowRole.BUTTON,
            sourceState = { NativeMergedRowAccessibilityState(binding?.enabled == true, null, null, listOf(glyph)) },
            invokeSourceClick = {
                val current = binding
                if (current?.enabled == true) { current.onTogglePlayback(); true } else false
            },
        )

        init {
            setWillNotDraw(false)
            clipChildren = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
            isClickable = true
            isFocusable = true
            accessibilityDelegate = mergedRowAccessibility
            glyph.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
            addView(glyph)
            addView(mergedRowAccessibility.roleView)
            setOnClickListener { binding?.takeIf { it.enabled }?.onTogglePlayback?.invoke() }
        }

        fun bind(value: SpringFineSeekBinding) {
            isEnabled = value.enabled
            isFocusable = value.enabled
            glyph.bind(if (value.isPlaying) ReverbIcon.Pause else ReverbIcon.Play, palette.surface)
            glyph.contentDescription = context.getString(if (value.isPlaying) R.string.player_pause else R.string.player_play)
            controller.bind(NativeClickBinding(value.interactionKey ?: NULL_KEY, value.enabled, { performClick() }))
            invalidate()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec); val h = MeasureSpec.getSize(heightMeasureSpec)
            val d = environment.density.density
            val sw = minOf(w, (48f * d).roundToInt())
            val sh = minOf(h, (48f * d).roundToInt())
            val sx = ((w - sw) * .5f).roundToInt()
            val sy = ((h - sh) * .5f).roundToInt()
            surfaceBounds.set(sx.toFloat(), sy.toFloat(), (sx + sw).toFloat(), (sy + sh).toFloat())
            val gw = minOf(sw, (23f * d).roundToInt())
            val gh = minOf(sh, (23f * d).roundToInt())
            glyph.measure(MeasureSpec.makeMeasureSpec(gw, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(gh, MeasureSpec.EXACTLY))
            mergedRowAccessibility.roleView.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
            setMeasuredDimension(w, h)
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            // Center inside the independently rounded48dp Surface, then center Surface inside64dp.
            val x = surfaceBounds.left.toInt() + ((surfaceBounds.width() - glyph.measuredWidth) * .5f).roundToInt()
            val y = surfaceBounds.top.toInt() + ((surfaceBounds.height() - glyph.measuredHeight) * .5f).roundToInt()
            glyph.layout(x, y, x + glyph.measuredWidth, y + glyph.measuredHeight)
            mergedRowAccessibility.roleView.layout(0, 0, width, height)
        }

        override fun onDraw(canvas: Canvas) {
            var fill = Color(palette.onSurface)
            if (!model.dragging && palette.onSurface == palette.surface) {
                // Material3 applies tonal elevation only when the color equals ColorScheme.surface.
                val overlayAlpha = (4.5f * ln(2f + 1f) + 2f) / 100f
                fill = Color(palette.primary).copy(alpha = overlayAlpha).compositeOver(fill)
            }
            surfacePaint.color = fill.toArgb()
            val radius = minOf(surfaceBounds.width(), surfaceBounds.height()) * .5f
            canvas.drawRoundRect(surfaceBounds, radius, radius, surfacePaint)
        }

        override fun onAttachedToWindow() { super.onAttachedToWindow(); controller.attached() }
        override fun onDetachedFromWindow() { controller.detached(); super.onDetachedFromWindow() }
        override fun addChildrenForAccessibility(outChildren: ArrayList<View>) =
            mergedRowAccessibility.addChildrenForAccessibility(outChildren)
        override fun createAccessibilityNodeInfo(): AccessibilityNodeInfo? =
            if (mergedRowAccessibility.isNodePresent()) super.createAccessibilityNodeInfo() else null
        override fun onTouchEvent(event: MotionEvent): Boolean = controller.touch(event)
        override fun dispatchKeyEvent(event: KeyEvent): Boolean = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_SPACE -> controller.key(event)
            else -> super.dispatchKeyEvent(event)
        }
        override fun onHoverEvent(event: MotionEvent): Boolean {
            val native = super.onHoverEvent(event)
            return controller.hover(event) || native
        }
        override fun onFocusChanged(gainFocus: Boolean, direction: Int, previous: Rect?) {
            super.onFocusChanged(gainFocus, direction, previous)
            controller.focusChanged(gainFocus)
        }
        override fun getAccessibilityClassName(): CharSequence = "android.view.View"
    }

    private companion object {
        const val PAINT_FLAGS = Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG or Paint.FILTER_BITMAP_FLAG
        val NULL_KEY = Any()
        val NO_INDICATION = object : NativeClickIndication {
            override fun press(token: NativePressToken) = Unit
            override fun release(token: NativePressToken) = Unit
            override fun cancel(token: NativePressToken) = Unit
            override fun stateEnter(token: NativeStateToken) = Unit
            override fun stateExit(token: NativeStateToken) = Unit
        }
    }
}
