package app.smallthingz.reverb

import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.paint
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.smallthingz.reverb.nativeui.ReverbIcon
import app.smallthingz.reverb.nativeui.ReverbIconDrawable
import app.smallthingz.reverb.nativeui.fitReverbIcon
import java.io.File
import kotlin.math.ceil
import kotlin.math.round
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject

private data class IconSample(val icon: ReverbIcon, val reference: ImageVector)

// Actual released vectors, never the generated candidate's paths.
private val iconSamples = listOf(
    IconSample(ReverbIcon.Settings, AppIcons.settings),
    IconSample(ReverbIcon.Incidents, AppIcons.incidents),
    IconSample(ReverbIcon.Share, AppIcons.share),
    IconSample(ReverbIcon.Copy, AppIcons.copy),
    IconSample(ReverbIcon.Delete, AppIcons.delete),
    IconSample(ReverbIcon.Edit, AppIcons.edit),
    IconSample(ReverbIcon.Info, AppIcons.info),
    IconSample(ReverbIcon.Close, AppIcons.close),
    IconSample(ReverbIcon.Trim, AppIcons.trim),
    IconSample(ReverbIcon.Back, AppIcons.back),
    IconSample(ReverbIcon.Check, AppIcons.check),
    IconSample(ReverbIcon.Checked, AppIcons.checked),
    IconSample(ReverbIcon.Unchecked, AppIcons.unchecked),
    IconSample(ReverbIcon.MultiSelect, AppIcons.multiSelect),
    IconSample(ReverbIcon.Play, AppIcons.play),
    IconSample(ReverbIcon.Pause, AppIcons.pause),
    IconSample(ReverbIcon.Save, AppIcons.save),
    IconSample(ReverbIcon.ExportRange, AppIcons.exportRange),
    IconSample(ReverbIcon.Library, AppIcons.library),
    IconSample(ReverbIcon.AudioFile, AppIcons.audioFile),
    IconSample(ReverbIcon.Capture, AppIcons.capture),
    IconSample(ReverbIcon.OneShot, AppIcons.oneShot),
    IconSample(ReverbIcon.Looping, AppIcons.looping),
    IconSample(ReverbIcon.Reset, AppIcons.reset),
    IconSample(ReverbIcon.Folder, AppIcons.folder),
    IconSample(ReverbIcon.Undo, AppIcons.undo),
    IconSample(ReverbIcon.BackgroundWarning, AppIcons.backgroundWarning),
    IconSample(ReverbIcon.ThemeSystem, AppIcons.themeSystem),
    IconSample(ReverbIcon.ThemeLight, AppIcons.themeLight),
    IconSample(ReverbIcon.ThemeDark, AppIcons.themeDark),
    IconSample(ReverbIcon.ArrowDropDown, AppIcons.arrowDropDown),
    IconSample(ReverbIcon.Add, Icons.Rounded.Add),
)

private enum class IconTintRole { INK, MUTED, PRIMARY, DISABLED }
private data class IconFrame(
    val name: String, val widthDp: Int, val heightDp: Int, val dark: Boolean,
    val rtl: Boolean, val tintRole: IconTintRole, val painterAlpha: Float = 1f,
)
private val iconFrames = listOf(
    IconFrame("large", 25, 25, false, false, IconTintRole.INK),
    IconFrame("shrink", 17, 17, true, true, IconTintRole.MUTED),
    IconFrame("rectangle", 23, 27, true, true, IconTintRole.PRIMARY),
    IconFrame("retint-alpha", 23, 27, true, true, IconTintRole.DISABLED, 0.65f),
    IconFrame("return-large", 25, 25, false, false, IconTintRole.INK),
)

