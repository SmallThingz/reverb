package app.smallthingz.reverb

import android.app.Application
import android.app.ActivityOptions
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.Display
import android.view.PixelCopy
import android.view.View
import android.view.ViewTreeObserver
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.floor

private const val PARITY_BASELINE = "3e16f8ddb78dca15df4b436d34d330801ce83ffd"
private const val PARITY_ORACLE_SHA256 = "807f2fdde7f224c91153de4b74c5c03c0713bdf41689a58c984951fad1d51952"

/** Exact, unmasked app-pixel comparison. Never launches the normal application Activity. */
internal fun verifyNativeGroupParity(instrumentation: Instrumentation, report: StringBuilder) {
    val context = instrumentation.targetContext
    check(context.packageName == "app.smallthingz.reverb.nativeparity.reliability")
    check(context.applicationContext.javaClass == Application::class.java) {
        "native_group_parity requires -PreverbNativeParity=true"
    }
    val directory = File(context.filesDir, "native-group-parity-${System.currentTimeMillis()}")
    check(directory.mkdir()) { "Cannot create fresh parity evidence directory" }
    val cases = JSONArray()
    val result = JSONObject()
        .put("baseline_commit", PARITY_BASELINE)
        .put("oracle_sha256", PARITY_ORACLE_SHA256)
        .put("package", context.packageName)
        .put("sdk", Build.VERSION.SDK_INT)
        .put("device", "${Build.MANUFACTURER}/${Build.MODEL}")
        .put("font_scale", context.resources.configuration.fontScale.toDouble())
        .put("comparison", "Exact ARGB pixels; no masks or tolerance")
        .put("cases", cases)
    result.put("passed", false)
    writeParityEvidence(directory, result, null)
    var activity: NativeParityActivity? = null
    var completed = 0
    var mismatches = 0
    var terminalFailure: Throwable? = null
    try {
        // Regression: a failing main-thread assertion must return to this caller, retain exact
        // throwable identity, and leave main alive for subsequent cleanup/draw work.
        val expectedDispatchFailure = IllegalStateException("Synthetic fixture dispatch failure")
        val returnedFailure = runCatching {
            nativeParityOnMain(instrumentation) { throw expectedDispatchFailure }
        }.exceptionOrNull()
        check(returnedFailure === expectedDispatchFailure)
        check(nativeParityOnMain(instrumentation) { Looper.myLooper() == Looper.getMainLooper() })
        report.append("PASS native_parity_main_dispatch exception handoff and surviving main thread\n")
        activity = launchNativeParityActivity(instrumentation)
        awaitNativeParityDraw(instrumentation, activity.fixtureRoot)
        requireNativeParityHost(instrumentation, activity)
        val currentDensity = context.resources.displayMetrics.densityDpi
        val densities = listOf(currentDensity, 160).distinct()
        for (densityDpi in densities) {
            val density = densityDpi / 160f
            val maxWidthDp = floor(activity.fixtureRoot.width / density).toInt()
            check(maxWidthDp >= 192) { "Actual device viewport too narrow for group fixture" }
            val widths = listOf(minOf(240, maxWidthDp), minOf(319, maxWidthDp)).distinct()
            for (dark in listOf(false, true)) for (rtl in listOf(false, true)) for (width in widths) {
                val fixture = NativeGroupCase(dark, rtl, densityDpi, width)
                val entry = JSONObject().put("name", fixture.name)
                    .put("dark", dark).put("rtl", rtl).put("density_dpi", densityDpi).put("width_dp", width)
                cases.put(entry)
                var reference: Bitmap? = null
                var candidate: Bitmap? = null
                var referenceAgain: Bitmap? = null
                var candidateAgain: Bitmap? = null
                var caseFailure: Throwable? = null
                try {
                    reference = renderAndCapture(instrumentation, activity, fixture, true)
                    saveNativeParityBitmap(directory, fixture.name + "-compose.png", reference)
                    candidate = renderAndCapture(instrumentation, activity, fixture, false)
                    saveNativeParityBitmap(directory, fixture.name + "-native.png", candidate)
                    referenceAgain = renderAndCapture(instrumentation, activity, fixture, true)
                    saveNativeParityBitmap(directory, fixture.name + "-compose-repeat.png", referenceAgain)
                    candidateAgain = renderAndCapture(instrumentation, activity, fixture, false)
                    saveNativeParityBitmap(directory, fixture.name + "-native-repeat.png", candidateAgain)
                    val referenceStability = compareNativeParityPixels(reference, referenceAgain)
                    val nativeStability = compareNativeParityPixels(candidate, candidateAgain)
                    val diff = compareNativeParityPixels(reference, candidate)
                    entry.put("width_px", reference.width).put("height_px", reference.height)
                        .put("reference_stability", referenceStability)
                        .put("native_stability", nativeStability)
                        .put("difference", diff)
                        .put("palette", "All native stored color roles plus chrome aliases equal Compose")
                        .put("legacy_palette_oracle", activity.legacyPaletteOracleStatus)
                    val passed = referenceStability.getLong("different_pixels") == 0L &&
                        nativeStability.getLong("different_pixels") == 0L &&
                        diff.getLong("different_pixels") == 0L
                    entry.put("passed", passed)
                    completed++
                    if (!passed) mismatches++
                    report.append(if (passed) "PASS " else "FAIL ").append("native_group_parity ")
                        .append(fixture.name).append(" differing_pixels=")
                        .append(diff.getLong("different_pixels")).append('\n')
                } catch (error: Throwable) {
                    caseFailure = error
                    entry.put("passed", false).put("error", android.util.Log.getStackTraceString(error))
                    throw error
                } finally {
                    reference?.recycle()
                    candidate?.recycle()
                    referenceAgain?.recycle()
                    candidateAgain?.recycle()
                    writeParityEvidence(directory, result, caseFailure)
                }
            }
        }
        check(completed > 0 && mismatches == 0) {
            "Exact group parity failed: $mismatches of $completed cases; evidence=${directory.name}"
        }
    } catch (error: Throwable) {
        terminalFailure = error
        result.put("terminal_error", android.util.Log.getStackTraceString(error))
        throw error
    } finally {
        var cleanupFailure: Throwable? = null
        try {
            activity?.let { host -> nativeParityOnMain(instrumentation) { host.finish() } }
        } catch (error: Throwable) {
            cleanupFailure = error
            terminalFailure?.addSuppressed(error)
            result.put("cleanup_error", android.util.Log.getStackTraceString(error))
        }
        result.put("completed_cases", completed).put("mismatched_cases", mismatches)
            .put("passed", completed > 0 && mismatches == 0 && terminalFailure == null && cleanupFailure == null)
        report.append("PARITY_EVIDENCE ").append(directory.name).append("/result.json\n")
        writeParityEvidence(directory, result, terminalFailure ?: cleanupFailure)
        if (terminalFailure == null) cleanupFailure?.let { throw it }
    }
}

