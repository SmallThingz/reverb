package app.smallthingz.reverb

import android.app.Instrumentation
import android.os.SystemClock
import android.view.Choreographer
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import app.smallthingz.reverb.nativeui.ReverbPalette
import app.smallthingz.reverb.nativeui.ReverbViewEnvironment
import app.smallthingz.reverb.nativeui.ReverbHomeBlobState
import smallthingz.ui.unit.Density
import smallthingz.ui.text.font.createFontFamilyResolver
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/** Continuous production-renderer evidence; synthetic visual input, no PCM or microphone claims. */
internal fun captureNativeBlobMotionTimeline(
    instrumentation: Instrumentation,
    blob: AudioBlobView,
    controller: AudioBlobController,
    palette: ReverbPalette,
    name: String,
    directory: File,
    scene: JSONObject,
) {
    val finished = CountDownLatch(1)
    val capacity = 32 * 300 + 32
    val times = LongArray(capacity)
    val phases = IntArray(capacity)
    val activities = FloatArray(capacity)
    val lives = FloatArray(capacity)
    val silences = FloatArray(capacity)
    val presences = FloatArray(capacity)
    val life = AudioBlobView::class.java.getDeclaredField("currentLife").apply { isAccessible = true }
    val silence = AudioBlobView::class.java.getDeclaredField("currentSilence").apply { isAccessible = true }
    val presence = AudioBlobView::class.java.getDeclaredField("currentPresence").apply { isAccessible = true }
    val zero = FloatArray(8)
    val low = floatArrayOf(.65f,.5f,.3f,.2f,.04f,.03f,.02f,.01f)
    val high = low.reversedArray()
    val loud = floatArrayOf(.8f,.7f,.6f,.5f,.7f,.8f,.6f,.5f)
    val labels = arrayOf("idle", "start / quiet", "three transients", "sustained low", "sustained high",
        "sustained loud", "decay / silence", "loud before stop", "stop / settle",
        "resume loud", "filled from loud", "resume quiet", "filled from quiet")
    var count = 0
    var start = 0L
    var lastPhase = -1
    var lastSecond = -1
    var failure: Throwable? = null
    var parent: FrameLayout? = null
    var label: TextView? = null
    var choreographer: Choreographer? = null
    var overlay: ViewGroup? = null
    var overlayWrapper: FrameLayout? = null
    val overlayType = Class.forName("app.smallthingz.reverb.nativeui.BlobOverlay")
    val bind = overlayType.getDeclaredMethod("bind", ReverbHomeBlobState::class.java, Float::class.javaPrimitiveType)
        .apply { isAccessible = true }
    val appearance = overlayType.getDeclaredMethod("setMaterialAppearance", Boolean::class.javaPrimitiveType,
        Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
        .apply { isAccessible = true }
    val geometry = overlayType.getDeclaredMethod("updateMaterialGeometry", AudioBlobView::class.java, Int::class.javaPrimitiveType)
        .apply { isAccessible = true }
    val callback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            try {
                check(blob.isShown && blob.hasWindowFocus() && blob.isHardwareAccelerated)
                if (start == 0L) start = frameTimeNanos
                val seconds = (frameTimeNanos - start) / 1_000_000_000.0
                if (seconds >= 32.0) { finished.countDown(); return }
                check(count < capacity) { "Motion timeline exceeded frame capacity" }
                val phase = when {
                    seconds < 2 -> 0; seconds < 4 -> 1; seconds < 6 -> 2
                    seconds < 9 -> 3; seconds < 12 -> 4; seconds < 14 -> 5
                    seconds < 17 -> 6; seconds < 19 -> 7; seconds < 22 -> 8
                    seconds < 24 -> 9; seconds < 26 -> 10; seconds < 28 -> 11; else -> 12
                }
                val active = phase in 1..7 || phase == 9 || phase == 11
                val filled = phase == 10 || phase == 12
                val pulse = phase == 2 && ((seconds >= 4.0 && seconds < 4.1) ||
                    (seconds >= 4.6 && seconds < 4.7) || (seconds >= 5.2 && seconds < 5.3))
                val activity = when { pulse -> .65f; phase == 3 || phase == 4 -> .35f
                    phase == 5 || phase == 7 || phase == 9 -> .8f; else -> 0f }
                val bins = when { pulse || phase == 5 || phase == 7 || phase == 9 -> loud
                    phase == 3 -> low; phase == 4 -> high; else -> zero }
                if (phase != lastPhase) {
                    blob.updateState(active, true, false, true, palette.primary, palette.tertiary,
                        if (filled) palette.primaryContainer else palette.surfaceContainerHighest, skipSilenceEnabled = true)
                    bind.invoke(overlay, ReverbHomeBlobState(controller, active, false, true, true,
                        filled, false, "15.1 MiB", if (active || filled) "2:58" else null, true, true), 1f)
                    lastPhase = phase
                }
                controller.submit(ReverbService.VisualizationFrame(activity, bins,
                    phase == 1 || phase == 6 || phase == 11, ReverbService.BufferSlot.ONE_SHOT, 1L, 1L))
                if (lastSecond != seconds.toInt()) {
                    lastSecond = seconds.toInt()
                    label?.text = "$name | ${labels[phase]} | ${lastSecond}s / 32s"
                }
                times[count] = frameTimeNanos
                phases[count] = phase
                activities[count] = activity
                lives[count] = life.getFloat(blob)
                silences[count] = silence.getFloat(blob)
                presences[count] = presence.getFloat(blob)
                count++
                choreographer!!.postFrameCallback(this)
            } catch (error: Throwable) { failure = error; finished.countDown() }
        }
    }
    val startedUptime = SystemClock.uptimeMillis()
    try {
        nativeParityOnMain(instrumentation) {
            parent = blob.parent as FrameLayout
            val environment = ReverbViewEnvironment(Density(blob.resources.displayMetrics.density,
                blob.resources.configuration.fontScale), createFontFamilyResolver(blob.context), palette)
            overlay = overlayType.getDeclaredConstructor(android.content.Context::class.java, ReverbViewEnvironment::class.java)
                .apply { isAccessible = true }.newInstance(blob.context, environment) as ViewGroup
            // Match Home's unclipped overlay parent; a tight child under fixtureRoot's default
            // clipChildren would truncate the startup support field into a false rectangle.
            overlayWrapper = FrameLayout(blob.context).apply {
                clipChildren = false
                clipToPadding = false
                addView(overlay, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            }
            parent!!.addView(overlayWrapper, FrameLayout.LayoutParams(blob.width, blob.height, Gravity.CENTER))
            overlay!!.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                geometry.invoke(overlay, blob, palette.surface)
            }
            blob.onReadoutAppearanceChanged = { darkInk, transitioning, supportNeeded ->
                appearance.invoke(overlay, darkInk, transitioning, supportNeeded); Unit }
            label = TextView(blob.context).apply {
                setTextColor(palette.onSurface)
                textSize = 14f
                gravity = Gravity.CENTER
            }
            parent!!.addView(label, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                (52 * blob.resources.displayMetrics.density).toInt(), Gravity.TOP))
            choreographer = Choreographer.getInstance()
            choreographer!!.postFrameCallback(callback)
        }
        check(finished.await(45, TimeUnit.SECONDS)) { "Motion timeline timed out" }
        failure?.let { throw it }
        check(count > 200) { "Too few motion timeline frames: $count" }
        File(directory, "$name-motion.csv").bufferedWriter().use { out ->
            out.appendLine("frame_nanos,phase,target_activity,current_life,current_silence,current_presence")
            for (index in 0 until count) out.appendLine("${times[index]},${phases[index]},${activities[index]},${lives[index]},${silences[index]},${presences[index]}")
        }
        scene.put("continuous_motion", JSONObject().put("duration_seconds", 32).put("frames", count)
            .put("start_uptime_ms", startedUptime).put("first_frame_nanos", start)
            .put("timeline", "$name-motion.csv").put("input", "synthetic visualization only"))
    } finally {
        nativeParityOnMain(instrumentation) {
            choreographer?.removeFrameCallback(callback)
            blob.onReadoutAppearanceChanged = null
            label?.let { parent?.removeView(it) }
            overlayWrapper?.let { parent?.removeView(it) }
        }
    }
}
