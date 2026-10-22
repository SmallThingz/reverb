package app.smallthingz.reverb.nativeui

import android.content.Context
import app.smallthingz.nativeui.runtime.MonotonicFrameClock
import kotlinx.coroutines.CoroutineScope
import android.graphics.Canvas
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import app.smallthingz.nativeui.text.font.FontFamily
import androidx.core.view.ViewCompat
import app.smallthingz.reverb.InlineFineSeekTarget
import app.smallthingz.reverb.NativeDragPointerEvent
import app.smallthingz.reverb.NativePointerPass
import app.smallthingz.reverb.NativeViewPhasePointerNode
import app.smallthingz.reverb.R
import app.smallthingz.reverb.formatPlaybackTime
import app.smallthingz.reverb.formatRangeTimeInput
import app.smallthingz.reverb.inlineTrimBoundaryLimits
import app.smallthingz.reverb.inlineTrimBoundaryProgressMillis
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Immutable visual projection only. Existing playback/trim owner supplies every value. */
internal data class LibraryPlayerUiState(
    val recordingRevisionKey: String,
    val prepared: Boolean,
    val isPlaying: Boolean,
    val durationMillis: Int,
    val positionMillis: Int,
    val trimMode: Boolean,
    val trimSaving: Boolean,
    val trimStartMillis: Int,
    val trimEndMillis: Int,
    val fineSeekTarget: InlineFineSeekTarget,
    val trimError: String?,
    val boundaryInputEnabled: Boolean,
    val backGestureActive: Boolean,
    val backProgress: Float,
    val backDirection: Float,
) {
    val trimBackProgress: Float get() = if (trimMode && !trimSaving && backGestureActive) backProgress.coerceIn(0f, 1f) else 0f
    val collapseProgress: Float get() = if (!trimMode && !trimSaving && backGestureActive) backProgress.coerceIn(0f, 1f) else 0f
    val progressFraction: Float get() = if (durationMillis <= 0) 0f else
        (positionMillis.toFloat() / durationMillis.toFloat()).coerceIn(0f, 1f)
    val trimVisualAlpha: Float get() = (1f - trimBackProgress).coerceIn(0f, 1f)
    val selectionStart: Float get() = if (trimMode && durationMillis > 0)
        (trimStartMillis.toFloat() / durationMillis.toFloat()).coerceIn(0f, 1f) * trimVisualAlpha else 0f
    val selectionEnd: Float get() {
        val rawStart = if (trimMode && durationMillis > 0)
            (trimStartMillis.toFloat() / durationMillis.toFloat()).coerceIn(0f, 1f) else 0f
        val rawEnd = if (trimMode && durationMillis > 0)
            (trimEndMillis.toFloat() / durationMillis.toFloat()).coerceIn(rawStart, 1f) else progressFraction
        return if (trimMode) rawEnd + (progressFraction - rawEnd) * trimBackProgress else progressFraction
    }
}

/** These callbacks are the existing owner's actions; this View never invokes MediaPlayer/storage. */
internal data class LibraryPlayerActions(
    val waveformBegin: (xPx: Float, widthPx: Float) -> Unit,
    val waveformMove: (xPx: Float, widthPx: Float) -> Unit,
    val waveformFinish: (normalRelease: Boolean) -> Unit,
    val selectBoundary: (InlineFineSeekTarget) -> Unit,
    val editBoundary: (InlineFineSeekTarget, requestedMillis: Double) -> Boolean,
    val cancelTrim: () -> Unit,
    val saveTrim: () -> Unit,
)

/**
 * Released RecordingInlinePlayer's visual Column. Fine-seek is the separately owned concrete
 * source port, supplied by the same native window. No playback scope, accepted trim job, back
 * dispatcher, waveform sampler or independent animation clock is created here.
 */