internal fun launchNativeParityActivity(instrumentation: Instrumentation): NativeParityActivity {
    val context = instrumentation.targetContext
    check(context.packageName == "app.smallthingz.reverb.nativeparity.reliability")
    val options = ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY)
    return instrumentation.startActivitySync(
        Intent(context, NativeParityActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        options.toBundle(),
    ) as NativeParityActivity
}

private fun writeParityEvidence(directory: File, result: JSONObject, primaryFailure: Throwable?) {
    try {
        writeQaEvidenceAtomically(File(directory, "result.json")) { output ->
            output.write(result.toString(2).toByteArray(Charsets.UTF_8))
        }
    } catch (error: Throwable) {
        if (primaryFailure == null) throw error
        if (primaryFailure !== error) primaryFailure.addSuppressed(error)
    }
}

private fun renderAndCapture(
    instrumentation: Instrumentation,
    activity: NativeParityActivity,
    case: NativeGroupCase,
    compose: Boolean,
): Bitmap {
    return captureNativeParityContent(instrumentation, activity) { activity.showCase(case, compose) }
}

internal fun captureNativeParityContent(
    instrumentation: Instrumentation,
    activity: NativeParityActivity,
    render: () -> View,
): Bitmap {
    requireNativeParityHost(instrumentation, activity, phase = "capture-before-render", beginCapture = true)
    lateinit var content: View
    nativeParityOnMain(instrumentation) { content = render() }
    instrumentation.waitForIdleSync()
    awaitNativeParityDraw(instrumentation, content)
    // A second draw and frame boundary lets queued hardware rendering settle. Repeated
    // reference/native captures above independently reject a nondeterministic result.
    awaitNativeParityDraw(instrumentation, content)
    requireNativeParityHost(instrumentation, activity, phase = "capture-after-draw")
    val rectangle = Rect()
    nativeParityOnMain(instrumentation) {
        check(content.isLaidOut && !content.isLayoutRequested && content.isShown && content.isHardwareAccelerated)
        val location = IntArray(2)
        content.getLocationInWindow(location)
        rectangle.set(location[0], location[1], location[0] + content.width, location[1] + content.height)
        check(rectangle.width() > 0 && rectangle.height() > 0)
    }
    val bitmap = Bitmap.createBitmap(rectangle.width(), rectangle.height(), Bitmap.Config.ARGB_8888)
    val copied = CountDownLatch(1)
    var status = Int.MIN_VALUE
    var submitted = false
    var recycleOnCompletion = false
    try {
        nativeParityOnMain(instrumentation) {
            PixelCopy.request(activity.window, rectangle, bitmap, { result ->
                status = result
                copied.countDown()
                if (recycleOnCompletion) bitmap.recycle()
            }, Handler(Looper.getMainLooper()))
            submitted = true
        }
        check(copied.await(5, TimeUnit.SECONDS)) { "PixelCopy timed out" }
        check(status == PixelCopy.SUCCESS) { "PixelCopy failed: $status" }
        requireNativeParityHost(instrumentation, activity, phase = "capture-after-pixelcopy")
        return bitmap
    } catch (error: Throwable) {
        try {
            nativeParityOnMain(instrumentation) {
                // A timeout does not cancel PixelCopy. Keep its destination alive until its
                // callback, instead of letting a late native write target a recycled bitmap.
                if (!submitted || copied.count == 0L) bitmap.recycle() else recycleOnCompletion = true
            }
        } catch (cleanup: Throwable) {
            error.addSuppressed(cleanup)
        }
        throw error
    }
}

