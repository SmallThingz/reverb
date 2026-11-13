package app.smallthingz.reverb

import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import app.smallthingz.reverb.nativeui.LibraryExpansionMotion
import app.smallthingz.reverb.nativeui.LibraryExpansionViewport
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** Actual production motion/viewport pixels, with the former standard-dispatch behavior beside it. */
internal fun verifyNativeLibraryExpansionClipping(instrumentation: Instrumentation, report: StringBuilder) {
    val context = instrumentation.targetContext
    check(context.packageName == "app.smallthingz.reverb.uitheme.reliability")
    check(context.applicationContext.javaClass == Application::class.java)
    val directory = File(context.filesDir, "native-library-expansion-clipping-${System.currentTimeMillis()}")
    check(directory.mkdir())
    val cases = JSONArray()
    val result = JSONObject().put("selector", "native_library_expansion_clipping")
        .put("sdk", Build.VERSION.SDK_INT).put("passed", false).put("cases", cases)
        .put("negative_control", "Former zero-padding standard ViewGroup dispatch and child flags; not an old APK execution")
    var host: NativeParityActivity? = null
    var failure: Throwable? = null
    fun <T> main(block: () -> T) = nativeParityOnMain(instrumentation, block)
    try {
        val activity = launchCurrentUiSmokeActivity(instrumentation).also { host = it }
        awaitNativeParityDraw(instrumentation, activity.fixtureRoot)
        val fixture = main {
            ExpansionClipBoard(activity).also {
                check(activity.fixtureRoot.width >= it.boardWidth && activity.fixtureRoot.height >= it.boardHeight)
                activity.fixtureRoot.removeAllViews()
                activity.fixtureRoot.addView(it, FrameLayout.LayoutParams(it.boardWidth, it.boardHeight))
            }
        }
        var time = 1_000_000_000L
        fun pose(name: String, millis: Int, visible: Boolean? = null, scale: Float = 1f) {
            val entry = JSONObject().put("name", name)
            cases.put(entry)
            main {
                visible?.let(fixture.motion::setVisible)
                fixture.layoutPose()
                time += millis * 1_000_000L
                fixture.motion.frame(time, scale)
                fixture.layoutPose()
                entry.put("viewport_height", fixture.viewport.height)
                    .put("layer_top", fixture.viewport.getChildAt(0).top)
                    .put("layer_height", fixture.viewport.getChildAt(0).height)
                    .put("alpha", fixture.motion.alpha.toDouble())
                val bitmap = Bitmap.createBitmap(fixture.boardWidth, fixture.boardHeight, Bitmap.Config.ARGB_8888)
                try {
                    fixture.draw(Canvas(bitmap))
                    saveNativeParityBitmap(directory, "$name-software.png", bitmap)
                    entry.put("software", fixture.verifyPixels(bitmap))
                } finally { bitmap.recycle() }
            }
            val hardwareBefore = main { fixture.productionPaint.hardwareDraws to fixture.controlPaint.hardwareDraws }
            val bitmap = captureNativeParityContent(instrumentation, activity) {
                fixture.productionPaint.invalidate()
                fixture.controlPaint.invalidate()
                fixture.invalidate()
                fixture
            }
            try {
                saveNativeParityBitmap(directory, "$name-hardware.png", bitmap)
                main {
                    // HWUI may cull transparent/empty subtrees. Visible poses must record fresh
                    // hardware draws; culled poses still have the independent PixelCopy oracle.
                    if (fixture.viewport.height > 0 && fixture.motion.alpha > 0.01f) {
                        check(fixture.productionPaint.hardwareDraws > hardwareBefore.first &&
                            fixture.controlPaint.hardwareDraws > hardwareBefore.second)
                    }
                    entry.put("hardware", fixture.verifyPixels(bitmap))
                        .put("production_hardware_draws", fixture.productionPaint.hardwareDraws - hardwareBefore.first)
                        .put("control_hardware_draws", fixture.controlPaint.hardwareDraws - hardwareBefore.second)
                }
            } finally { bitmap.recycle() }
        }
        pose("enter-start", 0, true)
        pose("enter-80", 80)
        pose("enter-160", 80)
        pose("enter-240", 80)
        pose("entered", 160)
        pose("exit-start", 0, false)
        pose("exit-48", 48)
        pose("exit-96", 48)
        pose("reverse-start", 0, true)
        pose("reverse-48", 48)
        pose("reverse-160", 112)
        pose("reopened", 1000)
        pose("zero-exit", 0, false, 0f)
        pose("zero-enter", 0, true, 0f)
        val measured = (0 until cases.length()).map(cases::getJSONObject)
        check(measured.count { it.getInt("layer_top") < 0 && it.getDouble("alpha") > 0.01 } >= 4)
        check(measured.single { it.getString("name") == "zero-exit" }.getInt("viewport_height") == 0)
        check(measured.single { it.getString("name") == "zero-enter" }.getInt("viewport_height") == 160)
        result.put("passed", true)
    } catch (error: Throwable) { failure = error }
    finally {
        failure = captureNativeParityCleanup(failure) { main { host?.fixtureRoot?.removeAllViews(); host?.finish() } }
        if (failure != null) result.put("passed", false).put("failure", failure!!.stackTraceToString())
        failure = captureNativeParityCleanup(failure) {
            writeQaEvidenceAtomically(File(directory, "result.json")) { it.write(result.toString(2).toByteArray()) }
        }
        report.appendLine("LIBRARY_EXPANSION_CLIPPING_EVIDENCE ${directory.absolutePath}")
    }
    failure?.let { throw it }
    report.appendLine("PASS native_library_expansion_clipping software and hardware viewport pixels; enter, exit, reversal, zero scale and old-dispatch negative control")
}