internal class ReverbLibraryPlayerView(
    context: Context,
    environment: ReverbViewEnvironment,
    val fineSeekView: ReverbSpringFineSeekView,
    nativeInputRoot: View,
    frames: MonotonicFrameClock,
    scope: CoroutineScope,
    durationScale: () -> Float,
) : ViewGroup(context) {
    private val content = PlayerColumn(context, environment, fineSeekView, nativeInputRoot, frames, scope, durationScale)
    private var state: LibraryPlayerUiState? = null
    val waveformInput: NativeViewPhasePointerNode get() = content.timeline
    val startLabel: ReverbParagraphView get() = content.times.start
    val endLabel: ReverbParagraphView get() = content.times.end
    val errorLabel: ReverbParagraphView? get() = content.error
    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        clipChildren = true
        clipToPadding = true
        addView(content)
        if (android.os.Build.VERSION.SDK_INT >= 29) isForceDarkAllowed = false
    }

    fun bind(environment: ReverbViewEnvironment, state: LibraryPlayerUiState, actions: LibraryPlayerActions,
             fineSeek: SpringFineSeekBinding) {
        val previous = this.state
        this.state = state
        if (previous?.recordingRevisionKey != state.recordingRevisionKey) content.timeline.setWaveform(null)
        content.bind(environment, state, actions)
        fineSeekView.setPalette(environment.palette)
        fineSeekView.bind(fineSeek)
        applyTransforms()
        if (previous?.collapseProgress != state.collapseProgress) requestLayout()
    }

    /** Publish a snapshot only when sample content changes; never copy arrays on an animation frame. */
    fun setWaveform(data: ProgressiveWaveformData?) = content.timeline.setWaveform(data)
    fun setWaveformFrame(frame: ProgressiveWaveformFrame) = content.timeline.setWaveformFrame(frame)

    /** Row disposal only; accepted operations remain owned by the existing controller/store. */
    fun release() { content.timeline.cancel(); fineSeekView.release() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        content.measure(widthMeasureSpec, heightMeasureSpec)
        val visible = (content.measuredHeight * (1f - (state?.collapseProgress ?: 0f))).roundToInt().coerceAtLeast(0)
        // Source custom layout deliberately reports the collapsing height after measuring full content.
        setMeasuredDimension(content.measuredWidth, visible)
    }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        content.layout(0, 0, content.measuredWidth, content.measuredHeight)
        applyTransforms()
    }
    private fun applyTransforms() {
        val current = state ?: return
        content.pivotX = content.width * .5f
        content.pivotY = 0f
        content.alpha = 1f - current.collapseProgress
        content.scaleY = 1f - .04f * current.collapseProgress
        content.translationX = current.backDirection * content.width * .08f * current.trimBackProgress
    }
}

private class PlayerColumn(context: Context, private var environment: ReverbViewEnvironment,
                           private val fineSeek: ReverbSpringFineSeekView, private val nativeInputRoot: View,
    private val frames: MonotonicFrameClock, private val scope: CoroutineScope,
    private val durationScale: () -> Float) : ViewGroup(context) {
    val timeline = PlayerTimeline(context, environment)
    val times = PlayerTimes(context, environment)
    var error: ReverbParagraphView? = null
        private set
    private var heading: PlayerTrimHeading? = null
    private var buttons: PlayerTrimButtons? = null
    private var state: LibraryPlayerUiState? = null
    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        clipChildren = false
        clipToPadding = false
        addView(timeline); addView(times); addView(fineSeek)
    }
    fun bind(value: ReverbViewEnvironment, next: LibraryPlayerUiState, actions: LibraryPlayerActions) {
        val previous = state
        val environmentChanged = environment !== value
        environment = value
        state = next
        if (next.trimMode) {
            if (heading == null) heading = PlayerTrimHeading(context, value).also { addView(it, 0) }
            if (buttons == null) buttons = PlayerTrimButtons(context, value, nativeInputRoot, frames, scope, durationScale).also(::addView)
            if (environmentChanged || previous == null || !previous.trimMode ||
                previous.trimStartMillis != next.trimStartMillis || previous.trimEndMillis != next.trimEndMillis) {
                heading!!.bind(value, next)
            }
            buttons!!.bind(value, next, actions)
        } else {
            heading?.let(::removeView); heading = null
            buttons?.let(::removeView); buttons = null
        }
        if (next.trimMode && next.trimError != null) {
            if (error == null) error = value.paragraph(context, next.trimError, ReverbTextRole.BODY_SMALL, value.palette.error)
                .also { addView(it, childCount - 1) }
            error!!.setText(next.trimError)
            value.bindParagraphStyle(error!!, ReverbTextRole.BODY_SMALL, value.palette.error)
        } else { error?.let(::removeView); error = null }
        timeline.bind(value, next, actions)
        times.bind(value, next, actions)
        fineSeek.alpha = if (next.trimMode) next.trimVisualAlpha else 1f
        heading?.let {
            it.alpha = next.trimVisualAlpha
            it.translationX = next.backDirection * it.width * .08f * next.trimBackProgress
        }
        if (environmentChanged || previous?.trimMode != next.trimMode) requestLayout()
    }
    private fun dp(value: Float) = (value * environment.textDensity.density).roundToInt()
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val availableWidth = max(0, width - dp(14f) - dp(14f))
        val bounded = MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED
        val maxHeight = MeasureSpec.getSize(heightMeasureSpec)
        var used = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val before = if (child === times || child === buttons) dp(2f) else if (child === error) dp(8f) else 0
            used += before
            val remaining = max(0, maxHeight - used)
            val fixed = when (child) { timeline -> dp(128f); fineSeek -> dp(if (state?.trimMode == true) 104f else 120f); else -> null }
            val hs = if (fixed != null) libraryPlayerExact(if (bounded) min(fixed, remaining) else fixed)
                else if (bounded) libraryPlayerAtMost(remaining) else MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            child.measure(if (child === error) libraryPlayerAtMost(availableWidth) else libraryPlayerExact(availableWidth), hs)
            used += child.measuredHeight
            if (child === heading) used += dp(5f)
        }
        setMeasuredDimension(width, resolveSize(used, heightMeasureSpec))
    }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        var y = 0
        val current = state
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            y += if (child === times || child === buttons) dp(2f) else if (child === error) dp(8f) else 0
            val x = if (layoutDirection == LAYOUT_DIRECTION_RTL) width - dp(14f) - child.measuredWidth else dp(14f)
            child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
            if (child === heading && current != null) {
                child.alpha = current.trimVisualAlpha
                child.translationX = current.backDirection * child.width * .08f * current.trimBackProgress
            }
            y += child.measuredHeight
            if (child === heading) y += dp(5f)
        }
    }
}

