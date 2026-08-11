package app.smallthingz.reverb

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.view.Choreographer
import android.view.View
import androidx.annotation.RequiresApi
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
    private var active = false
    private var recording = false
    private var enabledState = true
    private var saving = false
    private var aggregatedVisible = false
    private var windowFocused = false
    private var framePosted = false
    private var animationStartNanos = 0L
    private var lastFrameNanos = 0L
    private val choreographer = Choreographer.getInstance()

    private val renderer: Renderer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        runCatching<Renderer> { ShaderRenderer() }.getOrElse { FallbackRenderer() }
    } else {
        FallbackRenderer()
    }

    private val frameCallback = Choreographer.FrameCallback { frameTimeNanos ->
        framePosted = false
        if (!shouldAnimate()) return@FrameCallback
        if (animationStartNanos == 0L) animationStartNanos = frameTimeNanos
        val dtSeconds = if (lastFrameNanos == 0L) {
            1f / 30f
        } else {
            ((frameTimeNanos - lastFrameNanos) / 1_000_000_000f).coerceIn(0.001f, 0.1f)
        }
        lastFrameNanos = frameTimeNanos
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
        if (source.isEmpty()) {
            targetBands.fill(0f)
        } else {
            for (band in 0 until BAND_COUNT) {
                val start = band * source.size / BAND_COUNT
                val end = max(start + 1, (band + 1) * source.size / BAND_COUNT).coerceAtMost(source.size)
                var peak = 0f
                for (index in start until end) peak = max(peak, source[index])
                targetBands[band] = peak.coerceIn(0f, 1f)
            }
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
        if (!active) {
            currentActivity = 0f
            currentBands.fill(0f)
            invalidate()
        }
    }

    fun updateState(
        active: Boolean,
        recording: Boolean,
        enabled: Boolean,
        saving: Boolean,
        primary: Int,
        tertiary: Int,
        paused: Int,
        error: Int,
    ) {
        val stateChanged = this.active != active || this.recording != recording ||
            enabledState != enabled || this.saving != saving
        this.active = active
        this.recording = recording
        enabledState = enabled
        this.saving = saving
        renderer.setPalette(primary, tertiary, paused, error)
        if (!active || saving) {
            targetActivity = 0f
            targetBands.fill(0f)
        }
        if (stateChanged) {
            ensureAnimationState()
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val timeSeconds = if (animationStartNanos == 0L) 0f else {
            (lastFrameNanos - animationStartNanos) / 1_000_000_000f
        }
        renderer.draw(
            canvas = canvas,
            width = width,
            height = height,
            timeSeconds = timeSeconds,
            activity = currentActivity,
            bands = currentBands,
            active = active && !saving,
            recording = recording,
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

    private fun shouldAnimate(): Boolean =
        isAttachedToWindow && aggregatedVisible && windowFocused && active && enabledState && !saving

    private fun ensureAnimationState() {
        if (shouldAnimate()) {
            postNextFrame(immediate = true)
        } else {
            stopFrames()
            if (!active || saving) {
                currentActivity = 0f
                currentBands.fill(0f)
            }
        }
    }

    private fun postNextFrame(immediate: Boolean = false) {
        if (framePosted || !shouldAnimate()) return
        framePosted = true
        if (immediate) {
            choreographer.postFrameCallback(frameCallback)
        } else {
            val audioHot = hasHotAudio()
            val delay = if (audioHot) renderer.activeFrameDelayMillis else renderer.idleFrameDelayMillis
            choreographer.postFrameCallbackDelayed(frameCallback, delay)
        }
    }

    private fun hasHotAudio(): Boolean {
        if (currentActivity > ACTIVE_FRAME_THRESHOLD || targetActivity > ACTIVE_FRAME_THRESHOLD) return true
        for (index in currentBands.indices) {
            if (currentBands[index] > ACTIVE_BAND_THRESHOLD || targetBands[index] > ACTIVE_BAND_THRESHOLD) return true
        }
        return false
    }

    private fun stopFrames() {
        if (framePosted) {
            choreographer.removeFrameCallback(frameCallback)
            framePosted = false
        }
        lastFrameNanos = 0L
        animationStartNanos = 0L
    }

    private fun advance(dtSeconds: Float) {
        val normalized = (dtSeconds * 30f).coerceIn(0.25f, 3f)
        val activityRate = if (targetActivity > currentActivity) 0.34f else 0.16f
        val activityMix = (activityRate * normalized).coerceIn(0f, 0.82f)
        currentActivity += (targetActivity - currentActivity) * activityMix
        for (index in currentBands.indices) {
            val rate = if (targetBands[index] > currentBands[index]) 0.30f else 0.13f
            val mix = (rate * normalized).coerceIn(0f, 0.8f)
            currentBands[index] += (targetBands[index] - currentBands[index]) * mix
        }
    }

    private interface Renderer {
        val activeFrameDelayMillis: Long
        val idleFrameDelayMillis: Long
        fun resize(width: Int, height: Int)
        fun setPalette(primary: Int, tertiary: Int, paused: Int, error: Int)
        fun draw(
            canvas: Canvas,
            width: Int,
            height: Int,
            timeSeconds: Float,
            activity: Float,
            bands: FloatArray,
            active: Boolean,
            recording: Boolean,
        )
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private class ShaderRenderer : Renderer {
        override val activeFrameDelayMillis = 33L
        override val idleFrameDelayMillis = 66L
        private val shader = RuntimeShader(SHADER_SOURCE)
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).also { it.shader = shader }
        private var primary = 0
        private var tertiary = 0
        private var paused = 0
        private var error = 0

        override fun resize(width: Int, height: Int) {
            shader.setFloatUniform("resolution", width.toFloat(), height.toFloat())
        }

        override fun setPalette(primary: Int, tertiary: Int, paused: Int, error: Int) {
            if (this.primary == primary && this.tertiary == tertiary && this.paused == paused && this.error == error) return
            this.primary = primary
            this.tertiary = tertiary
            this.paused = paused
            this.error = error
            shader.setColorUniform("primaryColor", primary)
            shader.setColorUniform("tertiaryColor", tertiary)
            shader.setColorUniform("pausedColor", paused)
            shader.setColorUniform("errorColor", error)
        }

        override fun draw(
            canvas: Canvas,
            width: Int,
            height: Int,
            timeSeconds: Float,
            activity: Float,
            bands: FloatArray,
            active: Boolean,
            recording: Boolean,
        ) {
            if (width <= 0 || height <= 0) return
            shader.setFloatUniform("time", timeSeconds)
            shader.setFloatUniform("activity", activity)
            shader.setFloatUniform("active", if (active) 1f else 0f)
            shader.setFloatUniform("recording", if (recording) 1f else 0f)
            shader.setFloatUniform("bands0", bands[0], bands[1], bands[2], bands[3])
            shader.setFloatUniform("bands1", bands[4], bands[5], bands[6], bands[7])
            canvas.drawPaint(paint)
        }
    }

    private class FallbackRenderer : Renderer {
        override val activeFrameDelayMillis = 50L
        override val idleFrameDelayMillis = 84L
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val path = Path()
        private val x = FloatArray(FALLBACK_POINTS)
        private val y = FloatArray(FALLBACK_POINTS)
        private var width = 0
        private var height = 0
        private var primary = 0
        private var tertiary = 0
        private var paused = 0
        private var error = 0
        private var gradientState = -1

        override fun resize(width: Int, height: Int) {
            this.width = width
            this.height = height
            gradientState = -1
        }

        override fun setPalette(primary: Int, tertiary: Int, paused: Int, error: Int) {
            if (this.primary == primary && this.tertiary == tertiary && this.paused == paused && this.error == error) return
            this.primary = primary
            this.tertiary = tertiary
            this.paused = paused
            this.error = error
            gradientState = -1
        }

        override fun draw(
            canvas: Canvas,
            width: Int,
            height: Int,
            timeSeconds: Float,
            activity: Float,
            bands: FloatArray,
            active: Boolean,
            recording: Boolean,
        ) {
            if (width <= 0 || height <= 0) return
            val minSize = minOf(width, height).toFloat()
            val cx = width * 0.5f
            val cy = height * 0.5f
            val base = minSize * (0.265f + activity * 0.02f)
            for (index in 0 until FALLBACK_POINTS) {
                val angle = index.toFloat() / FALLBACK_POINTS * (PI.toFloat() * 2f) - PI.toFloat() / 2f
                val band = bands[index * BAND_COUNT / FALLBACK_POINTS]
                val idle = if (active) {
                    sin(angle * 3f + timeSeconds * 0.8f) * minSize * 0.005f +
                        sin(angle * 5f - timeSeconds * 0.55f) * minSize * 0.0025f
                } else 0f
                val radius = base + if (active) band * minSize * 0.042f + idle else 0f
                x[index] = cx + cos(angle) * radius
                y[index] = cy + sin(angle) * radius
            }
            path.reset()
            path.moveTo((x[0] + x[1]) * 0.5f, (y[0] + y[1]) * 0.5f)
            for (index in 1..FALLBACK_POINTS) {
                val current = index % FALLBACK_POINTS
                val next = (index + 1) % FALLBACK_POINTS
                path.quadTo(x[current], y[current], (x[current] + x[next]) * 0.5f, (y[current] + y[next]) * 0.5f)
            }
            path.close()

            val state = when {
                recording -> 2
                active -> 1
                else -> 0
            }
            if (gradientState != state) {
                paint.shader = when (state) {
                    2 -> null
                    1 -> LinearGradient(0f, 0f, width.toFloat(), height.toFloat(), primary, tertiary, Shader.TileMode.CLAMP)
                    else -> null
                }
                paint.color = if (recording) error else if (active) primary else paused
                gradientState = state
            }
            canvas.drawPath(path, paint)
        }
    }

    companion object {
        private const val BAND_COUNT = 8
        private const val FALLBACK_POINTS = 40
        private const val ACTIVE_FRAME_THRESHOLD = 0.035f
        private const val ACTIVE_BAND_THRESHOLD = 0.055f

        private const val SHADER_SOURCE = """
            uniform float2 resolution;
            uniform float time;
            uniform float activity;
            uniform float active;
            uniform float recording;
            uniform float4 bands0;
            uniform float4 bands1;
            layout(color) uniform half4 primaryColor;
            layout(color) uniform half4 tertiaryColor;
            layout(color) uniform half4 pausedColor;
            layout(color) uniform half4 errorColor;

            half4 main(float2 fragCoord) {
                float minSize = min(resolution.x, resolution.y);
                float2 p = (fragCoord - resolution * 0.5) / minSize;
                float angle = atan(p.y, p.x);
                float radius = length(p);

                float low = (bands0.x + bands0.y) * 0.5;
                float lowMid = (bands0.z + bands0.w) * 0.5;
                float highMid = (bands1.x + bands1.y) * 0.5;
                float high = (bands1.z + bands1.w) * 0.5;
                float h3 = sin(angle * 3.0 + time * 0.78);
                float h5 = sin(angle * 5.0 - time * 0.49 + 1.1);
                float h7 = sin(angle * 7.0 + time * 0.34 + 2.2);
                float h9 = sin(angle * 9.0 - time * 0.27 + 3.4);
                float audioWave =
                    h3 * low * 0.36 +
                    h5 * lowMid * 0.29 +
                    h7 * highMid * 0.21 +
                    h9 * high * 0.14;

                float idle = h3 * 0.0055 + h5 * 0.0027;
                float baseRadius = 0.265 + activity * 0.022;
                float blobRadius = baseRadius + active * (idle + audioWave * (0.052 + activity * 0.018));
                float distanceToEdge = radius - blobRadius;

                float body = smoothstep(0.012, -0.006, distanceToEdge);
                float glow = active * (1.0 - body) *
                    smoothstep(0.13, 0.0, max(distanceToEdge, 0.0)) *
                    (0.07 + activity * 0.10);

                float gradientMix = clamp(0.46 + p.x * 0.9 - p.y * 0.55, 0.0, 1.0);
                half4 activeColor = mix(primaryColor, tertiaryColor, half(gradientMix));
                half4 bodyColor = mix(pausedColor, activeColor, half(active));
                bodyColor = mix(bodyColor, errorColor, half(recording));
                half4 glowColor = mix(primaryColor, tertiaryColor, half(0.58));
                glowColor = mix(glowColor, errorColor, half(recording));

                float alpha = body + glow;
                half3 premultiplied = bodyColor.rgb * half(body) + glowColor.rgb * half(glow);
                return half4(premultiplied, half(alpha));
            }
        """
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