/** Native View host versus actual released vectors; no production Activity/data. */
internal fun verifyNativeIconParity(instrumentation: Instrumentation, report: StringBuilder) {
    val context = instrumentation.targetContext
    check(context.packageName == "app.smallthingz.reverb.nativeparity.reliability")
    check(context.applicationContext.javaClass == Application::class.java)
    check(iconSamples.size == 32 && iconSamples.map { it.icon }.distinct().size == 32)
    check(iconSamples.all { it.reference.defaultWidth == 24.dp && it.reference.defaultHeight == 24.dp })
    val directory = File(context.filesDir, "native-icon-parity-" + System.currentTimeMillis())
    check(directory.mkdir())
    val cases = JSONArray()
    val result = JSONObject()
        .put("baseline_commit", "3e16f8ddb78dca15df4b436d34d330801ce83ffd")
        .put("app_icons_sha256", "baeb2191e2c0e3c98de368c2ab112044abc719f2888fe5527c23d3fab4643273")
        .put("reference", "Released AppIcons and Rounded.Add; Material3 Icon/direct VectorPainter alpha route")
        .put("candidate", "Native Views; identical drawable instances through resize/RTL/retint sequence")
        .put("comparison", "Exact ARGB, repeated stability, and exact Fit geometry; no masks/tolerance")
        .put("sdk", Build.VERSION.SDK_INT).put("device", Build.MANUFACTURER + "/" + Build.MODEL)
        .put("cases", cases)
    var activity: NativeParityActivity? = null
    var activeBoards: IconParityBoards? = null
    var completed = 0
    var mismatches = 0
    var expectedCases = 0
    var terminalFailure: Throwable? = null
    try {
        val host = launchNativeParityActivity(instrumentation)
        activity = host
        awaitNativeParityDraw(instrumentation, host.fixtureRoot)
        requireNativeParityHost(instrumentation, host)
        // 280 dpi specifically exercises 25 dp -> 44 px -> Float 44.0000038 -> cache 45.
        val densities = listOf(context.resources.displayMetrics.densityDpi, 160, 280).distinct()
        expectedCases = densities.size * iconFrames.size
        for (densityDpi in densities) {
            lateinit var boards: IconParityBoards
            instrumentation.runOnMainSync { boards = IconParityBoards(host, densityDpi); activeBoards = boards }
            for (frame in iconFrames) {
                val name = densityDpi.toString() + "dpi-" + frame.name
                val entry = JSONObject().put("name", name).put("density_dpi", densityDpi)
                    .put("width_dp", frame.widthDp).put("height_dp", frame.heightDp)
                    .put("dark", frame.dark).put("rtl", frame.rtl)
                    .put("painter_alpha", frame.painterAlpha.toDouble()).put("glyph_count", iconSamples.size)
                    .put("oracle_route", if (frame.painterAlpha == 1f) "Material3 Icon" else "VectorPainter via Modifier.paint")
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
                    bitmaps.forEachIndexed { i, bitmap ->
                        saveNativeParityBitmap(directory, name + "-" + labels[i] + ".png", bitmap)
                    }
                    val referenceStable = compareNativeParityPixels(bitmaps[0], bitmaps[2])
                    val nativeStable = compareNativeParityPixels(bitmaps[1], bitmaps[3])
                    val difference = compareNativeParityPixels(bitmaps[0], bitmaps[1])
                    val geometry = boards.compareFit(frame)
                    val passed = geometry.getBoolean("equal") &&
                        referenceStable.getLong("different_pixels") == 0L &&
                        nativeStable.getLong("different_pixels") == 0L &&
                        difference.getLong("different_pixels") == 0L
                    entry.put("fit_geometry", geometry).put("reference_stability", referenceStable)
                        .put("native_stability", nativeStable).put("difference", difference).put("passed", passed)
                    completed++
                    if (!passed) mismatches++
                    report.append(if (passed) "PASS " else "FAIL ").append("native_icon_parity ").append(name)
                        .append(" differing_pixels=").append(difference.getLong("different_pixels"))
                        .append(" geometry_equal=").append(geometry.getBoolean("equal")).append('\n')
                } catch (error: Throwable) {
                    caseFailure = error
                    entry.put("passed", false).put("error", android.util.Log.getStackTraceString(error))
                    throw error
                } finally {
                    bitmaps.forEach(Bitmap::recycle)
                    writeIconParityEvidence(directory, result, caseFailure)
                }
            }
            instrumentation.runOnMainSync { boards.dispose(); activeBoards = null }
        }
        check(completed == expectedCases && expectedCases > 0 && mismatches == 0) {
            "Exact icon parity failed: $mismatches of $completed scenes; evidence=" + directory.name
        }
    } catch (error: Throwable) {
        terminalFailure = error
        result.put("terminal_error", android.util.Log.getStackTraceString(error))
        throw error
    } finally {
        var cleanupFailure: Throwable? = null
        try {
            instrumentation.runOnMainSync {
                try { activeBoards?.dispose() } finally { activity?.finish() }
            }
        } catch (error: Throwable) {
            cleanupFailure = error
            terminalFailure?.addSuppressed(error)
            result.put("cleanup_error", android.util.Log.getStackTraceString(error))
        }
        result.put("completed_cases", completed).put("expected_cases", expectedCases).put("mismatched_cases", mismatches)
            .put("passed", completed == expectedCases && expectedCases > 0 && mismatches == 0 &&
                terminalFailure == null && cleanupFailure == null)
        report.append("PARITY_EVIDENCE ").append(directory.name).append("/result.json\n")
        writeIconParityEvidence(directory, result, terminalFailure ?: cleanupFailure)
        if (terminalFailure == null) cleanupFailure?.let { throw it }
    }
}

