package app.smallthingz.reverb

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Bundle
import android.util.TypedValue
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.View
import kotlin.math.roundToInt

internal data class SilenceCurveTextLabel(val text: String, val bounds: RectF, val baseline: Float, val textSize: Float)
internal data class SilenceCurveLabelGeometry(
    val viewport: RectF,
    val plot: RectF,
    val labels: List<SilenceCurveTextLabel>,
    val tickTextSize: Float,
    val valueTextSize: Float,
)

/** Pointer-rate curve editing stays in the View; Compose receives one committed gesture. */
internal class SilenceCurveView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
    }
    // TypedValue uses Android's SP conversion, including nonlinear font scaling on Android 14+.
    private val tickTextSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11f, resources.displayMetrics)
    private val valueTextSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12f, resources.displayMetrics)
    private val path = Path()
    private val plot = RectF()
    private val gesture = SilenceCurveGesture()
    private val points: List<SilencePoint> get() = gesture.points
    private val selected: Int get() = gesture.selected
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private var geometry = SilenceCurvePlot(0f, 0f, 0f, 0f)
    private var activePointer = -1
    private var ownsTouchStream = false
    var onCommit: ((List<SilencePoint>, Int) -> Unit)? = null
    var onSelect: ((Int) -> Unit)? = null
    private var accent = android.graphics.Color.TRANSPARENT
    private var ink = android.graphics.Color.TRANSPARENT
    private var muted = android.graphics.Color.TRANSPARENT
    private var sampled: List<Float>? = null
    private var maximumHz = 20_000

    private companion object {
        val gridDecibels = intArrayOf(0, -24, -48, -72, -96)
        val gridFrequencies = intArrayOf(20, 100, 1_000, 10_000, 20_000)
    }

    init {
        contentDescription = context.getString(R.string.silence_graph_description)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        isFocusable = true
        isClickable = true
        paint.typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
    }

    fun bind(config: SilenceGateConfig, selectedPoint: Int, peaks: List<Float>?, maxHz: Int,
             accentColor: Int, inkColor: Int, mutedColor: Int, editable: Boolean) {
        val wasActive = gesture.active
        var changed = gesture.bind(config.points, selectedPoint, editable)
        if (wasActive && !gesture.active) releasePointer()
        val nextSamples = peaks?.takeIf { it.size >= 2 }
        if (sampled != nextSamples) {
            // Keep an owned snapshot so a caller's in-place sample update is observable.
            sampled = nextSamples?.toList()
            changed = true
        }
        val nextMaximumHz = maxHz.coerceIn(20, 20_000)
        if (maximumHz != nextMaximumHz || accent != accentColor || ink != inkColor || muted != mutedColor) {
            maximumHz = nextMaximumHz
            accent = accentColor; ink = inkColor; muted = mutedColor
            changed = true
        }
        if (isEnabled != editable) {
            isEnabled = editable
            changed = true
        }
        if (changed) invalidate()
    }

    private fun x(hz: Int) = plot.left + silenceFrequencyFraction(hz.toDouble()).toFloat() * plot.width()
    private fun y(db: Float) = plot.top + (-db / 96f).coerceIn(0f, 1f) * plot.height()
    private fun alpha(color: Int, amount: Int): Int = (color and 0xffffff) or (amount shl 24)

    private fun textWidth(text: String, size: Float): Float {
        labelPaint.textSize = size
        return labelPaint.measureText(text)
    }

    private fun textHeight(size: Float): Float {
        labelPaint.textSize = size
        return labelPaint.fontMetrics.let { it.bottom - it.top }
    }

    private fun headerLines(width: Float): Int =
        if (textWidth("20000 Hz", valueTextSize) + textWidth("-96 dBFS", valueTextSize) + 12 * density <= width) 1 else 2

    private fun headerHeight(width: Float): Float =
        headerLines(width) * textHeight(valueTextSize) + (headerLines(width) - 1) * 4 * density

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val measuredWidth = resolveSize((280 * density).roundToInt(), widthMeasureSpec)
        val tickHeight = textHeight(tickTextSize)
        val plotHeight = maxOf(160 * density, 4 * (tickHeight + 8 * density))
        val preferred = headerHeight(measuredWidth.toFloat()) + 16 * density + tickHeight * 1.5f + plotHeight
        setMeasuredDimension(measuredWidth, resolveSize(maxOf(245 * density, preferred).roundToInt(), heightMeasureSpec))
    }

    private class LabelGeometryCache(
        val width: Int,
        val height: Int,
        val density: Float,
        val tickSize: Float,
        val valueSize: Float,
        val typeface: android.graphics.Typeface?,
        var point: SilencePoint,
        var geometry: SilenceCurveLabelGeometry,
    )
    private var labelCache: LabelGeometryCache? = null

    private fun drawingLabelGeometry(): SilenceCurveLabelGeometry {
        val point = points[selected]
        val cached = labelCache
        if (cached != null && cached.width == width && cached.height == height &&
            cached.density == density && cached.tickSize == tickTextSize && cached.valueSize == valueTextSize &&
            cached.typeface === labelPaint.typeface && cached.geometry.plot == plot) {
            if (cached.point != point) {
                // Only the selected values change while dragging; static ticks keep their exact measured bounds.
                val labels = cached.geometry.labels.toMutableList()
                labels[0] = frequencyLabel(point)
                labels[1] = cutoffLabel(point)
                cached.geometry = cached.geometry.copy(labels = labels)
                cached.point = point
            }
            return cached.geometry
        }
        return createLabelGeometry().also {
            labelCache = LabelGeometryCache(width, height, density, tickTextSize, valueTextSize,
                labelPaint.typeface, point, it)
        }
    }

    private fun label(text: String, left: Float, top: Float, size: Float): SilenceCurveTextLabel {
        labelPaint.textSize = size
        val metrics = labelPaint.fontMetrics
        return SilenceCurveTextLabel(text, RectF(left, top, left + labelPaint.measureText(text),
            top + metrics.bottom - metrics.top), top - metrics.top, size)
    }

    private fun frequencyLabel(point: SilencePoint) = label("${point.hz} Hz", 0f, 0f, valueTextSize)

    private fun cutoffLabel(point: SilencePoint): SilenceCurveTextLabel {
        val cutoff = "${point.db} dBFS"
        val cutoffTop = if (headerLines(width.toFloat()) == 1) 0f else textHeight(valueTextSize) + 4 * density
        return label(cutoff, (width - textWidth(cutoff, valueTextSize)).coerceAtLeast(0f), cutoffTop, valueTextSize)
    }

    /** The renderer and regression fixtures share measured text bounds, not screenshot estimates. */
    private fun createLabelGeometry(): SilenceCurveLabelGeometry {
        val labels = mutableListOf<SilenceCurveTextLabel>()
        val point = points[selected]
        labels += frequencyLabel(point)
        labels += cutoffLabel(point)
        for (db in gridDecibels) {
            val text = db.toString()
            labels += label(text, plot.left - 8 * density - textWidth(text, tickTextSize),
                y(db.toFloat()) - textHeight(tickTextSize) / 2f, tickTextSize)
        }
        val bottomTop = plot.bottom + 8 * density
        val last = label("20k", plot.right - textWidth("20k", tickTextSize), bottomTop, tickTextSize)
        val first = label("20", plot.left, bottomTop, tickTextSize)
        labels += first
        var previousRight = first.bounds.right
        for ((hz, text) in listOf(100 to "100", 1_000 to "1k", 10_000 to "10k")) {
            val candidate = label(text, x(hz) - textWidth(text, tickTextSize) / 2, bottomTop, tickTextSize)
            if (candidate.bounds.left >= previousRight + 6 * density && candidate.bounds.right <= last.bounds.left - 6 * density) {
                labels += candidate
                previousRight = candidate.bounds.right
            }
        }
        labels += last
        return SilenceCurveLabelGeometry(RectF(0f, 0f, width.toFloat(), height.toFloat()),
            RectF(plot), labels, tickTextSize, valueTextSize)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (plot.width() <= 0 || plot.height() <= 0) return
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.LEFT
        val labels = drawingLabelGeometry().labels
        for (index in labels.indices) {
            val label = labels[index]
            paint.textSize = label.textSize
            paint.color = if (index < 2) ink else muted
            canvas.drawText(label.text, label.bounds.left, label.baseline, paint)
        }
        for (db in gridDecibels) {
            paint.color = alpha(muted, 38); paint.strokeWidth = density
            canvas.drawLine(plot.left, y(db.toFloat()), plot.right, y(db.toFloat()), paint)
        }
        for (hz in gridFrequencies) {
            val xp = x(hz)
            paint.color = alpha(muted, 32)
            canvas.drawLine(xp, plot.top, xp, plot.bottom, paint)
        }
        canvas.save()
        canvas.clipRect(plot)
        path.reset()
        path.moveTo(plot.left, plot.bottom)
        sampled?.let { levels ->
            levels.forEachIndexed { i, db -> path.lineTo(plot.left + i.toFloat() / (levels.size - 1) * plot.width(), y(db)) }
            path.lineTo(plot.right, plot.bottom); path.close()
            paint.color = alpha(muted, 66); paint.style = Paint.Style.FILL
            canvas.drawPath(path, paint)
        }
        path.reset()
        points.forEachIndexed { i, p -> if (i == 0) path.moveTo(x(p.hz), y(p.db.toFloat())) else path.lineTo(x(p.hz), y(p.db.toFloat())) }
        path.lineTo(plot.right, plot.bottom); path.lineTo(plot.left, plot.bottom); path.close()
        paint.color = alpha(accent, 24); paint.style = Paint.Style.FILL; canvas.drawPath(path, paint)
        if (maximumHz < 20_000) {
            paint.color = alpha(muted, 35)
            canvas.drawRect(x(maximumHz), plot.top, plot.right, plot.bottom, paint)
            paint.color = alpha(muted, 100); paint.strokeWidth = density
            canvas.drawLine(x(maximumHz), plot.top, x(maximumHz), plot.bottom, paint)
        }
        canvas.restore()
        path.reset()
        points.forEachIndexed { i, p -> if (i == 0) path.moveTo(x(p.hz), y(p.db.toFloat())) else path.lineTo(x(p.hz), y(p.db.toFloat())) }
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 2 * density; paint.color = accent
        paint.strokeJoin = Paint.Join.ROUND; canvas.drawPath(path, paint)
        paint.style = Paint.Style.FILL
        points.forEachIndexed { i, p ->
            val px = x(p.hz); val py = y(p.db.toFloat())
            if (i == selected) { paint.color = alpha(accent, 55); canvas.drawCircle(px, py, 14 * density, paint) }
            paint.color = if (isEnabled) accent else muted
            canvas.drawCircle(px, py, (if (i == selected) 7f else 5f) * density, paint)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cancelGesture()
        val tickHeight = textHeight(tickTextSize)
        plot.set(textWidth("-96", tickTextSize) + 8 * density,
            headerHeight(w.toFloat()) + 8 * density + tickHeight / 2,
            w - 16 * density, h - tickHeight - 8 * density)
        geometry = SilenceCurvePlot(plot.left, plot.top, plot.right, plot.bottom)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            cancelGesture(clearStream = true)
            if (!isEnabled || !gesture.begin(event.x, event.y, geometry, 32 * density, touchSlop)) return false
            activePointer = event.getPointerId(0)
            ownsTouchStream = true
            parent?.requestDisallowInterceptTouchEvent(true)
            invalidate()
            return true
        }
        if (!ownsTouchStream) return false
        if (!isEnabled) cancelGesture()
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (activePointer != -1) {
                    val index = event.findPointerIndex(activePointer)
                    if (index < 0) cancelGesture()
                    else {
                        gesture.move(event.getX(index), event.getY(index))
                        if (!gesture.active) releasePointer()
                        invalidate()
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                val index = event.findPointerIndex(activePointer)
                if (index >= 0 && gesture.active) gesture.move(event.getX(index), event.getY(index))
                else gesture.cancel()
                val edit = gesture.finish()
                releasePointer()
                ownsTouchStream = false
                if (edit != null) { publish(edit); performClick() }
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> cancelGesture(clearStream = true)
            MotionEvent.ACTION_POINTER_DOWN -> cancelGesture()
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) == activePointer) cancelGesture()
            }
        }
        // A cancelled multi-touch/model-change gesture never transfers a partial click to a parent.
        return true
    }

    private fun publish(edit: SilenceCurveEdit) {
        if (edit.changed) onCommit?.invoke(edit.points, edit.selected)
        onSelect?.invoke(edit.selected)
        sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_SELECTED)
    }

    private fun releasePointer() {
        activePointer = -1
        parent?.requestDisallowInterceptTouchEvent(false)
    }

    private fun cancelGesture(clearStream: Boolean = false) {
        gesture.cancel()
        releasePointer()
        if (clearStream) ownsTouchStream = false
        invalidate()
    }

    private fun selectPoint(index: Int): Boolean {
        cancelGesture()
        if (!gesture.selectPoint(index)) return false
        onSelect?.invoke(selected)
        invalidate()
        sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_SELECTED)
        return true
    }

    private fun setCutoff(value: Int): Boolean = publishControlEdit(gesture.setCutoff(value))

    private fun adjustCutoff(delta: Int): Boolean = publishControlEdit(gesture.adjustCutoff(delta))

    private fun adjustFrequency(direction: Int): Boolean = publishControlEdit(gesture.adjustFrequency(direction))

    private fun publishControlEdit(edit: SilenceCurveEdit?): Boolean {
        releasePointer()
        invalidate()
        if (edit == null) return false
        publish(edit)
        return true
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = android.widget.SeekBar::class.java.name
        info.text = context.getString(R.string.silence_curve_accessibility_value,
            selected + 1, points.size, points[selected].hz, points[selected].db)
        info.rangeInfo = AccessibilityNodeInfo.RangeInfo.obtain(
            AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT, -96f, 0f, points[selected].db.toFloat())
        if (!isEnabled) return
        if (selected > 0) info.addAction(AccessibilityNodeInfo.AccessibilityAction(
            R.id.silence_curve_previous_point_action, context.getString(R.string.silence_curve_previous_point)))
        if (selected < points.lastIndex) info.addAction(AccessibilityNodeInfo.AccessibilityAction(
            R.id.silence_curve_next_point_action, context.getString(R.string.silence_curve_next_point)))
        if (gesture.canAdjustFrequency(-1)) info.addAction(AccessibilityNodeInfo.AccessibilityAction(
            R.id.silence_curve_lower_frequency_action, context.getString(R.string.silence_curve_lower_frequency)))
        if (gesture.canAdjustFrequency(1)) info.addAction(AccessibilityNodeInfo.AccessibilityAction(
            R.id.silence_curve_higher_frequency_action, context.getString(R.string.silence_curve_higher_frequency)))
        if (points[selected].db < 0) info.addAction(AccessibilityNodeInfo.AccessibilityAction(
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, context.getString(R.string.silence_curve_raise_cutoff)))
        if (points[selected].db > -96) info.addAction(AccessibilityNodeInfo.AccessibilityAction(
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, context.getString(R.string.silence_curve_lower_cutoff)))
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS)
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        if (isEnabled) {
            when (action) {
                R.id.silence_curve_previous_point_action -> return selectPoint(selected - 1)
                R.id.silence_curve_next_point_action -> return selectPoint(selected + 1)
                R.id.silence_curve_lower_frequency_action -> return adjustFrequency(-1)
                R.id.silence_curve_higher_frequency_action -> return adjustFrequency(1)
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> return adjustCutoff(1)
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> return adjustCutoff(-1)
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id -> {
                    if (arguments?.containsKey(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE) != true) return false
                    val value = arguments.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE)
                    return value.isFinite() && setCutoff(value.roundToInt())
                }
            }
        }
        return super.performAccessibilityAction(action, arguments)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (isEnabled) when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (event.isShiftPressed) adjustFrequency(-1) else selectPoint(selected - 1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (event.isShiftPressed) adjustFrequency(1) else selectPoint(selected + 1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> { adjustCutoff(1); return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { adjustCutoff(-1); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        if (!hasWindowFocus) cancelGesture()
        super.onWindowFocusChanged(hasWindowFocus)
    }

    override fun onDetachedFromWindow() { cancelGesture(clearStream = true); super.onDetachedFromWindow() }
    override fun performClick(): Boolean { super.performClick(); return true }
}

internal data class SilenceCurvePlot(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    fun x(hz: Int): Float = left + silenceFrequencyFraction(hz.toDouble()).toFloat() * width
    fun y(db: Int): Float = top + -db / 96f * height
}

internal data class SilenceCurveEdit(val points: List<SilencePoint>, val selected: Int, val changed: Boolean)

/** A graph gesture owns one existing point. Adding/removing points belongs to explicit controls. */
internal class SilenceCurveGesture {
    var points: List<SilencePoint> = SilenceGateConfig().points
        private set
    var selected: Int = 1.coerceAtMost(points.lastIndex)
        private set
    private var boundPoints = points
    private var boundSelected = selected
    private var editable = true
    private var drag: Drag? = null
    val active: Boolean get() = drag != null

    private class Drag(
        val original: List<SilencePoint>,
        val previousSelection: Int,
        val pointIndex: Int,
        val downX: Float,
        val downY: Float,
        val plot: SilenceCurvePlot,
        val slop: Float,
    ) {
        var moved = false
    }

    fun bind(model: List<SilencePoint>, selectedPoint: Int, enabled: Boolean): Boolean {
        val enabledChanged = editable != enabled
        editable = enabled
        val nextSelection = selectedPoint.coerceIn(model.indices)
        val wasActive = active
        if (wasActive) {
            // Ordinary Compose/palette/sample updates must not overwrite a held drag. A new
            // model or selection, however, revokes it before the old UP can publish stale edits.
            if (enabled && model == boundPoints && nextSelection == boundSelected) return false
            cancel()
        }
        val modelChanged = points != model
        val selectionChanged = selected != nextSelection
        if (modelChanged) points = model.toList()
        selected = nextSelection
        acceptCurrentModel()
        return wasActive || modelChanged || selectionChanged || enabledChanged
    }

    fun begin(x: Float, y: Float, plot: SilenceCurvePlot, hitRadius: Float, touchSlop: Float): Boolean {
        cancel()
        if (!editable || !x.isFinite() || !y.isFinite() || plot.width <= 0f || plot.height <= 0f) return false
        if (x < plot.left - hitRadius || x > plot.right + hitRadius ||
            y < plot.top - hitRadius || y > plot.bottom + hitRadius) return false
        var nearest = -1
        var distance = hitRadius * hitRadius
        for (index in points.indices) {
            val dx = plot.x(points[index].hz) - x
            val dy = plot.y(points[index].db) - y
            val squared = dx * dx + dy * dy
            if (squared < distance || (squared == distance && (nearest == -1 || index == selected))) {
                nearest = index
                distance = squared
            }
        }
        if (nearest == -1) return false
        val previous = selected
        selected = nearest
        drag = Drag(points, previous, nearest, x, y, plot, touchSlop.coerceAtLeast(0f))
        return true
    }

    fun move(x: Float, y: Float) {
        val owner = drag ?: return
        if (!x.isFinite() || !y.isFinite()) { cancel(); return }
        val dx = x - owner.downX
        val dy = y - owner.downY
        if (!owner.moved && dx * dx + dy * dy <= owner.slop * owner.slop) return
        owner.moved = true
        val original = owner.original[owner.pointIndex]
        // Preserve where the finger grabbed the handle instead of snapping its center to DOWN.
        val hz = when (owner.pointIndex) {
            0 -> 20
            owner.original.lastIndex -> 20_000
            else -> silenceFrequencyAt(((owner.plot.x(original.hz) + dx - owner.plot.left) / owner.plot.width).toDouble())
                .coerceIn(owner.original[owner.pointIndex - 1].hz + 1, owner.original[owner.pointIndex + 1].hz - 1)
        }
        val db = (-96f * (owner.plot.y(original.db) + dy - owner.plot.top) / owner.plot.height)
            .roundToInt().coerceIn(-96, 0)
        val replacement = SilencePoint(hz, db)
        if (points[owner.pointIndex] != replacement) {
            points = points.toMutableList().apply { set(owner.pointIndex, replacement) }
        }
    }

    fun finish(): SilenceCurveEdit? {
        val owner = drag ?: return null
        drag = null
        val result = SilenceCurveEdit(points.toList(), selected, points != owner.original)
        acceptCurrentModel()
        return result
    }

    fun cancel() {
        val owner = drag ?: return
        points = owner.original
        selected = owner.previousSelection
        drag = null
    }

    fun selectPoint(index: Int): Boolean {
        cancel()
        val next = index.coerceIn(points.indices)
        if (next == selected) return false
        selected = next
        acceptCurrentModel()
        return true
    }

    fun setCutoff(db: Int): SilenceCurveEdit? {
        takeControl()
        return applyCutoff(db)
    }

    fun adjustCutoff(delta: Int): SilenceCurveEdit? {
        takeControl()
        return applyCutoff(points[selected].db + delta)
    }

    fun canAdjustFrequency(direction: Int): Boolean {
        if (!editable || selected == 0 || selected == points.lastIndex) return false
        return when {
            direction < 0 -> points[selected].hz > points[selected - 1].hz + 1
            direction > 0 -> points[selected].hz < points[selected + 1].hz - 1
            else -> false
        }
    }

    fun adjustFrequency(direction: Int): SilenceCurveEdit? {
        if (!editable || direction == 0) return null
        takeControl()
        val point = points[selected]
        val hz = if (selected == 0 || selected == points.lastIndex) point.hz else {
            // Step along the same logarithmic axis as dragging, without crossing a neighbor.
            silenceFrequencyAt(silenceFrequencyFraction(point.hz.toDouble()) + direction.coerceIn(-1, 1) / 100.0)
                .coerceIn(points[selected - 1].hz + 1, points[selected + 1].hz - 1)
        }
        return applyPoint(point.copy(hz = hz))
    }

    private fun takeControl() {
        // A keyboard/accessibility action addresses the displayed point, but must not commit
        // an unfinished pointer preview or derive its relative adjustment from that preview.
        val target = selected
        cancel()
        selected = target
    }

    private fun applyCutoff(db: Int): SilenceCurveEdit? = applyPoint(points[selected].copy(db = db.coerceIn(-96, 0)))

    private fun applyPoint(value: SilencePoint): SilenceCurveEdit? {
        val changed = points[selected] != value
        val selectionChanged = selected != boundSelected
        if (changed) points = points.toMutableList().apply { set(selected, value) }
        acceptCurrentModel()
        return if (changed || selectionChanged) SilenceCurveEdit(points.toList(), selected, changed) else null
    }

    private fun acceptCurrentModel() {
        boundPoints = points
        boundSelected = selected
    }
}
