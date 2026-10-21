package app.smallthingz.reverb.nativeui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import androidx.compose.runtime.MonotonicFrameClock
import androidx.lifecycle.Lifecycle
import app.smallthingz.reverb.NativeAndroidInteropHost
import app.smallthingz.reverb.NativeAndroidInteropRequests
import app.smallthingz.reverb.NativeRangeEndpointActions
import app.smallthingz.reverb.NativeRangeExportPresentation
import app.smallthingz.reverb.NativeRangeSelectionDurationWheel
import app.smallthingz.reverb.RangeEditTarget
import app.smallthingz.reverb.RangeExportEditorState
import app.smallthingz.reverb.ReverbService
import app.smallthingz.reverb.rangeExportSelectionWithinLimit
import kotlinx.coroutines.CoroutineScope
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Released RangeExportHomeContent's exact content viewport (parent already supplies18dp sides).
 * The retained owner supplies the shared model, opening/reveal frame and source geometry. This
 * View owns layout, widget bindings and conditional wheel lifetime only; no capture/export IO.
 *
 * NativeRangePointerBinding adopts the leaf phase nodes and native wheel's interop channel into
 * the window's existing root. Do not also dispatch a stock Android recognizer for that stream.
 */
internal class ReverbRangeExportContent(
    context: Context,
    initialEnvironment: ReverbViewEnvironment,
    val state: RangeExportEditorState,
    private val lifecycle: Lifecycle,
    private val scope: CoroutineScope,
    private val sourceWindow: View,
    inputRoot: () -> View?,
    onWaveformPositioned: (View) -> Unit,
) : ViewGroup(context), NativeAndroidInteropHost, AutoCloseable {
    private var environment = initialEnvironment
    private var presentation: NativeRangeExportPresentation? = null
    private var selectedBuffer = ReverbService.BufferSlot.ONE_SHOT
    private var onCancel: () -> Unit = {}
    private var onExport: () -> Unit = {}
    private var compact = true
    private var wheel: NativeRangeSelectionDurationWheel? = null
    val durationWheel: View? get() = wheel?.view
    override val nativeInteropRequests = NativeAndroidInteropRequests()
    val timeline = ReverbRangeTimelineView(context, initialEnvironment, onWaveformPositioned)
    val fine = ReverbSpringFineSeekView(context, SpringFineSeekEnvironment(
        initialEnvironment.palette, initialEnvironment.textDensity,
        ViewConfiguration.get(context).scaledTouchSlop.toFloat(),
        requireNotNull(scope.coroutineContext[MonotonicFrameClock]), scope, sourceWindow, { false },
    ))
    val controls = ReverbRangeExportControls(context, initialEnvironment, inputRoot, scope)
    private val initialCircle = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG or Paint.FILTER_BITMAP_FLAG)
    private data class FineKey(val enabled: Boolean, val playing: Boolean, val duration: Float, val target: RangeEditTarget)
    private data class WheelKey(val environment: ReverbViewEnvironment, val maximum: Double,
        val reachableMaximum: Double, val duration: Double, val enabled: Boolean, val visible: Boolean)
    private var fineKey: FineKey? = null
    private var wheelKey: WheelKey? = null
    private var bodyHeight = 0
    private var contentHeight = 0
    private var wheelGap = 0
    private var fineGap = 0
    private var closed = false
    private var endpointActions: List<NativeRangeEndpointActions> = emptyList()
    var onLeafStructureChanged: () -> Unit = {}
    var onGeometryChanged: () -> Unit = {}
    var onInputBindingChanged: () -> Unit = {}

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        clipChildren = false; clipToPadding = false
        if (android.os.Build.VERSION.SDK_INT >= 29) isForceDarkAllowed = false
        addView(timeline); addView(fine); addView(controls)
        initialCircle.color = initialEnvironment.palette.primary
    }

    var interactionEnabled: Boolean = false
        private set
    private val currentFineEnabled get() = fineKey?.enabled == true

    fun bind(
        environment: ReverbViewEnvironment,
        frame: NativeRangeExportPresentation,
        selectedBuffer: ReverbService.BufferSlot,
        onCancel: () -> Unit,
        onExport: () -> Unit,
    ) {
        if (closed) return
        val changedEnvironment = this.environment !== environment
        this.environment = environment
        this.presentation = frame; this.selectedBuffer = selectedBuffer
        interactionEnabled = frame.interactionReady && frame.backProgress <= 0f && !state.selectionDurationEditing
        this.onCancel = onCancel; this.onExport = onExport
        if (changedEnvironment) {
            timeline.setEnvironment(environment)
            fine.setPalette(environment.palette)
            initialCircle.color = environment.palette.primary
            requestLayout()
        }
        timeline.bind(state, frame)
        fine.alpha = frame.chromeAlpha
        controls.alpha = if (!frame.snapshotPresent) 1f else frame.chromeAlpha
        controls.bind(environment, state, !frame.snapshotPresent,
            rangeExportSelectionWithinLimit(state.selectionDurationExactSeconds, frame.maximumDurationSeconds),
            selectedBuffer, { this.onCancel() }, { this.onExport() })
        val nextFine = FineKey(interactionEnabled && state.snapshotReady,
            state.isPlaying, state.durationSeconds, state.lastTarget)
        if (fineKey != nextFine) {
            fineKey = nextFine
            fine.bind(SpringFineSeekBinding(
                enabled = nextFine.enabled,
                isPlaying = nextFine.playing,
                durationSeconds = nextFine.duration,
                interactionKey = nextFine.target,
                onTogglePlayback = {
                    if (currentFineEnabled && state.durationActionAvailable) state.togglePreview()
                },
                onInteractionStart = {},
                onBeginFineAdjust = state::beginFineAdjust,
                onFineAdjust = { delta, _ -> state.fineAdjust(delta) },
                onUpdateFineAdjustShuttle = state::updateFineAdjustShuttle,
                onEndFineAdjust = state::endFineAdjust,
                onCancelFineAdjust = state::pausePreview,
                createInputGesture = { onGestureCancel ->
                    state.inputGesture(
                        interactionAllowed = { currentFineEnabled },
                        onCancel = onGestureCancel,
                    )
                },
            ))
        }
        bindWheel()
        onInputBindingChanged()
        endpointActions.forEach { it.refresh() }
        invalidate()
    }

    /** Bind to the same window cursor and source-presence/float geometry owner as other labels. */
    fun bindEndpointAccessibility(window: PlainTextAccessibilityWindowScope,
        sourceNode: (View) -> NativeSourceAccessibilityNode): AutoCloseable {
        endpointActions.forEach { it.close() }
        val next = listOf(
            NativeRangeEndpointActions(timeline.endpointStart, sourceWindow, state, RangeEditTarget.START,
                lifecycle, { interactionEnabled }, window, { sourceNode(timeline.endpointStart) }),
            NativeRangeEndpointActions(timeline.endpointEnd, sourceWindow, state, RangeEditTarget.END,
                lifecycle, { interactionEnabled }, window, { sourceNode(timeline.endpointEnd) }),
        )
        endpointActions = next
        return AutoCloseable {
            next.forEach { it.close() }
            if (endpointActions === next) endpointActions = emptyList()
        }
    }

    private fun bindWheel() {
        val active = wheel ?: return
        val frame = presentation ?: return
        active.view.alpha = frame.chromeAlpha
        val key = WheelKey(environment, frame.maximumDurationSeconds,
            state.selectionDurationWheelLimitExactSeconds(frame.maximumDurationSeconds), state.selectionDurationExactSeconds,
            frame.interactionReady && frame.backProgress <= 0f, frame.visualizerVisible)
        if (wheelKey != key) {
            wheelKey = key
            active.bind(key.maximum, key.enabled, key.visible, environment)
        }
    }

    private fun updateCompact(value: Boolean) {
        if (compact == value && (value || wheel != null)) return
        compact = value
        if (value) {
            wheel?.let { previous ->
                removeView(previous.view)
                previous.close()
            }
            wheel = null
        } else {
            wheel = NativeRangeSelectionDurationWheel(context, state, lifecycle).also { addView(it.view, 0) }
        }
        wheelKey = null
        bindWheel()
        onLeafStructureChanged()
    }

    override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        // Source AndroidViewHolder forwards this request into its own PointerInteropFilter.
        nativeInteropRequests.request(disallowIntercept)
        super.requestDisallowInterceptTouchEvent(disallowIntercept)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec); val h = MeasureSpec.getSize(heightMeasureSpec)
        val controlsHeight = min(dp(70), h)
        val bottomSpace = min(dp(18), (h - controlsHeight).coerceAtLeast(0))
        bodyHeight = (h - controlsHeight - bottomSpace).coerceAtLeast(0)
        updateCompact(bodyHeight / environment.textDensity.density < 390f)
        controls.measure(exact(w), exact(controlsHeight))

        // Column's unweighted children consume available height in source order. A short window
        // constrains later children, rather than allowing an oversized stack to drift above center.
        var remaining = bodyHeight
        wheelGap = 0
        if (!compact) {
            val wheelHeight = min(dp(160), remaining)
            wheel?.view?.measure(exact((w - dp(24) - dp(24)).coerceAtLeast(0)), exact(wheelHeight))
            remaining -= wheelHeight
            wheelGap = min(dp(4), remaining)
            remaining -= wheelGap
        }
        val timelineHeight = min(dp(if (compact) 232 else 250), remaining)
        timeline.measure(exact(w), exact(timelineHeight))
        remaining -= timelineHeight
        fineGap = min(dp(if (compact) 2 else 8), remaining)
        remaining -= fineGap
        val fineHeight = min(dp(if (compact) 120 else 132), remaining)
        fine.measure(exact(w), exact(fineHeight))
        remaining -= fineHeight
        contentHeight = bodyHeight - remaining
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        var y = ((bodyHeight - contentHeight) * .5f).roundToInt()
        wheel?.view?.let {
            it.layout(dp(24), y, dp(24) + it.measuredWidth, y + it.measuredHeight)
            y += it.measuredHeight + wheelGap
        }
        timeline.layout(0, y, width, y + timeline.measuredHeight)
        y += timeline.measuredHeight + fineGap
        fine.layout(0, y, width, y + fine.measuredHeight)
        controls.layout(0, bodyHeight, width, bodyHeight + controls.measuredHeight)
        onGeometryChanged()
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val frame = presentation ?: return
        val source = frame.sourceGeometry ?: return
        if (!frame.transitionStarted) {
            canvas.drawCircle(source.centerXInLocalPx, source.centerYInLocalPx,
                source.bodyDiameterPx * .5f, initialCircle)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        onLeafStructureChanged = {}; onGeometryChanged = {}; onInputBindingChanged = {}
        val actions = endpointActions; endpointActions = emptyList()
        val previousWheel = wheel; wheel = null; wheelKey = null
        var failure: Throwable? = null
        fun release(block: () -> Unit) {
            try { block() } catch (error: Throwable) {
                if (failure == null) failure = error else if (failure !== error) failure!!.addSuppressed(error)
            }
        }
        actions.forEach { release(it::close) }
        release(controls::cancelInput)
        release(fine::release)
        if (previousWheel != null) release {
            try { removeView(previousWheel.view) } finally { previousWheel.close() }
        }
        failure?.let { throw it }
    }

    private fun dp(value: Int) = (value * environment.textDensity.density).roundToInt()
    private fun exact(value: Int) = MeasureSpec.makeMeasureSpec(value, MeasureSpec.EXACTLY)
}
