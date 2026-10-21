package app.smallthingz.reverb

import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.Display
import android.view.Gravity
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.smallthingz.reverb.nativeui.ReverbMaterialSwitchView
import app.smallthingz.reverb.nativeui.ReverbPalette
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject

/**
 * Requires the existing isolated QA Activity and capture helpers, not production app data.
 * Static exactness and finite observed motion comparisons are separate results.
 */
internal fun verifyNativeSwitchStaticParity(instrumentation: Instrumentation, report: StringBuilder) {
    val context = instrumentation.targetContext
    check(context.packageName == "app.smallthingz.reverb.nativeparity.reliability")
    check(context.applicationContext.javaClass == Application::class.java)
    val directory = File(context.filesDir, "native-switch-static-" + System.currentTimeMillis())
    check(directory.mkdir())
    val cases = JSONArray()
    val result = switchResult("A/B/A/B exact static ARGB, no tolerance or masks").put("cases", cases)
    var host: NativeParityActivity? = null
    var boards: SwitchParityBoards? = null
    var error: Throwable? = null
    var completed = 0
    var mismatched = 0
    val scenes = listOf(
        SwitchScene("fallback-light-160-ltr", 160, false, false, false),
        SwitchScene("fallback-dark-160-rtl", 160, true, true, false),
        SwitchScene("dynamic-light-device-rtl", context.resources.displayMetrics.densityDpi, false, true, true),
        SwitchScene("dynamic-dark-device-ltr", context.resources.displayMetrics.densityDpi, true, false, true),
    )
    try {
        val activity = launchNativeParityActivity(instrumentation)
        host = activity
        awaitNativeParityDraw(instrumentation, activity.fixtureRoot)
        for (scene in scenes) {
            requireNativeParityHost(instrumentation, activity)
            val entry = JSONObject().put("name", scene.name).put("density_dpi", scene.densityDpi)
                .put("dark", scene.dark).put("rtl", scene.rtl).put("dynamic", scene.dynamic)
            cases.put(entry)
            if (scene.dynamic && Build.VERSION.SDK_INT < 31) {
                entry.put("unqualified", "Dynamic palette is unavailable on this SDK")
                continue
            }
            lateinit var current: SwitchParityBoards
            nativeParityOnMain(instrumentation) {
                current = SwitchParityBoards(activity, scene, motion = false)
                boards = current
            }
            val images = mutableListOf<Bitmap>()
            var caseFailure: Throwable? = null
            try {
                repeat(4) { pass ->
                    images += captureNativeParityContent(instrumentation, activity) {
                        current.showStatic(compose = pass % 2 == 0)
                    }
                }
                listOf("compose", "native", "compose-repeat", "native-repeat").forEachIndexed { i, label ->
                    saveNativeParityBitmap(directory, scene.name + "-" + label + ".png", images[i])
                }
                val sourceStable = compareNativeParityPixels(images[0], images[2])
                val nativeStable = compareNativeParityPixels(images[1], images[3])
                val difference = compareNativeParityPixels(images[0], images[1])
                lateinit var geometry: JSONObject
                nativeParityOnMain(instrumentation) { geometry = current.geometry() }
                val passed = sourceStable.getLong("different_pixels") == 0L &&
                    nativeStable.getLong("different_pixels") == 0L &&
                    difference.getLong("different_pixels") == 0L && geometry.getBoolean("equal")
                entry.put("reference_stability", sourceStable).put("native_stability", nativeStable)
                    .put("difference", difference).put("geometry_and_own_target", geometry).put("passed", passed)
                completed++
                if (!passed) mismatched++
                report.append(if (passed) "PASS " else "FAIL ").append("native_switch_static ")
                    .append(scene.name).append(" differing_pixels=").append(difference.getLong("different_pixels")).append('\n')
            } catch (failure: Throwable) {
                caseFailure = failure
                entry.put("error", android.util.Log.getStackTraceString(failure))
                throw failure
            } finally {
                images.forEach(Bitmap::recycle)
                var cleanupFailure: Throwable? = null
                try { nativeParityOnMain(instrumentation) { current.dispose(); boards = null } }
                catch (cleanup: Throwable) {
                    cleanupFailure = cleanup
                    entry.put("cleanup_error", android.util.Log.getStackTraceString(cleanup))
                    caseFailure?.addSuppressed(cleanup)
                }
                writeSwitchEvidence(directory, result, caseFailure ?: cleanupFailure)
                if (caseFailure == null) cleanupFailure?.let { throw it }
            }
        }
        check(completed == if (Build.VERSION.SDK_INT >= 31) 4 else 2)
        check(mismatched == 0) { "Switch static mismatch; evidence=" + directory.name }
    } catch (failure: Throwable) {
        error = failure
        result.put("terminal_error", android.util.Log.getStackTraceString(failure))
        throw failure
    } finally {
        try {
            nativeParityOnMain(instrumentation) { try { boards?.dispose() } finally { host?.finish() } }
        } catch (cleanup: Throwable) {
            result.put("cleanup_error", android.util.Log.getStackTraceString(cleanup))
            if (error != null) error.addSuppressed(cleanup) else error = cleanup
        }
        result.put("completed_cases", completed).put("mismatched_cases", mismatched)
            .put("passed", error == null && completed == (if (Build.VERSION.SDK_INT >= 31) 4 else 2) && mismatched == 0)
        report.append("PARITY_EVIDENCE ").append(directory.name).append("/result.json\n")
        writeSwitchEvidence(directory, result, error)
        // Preserve the primary failure; do not silently discard a cleanup-only failure.
        if (result.has("cleanup_error") && !result.has("terminal_error")) throw checkNotNull(error)
    }
}

