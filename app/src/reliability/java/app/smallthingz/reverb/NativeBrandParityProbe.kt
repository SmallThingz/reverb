package app.smallthingz.reverb

import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Bitmap
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ComposeViewContext
import androidx.compose.ui.platform.ExperimentalComposeViewContextApi
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.smallthingz.reverb.nativeui.ReverbBrandMarkView
import app.smallthingz.reverb.nativeui.readReverbBrandPalette
import app.smallthingz.reverb.nativeui.reverbBrandFillBounds
import java.io.File
import kotlin.math.ceil
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject

private data class BrandFrame(val name: String, val widthDp: Int, val heightDp: Int, val rtl: Boolean)
private val brandFrames = listOf(
    BrandFrame("topbar", 46, 46, false),
    BrandFrame("about", 104, 104, true),
    BrandFrame("rectangle", 104, 46, true),
    BrandFrame("topbar-return", 46, 46, false),
)

/** Released composable/resource oracle; isolated Application, no production startup. */
internal fun verifyNativeBrandParity(instrumentation: Instrumentation, report: StringBuilder) {
    val context = instrumentation.targetContext
    check(context.packageName == "app.smallthingz.reverb.nativeparity.reliability")
    check(context.applicationContext.javaClass == Application::class.java)
    val directory = File(context.filesDir, "native-brand-parity-" + System.currentTimeMillis())
    check(directory.mkdir())
    val cases = JSONArray()
    val resourceContexts = JSONArray()
    val result = JSONObject()
        .put("baseline_commit", "3e16f8ddb78dca15df4b436d34d330801ce83ffd")
        .put("brand_mark_sha256", "c594b341689a9d8f881b2e170d72b8f2e178d1ccbfe612c515188a8287c7699e")
        .put("foreground_sha256", "3c4e42d229ee61a00f31e8dcee52625eb202e94ed266525dcd60ed2a0a3f2f4d")
        .put("reference", "Actual released ReverbBrandMark and original qualifier-selected resources")
        .put("candidate", "Native View retained through size and direction changes")
        .put("comparison", "Exact ARGB and ABAB stability; no pixel masks or tolerance")
        .put("scope", "Current-SDK resource light/night; does not simulate older Android resource branches")
        .put("sdk", Build.VERSION.SDK_INT).put("device", Build.MANUFACTURER + "/" + Build.MODEL)
        .put("resource_contexts", resourceContexts)
        .put("cases", cases)
    var activity: NativeParityActivity? = null
    var activeBoards: BrandParityBoards? = null
    var expected = 0
    var completed = 0
    var mismatches = 0
    var terminalFailure: Throwable? = null
    try {
        val host = launchNativeParityActivity(instrumentation)
        activity = host
        awaitNativeParityDraw(instrumentation, host.fixtureRoot)
        requireNativeParityHost(instrumentation, host)
        val densities = listOf(context.resources.displayMetrics.densityDpi, 280).distinct()
        expected = densities.size * 2 * brandFrames.size
        var previousTheme: Resources.Theme? = null
        for (densityDpi in densities) for (night in listOf(false, true)) {
            lateinit var boards: BrandParityBoards
            nativeParityOnMain(instrumentation) {
                boards = BrandParityBoards(host, densityDpi, night)
                activeBoards = boards
                resourceContexts.put(boards.resourceEvidence(previousTheme))
                previousTheme = boards.theme
            }
            for (frame in brandFrames) {
                val name = densityDpi.toString() + "dpi-" + (if (night) "night-" else "day-") + frame.name
                val entry = JSONObject().put("name", name).put("density_dpi", densityDpi)
                    .put("night", night).put("width_dp", frame.widthDp).put("height_dp", frame.heightDp)
                    .put("rtl", frame.rtl)
                cases.put(entry)
                val bitmaps = mutableListOf<Bitmap>()
                var caseFailure: Throwable? = null
                try {
                    repeat(4) { pass ->
                        bitmaps += captureNativeParityContent(instrumentation, host) {
                            boards.show(frame, compose = pass % 2 == 0)
                        }
                    }
                    val labels = listOf("compose", "native", "compose-repeat", "native-repeat")
                    bitmaps.forEachIndexed { index, bitmap ->
                        saveNativeParityBitmap(directory, name + "-" + labels[index] + ".png", bitmap)
                    }
                    val referenceStable = compareNativeParityPixels(bitmaps[0], bitmaps[2])
                    val candidateStable = compareNativeParityPixels(bitmaps[1], bitmaps[3])
                    val difference = compareNativeParityPixels(bitmaps[0], bitmaps[1])
                    val geometry = boards.compareGeometry(frame)
                    val passed = geometry.getBoolean("equal") &&
                        referenceStable.getLong("different_pixels") == 0L &&
                        candidateStable.getLong("different_pixels") == 0L &&
                        difference.getLong("different_pixels") == 0L
                    entry.put("reference_stability", referenceStable).put("native_stability", candidateStable)
                        .put("difference", difference).put("geometry", geometry).put("passed", passed)
                    completed++
                    if (!passed) mismatches++
                    report.append(if (passed) "PASS " else "FAIL ").append("native_brand_parity ")
                        .append(name).append(" differing_pixels=").append(difference.getLong("different_pixels"))
                        .append('\n')
                } catch (error: Throwable) {
                    caseFailure = error
                    entry.put("passed", false).put("error", android.util.Log.getStackTraceString(error))
                    throw error
                } finally {
                    bitmaps.forEach(Bitmap::recycle)
                    writeBrandEvidence(directory, result, caseFailure)
                }
            }
            nativeParityOnMain(instrumentation) { boards.dispose(); activeBoards = null }
        }
        check(expected > 0 && completed == expected && mismatches == 0) {
            "Exact brand parity failed: $mismatches of $completed scenes; evidence=" + directory.name
        }
    } catch (error: Throwable) {
        terminalFailure = error
        result.put("terminal_error", android.util.Log.getStackTraceString(error))
        throw error
    } finally {
        var cleanupFailure: Throwable? = null
        try {
            nativeParityOnMain(instrumentation) {
                try { activeBoards?.dispose() } finally { activity?.finish() }
            }
        } catch (error: Throwable) {
            cleanupFailure = error
            terminalFailure?.addSuppressed(error)
            result.put("cleanup_error", android.util.Log.getStackTraceString(error))
        }
        result.put("expected_cases", expected).put("completed_cases", completed).put("mismatched_cases", mismatches)
            .put("passed", expected > 0 && completed == expected && mismatches == 0 &&
                terminalFailure == null && cleanupFailure == null)
        report.append("PARITY_EVIDENCE ").append(directory.name).append("/result.json\n")
        writeBrandEvidence(directory, result, terminalFailure ?: cleanupFailure)
        if (terminalFailure == null) cleanupFailure?.let { throw it }
    }
}