/** Source MAIN-pass waveform gesture; no platform slop or competing stock click handler. */
private class PlayerTimeline(context: Context, private var environment: ReverbViewEnvironment) :
    ViewGroup(context), NativeViewPhasePointerNode {
    override val view: View get() = this
    private val waveform = ProgressiveWaveformView(context)
    private var first = ReverbTimelineMarkerView(context, environment)
    private var second: ReverbTimelineMarkerView? = null
    private var state: LibraryPlayerUiState? = null
    private var frame: ProgressiveWaveformFrame? = null
    private val input = InlineWaveformPointerOwner()
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO; clipChildren = false; addView(waveform); addView(first) }
    fun bind(value: ReverbViewEnvironment, next: LibraryPlayerUiState, callbacks: LibraryPlayerActions) {
        input.bind(InlineWaveformPointerBinding(
            InlineWaveformPointerKey(next.recordingRevisionKey, next.durationMillis, next.prepared, next.trimMode),
            next.trimSaving, callbacks.waveformBegin, callbacks.waveformMove, callbacks.waveformFinish))
        val geometryChanged = environment.textDensity.density != value.textDensity.density || state?.trimMode != next.trimMode
        environment = value; state = next
        waveform.setPalette(ProgressiveWaveformPalette(value.palette.primary, value.palette.onSurfaceVariant, value.palette.tertiary))
        if (next.trimMode && second == null) second = ReverbTimelineMarkerView(context, value).also(::addView)
        else if (!next.trimMode) { second?.let(::removeView); second = null }
        val startCursor = next.fineSeekTarget == InlineFineSeekTarget.TRIM_START
        first.bind(value, next.trimMode && startCursor, if (next.trimMode) startCursor else true,
            if (next.trimMode) next.trimVisualAlpha else .74f)
        second?.bind(value, next.fineSeekTarget == InlineFineSeekTarget.TRIM_END,
            next.fineSeekTarget == InlineFineSeekTarget.TRIM_END, next.trimVisualAlpha)
        applyFrame()
        if (geometryChanged) requestLayout()
        if (isLaidOut && !isLayoutRequested) placeMarkers()
    }
    fun setWaveform(data: ProgressiveWaveformData?) = waveform.setWaveform(data)
    fun setWaveformFrame(value: ProgressiveWaveformFrame) { frame = value; applyFrame() }
    private fun applyFrame() {
        val f = frame ?: return
        val s = state ?: return
        waveform.setFrame(f.copy(startFraction = s.selectionStart, endFraction = s.selectionEnd))
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec); val height = MeasureSpec.getSize(heightMeasureSpec)
        waveform.measure(libraryPlayerExact(width), libraryPlayerExact(height))
        val visualWidth = min(width, (12f * environment.textDensity.density).roundToInt())
        first.measure(libraryPlayerExact(visualWidth), libraryPlayerExact(height)); second?.measure(libraryPlayerExact(visualWidth), libraryPlayerExact(height))
        setMeasuredDimension(width, height)
    }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        waveform.layout(0, 0, width, height)
        placeMarkers()
    }
    private fun placeMarkers() {
        val s = state ?: return
        // BoxWithConstraints maxWidth.toPx retains this Float divide/multiply before marker offsets.
        val d = environment.textDensity.density
        val waveformWidth = ((width / d) * d).coerceAtLeast(1f)
        fun place(marker: View, fraction: Float) {
            val offset = (waveformWidth * fraction - 12f * d * .5f).roundToInt()
            val x = if (layoutDirection == LAYOUT_DIRECTION_RTL) width - marker.measuredWidth - offset else offset
            marker.layout(x, 0, x + marker.measuredWidth, height)
        }
        place(first, if (s.trimMode) s.selectionStart else s.progressFraction)
        second?.let { place(it, s.selectionEnd) }
    }
    override fun dispatch(event: NativeDragPointerEvent, pass: NativePointerPass) {
        if (pass == NativePointerPass.MAIN) input.dispatch(event, width)
    }
    override fun cancel() = input.cancel()
    override fun onDetachedFromWindow() { input.detached(); super.onDetachedFromWindow() }
}

