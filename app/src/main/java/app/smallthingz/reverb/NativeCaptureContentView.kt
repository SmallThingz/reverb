package app.smallthingz.reverb

import android.content.Context
import android.graphics.RectF
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import androidx.activity.OnBackPressedDispatcher
import app.smallthingz.nativeui.runtime.MonotonicFrameClock
import app.smallthingz.nativeui.geometry.Offset
import app.smallthingz.nativeui.geometry.Rect
import androidx.lifecycle.LifecycleOwner
import app.smallthingz.reverb.ReverbService.BufferSlot
import app.smallthingz.reverb.nativeui.*
import kotlinx.coroutines.CoroutineScope
import kotlin.math.roundToInt

/**
 * CaptureScreen's released Box: exactly one normal/range body plus the retained save-status overlay.
 * Snapshot/service/durable mutation ownership remains exclusively NativeCaptureUiOwner.
 */
internal class NativeCaptureContentView(
    context: Context,
    private var environment: ReverbViewEnvironment,
    private val owner: NativeCaptureUiOwner,
    private val lifecycleOwner: LifecycleOwner,
    dispatcher: OnBackPressedDispatcher,
    private val scope: CoroutineScope,
    private val windowRoot: View,
    private val inputRoot: View,
    private val pointer: NativeWindowPointerCoordinates,
    private val textWindow: NativeWindowTextAccessibilityOwner,
    private val textGeometry: NativePlainTextViewGeometry,
    private val sourceNode: (View) -> NativeSourceAccessibilityNode,
    private val materialButtons: () -> NativeMaterialButtonHost,
    private val structureChanged: () -> Unit,
) : ViewGroup(context), AutoCloseable {
    private var current = owner.state.value
    private var closed = false
    private var normal: ReverbHomeBodyOwner? = null
    private var rangeOwner: NativeRangeExportOwner? = null
    private var rangeView: ReverbRangeExportContent? = null
    private var rangePointer: NativeRangePointerBinding? = null
    private var rangeText: AutoCloseable? = null
    private var bodyText: NativePageTextAccessibilityOwner? = null
    private var renderedRangeConfig: ExportUiConfig? = null
    private var lastHomeRoot: Rect? = null
    private var lastHomeBlob: Rect? = null
    private var sourceKeySlot: BufferSlot? = null
    private var sourceKeySnapshot: ReverbService.TimelineSnapshot? = null
    private var sourceGeometry: RangeMorphSourceGeometry? = null
    private var backProgress = 0f
    private val rangeBack = NativeLibraryPredictiveBack(dispatcher, lifecycleOwner, scope,
        requireNotNull(scope.coroutineContext[MonotonicFrameClock])) { active, progress, _ ->
        backProgress = if (active) progress else 0f
        if (!closed) renderRange()
    }
    private val transient = NativeCaptureTransientHost(this, environment, owner, materialButtons(), scope)

    init {
        clipChildren = false; clipToPadding = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(transient.statusCard)
        bind(current, environment)
    }

    /** Only these actual child layouts override Main's ordinary direct Home traversal. */
    fun pointerLayout(view: View): NativeViewPointerHitLayout? = when {
        view === rangeView -> rangePointer?.layout
        view === transient.statusCard -> transient.statusHitLayout
        else -> null
    }

    fun bind(state: NativeCaptureUiState, nextEnvironment: ReverbViewEnvironment) {
        if (closed) return
        val changedEnvironment = environment !== nextEnvironment
        current = state
        environment = nextEnvironment
        rangeBack.bind(state.rangeSnapshotBuffer != null && state.body.visualizerVisible, owner::dismissRangeExport)
        val slot = state.rangeSnapshotBuffer
        if (slot == null) {
            if (rangeView != null) disposeRange()
            sourceKeySlot = null; sourceKeySnapshot = null; sourceGeometry = null
            val body = normal ?: ReverbHomeBodyOwner(context, environment, state.body,
                state.selectedBuffer, owner.actions, scope, { inputRoot }, ::homeBoundsChanged).also {
                normal = it
                addView(it.view, 0)
                bindBodyText(it.view)
                structureChanged()
            }
            if (changedEnvironment) body.setEnvironment(environment)
            body.bind(state.body, state.selectedBuffer, owner.actions)
        } else {
            if (sourceKeySlot != slot || sourceKeySnapshot !== state.rangeSnapshot) {
                sourceKeySlot = slot; sourceKeySnapshot = state.rangeSnapshot
                val controller = if (slot == BufferSlot.ONE_SHOT) state.body.oneShotController else state.body.loopingController
                sourceGeometry = rangeMorphSourceGeometry(lastHomeRoot, lastHomeBlob,
                    state.body.listening && state.body.activeBuffer == slot,
                    if (slot == BufferSlot.ONE_SHOT) state.body.oneShotEnabled else state.body.loopingEnabled,
                    owner.sampledBlobActivity(slot), controller.currentBaseRadiusFraction())
            }
            normal?.let { bodyText?.close(); bodyText = null; removeView(it.view); it.close(); normal = null }
            if (rangeOwner?.selectedBuffer != slot) {
                disposeRange()
                val metrics = if (slot == BufferSlot.ONE_SHOT) state.body.oneShotMetrics else state.body.loopingMetrics
                val created = NativeRangeExportOwner(context, lifecycleOwner, slot,
                    metrics.seconds.coerceAtLeast(.05f), scope, state.body.visualizerVisible, ::rangeRendered)
                rangeOwner = created
                val content = ReverbRangeExportContent(context, environment, created.state,
                    lifecycleOwner.lifecycle, scope, windowRoot, { inputRoot }) { waveform ->
                    val mapped = textGeometry.capture(waveform, Offset.Zero,
                        Rect(0f, 0f, waveform.width.toFloat(), waveform.height.toFloat()))
                    mapped?.let { created.waveformMeasured(it.visibleBoundsInRoot) }
                }
                rangeView = content
                rangePointer = NativeRangePointerBinding(content, pointer, { environment.textDensity.density },
                    { ViewConfiguration.get(context).scaledTouchSlop.toFloat() })
                addView(content, 0)
                bindBodyText(content)
                val pointerMembershipChanged = content.onLeafStructureChanged
                content.onLeafStructureChanged = {
                    pointerMembershipChanged()
                    bodyText?.refresh()
                    structureChanged()
                }
                rangeText = content.bindEndpointAccessibility(textWindow.textWindow, sourceNode)
                structureChanged()
            }
            // Capture this rendered config once. The accepted submit closure uses this exact value,
            // just as CaptureScreen's submitRangeExport captures its current composition's config.
            renderedRangeConfig = owner.rangeExportConfig()
            renderRange()
        }
        transient.bind(state, environment)
        requestLayout()
    }

    private fun bindBodyText(body: ViewGroup) {
        bodyText?.close()
        bodyText = textWindow.bindContent(body, NativeTextSemanticsClipPolicy { view ->
            if ((view.parent is ReverbGroupLayout && (view.parent as ReverbGroupLayout).pointerContent === view) ||
                view is ReverbHomeControlSurface)
                NativeTextSemanticsClip.Rectangle(Rect(0f, 0f, view.width.toFloat(), view.height.toFloat()))
            else NativeTextSemanticsClip.Unclipped
        }, sourceNode) { sourceNode(it).present }
    }

    private fun homeBoundsChanged(body: View, bodyLocal: RectF, blob: View) {
        lastHomeRoot = textGeometry.capture(body, Offset.Zero,
            Rect(bodyLocal.left, bodyLocal.top, bodyLocal.right, bodyLocal.bottom))?.visibleBoundsInRoot
        lastHomeBlob = textGeometry.capture(blob, Offset.Zero,
            Rect(0f, 0f, blob.width.toFloat(), blob.height.toFloat()))?.visibleBoundsInRoot
    }

    private fun renderRange() {
        val range = rangeOwner ?: return
        val config = renderedRangeConfig ?: return
        range.bind(current.rangeSnapshot, sourceGeometry, owner.rangeExportLimitSeconds(config),
            backProgress, current.body.visualizerVisible, ::rangeRendered)
    }

    private fun rangeRendered(state: RangeExportEditorState, frame: NativeRangeExportPresentation) {
        if (closed) return
        val slot = rangeOwner?.selectedBuffer ?: return
        val config = renderedRangeConfig ?: return
        rangeView?.bind(environment, frame, slot, owner::dismissRangeExport) {
            owner.submitRangeExport(state.startSeconds, state.endSeconds, config)
        }
        // Existing text objects read current paragraphs/geometry lazily. Refresh membership only
        // when the actual optional wheel/controls change, not on every morph/playback frame.
    }

    private fun disposeRange() {
        val pointer = rangePointer; rangePointer = null
        val endpoints = rangeText; rangeText = null
        val text = bodyText; bodyText = null
        val content = rangeView; rangeView = null
        val owner = rangeOwner; rangeOwner = null
        renderedRangeConfig = null
        var failure: Throwable? = null
        fun release(block: () -> Unit) { try { block() } catch (error: Throwable) {
            if (failure == null) failure = error else if (failure !== error) failure!!.addSuppressed(error)
        } }
        release { pointer?.close() }; release { endpoints?.close() }; release { text?.close() }
        release { content?.let { removeView(it); it.close() } }; release { owner?.close() }
        structureChanged()
        failure?.let { throw it }
    }

    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w); val height = MeasureSpec.getSize(h)
        val side = dp(18)
        normal?.view?.measure(exact(width), exact(height))
        rangeView?.measure(exact((width - side * 2).coerceAtLeast(0)), exact(height))
        transient.statusCard.measure(atMost((width - side * 2).coerceAtLeast(0)),
            atMost((height - dp(142)).coerceAtLeast(0)))
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        normal?.view?.layout(0, 0, width, height)
        rangeView?.let { it.layout(dp(18), 0, dp(18) + it.measuredWidth, height) }
        val card = transient.statusCard
        val x = ((width - card.measuredWidth) * .5f).roundToInt()
        val bottom = (height - dp(142)).coerceAtLeast(0)
        card.layout(x, bottom - card.measuredHeight, x + card.measuredWidth, bottom)
    }

    override fun close() {
        if (closed) return
        closed = true
        var failure: Throwable? = null
        fun release(block: () -> Unit) { try { block() } catch (error: Throwable) {
            if (failure == null) failure = error else if (failure !== error) failure!!.addSuppressed(error)
        } }
        release(rangeBack::close)
        release(::disposeRange)
        release { normal?.let { removeView(it.view); it.close() }; normal = null }
        release { bodyText?.close() }; bodyText = null
        release(transient::close)
        failure?.let { throw it }
    }

    private fun dp(value: Int) = (value * environment.textDensity.density).roundToInt()
    private fun exact(value: Int) = MeasureSpec.makeMeasureSpec(value, MeasureSpec.EXACTLY)
    private fun atMost(value: Int) = MeasureSpec.makeMeasureSpec(value, MeasureSpec.AT_MOST)
}
