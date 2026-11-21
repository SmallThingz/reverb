package app.smallthingz.reverb

import android.content.Context
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.roundToInt
import smallthingz.ui.components.NativeCalibrationRecipe
import smallthingz.ui.components.NativeCurvePlot
import smallthingz.ui.components.NativeFrequencyCurveView

/** Retains one typed point edit until gesture completion. */
internal class SilenceCurveView(context: Context, recipe: NativeCalibrationRecipe = NativeCalibrationRecipe.Default) :
    NativeFrequencyCurveView(context, recipe) {
    private val gesture = SilenceCurveGesture()
    private val points: List<SilencePoint> get() = gesture.points
    override val pointCount: Int get() = points.size
    override val selected: Int get() = gesture.selected
    override fun frequencyHz(index: Int): Int = points[index].hz
    override fun cutoffDb(index: Int): Int = points[index].db
    override fun frequencyFraction(hz: Int): Float = silenceFrequencyFraction(hz.toDouble()).toFloat()

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private var activePointer = -1
    private var ownsTouchStream = false
    var onCommit: ((List<SilencePoint>, Int) -> Unit)? = null
    var onSelect: ((Int) -> Unit)? = null

    init { contentDescription = context.getString(R.string.silence_graph_description) }

    fun bind(config: SilenceGateConfig, selectedPoint: Int, peaks: List<Float>?, maxHz: Int,
             accentColor: Int, inkColor: Int, mutedColor: Int, editable: Boolean) {
        val wasActive = gesture.active
        val changed = gesture.bind(config.points, selectedPoint, editable)
        if (wasActive && !gesture.active) releasePointer()
        bindPlot(peaks, maxHz, accentColor, inkColor, mutedColor, editable, changed)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        cancelGesture()
        super.onSizeChanged(w, h, oldw, oldh)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            cancelGesture(clearStream = true)
            if (!isEnabled || !gesture.begin(event.x, event.y, plotGeometry, pointHitRadiusPx, touchSlop)) return false
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

internal typealias SilenceCurvePlot = NativeCurvePlot

internal fun SilenceCurvePlot.x(hz: Int): Float = x(silenceFrequencyFraction(hz.toDouble()).toFloat())
internal fun SilenceCurvePlot.y(db: Int): Float = y(-db / 96f)

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