private class ExpansionClipPaint(context: Context) : View(context) {
    private val paint = Paint().apply { color = Color.MAGENTA }
    var hardwareDraws = 0
        private set
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(resolveSize(160, widthMeasureSpec), resolveSize(160, heightMeasureSpec))
    }
    override fun onDraw(canvas: Canvas) {
        if (canvas.isHardwareAccelerated) hardwareDraws++
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }
}

private class ExpansionClipBoard(context: Context) : FrameLayout(context) {
    val boardWidth = 384
    val boardHeight = 360
    val motion = LibraryExpansionMotion(false)
    val productionPaint = ExpansionClipPaint(context)
    val controlPaint = ExpansionClipPaint(context)
    val viewport = LibraryExpansionViewport(context, { motion }, {}).apply { setContent(productionPaint) }
    private val controlLayer = FrameLayout(context).apply {
        clipChildren = false
        clipToPadding = false
        addView(controlPaint, LayoutParams(-1, -2))
    }
    // Same child hierarchy and clipping flags as the former viewport, without its new dispatch clip.
    // Placement is copied from the measured production layer, so this is not a second motion model.
    private val control = object : ViewGroup(context) {
        init { clipChildren = true; clipToPadding = true; addView(controlLayer) }
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            controlLayer.measure(MeasureSpec.makeMeasureSpec(160, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(160, MeasureSpec.EXACTLY))
            setMeasuredDimension(viewport.measuredWidth, viewport.measuredHeight)
        }
        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            val actual = viewport.getChildAt(0)
            controlLayer.layout(actual.left, actual.top, actual.right, actual.bottom)
        }
    }
    init {
        clipChildren = false
        clipToPadding = false
        setBackgroundColor(Color.WHITE)
        addView(viewport, LayoutParams(160, -2).apply { leftMargin = 16; topMargin = 180 })
        addView(control, LayoutParams(160, -2).apply { leftMargin = 208; topMargin = 180 })
    }
    fun layoutPose() {
        viewport.forceLayout()
        control.forceLayout()
        forceLayout()
        measure(MeasureSpec.makeMeasureSpec(boardWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(boardHeight, MeasureSpec.EXACTLY))
        layout(0, 0, boardWidth, boardHeight)
        controlLayer.alpha = motion.alpha
        check(viewport.width == 160 && viewport.getChildAt(0).height == 160)
        check(control.width == viewport.width && control.height == viewport.height)
        check(viewport.getChildAt(0).top == viewport.height - 160)
        check(paddingTop == 0 && !clipChildren && !clipToPadding)
        invalidate()
    }
    fun verifyPixels(bitmap: Bitmap): JSONObject {
        check(bitmap.width == boardWidth && bitmap.height == boardHeight)
        fun counts(left: Int): Pair<Int, Int> {
            var inside = 0
            var outside = 0
            for (y in 0 until boardHeight) for (x in left until left + 160) {
                if (bitmap.getPixel(x, y) == Color.WHITE) continue
                if (y >= 180 && y < 180 + viewport.height) inside++ else outside++
            }
            return inside to outside
        }
        val (inside, outside) = counts(16)
        val (controlInside, controlOutside) = counts(208)
        val negativeVisible = viewport.getChildAt(0).top < 0 && motion.alpha > 0.01f
        check(outside == 0) { "Production viewport painted $outside pixels outside its animated bounds" }
        if (viewport.height > 0 && motion.alpha > 0.01f) check(inside > 0 && controlInside > 0)
        if (negativeVisible) check(controlOutside > 0) { "Negative control did not reproduce the old dispatch overflow" }
        if (viewport.height == 0) check(inside == 0 && outside == 0)
        return JSONObject().put("inside_painted", inside).put("outside_painted", outside)
            .put("control_inside_painted", controlInside).put("control_outside_painted", controlOutside)
            .put("negative_control_required", negativeVisible)
    }
}
