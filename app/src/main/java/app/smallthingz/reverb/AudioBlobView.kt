package app.smallthingz.reverb

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.view.Choreographer
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Lightweight visualizer surface. Audio analysis is pushed in at a low rate and this view
 * interpolates it independently, so rendering never drives microphone or disk I/O cadence.
 */
internal class AudioBlobView(context: Context) : View(context) {
    private val targetBands = FloatArray(BAND_COUNT)
    private val currentBands = FloatArray(BAND_COUNT)
    private var targetActivity = 0f
    private var currentActivity = 0f
    private var targetLife = COLLAPSED_LIFE
    private var currentLife = COLLAPSED_LIFE
    private var active = false
    private var enabledState = true
    private var saving = false
    private var renderingVisible = true
    private var aggregatedVisible = false
    private var windowFocused = false
    private var framePosted = false
    private var animationTimeSeconds = 0f
    private var lastFrameNanos = 0L
    private var lastAudioSignalNanos = 0L
    private val choreographer = Choreographer.getInstance()

    private val renderer: Renderer = PathRenderer()

    private val frameCallback = Choreographer.FrameCallback { frameTimeNanos ->
        framePosted = false
        if (!shouldAnimate()) return@FrameCallback
        val dtSeconds = if (lastFrameNanos == 0L) {
            1f / 60f
        } else {
            ((frameTimeNanos - lastFrameNanos) / 1_000_000_000f).coerceIn(0.001f, 0.1f)
        }
        lastFrameNanos = frameTimeNanos
        animationTimeSeconds += dtSeconds
        advance(dtSeconds)
        invalidate()
        postNextFrame()
    }

    init {
        setWillNotDraw(false)
        isFocusable = false
        isClickable = false
    }

    fun submitFrame(frame: ReverbService.VisualizationFrame) {
        val wasHot = hasHotAudio()
        targetActivity = frame.activity.coerceIn(0f, 1f)
        val source = frame.bins
        var hasSignal = targetActivity > SIGNAL_ACTIVITY_THRESHOLD
        if (source.size == BAND_COUNT * 2) {
            for (band in 0 until BAND_COUNT) {
                val index = band * 2
                val value = max(source[index], source[index + 1]).coerceIn(0f, 1f)
                targetBands[band] = value
                if (value > SIGNAL_BAND_THRESHOLD) hasSignal = true
            }
        } else if (source.isEmpty()) {
            targetBands.fill(0f)
        } else {
            for (band in 0 until BAND_COUNT) {
                val start = band * source.size / BAND_COUNT
                val end = max(start + 1, (band + 1) * source.size / BAND_COUNT).coerceAtMost(source.size)
                var peak = 0f
                for (index in start until end) peak = max(peak, source[index])
                val value = peak.coerceIn(0f, 1f)
                targetBands[band] = value
                if (value > SIGNAL_BAND_THRESHOLD) hasSignal = true
            }
        }
        if (hasSignal) {
            lastAudioSignalNanos = System.nanoTime()
        }
        if (!wasHot && hasHotAudio() && framePosted) {
            choreographer.removeFrameCallback(frameCallback)
            framePosted = false
            postNextFrame(immediate = true)
        } else {
            ensureAnimationState()
        }
    }

    fun clearFrame() {
        targetActivity = 0f
        targetBands.fill(0f)
        currentActivity = 0f
        currentBands.fill(0f)
        lastAudioSignalNanos = 0L
        invalidate()
    }

