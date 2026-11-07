package app.smallthingz.reverb

import android.app.Instrumentation
import android.os.SystemClock
import android.view.Choreographer
import app.smallthingz.reverb.nativeui.ReverbPalette
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import org.json.JSONObject

/** Real vsync restart after natural settling; never clears the renderer clock or calls stopFrames. */
internal fun verifyNativeBlobRestartClock(
    instrumentation: Instrumentation,
    blob: AudioBlobView,
    palette: ReverbPalette,
    scene: JSONObject,
) {
    fun <T> main(action: () -> T): T = nativeParityOnMain(instrumentation, action)
    fun field(name: String) = AudioBlobView::class.java.getDeclaredField(name).apply { isAccessible = true }
    val life = field("currentLife")
    val presence = field("currentPresence")
    val posted = field("framePosted")
    val clock = field("lastFrameNanos")
    val shaderTime = field("shaderTimeSeconds")
    fun naturallySettled(active: Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5000L
        while (!main {
            check(blob.isShown && blob.hasWindowFocus() && blob.isHardwareAccelerated)
            !posted.getBoolean(blob) && life.getFloat(blob) == (if (active) 1f else .38f) &&
                presence.getFloat(blob) == (if (active) 1f else 0f)
        }) {
            check(SystemClock.uptimeMillis() < deadline) { "Blob did not naturally settle" }
            awaitNativeParityDraw(instrumentation, blob)
        }
    }
    naturallySettled(true)
    main { blob.updateState(false, true, false, true, palette.primary, palette.tertiary,
        palette.surfaceContainerHighest, skipSilenceEnabled = true) }
    naturallySettled(false)
    val stoppedClock = main { clock.getLong(blob) }
    SystemClock.sleep(200L)
    val observed = CountDownLatch(1)
    var firstDelta = Float.NaN
    var firstLife = Float.NaN
    var firstPresence = Float.NaN
    var before = 0f
    var expectedDelta = 0f
    var callbackFailure: Throwable? = null
    var choreographer: Choreographer? = null
    val observer = Choreographer.FrameCallback {
        try {
            check(blob.isShown && blob.hasWindowFocus())
            firstDelta = shaderTime.getFloat(blob) - before
            firstLife = life.getFloat(blob)
            firstPresence = presence.getFloat(blob)
        } catch (error: Throwable) { callbackFailure = error }
        finally { observed.countDown() }
    }
    try {
        main {
            before = shaderTime.getFloat(blob)
            val rate = blob.display?.refreshRate?.takeIf { it.isFinite() && it >= 24f }?.coerceIn(24f, 240f) ?: 60f
            expectedDelta = 1f / rate
            blob.updateState(true, true, false, true, palette.primary, palette.tertiary,
                palette.surfaceContainerHighest, skipSilenceEnabled = true)
            // Both callbacks enter the same Choreographer queue in this one main-thread action;
            // the observer follows the renderer's already-posted first callback.
            choreographer = Choreographer.getInstance()
            choreographer!!.postFrameCallback(observer)
        }
        check(observed.await(3, TimeUnit.SECONDS)) { "Restart first-vsync observer timed out" }
        callbackFailure?.let { throw it }
        scene.put("natural_restart_clock", JSONObject().put("stopped_clock_nanos", stoppedClock)
            .put("idle_wait_ms", 200).put("first_delta_seconds", firstDelta.toDouble())
            .put("expected_display_delta_seconds", expectedDelta.toDouble())
            .put("first_life", firstLife.toDouble()).put("first_presence", firstPresence.toDouble()))
        check(stoppedClock == 0L) { "Natural stop retained stale clock $stoppedClock" }
        check(abs(firstDelta - expectedDelta) < .001f && firstLife < .55f && firstPresence < .3f) {
            "Restart consumed idle time: dt=$firstDelta expected=$expectedDelta life=$firstLife presence=$firstPresence"
        }
    } finally {
        main { choreographer?.removeFrameCallback(observer) }
    }
    naturallySettled(true)
}