private fun writeBrandEvidence(directory: File, result: JSONObject, primary: Throwable?) {
    try { File(directory, "result.json").writeText(result.toString(2)) }
    catch (error: Throwable) {
        if (primary == null) throw error
        primary.addSuppressed(error)
    }
}

@OptIn(ExperimentalComposeViewContextApi::class)
private class BrandParityBoards(private val activity: NativeParityActivity, densityDpi: Int, night: Boolean) {
    private val configuration = Configuration(activity.resources.configuration).apply {
        this.densityDpi = densityDpi
        uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            (if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
    }
    private val context = activity.createConfigurationContext(configuration)
    private val density = Density(context.resources.displayMetrics.density, configuration.fontScale)
    private val boardSize = with(density) { 120.dp.roundToPx() }
    private val outside = if (night) 0xff242424.toInt() else 0xffe7e7e7.toInt()
    private var frame by mutableStateOf(brandFrames.first())
    private val referenceHost = FrameLayout(context)
    private val reference = ComposeView(context)
    private val native = NativeBrandBoard(context)
    private val vector = ImageVector.vectorResource(theme = context.theme, res = context.resources, resId = R.drawable.ic_launcher_foreground)
    private val referenceContext: ComposeViewContext
    val theme: Resources.Theme get() = context.theme

    init {
        check(boardSize <= activity.fixtureRoot.width && boardSize <= activity.fixtureRoot.height)
        check(context !== activity.fixtureRoot.context)
        check(theme.resources === context.resources)
        activity.fixtureRoot.removeAllViews()
        activity.fixtureRoot.addView(referenceHost, FrameLayout.LayoutParams(boardSize, boardSize, Gravity.CENTER))
        // Compose 1.11 shares its root ImageVectorCache. Android Theme equality compares styles,
        // not resource configuration, so day/night contexts can otherwise collide on Theme + ID.
        // The attached configured host gives this board its own cache before its first composition.
        referenceContext = ComposeViewContext(referenceHost)
        reference.setContent {
            val current = frame
            CompositionLocalProvider(
                LocalContext provides context, LocalConfiguration provides configuration,
                LocalResources provides context.resources, LocalDensity provides density,
                LocalLayoutDirection provides if (current.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                Layout(content = { ReverbBrandMark(Modifier, context.getString(R.string.app_name)) }) { children, _ ->
                    val width = with(density) { current.widthDp.dp.roundToPx() }
                    val height = with(density) { current.heightDp.dp.roundToPx() }
                    val child = children.single().measure(Constraints.fixed(width, height))
                    layout(boardSize, boardSize) {
                        child.place(((boardSize - width) / 2f).roundToInt(), ((boardSize - height) / 2f).roundToInt())
                    }
                }
            }
        }
        if (Build.VERSION.SDK_INT >= 29) {
            referenceHost.isForceDarkAllowed = false
            native.isForceDarkAllowed = false
        }
        reference.createComposition(referenceContext)
        referenceHost.addView(reference, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        activity.fixtureRoot.addView(native, FrameLayout.LayoutParams(boardSize, boardSize, Gravity.CENTER))
    }

    fun show(next: BrandFrame, compose: Boolean): View {
        check(activity.hasWindowFocus())
        frame = next
        val direction = if (next.rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        activity.fixtureRoot.setBackgroundColor(outside)
        reference.setBackgroundColor(outside)
        native.setBackgroundColor(outside)
        reference.layoutDirection = direction
        native.layoutDirection = direction
        native.update(with(density) { next.widthDp.dp.roundToPx() }, with(density) { next.heightDp.dp.roundToPx() })
        referenceHost.visibility = if (compose) View.VISIBLE else View.GONE
        native.visibility = if (compose) View.GONE else View.VISIBLE
        return if (compose) reference else native
    }

    fun resourceEvidence(previousTheme: Resources.Theme?): JSONObject {
        val palette = readReverbBrandPalette(context)
        val resources = context.resources
        return JSONObject()
            .put("requested_density_dpi", configuration.densityDpi)
            .put("requested_night", configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)
            .put("resolved_density_dpi", resources.configuration.densityDpi)
            .put("resolved_night", resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)
            .put("context_identity", System.identityHashCode(context))
            .put("resources_identity", System.identityHashCode(resources))
            .put("theme_identity", System.identityHashCode(theme))
            .put("theme_hash", theme.hashCode())
            .put("theme_uses_context_resources", theme.resources === resources)
            .put("theme_equals_previous", previousTheme?.let { theme == it } ?: JSONObject.NULL)
            .put("theme_identical_to_previous", previousTheme?.let { theme === it } ?: JSONObject.NULL)
            .put("compose_context_identity", System.identityHashCode(referenceContext))
            .put("compose_cache_scope", "Independent configured attached host per density/night board")
            .put("launcher_background_argb", palette.background.toLong() and 0xffffffffL)
            .put("launcher_accent_argb", palette.accent.toLong() and 0xffffffffL)
            .put("launcher_main_argb", palette.main.toLong() and 0xffffffffL)
    }

    fun compareGeometry(frame: BrandFrame): JSONObject {
        val width = with(density) { frame.widthDp.dp.roundToPx() }
        val height = with(density) { frame.heightDp.dp.roundToPx() }
        val source = with(density) { Size(vector.defaultWidth.toPx(), vector.defaultHeight.toPx()) }
        val scale = ContentScale.FillBounds.computeScaleFactor(source, Size(width.toFloat(), height.toFloat()))
        val draw = Size(source.width * scale.scaleX, source.height * scale.scaleY)
        val offset = Alignment.Center.align(IntSize(draw.width.roundToInt(), draw.height.roundToInt()), IntSize(width, height),
            if (frame.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr)
        val actual = reverbBrandFillBounds(density.density, width, height)
        val equal = actual.drawWidth.toBits() == draw.width.toBits() && actual.drawHeight.toBits() == draw.height.toBits() &&
            actual.cacheWidth == ceil(draw.width).toInt() && actual.cacheHeight == ceil(draw.height).toInt() &&
            actual.left == offset.x && actual.top == offset.y
        return JSONObject().put("equal", equal)
            .put("reference_draw_width", draw.width.toDouble()).put("reference_draw_height", draw.height.toDouble())
            .put("native_draw_width", actual.drawWidth.toDouble()).put("native_draw_height", actual.drawHeight.toDouble())
            .put("reference_cache_width", ceil(draw.width).toInt()).put("native_cache_width", actual.cacheWidth)
            .put("reference_cache_height", ceil(draw.height).toInt()).put("native_cache_height", actual.cacheHeight)
    }

    fun dispose() {
        reference.disposeComposition()
        activity.fixtureRoot.removeView(referenceHost)
        activity.fixtureRoot.removeView(native)
    }
}

private class NativeBrandBoard(context: Context) : ViewGroup(context) {
    private val mark = ReverbBrandMarkView(context, context.getString(R.string.app_name))
    private var markWidth = 0
    private var markHeight = 0
    init { addView(mark) }
    fun update(width: Int, height: Int) {
        if (markWidth == width && markHeight == height) return
        markWidth = width
        markHeight = height
        requestLayout()
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        mark.measure(MeasureSpec.makeMeasureSpec(markWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(markHeight, MeasureSpec.EXACTLY))
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
    }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val x = ((width - markWidth) / 2f).roundToInt()
        val y = ((height - markHeight) / 2f).roundToInt()
        mark.layout(x, y, x + markWidth, y + markHeight)
    }
}
