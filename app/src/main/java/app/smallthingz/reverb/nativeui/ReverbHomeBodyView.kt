package app.smallthingz.reverb.nativeui

import android.content.Context
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import app.smallthingz.reverb.AudioBlobController
import app.smallthingz.reverb.BufferMetrics
import app.smallthingz.reverb.CaptureBufferUiState
import app.smallthingz.reverb.R
import app.smallthingz.reverb.RetentionMode
import app.smallthingz.reverb.ReverbService.BufferSlot
import app.smallthingz.reverb.captureBufferReadout
import app.smallthingz.reverb.captureBufferUiState
import app.smallthingz.reverb.captureServiceInteractionReady
import app.smallthingz.reverb.isCaptureBlockedByOtherBuffer
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope

internal data class ReverbHomeBodyState(
    val activeBuffer: BufferSlot?,
    val oneShotMetrics: BufferMetrics,
    val loopingMetrics: BufferMetrics,
    val oneShotEnabled: Boolean,
    val oneShotFull: Boolean,
    val loopingEnabled: Boolean,
    val retentionMode: RetentionMode?,
    val listening: Boolean,
    val saving: Boolean,
    val serviceConnected: Boolean,
    val serviceStateHydrated: Boolean,
    val preparingRange: Boolean,
    val oneShotController: AudioBlobController,
    val loopingController: AudioBlobController,
    val visualizerVisible: Boolean,
)

/**
 * Normal MainCaptureContent branch only. The shell owns the source buffer transition state,
 * source-equivalent horizontal pointer detector, range branch, window/root and save-status overlay.
 *
 * No recorder methods, coroutine workers, data operations, permission APIs or Settings writes.
 * Native input root and blob click environment are mandatory: there is no silent no-swipe default.
 */