    fun updateState(
        active: Boolean,
        enabled: Boolean,
        saving: Boolean,
        visible: Boolean,
        primary: Int,
        tertiary: Int,
        paused: Int,
    ) {
        val stateChanged = this.active != active ||
            enabledState != enabled || this.saving != saving || renderingVisible != visible
        val shouldClearAudio = (this.active && !active) || (!this.saving && saving) ||
            (enabledState && !enabled)
        this.active = active
        enabledState = enabled
        this.saving = saving
        renderingVisible = visible
        targetLife = when {
            !enabled -> DISABLED_LIFE
            active && !saving -> 1f
            else -> COLLAPSED_LIFE
        }
        val paletteChanged = renderer.setPalette(primary, tertiary, paused)
        if (shouldClearAudio) {
            targetActivity = 0f
            targetBands.fill(0f)
            lastAudioSignalNanos = 0L
        }
        if (stateChanged) ensureAnimationState()
        if (stateChanged || paletteChanged) invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        renderer.draw(
            canvas = canvas,
            width = width,
            height = height,
            timeSeconds = animationTimeSeconds,
            activity = currentActivity,
            bands = currentBands,
            life = currentLife,
            active = active && !saving,
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        renderer.resize(w, h)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        aggregatedVisible = isShown
        windowFocused = hasWindowFocus()
        ensureAnimationState()
    }

    override fun onDetachedFromWindow() {
        stopFrames()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        aggregatedVisible = isVisible
        ensureAnimationState()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        windowFocused = hasWindowFocus
        ensureAnimationState()
    }

    private fun shouldAnimate(): Boolean {
        if (!renderingVisible || !isAttachedToWindow || !aggregatedVisible || !windowFocused) return false
        if (kotlin.math.abs(currentLife - targetLife) > LIFE_EPSILON) return true
        if (!enabledState || !active || saving) return false
        return hasRecentAudioSignal() || hasResidualAudio()
    }

    private fun ensureAnimationState() {
        if (shouldAnimate()) {
            postNextFrame(immediate = true)
        } else {
            stopFrames()
            if (!renderingVisible) {
                currentActivity = 0f
                currentBands.fill(0f)
                currentLife = COLLAPSED_LIFE
            } else if (!active || saving) {
                currentActivity = 0f
                currentBands.fill(0f)
                currentLife = targetLife
            }
            invalidate()
        }
    }

    private fun postNextFrame(immediate: Boolean = false) {
        if (framePosted || !shouldAnimate()) return
        framePosted = true
        val lifeMoving = kotlin.math.abs(currentLife - targetLife) > LIFE_EPSILON
        if (immediate || lifeMoving || hasHotAudio()) {
            choreographer.postFrameCallback(frameCallback)
        } else {
            choreographer.postFrameCallbackDelayed(frameCallback, IDLE_FRAME_DELAY_MILLIS)
        }
    }

    private fun hasHotAudio(): Boolean {
        if (currentActivity > ACTIVE_FRAME_THRESHOLD || targetActivity > ACTIVE_FRAME_THRESHOLD) return true
        for (index in currentBands.indices) {
            if (currentBands[index] > ACTIVE_BAND_THRESHOLD || targetBands[index] > ACTIVE_BAND_THRESHOLD) return true
        }
        return false
    }

    private fun hasResidualAudio(): Boolean {
        if (currentActivity > RESIDUAL_FRAME_THRESHOLD || targetActivity > RESIDUAL_FRAME_THRESHOLD) return true
        for (index in currentBands.indices) {
            if (currentBands[index] > RESIDUAL_BAND_THRESHOLD || targetBands[index] > RESIDUAL_BAND_THRESHOLD) return true
        }
        return false
    }

    private fun hasRecentAudioSignal(): Boolean {
        if (!active || lastAudioSignalNanos == 0L) return false
        return System.nanoTime() - lastAudioSignalNanos <= RECENT_AUDIO_HOLD_NANOS
    }

    private fun stopFrames() {
        if (framePosted) {
            choreographer.removeFrameCallback(frameCallback)
            framePosted = false
        }
        lastFrameNanos = 0L
    }

    private fun advance(dtSeconds: Float) {
        val activityRate = if (targetActivity > currentActivity) 13f else 6f
        val activityMix = (activityRate * dtSeconds).coerceIn(0f, 0.86f)
        currentActivity += (targetActivity - currentActivity) * activityMix
        for (index in currentBands.indices) {
            val rate = if (targetBands[index] > currentBands[index]) 12f else 5f
            val mix = (rate * dtSeconds).coerceIn(0f, 0.84f)
            currentBands[index] += (targetBands[index] - currentBands[index]) * mix
        }
        val lifeRate = if (targetLife > currentLife) 24f else 18f
        val lifeMix = (lifeRate * dtSeconds).coerceIn(0f, 0.90f)
        currentLife += (targetLife - currentLife) * lifeMix
        if (kotlin.math.abs(currentLife - targetLife) <= LIFE_EPSILON) currentLife = targetLife
        if (!active && currentLife == targetLife) {
            currentActivity = 0f
            currentBands.fill(0f)
        }
    }

    private interface Renderer {
        fun resize(width: Int, height: Int)
        fun setPalette(primary: Int, tertiary: Int, paused: Int): Boolean
        fun draw(
            canvas: Canvas,
            width: Int,
            height: Int,
            timeSeconds: Float,
            activity: Float,
            bands: FloatArray,
            life: Float,
            active: Boolean,
        )
    }

    private class PathRenderer : Renderer {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val path = Path()
        private val x = FloatArray(PATH_POINTS)
        private val y = FloatArray(PATH_POINTS)
        private var primary = 0
        private var tertiary = 0
        private var paused = 0
        private var gradientState = -1

        override fun resize(width: Int, height: Int) {
            gradientState = -1
        }

        override fun setPalette(primary: Int, tertiary: Int, paused: Int): Boolean {
            if (this.primary == primary && this.tertiary == tertiary && this.paused == paused) return false
            this.primary = primary
            this.tertiary = tertiary
            this.paused = paused
            gradientState = -1
            return true
        }

        override fun draw(
            canvas: Canvas,
            width: Int,
            height: Int,
            timeSeconds: Float,
            activity: Float,
            bands: FloatArray,
            life: Float,
            active: Boolean,
        ) {
            if (width <= 0 || height <= 0) return
            val minSize = minOf(width, height).toFloat()
            val cx = width * 0.5f
            val cy = height * 0.5f
            val base = minSize * (0.095f + life * (0.235f + activity * 0.018f))
            val phase3 = timeSeconds * 0.8f
            val phase5 = -timeSeconds * 0.55f
            val phase3Sin = if (active) sin(phase3) else 0f
            val phase3Cos = if (active) cos(phase3) else 1f
            val phase5Sin = if (active) sin(phase5) else 0f
            val phase5Cos = if (active) cos(phase5) else 1f

            for (index in 0 until PATH_POINTS) {
                val band = bands[PATH_BAND_INDEX[index]]
                val idle = if (active) {
                    val h3 = PATH_WAVE3_SIN[index] * phase3Cos + PATH_WAVE3_COS[index] * phase3Sin
                    val h5 = PATH_WAVE5_SIN[index] * phase5Cos + PATH_WAVE5_COS[index] * phase5Sin
                    (h3 * 0.005f + h5 * 0.0025f) * minSize
                } else {
                    0f
                }
                val radius = base + if (active) band * minSize * 0.078f + idle else 0f
                x[index] = cx + PATH_UNIT_X[index] * radius
                y[index] = cy + PATH_UNIT_Y[index] * radius
            }

            path.reset()
            path.moveTo((x[0] + x[1]) * 0.5f, (y[0] + y[1]) * 0.5f)
            for (index in 1..PATH_POINTS) {
                val current = index % PATH_POINTS
                val next = (index + 1) % PATH_POINTS
                path.quadTo(
                    x[current],
                    y[current],
                    (x[current] + x[next]) * 0.5f,
                    (y[current] + y[next]) * 0.5f,
                )
            }
            path.close()

            val state = if (active) 1 else 0
            if (gradientState != state) {
                paint.shader = if (active) {
                    LinearGradient(
                        0f,
                        0f,
                        width.toFloat(),
                        height.toFloat(),
                        primary,
                        tertiary,
                        Shader.TileMode.CLAMP,
                    )
                } else {
                    null
                }
                paint.color = if (active) primary else paused
                gradientState = state
            }
            canvas.drawPath(path, paint)
        }
    }

    companion object {
        private const val BAND_COUNT = 8
        private const val PATH_POINTS = 32
        private const val IDLE_FRAME_DELAY_MILLIS = 33L
        private val PATH_UNIT_X = FloatArray(PATH_POINTS) { index ->
            cos(pathAngle(index))
        }
        private val PATH_UNIT_Y = FloatArray(PATH_POINTS) { index ->
            sin(pathAngle(index))
        }
        private val PATH_WAVE3_SIN = FloatArray(PATH_POINTS) { index ->
            sin(pathAngle(index) * 3f)
        }
        private val PATH_WAVE3_COS = FloatArray(PATH_POINTS) { index ->
            cos(pathAngle(index) * 3f)
        }
        private val PATH_WAVE5_SIN = FloatArray(PATH_POINTS) { index ->
            sin(pathAngle(index) * 5f)
        }
        private val PATH_WAVE5_COS = FloatArray(PATH_POINTS) { index ->
            cos(pathAngle(index) * 5f)
        }
        private val PATH_BAND_INDEX = IntArray(PATH_POINTS) { index ->
            index * BAND_COUNT / PATH_POINTS
        }
        private const val ACTIVE_FRAME_THRESHOLD = 0.018f
        private const val ACTIVE_BAND_THRESHOLD = 0.030f
        private const val RESIDUAL_FRAME_THRESHOLD = 0.003f
        private const val RESIDUAL_BAND_THRESHOLD = 0.006f
        private const val SIGNAL_ACTIVITY_THRESHOLD = 0.006f
        private const val SIGNAL_BAND_THRESHOLD = 0.010f
        private const val RECENT_AUDIO_HOLD_NANOS = 480_000_000L
        private const val COLLAPSED_LIFE = 0.38f
        private const val DISABLED_LIFE = 0.36f
        private const val LIFE_EPSILON = 0.006f

        private fun pathAngle(index: Int): Float =
            index.toFloat() / PATH_POINTS * (PI.toFloat() * 2f) - PI.toFloat() / 2f
    }
}

internal class AudioBlobController {
    private var view: AudioBlobView? = null
    private var latestFrame = ReverbService.VisualizationFrame.EMPTY

    fun attach(view: AudioBlobView) {
        this.view = view
        view.submitFrame(latestFrame)
    }

    fun detach(view: AudioBlobView) {
        if (this.view === view) this.view = null
    }

    fun submit(frame: ReverbService.VisualizationFrame) {
        latestFrame = frame
        view?.submitFrame(frame)
    }

    fun clear() {
        latestFrame = ReverbService.VisualizationFrame.EMPTY
        view?.clearFrame()
    }
}