/**
 * Same-buffer comparison, without waitForIdle/draw helpers inside the motion interval.
 * Reports observed pairs only: PixelCopy does not identify the rendered frame timestamp.
 */
internal fun verifyNativeSwitchObservedMotion(instrumentation: Instrumentation, report: StringBuilder) {
    val context = instrumentation.targetContext
    check(context.packageName == "app.smallthingz.reverb.nativeparity.reliability")
    check(context.applicationContext.javaClass == Application::class.java)
    val directory = File(context.filesDir, "native-switch-motion-" + System.currentTimeMillis())
    check(directory.mkdir())
    val cases = JSONArray()
    val result = switchResult("Reference/native halves from one PixelCopy request; finite observed buffers only")
        .put("cases", cases).put("dynamic_frame_phase_qualified", false)
        .put("timing_limit", "Request/callback times are not compositor presentation times; duplicates/gaps are retained")
    var host: NativeParityActivity? = null
    var boards: SwitchParityBoards? = null
    var primary: Throwable? = null
    try {
        val activity = launchNativeParityActivity(instrumentation)
        host = activity
        awaitNativeParityDraw(instrumentation, activity.fixtureRoot)
        // Both directions and themes; current real animator scale is observed, never modified.
        for (rtl in listOf(false, true)) {
            val scene = SwitchScene(if (rtl) "dark-rtl" else "light-ltr",
                context.resources.displayMetrics.densityDpi, rtl, rtl, Build.VERSION.SDK_INT >= 31)
            requireNativeParityHost(instrumentation, activity)
            lateinit var current: SwitchParityBoards
            nativeParityOnMain(instrumentation) {
                current = SwitchParityBoards(activity, scene, motion = true)
                boards = current
            }
            val initial = captureNativeParityContent(instrumentation, activity) { current.showMotion() }
            val entry = JSONObject().put("name", scene.name)
                .put("animator_duration_scale", android.animation.ValueAnimator.getDurationScale().toDouble())
                .put("events", current.events).put("composition_acceptance", current.compositionAcceptance)
            cases.put(entry)
            try { saveNativeParityBitmap(directory, scene.name + "-initial.png", initial) } finally { initial.recycle() }
            val sampler = SwitchMotionSampler(activity, current)
            val captures = sampler.run(instrumentation)
            val comparisons = JSONArray()
            entry.put("samples", comparisons)
            try {
                var lastReferenceHash: String? = null
                var lastNativeHash: String? = null
                var differences = 0
                captures.forEachIndexed { index, sample ->
                    val bitmap = sample.bitmap
                    val reference = Bitmap.createBitmap(bitmap, 0, 0, current.paneWidth, current.paneHeight)
                    val native = Bitmap.createBitmap(bitmap, current.paneWidth, 0, current.paneWidth, current.paneHeight)
                    try {
                        val difference = compareNativeParityPixels(reference, native)
                        val referenceHash = difference.getString("reference_argb_sha256")
                        val nativeHash = difference.getString("candidate_argb_sha256")
                        difference.put("request_choreographer_ns", sample.requestFrameNs)
                            .put("request_system_ns", sample.requestSystemNs).put("callback_system_ns", sample.callbackSystemNs)
                            .put("same_as_previous_reference", referenceHash == lastReferenceHash)
                            .put("same_as_previous_native", nativeHash == lastNativeHash)
                        lastReferenceHash = referenceHash
                        lastNativeHash = nativeHash
                        comparisons.put(difference)
                        if (difference.getLong("different_pixels") != 0L) differences++
                        saveNativeParityBitmap(directory, scene.name + "-pair-" + index.toString().padStart(2, '0') + ".png", bitmap)
                    } finally { reference.recycle(); native.recycle() }
                }
                entry.put("captured_buffers", captures.size).put("observed_mismatched_pairs", differences)
                    .put("observed_pairs_equal", differences == 0 && captures.isNotEmpty())
                    .put("unqualified", "All-frame timing/latency, zero-scale motion, explicit external press/ripple, retained detach lifecycle")
                report.append("OBSERVED native_switch_motion ").append(scene.name)
                    .append(" buffers=").append(captures.size).append(" unequal_pairs=").append(differences).append('\n')
            } finally { captures.forEach { it.bitmap.recycle() } }
            // This is a post-interval image, not a proof of settlement at arbitrary animator scale.
            // It cannot excuse an intermediate mismatch.
            val final = captureNativeParityContent(instrumentation, activity) { current.showMotion() }
            try { saveNativeParityBitmap(directory, scene.name + "-final.png", final) } finally { final.recycle() }
            entry.put("post_interval_image_proves_settlement", false)
            nativeParityOnMain(instrumentation) { current.dispose(); boards = null }
            writeSwitchEvidence(directory, result, null)
        }
    } catch (failure: Throwable) {
        primary = failure
        result.put("terminal_error", android.util.Log.getStackTraceString(failure))
        throw failure
    } finally {
        try {
            nativeParityOnMain(instrumentation) { try { boards?.dispose() } finally { host?.finish() } }
        } catch (cleanup: Throwable) {
            result.put("cleanup_error", android.util.Log.getStackTraceString(cleanup))
            if (primary != null) primary.addSuppressed(cleanup) else primary = cleanup
        }
        // There is intentionally no blanket motion "passed" field.
        report.append("PARITY_EVIDENCE ").append(directory.name).append("/result.json\n")
        writeSwitchEvidence(directory, result, primary)
        if (result.has("cleanup_error") && !result.has("terminal_error")) throw checkNotNull(primary)
    }
}

