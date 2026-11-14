package app.smallthingz.reverb

import smallthingz.ui.components.NativeModalWindowRuntime

import smallthingz.ui.components.NativeGroupEdges
import android.content.Context
import android.app.Application
import android.app.Instrumentation
import android.view.View
import android.widget.LinearLayout
import app.smallthingz.reverb.nativeui.*
import kotlinx.coroutines.CoroutineScope
import smallthingz.ui.MotionDurationScale
import kotlin.math.roundToInt

/** Narrow current-QA entry: no Activity, automation, storage, or audio owner is needed. */
internal fun verifyNativeLibraryExpansionMeasurement(instrumentation: Instrumentation, report: StringBuilder) {
    val context = instrumentation.targetContext
    check(context.packageName == "app.smallthingz.reverb.uitheme.reliability")
    check(context.applicationContext.javaClass == Application::class.java)
    nativeParityOnMain(instrumentation) {
        val runtime = NativeModalWindowRuntime(context)
        try {
            val environment = nativeParityEnvironment(context,
                androidx.compose.ui.unit.Density(context.resources.displayMetrics.density,
                    context.resources.configuration.fontScale), ReverbPalette.resolve(context, false))
            verifyNativeLibraryExpansionMeasurement(context, environment, runtime.scope, View(context))
        } finally {
            runtime.close()
        }
    }
    report.appendLine("PASS native_library_expansion_measurement: actual row/surface/sibling enter, exit, reversal, zero scale, removal, recycle, intrinsic resize, parent constraints")
}

/** Real Android measurement/layout regression; inert Views never acquire a player or repository. */
internal fun verifyNativeLibraryExpansionMeasurement(
    context: Context,
    environment: ReverbViewEnvironment,
    scope: CoroutineScope,
    inputRoot: View,
) {
    val scale = object : MotionDurationScale { override var scaleFactor = 1f }
    val row = ReverbLibraryRecordingRow(context, environment,
        CoroutineScope(scope.coroutineContext + scale), inputRoot)
    fun dp(value: Int) = (value * environment.textDensity.density).roundToInt()
    fun fixed(height: Int) = object : View(context) {
        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            setMeasuredDimension(resolveSize(dp(16), widthSpec), resolveSize(height, heightSpec))
        }
    }
    val sibling = fixed(dp(32))
    val parent = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(row, LinearLayout.LayoutParams(-1, -2))
        addView(sibling, LinearLayout.LayoutParams(-1, -2))
    }
    val callbacks = LibraryRecordingRowActions(null, null, null)
    var model = LibraryRecordingRow("measurement-one", "Recording", "1:20", null,
        false, false, false, NativeGroupEdges(true, true))
    row.bind(model, callbacks)
    val unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
    val width = View.MeasureSpec.makeMeasureSpec(dp(360), View.MeasureSpec.EXACTLY)
    fun layout() {
        parent.measure(width, unspecified)
        parent.layout(0, 0, parent.measuredWidth, parent.measuredHeight)
    }
    fun aligned(label: String) {
        check(row.height == row.summarySurface.height) { "$label: row=${row.height}, surface=${row.summarySurface.height}" }
        check(sibling.top == row.bottom && row.top + row.summarySurface.bottom <= sibling.top) {
            "$label: expansion overlaps following row"
        }
    }
    // Use the production frame callback, including its descendant requestLayout invalidation.
    // The fixture stays unattached so no real frame job races this deterministic clock.
    val frameLoop = row.javaClass.getDeclaredField("frames").apply { isAccessible = true }.get(row)
    val viewport = row.javaClass.getDeclaredField("expansionViewport").apply { isAccessible = true }.get(row) as View
    @Suppress("UNCHECKED_CAST")
    val sample = frameLoop.javaClass.getDeclaredField("sample").apply { isAccessible = true }
        .get(frameLoop) as (Long, Float) -> Unit
    var time = 1_000_000_000L
    fun frame(label: String, millis: Int = 16, checkAlignment: Boolean = true) {
        time += millis * 1_000_000L
        sample(time, scale.scaleFactor)
        layout()
        if (checkAlignment) aligned(label)
    }
    fun visible(value: Boolean) {
        model = model.copy(expanded = value)
        row.bind(model, callbacks)
        layout()
    }
    try {
        layout()
        val collapsed = row.height
        row.setExpansionView(fixed(dp(240)))
        visible(true)
        repeat(24) { frame("enter-$it") }
        check(row.height > collapsed)
        val expanded = row.height
        visible(false)
        repeat(20) { frame("exit-$it") }
        check(row.height == collapsed)
        row.setExpansionView(null)
        layout()
        aligned("detached after exit")
        check(row.height == collapsed)

        row.setExpansionView(fixed(dp(240)))
        visible(true)
        repeat(8) { frame("partial enter-$it") }
        check(row.height in (collapsed + 1) until expanded)
        visible(false)
        repeat(4) { frame("interrupted exit-$it") }
        visible(true)
        repeat(100) { frame("reopen-$it") }
        check(row.height == expanded)

        scale.scaleFactor = 0f
        visible(false)
        frame("zero scale exit")
        check(row.height == collapsed)
        visible(true)
        frame("zero scale enter")
        check(row.height == expanded)
        row.setExpansionView(null)
        layout()
        aligned("removed nonzero viewport")
        check(row.height == collapsed)

        row.unbind()
        model = model.copy(recordingId = "measurement-two", expanded = false)
        row.bind(model, callbacks)
        layout()
        aligned("recycled identity")
        check(row.height == collapsed)

        // Summary changes retain their own outer tween; they are not an expansion frame.
        scale.scaleFactor = 1f
        row.setSummarySlots(null, fixed(dp(160)))
        layout()
        val target = row.summarySurface.height
        check(target > collapsed && row.height == collapsed)
        frame("intrinsic start", checkAlignment = false)
        frame("intrinsic middle", 100, checkAlignment = false)
        check(row.height in (collapsed + 1) until target)
        frame("intrinsic settled", 400)
        check(row.height == target)

        // Parent constraints still cap the actual measured contribution, including a tight cap.
        scale.scaleFactor = 0f
        row.setExpansionView(fixed(dp(240)))
        visible(true)
        frame("constrained setup")
        for (cap in listOf(dp(40), target + dp(80))) {
            row.forceLayout()
            row.measure(width, View.MeasureSpec.makeMeasureSpec(cap, View.MeasureSpec.AT_MOST))
            row.layout(0, 0, row.measuredWidth, row.measuredHeight)
            check(row.height <= cap && row.height == row.summarySurface.height)
            check(if (cap < target) viewport.measuredHeight == 0 else viewport.measuredHeight > 0)
        }
        val exactHeight = target + dp(80)
        row.forceLayout()
        row.measure(width, View.MeasureSpec.makeMeasureSpec(exactHeight, View.MeasureSpec.EXACTLY))
        row.layout(0, 0, row.measuredWidth, row.measuredHeight)
        check(row.height == exactHeight && row.summarySurface.height == exactHeight)
    } finally {
        row.unbind()
        parent.removeAllViews()
    }
}