private fun writeIconParityEvidence(directory: File, result: JSONObject, primaryFailure: Throwable?) {
    try { File(directory, "result.json").writeText(result.toString(2)) }
    catch (error: Throwable) {
        if (primaryFailure == null) throw error
        primaryFailure.addSuppressed(error)
    }
}

private class IconParityBoards(private val activity: NativeParityActivity, densityDpi: Int) {
    private val configuration = Configuration(activity.resources.configuration).apply { this.densityDpi = densityDpi }
    private val context = activity.createConfigurationContext(configuration)
    private val density = Density(context.resources.displayMetrics.density, configuration.fontScale)
    private val cellSize = with(density) { 40.dp.roundToPx() }
    private val boardWidth = cellSize * 4
    private val boardHeight = cellSize * 8
    private var frame by mutableStateOf(iconFrames.first())
    private val reference = ComposeView(context)
    private val native = NativeIconBoard(context, density.density, cellSize)

    init {
        check(boardWidth <= activity.fixtureRoot.width && boardHeight <= activity.fixtureRoot.height) {
            "Icon board exceeds the physical viewport: " + boardWidth + "x" + boardHeight
        }
        activity.fixtureRoot.removeAllViews()
        reference.setContent {
            val current = frame
            val tint = iconTint(current, iconColors(context, current.dark))
            CompositionLocalProvider(
                LocalContext provides context, LocalConfiguration provides configuration, LocalDensity provides density,
                LocalLayoutDirection provides if (current.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                Layout(content = {
                    iconSamples.forEach { sample ->
                        // Both routes must retain the same painter/cache across resize and alpha
                        // frames, just as the native board retains its Drawable. Icon(imageVector)
                        // would remember a different painter inside only the opaque branch.
                        val painter = rememberVectorPainter(sample.reference)
                        if (current.painterAlpha == 1f) {
                            Icon(painter = painter, contentDescription = null, tint = tint)
                        } else {
                            // Icon has no painter-alpha parameter. This source-derived
                            // route checks this same VectorPainter's bitmap-composite alpha.
                            Box(Modifier.paint(painter, contentScale = ContentScale.Fit,
                                colorFilter = ColorFilter.tint(tint), alpha = current.painterAlpha))
                        }
                    }
                }) { measurables, _ ->
                    val width = with(density) { current.widthDp.dp.roundToPx() }
                    val height = with(density) { current.heightDp.dp.roundToPx() }
                    val children = measurables.map { it.measure(Constraints.fixed(width, height)) }
                    layout(boardWidth, boardHeight) {
                        children.forEachIndexed { i, child ->
                            child.place((i % 4) * cellSize + ((cellSize - width) / 2f).roundToInt(),
                                (i / 4) * cellSize + ((cellSize - height) / 2f).roundToInt())
                        }
                    }
                }
            }
        }
        activity.fixtureRoot.addView(reference, FrameLayout.LayoutParams(boardWidth, boardHeight, Gravity.CENTER))
        activity.fixtureRoot.addView(native, FrameLayout.LayoutParams(boardWidth, boardHeight, Gravity.CENTER))
    }

    fun show(next: IconFrame, compose: Boolean): View {
        check(activity.hasWindowFocus())
        frame = next
        val colors = iconColors(context, next.dark)
        val background = colors.surface.toArgb()
        val direction = if (next.rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        val width = with(density) { next.widthDp.dp.roundToPx() }
        val height = with(density) { next.heightDp.dp.roundToPx() }
        activity.fixtureRoot.setBackgroundColor(background)
        reference.setBackgroundColor(background)
        native.setBackgroundColor(background)
        reference.layoutDirection = direction
        native.layoutDirection = direction
        native.update(width, height, iconTint(next, colors).toArgb(), next.painterAlpha, direction)
        // Attached siblings retain native Drawable caches across every frame.
        reference.visibility = if (compose) View.VISIBLE else View.GONE
        native.visibility = if (compose) View.GONE else View.VISIBLE
        return if (compose) reference else native
    }

    fun compareFit(frame: IconFrame): JSONObject {
        val width = with(density) { frame.widthDp.dp.roundToPx() }
        val height = with(density) { frame.heightDp.dp.roundToPx() }
        val vector = iconSamples.first().reference
        val source = with(density) { Size(vector.defaultWidth.toPx(), vector.defaultHeight.toPx()) }
        // Independent oracle invokes the pinned Fit and Alignment implementations.
        val scale = ContentScale.Fit.computeScaleFactor(source, Size(width.toFloat(), height.toFloat()))
        val draw = Size(source.width * scale.scaleX, source.height * scale.scaleY)
        val offset = Alignment.Center.align(IntSize(draw.width.roundToInt(), draw.height.roundToInt()),
            IntSize(width, height), if (frame.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr)
        val actual = fitReverbIcon(density.density, width, height)
        val equal = actual.drawWidth.toBits() == draw.width.toBits() && actual.drawHeight.toBits() == draw.height.toBits() &&
            actual.cacheWidth == ceil(draw.width).toInt() && actual.cacheHeight == ceil(draw.height).toInt() &&
            actual.left == offset.x && actual.top == offset.y
        return JSONObject().put("equal", equal).put("measured_width", width).put("measured_height", height)
            .put("reference_draw_width", draw.width.toDouble()).put("reference_draw_height", draw.height.toDouble())
            .put("native_draw_width", actual.drawWidth.toDouble()).put("native_draw_height", actual.drawHeight.toDouble())
            .put("reference_cache_width", ceil(draw.width).toInt()).put("native_cache_width", actual.cacheWidth)
            .put("reference_cache_height", ceil(draw.height).toInt()).put("native_cache_height", actual.cacheHeight)
            .put("reference_offset", JSONArray(listOf(offset.x, offset.y)))
            .put("native_offset", JSONArray(listOf(actual.left, actual.top)))
    }

    fun dispose() {
        reference.disposeComposition()
        activity.fixtureRoot.removeView(reference)
        activity.fixtureRoot.removeView(native)
    }
}

private class NativeIconBoard(context: Context, density: Float, private val cellSize: Int) : ViewGroup(context) {
    private var iconWidth = 0
    private var iconHeight = 0
    private val cells = iconSamples.map { NativeIconCell(context, ReverbIconDrawable(it.icon, density, 0xff000000.toInt())) }
    init { clipChildren = false; clipToPadding = false; cells.forEach { addView(it) } }

    fun update(width: Int, height: Int, tint: Int, alpha: Float, direction: Int) {
        val layoutChanged = iconWidth != width || iconHeight != height
        iconWidth = width
        iconHeight = height
        cells.forEach {
            it.layoutDirection = direction
            it.icon.layoutDirection = direction
            it.icon.setTint(tint)
            // AndroidPaint.setNativeAlpha uses ties-even round.
            it.icon.alpha = round(alpha * 255f).toInt()
            it.invalidate()
        }
        if (layoutChanged) requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        cells.forEach { it.measure(MeasureSpec.makeMeasureSpec(iconWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(iconHeight, MeasureSpec.EXACTLY)) }
        setMeasuredDimension(cellSize * 4, cellSize * 8)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        cells.forEachIndexed { i, cell ->
            val x = (i % 4) * cellSize + ((cellSize - iconWidth) / 2f).roundToInt()
            val y = (i / 4) * cellSize + ((cellSize - iconHeight) / 2f).roundToInt()
            cell.layout(x, y, x + iconWidth, y + iconHeight)
        }
    }
}

private class NativeIconCell(context: Context, val icon: ReverbIconDrawable) : View(context) {
    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) { icon.setBounds(0, 0, width, height) }
    override fun onDraw(canvas: AndroidCanvas) { icon.draw(canvas) }
}

private fun iconColors(context: Context, dark: Boolean): ColorScheme = when {
    Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(context)
    Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(context)
    dark -> darkColorScheme()
    else -> lightColorScheme()
}
private fun iconTint(frame: IconFrame, colors: ColorScheme): Color = when (frame.tintRole) {
    IconTintRole.INK -> colors.onSurface
    IconTintRole.MUTED -> colors.onSurfaceVariant
    IconTintRole.PRIMARY -> colors.primary
    IconTintRole.DISABLED -> colors.onSurface.copy(alpha = 0.38f)
}