internal class ReverbHomeBodyView(
    context: Context,
    initialEnvironment: ReverbViewEnvironment,
    nativeInputRoot: () -> View?,
    blobInputEnvironment: NativeClickEnvironment,
    private val animationScope: CoroutineScope,
    swipeSurfaceFactory: (ReverbHomeBlobControl) -> ViewGroup,
    private val onBoundsChanged: (body: View, bodyLocalBounds: RectF, blob: View) -> Unit,
) : ViewGroup(context), AutoCloseable {
    private var environment = initialEnvironment
    private var state: ReverbHomeBodyState? = null
    private var renderedBuffer = BufferSlot.ONE_SHOT
    private var flip = ReverbHomeFlipFrame()
    private var actions: ReverbHomeActions? = null
    private val blob = ReverbHomeBlobControl(context, initialEnvironment, blobInputEnvironment, animationScope) {}
    private var progress: ReverbIncidentLoadingView? = null
    private val controls = ReverbHomeControls(context, initialEnvironment, nativeInputRoot, animationScope)
    /** Attach the source-equivalent horizontal detector to this exact weighted Box only. */
    val swipeSurface: ViewGroup = swipeSurfaceFactory(blob)
    val blobInputView: View get() = blob
    private val bodyBounds = RectF()
    private var geometry = reverbHomeGeometry(0, 0, initialEnvironment.textDensity.density)

    init {
        clipChildren = false
        clipToPadding = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        if (android.os.Build.VERSION.SDK_INT >= 29) isForceDarkAllowed = false
        swipeSurface.addView(blob)
        addView(swipeSurface)
        addView(controls)
    }

    fun bind(value: ReverbHomeBodyState, renderedBuffer: BufferSlot,
        frame: ReverbHomeFlipFrame, actions: ReverbHomeActions) {
        state = value
        this.renderedBuffer = renderedBuffer
        flip = frame
        this.actions = actions
        val metrics = if (renderedBuffer == BufferSlot.ONE_SHOT) value.oneShotMetrics else value.loopingMetrics
        val enabled = if (renderedBuffer == BufferSlot.ONE_SHOT) value.oneShotEnabled else value.loopingEnabled
        val ui = captureBufferUiState(renderedBuffer, enabled, value.oneShotFull, value.listening, value.activeBuffer)
        val recording = ui == CaptureBufferUiState.RECORDING
        val disabled = ui == CaptureBufferUiState.DISABLED
        val full = ui == CaptureBufferUiState.FILLED
        val serviceReady = captureServiceInteractionReady(value.serviceConnected, value.serviceStateHydrated) &&
            !value.preparingRange
        val captureEnabled = serviceReady && value.serviceConnected &&
            (ui == CaptureBufferUiState.READY || recording)
        val clickable = serviceReady && !value.saving && (disabled || captureEnabled)
        val readout = captureBufferReadout(value.retentionMode, disabled, metrics.seconds, metrics.bytes,
            context.getString(R.string.buffer_disabled))
        blob.bind(ReverbHomeBlobState(
            if (renderedBuffer == BufferSlot.ONE_SHOT) value.oneShotController else value.loopingController,
            recording, value.saving, captureEnabled, clickable, full,
            disabled || isCaptureBlockedByOtherBuffer(renderedBuffer, value.listening, value.activeBuffer),
            readout.primary, readout.secondary, value.visualizerVisible,
        ), frame) {
            if (disabled) actions.openBufferSettings(renderedBuffer) else actions.activateBuffer(renderedBuffer)
        }
        controls.bind(ReverbHomeControlsState(renderedBuffer, value.activeBuffer, value.listening,
            value.oneShotEnabled, value.oneShotFull, value.loopingEnabled, serviceReady, value.saving,
            metrics.seconds > 0f, recording), frame, actions)
        val showProgress = value.visualizerVisible && (!value.serviceStateHydrated || value.preparingRange)
        if (showProgress) {
            val indicator = progress ?: ReverbIncidentLoadingView(
                context, environment.palette.primary,
                requireNotNull(animationScope.coroutineContext[app.smallthingz.nativeui.runtime.MonotonicFrameClock]),
                animationScope,
                { animationScope.coroutineContext[app.smallthingz.nativeui.MotionDurationScale]?.scaleFactor ?: 1f },
            ).also {
                progress = it
                swipeSurface.addView(it)
            }
            indicator.contentDescription = context.getString(if (value.preparingRange) R.string.preparing_audio_selection
                else R.string.loading_audio_history)
        } else progress?.let {
            swipeSurface.removeView(it)
            progress = null
        }
    }

    fun setEnvironment(value: ReverbViewEnvironment) {
        environment = value
        blob.setEnvironment(value)
        controls.setEnvironment(value)
        progress?.setColor(value.palette.primary)
        val current = state
        val callbacks = actions
        if (current != null && callbacks != null) bind(current, renderedBuffer, flip, callbacks)
        requestLayout()
    }

    /** Called by the horizontal parent ownership adapter on source-equivalent consumption. */
    fun cancelBlobGesture() = blob.cancelGesture()

    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w)
        val height = MeasureSpec.getSize(h)
        setMeasuredDimension(width, height)
        geometry = reverbHomeGeometry(width, height, environment.textDensity.density)
        controls.measure(exact(geometry.clusterWidth), exact(geometry.clusterHeight))
        swipeSurface.measure(exact(geometry.bodyWidth), exact(geometry.blobAreaHeight))
        blob.measure(exact(geometry.blobSize), exact(geometry.blobSize))
        progress?.let { progress ->
            val size = dp(24)
            // The bottom padding is outside the size modifier and consumes height first.
            progress.measure(exact(size.coerceAtMost(geometry.bodyWidth)),
                exact(size.coerceAtMost((geometry.blobAreaHeight - dp(12)).coerceAtLeast(0))))
        }
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        swipeSurface.layout(geometry.side, 0, geometry.side + geometry.bodyWidth, geometry.blobAreaHeight)
        val blobLeft = ((geometry.bodyWidth - blob.measuredWidth) / 2f).roundToInt()
        val blobTop = ((geometry.blobAreaHeight - blob.measuredHeight) / 2f).roundToInt()
        blob.layout(blobLeft, blobTop, blobLeft + blob.measuredWidth, blobTop + blob.measuredHeight)
        progress?.let { progress ->
            val left = ((geometry.bodyWidth - progress.measuredWidth) / 2f).roundToInt()
            val bottom = geometry.blobAreaHeight - dp(12).coerceAtMost(geometry.blobAreaHeight)
            progress.layout(left, bottom - progress.measuredHeight, left + progress.measuredWidth, bottom)
        }
        val clusterLeft = geometry.side + ((geometry.bodyWidth - controls.measuredWidth) / 2f).roundToInt()
        controls.layout(clusterLeft, geometry.blobAreaHeight,
            clusterLeft + controls.measuredWidth, geometry.blobAreaHeight + controls.measuredHeight)
        bodyBounds.set(geometry.side.toFloat(), 0f,
            (geometry.side + geometry.bodyWidth).toFloat(), height.toFloat())
        onBoundsChanged(this, bodyBounds, blob)
    }
    override fun close() = blob.close()
    private fun dp(value: Int) = (value * environment.textDensity.density).roundToInt()
    private fun exact(value: Int) = MeasureSpec.makeMeasureSpec(value, MeasureSpec.EXACTLY)
}