private val playerTimeTypography =
    ReverbParagraphStyles[ReverbTextRole.BODY_SMALL].copy(fontFamily = FontFamily.Monospace)

/** Match the formatter's exact units, including its Double rounding before flooring trim tenths. */
internal fun libraryPlayerTimeDisplayUnit(millis: Int, trimMode: Boolean): Long =
    if (trimMode) floor((millis / 1000.0).coerceAtLeast(0.0) * 10.0).toLong()
    else (millis.coerceAtLeast(0) / 1000).toLong()

private class PlayerTimes(context: Context, private var environment: ReverbViewEnvironment) : ViewGroup(context) {
    val start = environment.paragraph(context, "", ReverbTextRole.BODY_SMALL)
    val end = environment.paragraph(context, "", ReverbTextRole.BODY_SMALL)
    private val startBoundary = PlayerBoundary(start, InlineFineSeekTarget.TRIM_START)
    private val endBoundary = PlayerBoundary(end, InlineFineSeekTarget.TRIM_END)
    private var lastTrimMode: Boolean? = null
    private var lastStartUnit = Long.MIN_VALUE
    private var lastEndUnit = Long.MIN_VALUE
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO; addView(start); addView(end) }
    fun bind(value: ReverbViewEnvironment, state: LibraryPlayerUiState, actions: LibraryPlayerActions) {
        val modeChanged = lastTrimMode != state.trimMode
        val typographyChanged = lastTrimMode == null || environment !== value
        environment = value
        fun bindText(label: ReverbParagraphView, first: Boolean) {
            val target = if (first) InlineFineSeekTarget.TRIM_START else InlineFineSeekTarget.TRIM_END
            val millis = if (state.trimMode) {
                if (first) state.trimStartMillis else state.trimEndMillis
            } else if (first) state.positionMillis else state.durationMillis
            val unit = libraryPlayerTimeDisplayUnit(millis, state.trimMode)
            if (modeChanged || unit != (if (first) lastStartUnit else lastEndUnit)) {
                label.setText(if (state.trimMode) formatRangeTimeInput(millis / 1000.0) else formatPlaybackTime(millis))
                if (first) lastStartUnit = unit else lastEndUnit = unit
            }
            if (typographyChanged) {
                label.setTypography(playerTimeTypography, value.textDensity, value.sharedFontResolver)
            }
            label.setTextColor(if (state.trimMode) { if (state.fineSeekTarget == target) value.palette.primary else value.palette.ink }
                else if (first) value.palette.ink else value.palette.muted)
        }
        bindText(start, true); bindText(end, false)
        lastTrimMode = state.trimMode
        startBoundary.bind(state, actions); endBoundary.bind(state, actions)
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        start.measure(libraryPlayerAtMost(width), heightMeasureSpec.toAtMost())
        end.measure(libraryPlayerAtMost(max(0, width - start.measuredWidth)), heightMeasureSpec.toAtMost())
        setMeasuredDimension(width, resolveSize(max(start.measuredHeight, end.measuredHeight), heightMeasureSpec))
    }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        fun place(child: View, logical: Int) {
            val x = if (layoutDirection == LAYOUT_DIRECTION_RTL) width - logical - child.measuredWidth else logical
            val y = ((height - child.measuredHeight) / 2f).roundToInt()
            child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
        }
        place(start, 0); place(end, width - end.measuredWidth)
    }
}