internal fun requireNativeParityHost(
    instrumentation: Instrumentation,
    activity: NativeParityActivity,
    phase: String = "host-check",
    beginCapture: Boolean = false,
) {
    nativeParityOnMain(instrumentation) {
        activity.recordParityHostCheck(phase, beginCapture)
        val finishing = activity.isFinishing
        val windowFocus = activity.hasWindowFocus()
        val shown = activity.fixtureRoot.isShown
        check(!finishing && windowFocus && shown) {
            "QA host is no longer the focused visible Activity; stop without reclaiming focus " +
                "(finishing=$finishing, ownWindowFocus=$windowFocus, shown=$shown, " +
                "display=${activity.fixtureRoot.display?.displayId}, phase=$phase, ${activity.parityFocusDiagnostics()})"
        }
        check(activity.fixtureRoot.display?.displayId == Display.DEFAULT_DISPLAY) {
            "Parity fixture requires the actual physical display (phase=$phase, ${activity.parityFocusDiagnostics()})"
        }
    }
}

internal fun awaitNativeParityDraw(instrumentation: Instrumentation, view: View) {
    val drawn = CountDownLatch(1)
    lateinit var listener: ViewTreeObserver.OnDrawListener
    nativeParityOnMain(instrumentation) {
        listener = ViewTreeObserver.OnDrawListener {
            // Removal during dispatch is forbidden; schedule it after this traversal.
            view.post {
                if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnDrawListener(listener)
                Choreographer.getInstance().postFrameCallback { drawn.countDown() }
            }
        }
        view.viewTreeObserver.addOnDrawListener(listener)
        view.invalidate()
    }
    try {
        check(drawn.await(5, TimeUnit.SECONDS)) { "No completed UI draw/frame boundary" }
    } finally {
        nativeParityOnMain(instrumentation) {
            if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnDrawListener(listener)
        }
    }
}

internal fun saveNativeParityBitmap(directory: File, name: String, bitmap: Bitmap) {
    File(directory, name).outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
}

internal fun compareNativeParityPixels(reference: Bitmap, candidate: Bitmap): JSONObject {
    check(reference.width == candidate.width && reference.height == candidate.height) {
        "Layout size differs: ${reference.width}x${reference.height} vs ${candidate.width}x${candidate.height}"
    }
    val expected = IntArray(reference.width * reference.height)
    val actual = IntArray(expected.size)
    reference.getPixels(expected, 0, reference.width, 0, 0, reference.width, reference.height)
    candidate.getPixels(actual, 0, candidate.width, 0, 0, candidate.width, candidate.height)
    var different = 0L
    var left = reference.width
    var top = reference.height
    var right = -1
    var bottom = -1
    var maxChannelDelta = 0
    for (index in expected.indices) {
        if (expected[index] == actual[index]) continue
        different++
        val x = index % reference.width
        val y = index / reference.width
        left = minOf(left, x); right = maxOf(right, x)
        top = minOf(top, y); bottom = maxOf(bottom, y)
        val first = expected[index]
        val second = actual[index]
        maxChannelDelta = maxOf(maxChannelDelta,
            abs(Color.alpha(first) - Color.alpha(second)), abs(Color.red(first) - Color.red(second)),
            abs(Color.green(first) - Color.green(second)), abs(Color.blue(first) - Color.blue(second)))
    }
    return JSONObject().put("different_pixels", different)
        .put("total_pixels", expected.size).put("max_channel_delta", maxChannelDelta)
        .put("difference_bounds_inclusive", if (different == 0L) JSONObject.NULL else JSONArray(listOf(left, top, right, bottom)))
        .put("reference_argb_sha256", nativeParityPixelHash(expected)).put("candidate_argb_sha256", nativeParityPixelHash(actual))
}

internal fun nativeParityPixelHash(pixels: IntArray): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val bytes = ByteBuffer.allocate(minOf(pixels.size, 16 * 1024) * Int.SIZE_BYTES)
    val integers = bytes.asIntBuffer() // ByteBuffer's default big-endian ARGB order is evidence format.
    var offset = 0
    while (offset < pixels.size) {
        val count = minOf(integers.capacity(), pixels.size - offset)
        integers.clear()
        integers.put(pixels, offset, count)
        digest.update(bytes.array(), 0, count * Int.SIZE_BYTES)
        offset += count
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