private data class SwitchScene(val name: String, val densityDpi: Int, val dark: Boolean, val rtl: Boolean, val dynamic: Boolean)
private data class SwitchState(val checked: Boolean, val enabled: Boolean)
private val switchStates = listOf(SwitchState(false, true), SwitchState(true, true), SwitchState(false, false), SwitchState(true, false))

private class SwitchParityBoards(
    private val activity: NativeParityActivity,
    private val scene: SwitchScene,
    private val motion: Boolean,
) {
    private val configuration = Configuration(activity.resources.configuration).apply { densityDpi = scene.densityDpi }
    private val context = activity.createConfigurationContext(configuration)
    private val density = Density(context.resources.displayMetrics.density, configuration.fontScale)
    private val cellWidth = with(density) { 80.dp.roundToPx() }
    private val cellHeight = with(density) { 64.dp.roundToPx() }
    private val columns = if (motion) 1 else 4
    private val rows = if (motion) 1 else 2
    val paneWidth = cellWidth * columns
    val paneHeight = cellHeight * rows
    private val colors: ColorScheme = when {
        scene.dynamic && scene.dark -> dynamicDarkColorScheme(context)
        scene.dynamic -> dynamicLightColorScheme(context)
        scene.dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    private val palette = if (scene.dynamic) ReverbPalette.resolve(context, scene.dark) else ReverbPalette.fallback(scene.dark)
    private var states by mutableStateOf(if (motion) listOf(switchStates.first()) else switchStates + switchStates)
    private val referenceSizes = Array(states.size) { IntArray(2) }
    val events = JSONArray()
    val compositionAcceptance = JSONArray()
    private val reference = ComposeView(context)
    private val native = NativeSwitchBoard(context, palette, states, columns, cellWidth, cellHeight)
    private val container = FrameLayout(context)
    private val direction = if (scene.rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR

    init {
        val totalWidth = paneWidth * if (motion) 2 else 1
        check(totalWidth <= activity.fixtureRoot.width && paneHeight <= activity.fixtureRoot.height)
        // Match AndroidComposeView's root policy for both backdrop and child glyphs.
        if (Build.VERSION.SDK_INT >= 29) container.isForceDarkAllowed = false
        container.setBackgroundColor(colors.surface.toArgb())
        container.layoutDirection = direction
        reference.layoutDirection = direction
        native.layoutDirection = direction
        reference.setContent {
            val current = states
            CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides configuration,
                LocalDensity provides density,
                LocalLayoutDirection provides if (scene.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                MaterialTheme(colorScheme = colors) {
                    SideEffect {
                        if (motion) compositionAcceptance.put(JSONObject().put("system_ns", System.nanoTime())
                            .put("checked", current.single().checked).put("enabled", current.single().enabled))
                    }
                    Layout(content = {
                        current.forEachIndexed { i, state ->
                            val background = if (i / columns == 0) colors.surface else colors.surfaceContainerLow
                            Box(Modifier.fillMaxSize().background(background), contentAlignment = Alignment.Center) {
                                // Exactly the released call shape. No interactionSource, thumb content or callback.
                                Switch(state.checked, onCheckedChange = null, enabled = state.enabled,
                                    modifier = Modifier.onGloballyPositioned {
                                        referenceSizes[i][0] = it.size.width
                                        referenceSizes[i][1] = it.size.height
                                    })
                            }
                        }
                    }) { measurables, _ ->
                        val children = measurables.map { it.measure(Constraints.fixed(cellWidth, cellHeight)) }
                        layout(paneWidth, paneHeight) {
                            children.forEachIndexed { i, child -> child.place((i % columns) * cellWidth, (i / columns) * cellHeight) }
                        }
                    }
                }
            }
        }
        activity.fixtureRoot.removeAllViews()
        container.addView(reference, FrameLayout.LayoutParams(paneWidth, paneHeight, Gravity.LEFT or Gravity.TOP))
        container.addView(native, FrameLayout.LayoutParams(paneWidth, paneHeight, Gravity.LEFT or Gravity.TOP).apply {
            leftMargin = if (motion) paneWidth else 0
        })
        activity.fixtureRoot.addView(container, FrameLayout.LayoutParams(totalWidth, paneHeight, Gravity.CENTER))
    }

    fun showStatic(compose: Boolean): View {
        check(!motion && activity.hasWindowFocus())
        reference.visibility = if (compose) View.VISIBLE else View.GONE
        native.visibility = if (compose) View.GONE else View.VISIBLE
        return container
    }

    fun showMotion(): View {
        check(motion && activity.hasWindowFocus())
        reference.visibility = View.VISIBLE
        native.visibility = View.VISIBLE
        return container
    }

    fun motionTarget(next: SwitchState, frameNs: Long, eventIndex: Int) {
        check(motion)
        // Both targets are assigned in one UI callback. Source/native acceptance phase is under test.
        events.put(JSONObject().put("index", eventIndex).put("frame_ns", frameNs).put("system_ns", System.nanoTime())
            .put("checked", next.checked).put("enabled", next.enabled).put("assignment_order", "compose-state then native-state"))
        states = listOf(next)
        native.update(next)
    }

    fun geometry(): JSONObject {
        val requiredWidth = with(density) { 52.dp.roundToPx() }
        val requiredHeight = with(density) { 32.dp.roundToPx() }
        val source = JSONArray(referenceSizes.map { JSONArray(it.toList()) })
        val actual = JSONArray()
        var equal = referenceSizes.all { it[0] == requiredWidth && it[1] == requiredHeight }
        for (i in 0 until native.childCount) {
            val child = native.getChildAt(i)
            val ownTarget = child.isClickable || child.isFocusable ||
                child.importantForAccessibility != View.IMPORTANT_FOR_ACCESSIBILITY_NO
            actual.put(JSONArray(listOf(child.measuredWidth, child.measuredHeight, ownTarget)))
            equal = equal && child.measuredWidth == requiredWidth && child.measuredHeight == requiredHeight && !ownTarget
        }
        return JSONObject().put("equal", equal).put("required_width", requiredWidth).put("required_height", requiredHeight)
            .put("reference_sizes", source).put("native_width_height_own_target", actual)
            .put("scope", "Decorative glyph only; parent row semantics/keyboard/48dp target are separate")
    }

    fun rectangleInWindow(): Rect {
        // Motion legitimately requests child layout before this traversal. The fixture's own
        // fixed board bounds do not change; requiring !isLayoutRequested would reject all motion.
        check(container.isLaidOut && container.isShown && container.isHardwareAccelerated)
        check(container.width == paneWidth * 2 && container.height == paneHeight)
        val location = IntArray(2)
        container.getLocationInWindow(location)
        return Rect(location[0], location[1], location[0] + container.width, location[1] + container.height)
    }

    fun dispose() { reference.disposeComposition(); activity.fixtureRoot.removeView(container) }
}

private class NativeSwitchBoard(
    context: Context, palette: ReverbPalette, states: List<SwitchState>,
    private val columns: Int, private val cellWidth: Int, private val cellHeight: Int,
) : ViewGroup(context) {
    private val paint = Paint()
    private val backgrounds = intArrayOf(palette.surface, palette.surfaceContainerLow)
    private val glyphs = states.map { ReverbMaterialSwitchView(context, palette, it.checked, it.enabled) }
    init { setWillNotDraw(false); glyphs.forEach { addView(it) } }
    fun update(state: SwitchState) { check(glyphs.size == 1); glyphs.single().setState(state.checked, state.enabled) }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        glyphs.forEach { it.measure(MeasureSpec.makeMeasureSpec(cellWidth, MeasureSpec.AT_MOST),
            MeasureSpec.makeMeasureSpec(cellHeight, MeasureSpec.AT_MOST)) }
        setMeasuredDimension(cellWidth * columns, cellHeight * (glyphs.size / columns))
    }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        glyphs.forEachIndexed { i, glyph ->
            val x = (i % columns) * cellWidth + ((cellWidth - glyph.measuredWidth) / 2f).roundToInt()
            val y = (i / columns) * cellHeight + ((cellHeight - glyph.measuredHeight) / 2f).roundToInt()
            glyph.layout(x, y, x + glyph.measuredWidth, y + glyph.measuredHeight)
        }
    }
    override fun onDraw(canvas: Canvas) {
        for (row in 0 until glyphs.size / columns) {
            paint.color = backgrounds[row]
            canvas.drawRect(0f, (row * cellHeight).toFloat(), width.toFloat(), ((row + 1) * cellHeight).toFloat(), paint)
        }
    }
}