/** Adds the source boundary semantics/actions to the original Text leaf, never a new text label. */
private class PlayerBoundary(private val label: ReverbParagraphView, private val target: InlineFineSeekTarget) {
    private var state: LibraryPlayerUiState? = null
    private var actions: LibraryPlayerActions? = null
    init {
        label.setOnFocusChangeListener { _, focused -> if (focused && state?.boundaryInputEnabled == true) actions?.selectBoundary?.invoke(target) }
        label.setOnKeyListener { _, code, event ->
            val current = state
            val limits = current?.let { inlineTrimBoundaryLimits(it.durationMillis, target) }
            if (current == null || !current.trimMode || limits == null || event.action != KeyEvent.ACTION_DOWN) false
            else {
                val selected = if (target == InlineFineSeekTarget.TRIM_START) current.trimStartMillis else current.trimEndMillis
                val requested = when (code) {
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_DOWN -> selected.toDouble() - 1000.0
                    KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP -> selected.toDouble() + 1000.0
                    KeyEvent.KEYCODE_MOVE_HOME -> limits.first.toDouble()
                    KeyEvent.KEYCODE_MOVE_END -> limits.last.toDouble()
                    else -> null
                }
                requested?.let { actions?.editBoundary?.invoke(target, it) } ?: false
            }
        }
        label.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                val current = state ?: return
                val limits = inlineTrimBoundaryLimits(current.durationMillis, target)
                if (!current.trimMode || limits == null) return
                val selected = if (target == InlineFineSeekTarget.TRIM_START) current.trimStartMillis else current.trimEndMillis
                info.className = "android.widget.SeekBar"
                info.contentDescription = host.context.getString(if (target == InlineFineSeekTarget.TRIM_START)
                    R.string.trim_start_accessibility else R.string.trim_end_accessibility)
                androidx.core.view.accessibility.AccessibilityNodeInfoCompat.wrap(info).stateDescription = formatRangeTimeInput(selected / 1000.0)
                info.isEnabled = current.boundaryInputEnabled
                info.rangeInfo = AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT,
                    limits.first.toFloat() / 1000f, limits.last.toFloat() / 1000f, selected.coerceIn(limits).toFloat() / 1000f)
                if (current.boundaryInputEnabled) {
                    info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS)
                    if (selected < limits.last) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
                    if (selected > limits.first) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
                }
            }
            override fun performAccessibilityAction(host: View, action: Int, arguments: Bundle?): Boolean {
                val current = state
                if (current != null && current.trimMode && current.boundaryInputEnabled) {
                    val limits = inlineTrimBoundaryLimits(current.durationMillis, target)
                    val seconds = if (action == AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id &&
                        arguments?.containsKey(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE) == true)
                        arguments.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE) else null
                    if (seconds != null) return inlineTrimBoundaryProgressMillis(seconds, current.durationMillis, target)
                        ?.let { actions?.editBoundary?.invoke(target, it) } ?: false
                    if (limits != null && (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD || action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) {
                        val selected = (if (target == InlineFineSeekTarget.TRIM_START) current.trimStartMillis else current.trimEndMillis).coerceIn(limits).toFloat() / 1000f
                        val step = (limits.last.toFloat() / 1000f - limits.first.toFloat() / 1000f) / 20
                        val requested = selected + if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) step else -step
                        return inlineTrimBoundaryProgressMillis(requested, current.durationMillis, target)
                            ?.let { actions?.editBoundary?.invoke(target, it) } ?: false
                    }
                }
                return super.performAccessibilityAction(host, action, arguments)
            }
        }
    }
    fun bind(value: LibraryPlayerUiState, callbacks: LibraryPlayerActions) {
        state = value; actions = callbacks
        label.isFocusable = value.trimMode && value.boundaryInputEnabled && inlineTrimBoundaryLimits(value.durationMillis, target) != null
    }
}

private fun libraryPlayerExact(value: Int) = View.MeasureSpec.makeMeasureSpec(value.coerceAtLeast(0), View.MeasureSpec.EXACTLY)
private fun libraryPlayerAtMost(value: Int) = View.MeasureSpec.makeMeasureSpec(value.coerceAtLeast(0), View.MeasureSpec.AT_MOST)
private fun Int.toAtMost(): Int = if (View.MeasureSpec.getMode(this) == View.MeasureSpec.UNSPECIFIED) this else libraryPlayerAtMost(View.MeasureSpec.getSize(this))
