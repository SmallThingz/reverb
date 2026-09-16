package app.smallthingz.reverb

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.annotation.ColorInt
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sin

internal class RangeDurationWheelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {
    private class Wheel(var position: Double, var count: Int)

    private val density = resources.displayMetrics.density
    private val textScale = density * resources.configuration.fontScale
    private val rowPx = 56f * density
    private val radiusPx = 52f * density
    private val perspectivePx = 320f * density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    private val hourWheel = Wheel(0.0, 1)
    private val minuteWheel = Wheel(0.0, 60)
    private val secondWheel = Wheel(0.0, 60)
    private val profileWheel = Wheel(0.0, PROFILE_LABELS.size)
    private val wheels = arrayOf(hourWheel, minuteWheel, secondWheel, profileWheel)
    private var hourValues = intArrayOf(0)
    private var minuteValues = rangeDurationWheelValues(step = 1, maxInclusive = 59, currentValue = 0)
    private var secondValues = rangeDurationWheelValues(step = 1, maxInclusive = 59, currentValue = 0)

    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        textSize = 25f * textScale
    }
    private val profilePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        textSize = 14f * textScale
    }
    private val colonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        textSize = 25f * textScale
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
        strokeCap = Paint.Cap.ROUND
    }
    private val valueBaseline = -(valuePaint.ascent() + valuePaint.descent()) * 0.5f
    private val profileBaseline = -(profilePaint.ascent() + profilePaint.descent()) * 0.5f
    private val colonBaseline = -(colonPaint.ascent() + colonPaint.descent()) * 0.5f
    private val numberBuffer = CharArray(12)

    @ColorInt private var inkColor = Color.WHITE
    @ColorInt private var mutedColor = Color.GRAY
    @ColorInt private var borderColor = Color.DKGRAY
    @ColorInt private var errorColor = Color.RED

    private var maximumDurationSecondsExact = 0.0
    private var maximumWholeSeconds = 0
    private var maximumParts = RangeDurationWheelTimeParts(0, 0, 0)
    private var currentDurationSecondsExact = 0.0

    private var timeRight = 1f
    private var timeColumnWidth = 1f
    private var hourCenter = 0f
    private var minuteCenter = 0f
    private var secondCenter = 0f
    private var firstColon = 0f
    private var secondColon = 0f
    private var dividerX = 0f
    private var profileStart = 0f
    private var profileWidth = 1f
    private var profileCenter = 0f

    private var profileIndex = 0
    private var activeWheelIndex = NO_WHEEL
    private var pointerId = MotionEvent.INVALID_POINTER_ID
    private var downY = 0f
    private var downPosition = 0.0
    private var dragged = false

    private var animationWheelIndex = NO_WHEEL
    private var animationStart = 0.0
    private var animationTarget = 0.0
    private var animationStartedNanos = 0L
    private var lastCommittedSeconds = 0
    private var accessibilityLabel = ""

    var onInteractionStart: (() -> Unit)? = null
    var onDurationChanged: ((Int) -> Unit)? = null

    private val animationFrame = object : Runnable {
        override fun run() {
            val index = animationWheelIndex
            if (index == NO_WHEEL) return
            val elapsedMs = (System.nanoTime() - animationStartedNanos) / 1_000_000f
            val t = (elapsedMs / SNAP_DURATION_MS).coerceIn(0f, 1f)
            val eased = 1f - (1f - t) * (1f - t) * (1f - t)
            wheels[index].position = animationStart +
                (animationTarget - animationStart) * eased.toDouble()
            invalidate()
            if (t < 1f) {
                postOnAnimation(this)
            } else {
                wheels[index].position = animationTarget
                animationWheelIndex = NO_WHEEL
                finishInteraction(index)
            }
        }
    }

    init {
        isClickable = true
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        updateContentDescription()
    }

    fun setAccessibilityLabel(label: String) {
        if (accessibilityLabel == label) return
        accessibilityLabel = label
        updateContentDescription()
    }

    fun setPalette(
        @ColorInt ink: Int,
        @ColorInt muted: Int,
        @ColorInt border: Int,
        @ColorInt error: Int,
    ) {
        if (
            inkColor == ink && mutedColor == muted && borderColor == border && errorColor == error
        ) return
        inkColor = ink
        mutedColor = muted
        borderColor = border
        errorColor = error
        invalidate()
    }

    fun setMaximumDurationSeconds(seconds: Double) {
        val safe = seconds.takeIf { it.isFinite() }?.coerceAtLeast(0.0) ?: 0.0
        if (maximumDurationSecondsExact == safe) return
        val current = currentDisplayParts()
        maximumDurationSecondsExact = safe
        maximumWholeSeconds = rangeDurationWheelWholeLimitSeconds(safe)
        maximumParts = splitRangeDurationWheelSeconds(maximumWholeSeconds)
        rebuildTimeWheels(current.hours, current.minutes, current.seconds)
        updateContentDescription()
        invalidate()
    }

    fun setDurationSeconds(seconds: Double) {
        val safe = seconds.takeIf { it.isFinite() }?.coerceAtLeast(0.0) ?: 0.0
        if (isGestureActive()) return
        if (currentDurationSecondsExact == safe) return
        stopAnimation()
        currentDurationSecondsExact = safe
        val displaySeconds = rangeDurationWheelDisplaySeconds(safe, maximumDurationSecondsExact)
        val parts = splitRangeDurationWheelSeconds(displaySeconds)
        rebuildTimeWheels(parts.hours, parts.minutes, parts.seconds)
        lastCommittedSeconds = displaySeconds
        updateContentDescription()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredWidth = (280f * density).toInt()
        val desiredHeight = (160f * density).toInt()
        setMeasuredDimension(
            resolveSize(desiredWidth, widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec),
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        profileWidth = w * 0.19f
        val separatorGap = 14f * density
        profileStart = w - profileWidth
        timeRight = (profileStart - separatorGap).coerceAtLeast(1f)
        timeColumnWidth = timeRight / 3f
        hourCenter = timeColumnWidth * 0.5f
        minuteCenter = timeColumnWidth * 1.5f
        secondCenter = timeColumnWidth * 2.5f
        firstColon = timeColumnWidth
        secondColon = timeColumnWidth * 2f
        dividerX = profileStart - separatorGap * 0.5f
        profileCenter = profileStart + profileWidth * 0.5f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        linePaint.color = borderColor
        drawSelection(canvas, hourCenter, timeColumnWidth * 0.38f)
        drawSelection(canvas, minuteCenter, timeColumnWidth * 0.38f)
        drawSelection(canvas, secondCenter, timeColumnWidth * 0.38f)
        drawSelection(canvas, profileCenter, profileWidth * 0.40f)
        canvas.drawLine(dividerX, 0f, dividerX, height.toFloat(), linePaint)

        val errorMask = currentErrorMask()
        colonPaint.alpha = 255
        colonPaint.color = if (
            errorMask and RANGE_DURATION_WHEEL_ERROR_HOUR != 0
        ) errorColor else mutedColor
        canvas.drawText(":", firstColon, height * 0.5f + colonBaseline, colonPaint)
        colonPaint.color = if (
            errorMask and (RANGE_DURATION_WHEEL_ERROR_HOUR or RANGE_DURATION_WHEEL_ERROR_MINUTE) != 0
        ) errorColor else mutedColor
        canvas.drawText(":", secondColon, height * 0.5f + colonBaseline, colonPaint)

        drawNumberWheel(canvas, hourWheel, hourCenter, NumberKind.HOUR)
        drawNumberWheel(canvas, minuteWheel, minuteCenter, NumberKind.MINUTE)
        drawNumberWheel(canvas, secondWheel, secondCenter, NumberKind.SECOND)
        drawProfileWheel(canvas, profileCenter)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val index = wheelIndexAt(event.x)
                if (index == NO_WHEEL) return false
                stopAnimation()
                onInteractionStart?.invoke()
                requestFocus()
                parent?.requestDisallowInterceptTouchEvent(true)
                activeWheelIndex = index
                pointerId = event.getPointerId(0)
                downY = event.y
                downPosition = wheels[index].position
                dragged = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val pointerIndex = event.findPointerIndex(pointerId)
                if (pointerIndex < 0 || activeWheelIndex == NO_WHEEL) return false
                val deltaY = event.getY(pointerIndex) - downY
                if (abs(deltaY) > touchSlop) dragged = true
                wheels[activeWheelIndex].position = downPosition - deltaY / rowPx
                postInvalidateOnAnimation()
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (activeWheelIndex == NO_WHEEL) return false
                if (!dragged) {
                    performClick()
                    val third = height / 3f
                    when {
                        event.y < third -> wheels[activeWheelIndex].position -= 1.0
                        event.y > third * 2f -> wheels[activeWheelIndex].position += 1.0
                    }
                }
                snapActiveWheel()
                pointerId = MotionEvent.INVALID_POINTER_ID
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) == pointerId) {
                    if (activeWheelIndex != NO_WHEEL) snapActiveWheel()
                    pointerId = MotionEvent.INVALID_POINTER_ID
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (activeWheelIndex != NO_WHEEL) snapActiveWheel()
                pointerId = MotionEvent.INVALID_POINTER_ID
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_SCROLL &&
            event.isFromSource(InputDevice.SOURCE_CLASS_POINTER)
        ) {
            val index = wheelIndexAt(event.x)
            if (index == NO_WHEEL) return false
            stopAnimation()
            onInteractionStart?.invoke()
            val delta = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            if (delta == 0f) return false
            wheels[index].position += if (delta > 0f) -1.0 else 1.0
            animateTo(index, round(wheels[index].position))
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDetachedFromWindow() {
        stopAnimation()
        super.onDetachedFromWindow()
    }

    private fun snapActiveWheel() {
        val index = activeWheelIndex
        activeWheelIndex = NO_WHEEL
        if (index == NO_WHEEL) return
        animateTo(index, round(wheels[index].position))
    }

    private fun animateTo(index: Int, target: Double) {
        stopAnimation()
        val start = wheels[index].position
        if (abs(target - start) < 0.0001) {
            wheels[index].position = target
            finishInteraction(index)
            return
        }
        animationWheelIndex = index
        animationStart = start
        animationTarget = target
        animationStartedNanos = System.nanoTime()
        postOnAnimation(animationFrame)
    }

    private fun stopAnimation() {
        if (animationWheelIndex == NO_WHEEL) return
        removeCallbacks(animationFrame)
        animationWheelIndex = NO_WHEEL
    }

    private fun finishInteraction(index: Int) {
        if (index == PROFILE_WHEEL) {
            applyProfileSelection()
        } else {
            val selected = currentDisplayParts()
            rebuildTimeWheels(selected.hours, selected.minutes, selected.seconds)
        }
        publishDurationIfChanged()
        updateContentDescription()
        invalidate()
    }

    private fun publishDurationIfChanged() {
        val seconds = currentTotalSeconds()
        currentDurationSecondsExact = seconds.toDouble()
        if (seconds == lastCommittedSeconds) return
        lastCommittedSeconds = seconds
        onDurationChanged?.invoke(seconds)
    }

    private fun applyProfileSelection() {
        val newProfile = normalizedIndex(profileWheel)
        val selected = currentDisplayParts()
        profileIndex = newProfile
        profileWheel.position = newProfile.toDouble()
        rebuildTimeWheels(selected.hours, selected.minutes, selected.seconds)
    }

    private fun rebuildTimeWheels(hours: Int, minutes: Int, seconds: Int) {
        val step = rangeDurationWheelProfileStep(profileIndex)
        hourValues = rangeDurationWheelValues(
            step = 1,
            maxInclusive = maximumParts.hours,
            currentValue = hours,
        )
        val minuteConstrained = hours == maximumParts.hours
        val minuteMax = if (minuteConstrained) maximumParts.minutes else 59
        minuteValues = rangeDurationWheelValues(
            step = step,
            maxInclusive = minuteMax,
            currentValue = minutes.coerceIn(0, 59),
            includeMaximumBoundary = minuteConstrained,
        )
        val secondConstrained = hours == maximumParts.hours && minutes == maximumParts.minutes
        val secondMax = if (secondConstrained) maximumParts.seconds else 59
        secondValues = rangeDurationWheelValues(
            step = step,
            maxInclusive = secondMax,
            currentValue = seconds.coerceIn(0, 59),
            includeMaximumBoundary = secondConstrained,
        )
        setWheelValue(hourWheel, hourValues, hours)
        setWheelValue(minuteWheel, minuteValues, minutes.coerceIn(0, 59))
        setWheelValue(secondWheel, secondValues, seconds.coerceIn(0, 59))
    }

    private fun setWheelValue(wheel: Wheel, values: IntArray, value: Int) {
        wheel.count = values.size.coerceAtLeast(1)
        val index = values.indexOf(value).takeIf { it >= 0 } ?: 0
        wheel.position = index.toDouble()
    }

    private fun currentDisplayParts(): RangeDurationWheelTimeParts = RangeDurationWheelTimeParts(
        hours = currentHour(),
        minutes = currentMinute(),
        seconds = currentSecond(),
    )

    private fun currentTotalSeconds(): Int = composeRangeDurationWheelSeconds(
        hours = currentHour(),
        minutes = currentMinute(),
        seconds = currentSecond(),
    )

    private fun currentHour(): Int = hourValues[normalizedIndex(hourWheel)]

    private fun currentMinute(): Int = minuteValues[normalizedIndex(minuteWheel)]

    private fun currentSecond(): Int = secondValues[normalizedIndex(secondWheel)]

    private fun normalizedIndex(wheel: Wheel): Int {
        val rounded = round(wheel.position).toLong()
        val count = wheel.count.toLong().coerceAtLeast(1L)
        val modulo = rounded % count
        return (if (modulo < 0L) modulo + count else modulo).toInt()
    }

    private fun drawNumberWheel(
        canvas: Canvas,
        wheel: Wheel,
        centerX: Float,
        kind: NumberKind,
    ) {
        val values = when (kind) {
            NumberKind.HOUR -> hourValues
            NumberKind.MINUTE -> minuteValues
            NumberKind.SECOND -> secondValues
        }
        val base = floor(wheel.position).toLong()
        for (offset in -3..3) {
            val logical = base + offset
            val relative = logical - wheel.position
            if (abs(relative) >= VISIBLE_LIMIT) continue
            val value = values[modulo(logical, values.size)]
            drawCylinderValue(
                canvas = canvas,
                relative = relative.toFloat(),
                centerX = centerX,
                paint = valuePaint,
                overLimit = timeValueIsOverLimit(kind, value),
            ) {
                drawNumber(canvas, value, valuePaint)
            }
        }
    }

    private fun currentErrorMask(): Int {
        val hours = currentHour()
        val minutes = currentMinute()
        val seconds = currentSecond()
        return when {
            hours > maximumParts.hours ->
                RANGE_DURATION_WHEEL_ERROR_HOUR or
                    RANGE_DURATION_WHEEL_ERROR_MINUTE or
                    RANGE_DURATION_WHEEL_ERROR_SECOND
            hours < maximumParts.hours -> 0
            minutes > maximumParts.minutes ->
                RANGE_DURATION_WHEEL_ERROR_MINUTE or RANGE_DURATION_WHEEL_ERROR_SECOND
            minutes < maximumParts.minutes -> 0
            seconds > maximumParts.seconds -> RANGE_DURATION_WHEEL_ERROR_SECOND
            else -> 0
        }
    }

    private fun timeValueIsOverLimit(kind: NumberKind, candidate: Int): Boolean {
        val errorMask = currentErrorMask()
        return when (kind) {
            NumberKind.HOUR ->
                errorMask and RANGE_DURATION_WHEEL_ERROR_HOUR != 0 ||
                    candidate > maximumParts.hours
            NumberKind.MINUTE ->
                errorMask and RANGE_DURATION_WHEEL_ERROR_MINUTE != 0 ||
                    (currentHour() == maximumParts.hours && candidate > maximumParts.minutes)
            NumberKind.SECOND ->
                errorMask and RANGE_DURATION_WHEEL_ERROR_SECOND != 0 ||
                    (currentHour() == maximumParts.hours &&
                        currentMinute() == maximumParts.minutes &&
                        candidate > maximumParts.seconds)
        }
    }

    private fun drawProfileWheel(canvas: Canvas, centerX: Float) {
        val base = floor(profileWheel.position).toLong()
        for (offset in -3..3) {
            val logical = base + offset
            val relative = logical - profileWheel.position
            if (abs(relative) >= VISIBLE_LIMIT) continue
            val value = PROFILE_LABELS[modulo(logical, PROFILE_LABELS.size)]
            drawCylinderValue(canvas, relative.toFloat(), centerX, profilePaint) {
                canvas.drawText(value, 0f, profileBaseline, profilePaint)
            }
        }
    }

    private inline fun drawCylinderValue(
        canvas: Canvas,
        relative: Float,
        centerX: Float,
        paint: Paint,
        overLimit: Boolean = false,
        drawText: () -> Unit,
    ) {
        val angle = relative * ANGLE_STEP_DEGREES
        val radians = angle * PI.toFloat() / 180f
        val sine = sin(radians)
        val cosine = cos(radians)
        if (cosine <= 0f) return
        val y = sine * radiusPx
        val z = cosine * radiusPx - radiusPx
        val projectionScale = perspectivePx / (perspectivePx - z)
        val absolute = abs(relative)
        val edgeFade = ((VISIBLE_LIMIT - absolute) / EDGE_FADE_SPAN).coerceIn(0f, 1f)
        val facing = cosine.coerceAtLeast(MIN_FACE_ALPHA)
        val opacity = min(1f, facing * (0.55f + edgeFade * 0.45f))
        val centerMix = (1f - absolute / 0.85f).coerceIn(0f, 1f)

        paint.color = if (overLimit) errorColor else blendColor(mutedColor, inkColor, centerMix)
        paint.alpha = (opacity * 255f).toInt().coerceIn(0, 255)

        canvas.save()
        canvas.translate(centerX, height * 0.5f + y)
        val scale = projectionScale * (1f - min(absolute, 1.5f) * 0.05f)
        canvas.scale(scale, scale * cosine)
        drawText()
        canvas.restore()
    }

    private fun drawNumber(canvas: Canvas, value: Int, paint: Paint) {
        var cursor = numberBuffer.size
        var remaining = value
        do {
            numberBuffer[--cursor] = ('0'.code + remaining % 10).toChar()
            remaining /= 10
        } while (remaining > 0)
        if (numberBuffer.size - cursor == 1) numberBuffer[--cursor] = '0'
        canvas.drawText(
            numberBuffer,
            cursor,
            numberBuffer.size - cursor,
            0f,
            valueBaseline,
            paint,
        )
    }

    private fun drawSelection(canvas: Canvas, centerX: Float, halfWidth: Float) {
        val top = height * 0.5f - rowPx * 0.5f
        val bottom = height * 0.5f + rowPx * 0.5f
        canvas.drawLine(centerX - halfWidth, top, centerX + halfWidth, top, linePaint)
        canvas.drawLine(centerX - halfWidth, bottom, centerX + halfWidth, bottom, linePaint)
    }

    private fun wheelIndexAt(x: Float): Int {
        if (x >= profileStart) return PROFILE_WHEEL
        if (x < 0f || x > timeRight) return NO_WHEEL
        val hitHalfWidth = timeColumnWidth * 0.40f
        return when {
            abs(x - hourCenter) <= hitHalfWidth -> HOUR_WHEEL
            abs(x - minuteCenter) <= hitHalfWidth -> MINUTE_WHEEL
            abs(x - secondCenter) <= hitHalfWidth -> SECOND_WHEEL
            else -> NO_WHEEL
        }
    }

    private fun updateContentDescription() {
        val parts = splitRangeDurationWheelSeconds(currentTotalSeconds())
        contentDescription = buildString(40) {
            if (accessibilityLabel.isNotEmpty()) {
                append(accessibilityLabel)
                append(", ")
            }
            append(parts.hours)
            append(':')
            if (parts.minutes < 10) append('0')
            append(parts.minutes)
            append(':')
            if (parts.seconds < 10) append('0')
            append(parts.seconds)
            append(' ')
            append(PROFILE_LABELS[profileIndex])
        }
    }

    private fun isGestureActive(): Boolean =
        activeWheelIndex != NO_WHEEL || animationWheelIndex != NO_WHEEL

    private fun modulo(value: Long, count: Int): Int {
        val divisor = count.toLong()
        val result = value % divisor
        return (if (result < 0L) result + divisor else result).toInt()
    }

    private fun blendColor(from: Int, to: Int, amount: Float): Int {
        val t = amount.coerceIn(0f, 1f)
        return Color.argb(
            (Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * t).toInt(),
            (Color.red(from) + (Color.red(to) - Color.red(from)) * t).toInt(),
            (Color.green(from) + (Color.green(to) - Color.green(from)) * t).toInt(),
            (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * t).toInt(),
        )
    }

    private enum class NumberKind { HOUR, MINUTE, SECOND }

    private companion object {
        const val HOUR_WHEEL = 0
        const val MINUTE_WHEEL = 1
        const val SECOND_WHEEL = 2
        const val PROFILE_WHEEL = 3
        const val NO_WHEEL = -1
        const val ANGLE_STEP_DEGREES = 42f
        const val VISIBLE_LIMIT = 2.34f
        const val EDGE_FADE_SPAN = 0.42f
        const val MIN_FACE_ALPHA = 0.18f
        const val SNAP_DURATION_MS = 150f
        val PROFILE_LABELS = arrayOf("1x", "5x", "15x")
    }
}