private data class SwitchFrame(val bitmap: Bitmap, val requestFrameNs: Long, val requestSystemNs: Long, val callbackSystemNs: Long)

private class SwitchMotionSampler(private val activity: NativeParityActivity, private val boards: SwitchParityBoards) :
    Choreographer.FrameCallback {
    private val done = CountDownLatch(1)
    private val handler = Handler(Looper.getMainLooper())
    private val captures = mutableListOf<SwitchFrame>()
    private var startedNs = Long.MIN_VALUE
    private var event = 0
    private var running = false
    private var pending = false
    private var failure: Throwable? = null
    private val offsets = longArrayOf(0, 70_000_000, 140_000_000, 300_000_000)
    private val targets = listOf(SwitchState(true, true), SwitchState(false, true),
        SwitchState(true, false), SwitchState(false, true))

    fun run(instrumentation: Instrumentation): List<SwitchFrame> {
        requireNativeParityHost(instrumentation, activity)
        nativeParityOnMain(instrumentation) { running = true; Choreographer.getInstance().postFrameCallback(this) }
        try {
            check(done.await(5, TimeUnit.SECONDS)) { "Switch motion sampling timed out" }
            failure?.let { throw it }
            requireNativeParityHost(instrumentation, activity)
            check(event == targets.size && captures.isNotEmpty())
            return captures.toList()
        } catch (error: Throwable) {
            var primary = error
            nativeParityOnMain(instrumentation) {
                // A pending PixelCopy can outlive an earlier ownership failure. If waiting for
                // that callback times out, keep the earlier cause and suppress the timeout.
                failure?.takeIf { it !== error }?.let { it.addSuppressed(error); primary = it }
                running = false
                Choreographer.getInstance().removeFrameCallback(this)
                captures.forEach { it.bitmap.recycle() }
                captures.clear()
                // Pending PixelCopy retains its destination; its callback recycles after seeing !running.
            }
            throw primary
        }
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        try {
            check(activity.hasWindowFocus() && !activity.isFinishing && activity.fixtureRoot.isShown &&
                activity.fixtureRoot.display?.displayId == Display.DEFAULT_DISPLAY) {
                "Own QA host lost focus/physical visibility; motion sampling stopped without reclaiming focus"
            }
            if (startedNs == Long.MIN_VALUE) startedNs = frameTimeNanos
            val elapsed = frameTimeNanos - startedNs
            while (event < offsets.size && elapsed >= offsets[event]) {
                boards.motionTarget(targets[event], frameTimeNanos, event)
                event++
            }
            if (elapsed >= 1_000_000_000L) {
                running = false
                if (!pending) done.countDown()
                return
            }
            Choreographer.getInstance().postFrameCallback(this)
            if (pending || captures.size >= 64) return
            val rectangle = boards.rectangleInWindow()
            val bitmap = Bitmap.createBitmap(rectangle.width(), rectangle.height(), Bitmap.Config.ARGB_8888)
            val requestNs = System.nanoTime()
            pending = true
            try {
                PixelCopy.request(activity.window, rectangle, bitmap, { status ->
                    pending = false
                    if (status != PixelCopy.SUCCESS) {
                        bitmap.recycle()
                        val copyFailure = IllegalStateException("Motion PixelCopy failed: " + status)
                        if (failure == null) failure = copyFailure else failure?.addSuppressed(copyFailure)
                        running = false
                        Choreographer.getInstance().removeFrameCallback(this)
                        done.countDown()
                    } else if (!running) {
                        // End/timeout/ownership failure: do not append a late unowned sample.
                        bitmap.recycle()
                        done.countDown()
                    } else captures += SwitchFrame(bitmap, frameTimeNanos, requestNs, System.nanoTime())
                }, handler)
            } catch (error: Throwable) {
                pending = false
                bitmap.recycle()
                throw error
            }
        } catch (error: Throwable) {
            if (failure == null) failure = error else failure?.addSuppressed(error)
            running = false
            Choreographer.getInstance().removeFrameCallback(this)
            if (!pending) done.countDown()
        }
    }
}

private fun writeSwitchEvidence(directory: File, result: JSONObject, primaryFailure: Throwable?) {
    try { File(directory, "result.json").writeText(result.toString(2)) }
    catch (writeFailure: Throwable) {
        if (primaryFailure == null) throw writeFailure
        primaryFailure.addSuppressed(writeFailure)
    }
}

private fun switchResult(comparison: String): JSONObject = JSONObject()
    .put("baseline_commit", "3e16f8ddb78dca15df4b436d34d330801ce83ffd")
    .put("reference", "Pinned Material3 1.4.0 Switch(checked,onCheckedChange=null,enabled); standard MotionScheme")
    .put("candidate", "ReverbMaterialSwitchView; no production UI wiring")
    .put("comparison", comparison).put("sdk", Build.VERSION.SDK_INT)
    .put("device", Build.MANUFACTURER + "/" + Build.MODEL)
