package app.smallthingz.reverb

import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import smallthingz.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.smallthingz.reverb.nativeui.ReverbTextRole
import app.smallthingz.reverb.nativeui.ReverbParagraphStyles
import app.smallthingz.reverb.nativeui.ReverbTextScale
import app.smallthingz.reverb.nativeui.ReverbParagraphView
import app.smallthingz.reverb.nativeui.resolveMetrics
import java.io.File
import kotlin.math.ceil
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject

private data class TextParitySample(
    val name: String,
    val text: String,
    val role: ReverbTextRole,
    val maxLines: Int,
)

private val textParitySamples = listOf(
    TextParitySample("inherited-heading", "Permissions", ReverbTextRole.HEADLINE_SMALL, 1),
    TextParitySample("custom-title", "Output compression", ReverbTextRole.TITLE_MEDIUM, 1),
    TextParitySample("custom-label", "Continue", ReverbTextRole.LABEL_LARGE, 1),
    TextParitySample("wrapped-body", "The newest audio stays ready. Saved recordings remain in Library.", ReverbTextRole.BODY_MEDIUM, 3),
    TextParitySample("fallback-glyphs", "မြန်မာ བོད་ཡིག हिंदी 😀", ReverbTextRole.BODY_SMALL, 2),
    TextParitySample("trailing-newline", "One-shot\n", ReverbTextRole.BODY_MEDIUM, 2),
)

// Pure viewport planning; no sample is cropped, scaled, skipped or reordered.
internal data class NativeTextViewportBatch(val first: Int, val endExclusive: Int, val height: Int)

internal fun nativeTextViewportBatches(heights: List<Int>, availableHeight: Int): List<NativeTextViewportBatch> {
    require(availableHeight > 0 && heights.isNotEmpty())
    require(heights.all { it in 1..availableHeight }) { "A text sample exceeds the physical viewport" }
    val batches = mutableListOf<NativeTextViewportBatch>()
    var first = 0
    var used = 0
    heights.forEachIndexed { index, height ->
        if (height > availableHeight - used) {
            batches += NativeTextViewportBatch(first, index, used)
            first = index
            used = 0
        }
        used += height
    }
    batches += NativeTextViewportBatch(first, heights.size, used)
    return batches
}

private data class TextParityLayout(
    val configuration: Configuration,
    val context: Context,
    val density: Density,
    val marginPx: Int,
    val width: Int,
    val rowHeights: List<Int>,
    val viewportWidth: Int,
    val viewportHeight: Int,
)

private fun textParityLayout(activity: NativeParityActivity, dark: Boolean, fontScale: Float): TextParityLayout {
    val configuration = Configuration(activity.resources.configuration).apply {
        this.fontScale = fontScale
        uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
    }
    val context = activity.createConfigurationContext(configuration)
    val density = Density(context.resources.displayMetrics.density, fontScale)
    val scale = referenceTextScale(density)
    val marginPx = with(density) { 8.dp.roundToPx() }
    val width = minOf(with(density) { 360.dp.roundToPx() }, activity.fixtureRoot.width - marginPx * 2)
    val heights = textParitySamples.map { sample ->
        ceil(sample.role.style.resolveMetrics(scale).lineHeightPx * sample.maxLines).toInt() + marginPx
    }
    return TextParityLayout(configuration, context, density, marginPx, width, heights,
        activity.fixtureRoot.width, activity.fixtureRoot.height)
}

/** Six stable scenes; compares actual pixels and paragraph metrics without modifying device settings. */
internal fun verifyNativeTextParity(instrumentation: Instrumentation, report: StringBuilder) {
    val context = instrumentation.targetContext
    check(context.packageName.endsWith(".nativeparity.reliability"))
    check(context.applicationContext.javaClass == Application::class.java) {
        "native_text_parity requires -PreverbNativeParity=true"
    }
    // Read the actual pinned production object; do not reproduce the reference role table.
    val typography = Class.forName("app.smallthingz.reverb.ReverbThemeKt")
        .getDeclaredField("ReverbTypography")
        .apply { isAccessible = true }.get(null) as Typography
    val directory = File(context.filesDir, "native-text-parity-${System.currentTimeMillis()}")
    check(directory.mkdir())
    val cases = JSONArray()
    val result = JSONObject()
        .put("baseline_commit", "3e16f8ddb78dca15df4b436d34d330801ce83ffd")
        .put("reference", "Actual released ReverbTypography and callback-free Material3 Text; ui-text 1.11.2")
        .put("metric_reference", "Separate onTextLayout route; its raster must equal the callback-free reference")
        .put("candidate", "Ordinary native ReverbParagraphView using Android TextPaint/StaticLayout and native role metadata")
        .put("comparison", "Exact ARGB and text metrics per viewport batch; no masks or tolerance")
        .put("schema", "native_text_viewport_batches_v1")
        .put("sample_names", JSONArray(textParitySamples.map { it.name }))
        .put("sdk", Build.VERSION.SDK_INT)
        .put("device", "${Build.MANUFACTURER}/${Build.MODEL}")
        .put("cases", cases)
    result.put("passed", false)
    writeTextParityEvidence(directory, result, null)
    var activity: NativeParityActivity? = null
    var completed = 0
    var mismatches = 0
    var terminalFailure: Throwable? = null
    try {
        val host = launchNativeParityActivity(instrumentation)
        activity = host
        awaitNativeParityDraw(instrumentation, host.fixtureRoot)
        requireNativeParityHost(instrumentation, host)
        for (dark in listOf(false, true)) for (fontScale in listOf(1f, 1.5f, 2f)) {
            val name = "${if (dark) "dark" else "light"}-font${fontScale}"
            val entry = JSONObject().put("name", name).put("dark", dark).put("font_scale", fontScale.toDouble())
            cases.put(entry)
            val batchEntries = JSONArray()
            entry.put("batches", batchEntries)
            var caseFailure: Throwable? = null
            try {
                val layout = nativeParityOnMain(instrumentation) { textParityLayout(host, dark, fontScale) }
                val availableHeight = layout.viewportHeight - layout.marginPx * 2
                val plan = nativeTextViewportBatches(layout.rowHeights, availableHeight)
                entry.put("viewport_width", layout.viewportWidth).put("viewport_height", layout.viewportHeight)
                    .put("margin_px", layout.marginPx).put("fixture_width", layout.width)
                    .put("available_height", availableHeight)
                    .put("sample_heights", JSONArray(layout.rowHeights))
                check(layout.width > 0)
                val coveredSamples = mutableListOf<String>()
                var scenePassed = true
                var differentPixels = 0L
                var metricRouteDifferentPixels = 0L
                var sceneMetricsEqual = true
                for ((batchIndex, batch) in plan.withIndex()) {
                    val selectedSamples = textParitySamples.subList(batch.first, batch.endExclusive)
                    val batchName = "$name-batch$batchIndex"
                    val batchEntry = JSONObject().put("index", batchIndex)
                        .put("first_sample", batch.first).put("end_sample_exclusive", batch.endExclusive)
                        .put("sample_names", JSONArray(selectedSamples.map { it.name }))
                        .put("width", layout.width).put("height", batch.height)
                    batchEntries.put(batchEntry)
                    val bitmaps = mutableListOf<Bitmap>()
                    val metrics = List(5) { linkedMapOf<String, JSONObject>() }
                    try {
                        repeat(5) { pass ->
                            bitmaps += captureNativeParityContent(instrumentation, host) {
                                showTextParityCase(
                                    host, typography, dark, fontScale, batch, layout,
                                    compose = pass % 2 == 0,
                                    collectReferenceMetrics = pass == 4,
                                    metrics = metrics[pass],
                                )
                            }
                        }
                        check(bitmaps.all { it.width == layout.width && it.height == batch.height })
                        val names = listOf("compose", "native", "compose-repeat", "native-repeat", "compose-metric-route")
                        val pngNames = names.map { "$batchName-$it.png" }
                        bitmaps.forEachIndexed { index, bitmap ->
                            saveNativeParityBitmap(directory, pngNames[index], bitmap)
                        }
                        batchEntry.put("png_files", JSONArray(pngNames))
                        val referenceStable = compareNativeParityPixels(bitmaps[0], bitmaps[2])
                        val nativeStable = compareNativeParityPixels(bitmaps[1], bitmaps[3])
                        val difference = compareNativeParityPixels(bitmaps[0], bitmaps[1])
                        val metricRouteAgreement = compareNativeParityPixels(bitmaps[0], bitmaps[4])
                        val metricEntries = JSONArray()
                        var metricsEqual = true
                        for (sample in selectedSamples) {
                            val reference = checkNotNull(metrics[4][sample.name]) { "Reference metrics missing: ${sample.name}" }
                            val candidate = checkNotNull(metrics[1][sample.name]) { "Native metrics missing: ${sample.name}" }
                            val equal = listOf("width", "height", "line_count", "first_baseline", "last_baseline", "alignment_first_baseline", "alignment_last_baseline").all { key ->
                                reference.getDouble(key) == candidate.getDouble(key)
                            }
                            metricsEqual = metricsEqual && equal
                            metricEntries.put(JSONObject().put("sample", sample.name)
                                .put("reference", reference).put("candidate", candidate).put("equal", equal))
                        }
                        val passed = metricsEqual &&
                            referenceStable.getLong("different_pixels") == 0L &&
                            nativeStable.getLong("different_pixels") == 0L &&
                            metricRouteAgreement.getLong("different_pixels") == 0L &&
                            difference.getLong("different_pixels") == 0L
                        batchEntry.put("reference_stability", referenceStable).put("native_stability", nativeStable)
                            .put("reference_metric_route_agreement", metricRouteAgreement)
                            .put("difference", difference).put("paragraph_metrics", metricEntries).put("passed", passed)
                        coveredSamples += selectedSamples.map { it.name }
                        scenePassed = scenePassed && passed
                        differentPixels += difference.getLong("different_pixels")
                        metricRouteDifferentPixels += metricRouteAgreement.getLong("different_pixels")
                        sceneMetricsEqual = sceneMetricsEqual && metricsEqual
                    } catch (error: Throwable) {
                        batchEntry.put("passed", false).put("error", android.util.Log.getStackTraceString(error))
                        throw error
                    } finally {
                        bitmaps.forEach(Bitmap::recycle)
                    }
                }
                check(coveredSamples == textParitySamples.map { it.name }) { "Text batch coverage is incomplete or reordered" }
                entry.put("completed_samples", JSONArray(coveredSamples)).put("passed", scenePassed)
                completed++
                if (!scenePassed) mismatches++
                report.append(if (scenePassed) "PASS " else "FAIL ").append("native_text_parity ")
                    .append(name).append(" differing_pixels=").append(differentPixels)
                    .append(" metric_route_differing_pixels=").append(metricRouteDifferentPixels)
                    .append(" metrics_equal=").append(sceneMetricsEqual).append('\n')
            } catch (error: Throwable) {
                caseFailure = error
                entry.put("passed", false).put("error", android.util.Log.getStackTraceString(error))
                throw error
            } finally {
                writeTextParityEvidence(directory, result, caseFailure)
            }
        }
        check(completed == 6 && mismatches == 0) {
            "Exact text parity failed: $mismatches of $completed scenes; evidence=${directory.name}"
        }
    } catch (error: Throwable) {
        terminalFailure = error
        result.put("terminal_error", android.util.Log.getStackTraceString(error))
        throw error
    } finally {
        var cleanupFailure: Throwable? = null
        try {
            activity?.let { host -> instrumentation.runOnMainSync { host.finish() } }
        } catch (error: Throwable) {
            cleanupFailure = error
            terminalFailure?.addSuppressed(error)
            result.put("cleanup_error", android.util.Log.getStackTraceString(error))
        }
        result.put("completed_cases", completed).put("mismatched_cases", mismatches)
            .put("passed", completed == 6 && mismatches == 0 && terminalFailure == null && cleanupFailure == null)
        report.append("PARITY_EVIDENCE ").append(directory.name).append("/result.json\n")
        writeTextParityEvidence(directory, result, terminalFailure ?: cleanupFailure)
        if (terminalFailure == null) cleanupFailure?.let { throw it }
    }
}

private fun writeTextParityEvidence(directory: File, result: JSONObject, primaryFailure: Throwable?) {
    try {
        writeQaEvidenceAtomically(File(directory, "result.json")) { output ->
            output.write(result.toString(2).toByteArray(Charsets.UTF_8))
        }
    } catch (error: Throwable) {
        if (primaryFailure == null) throw error
        if (primaryFailure !== error) primaryFailure.addSuppressed(error)
    }
}

private fun showTextParityCase(
    activity: NativeParityActivity,
    typography: Typography,
    dark: Boolean,
    fontScale: Float,
    batch: NativeTextViewportBatch,
    planned: TextParityLayout,
    compose: Boolean,
    collectReferenceMetrics: Boolean,
    metrics: MutableMap<String, JSONObject>,
): View {
    check(activity.hasWindowFocus())
    val layout = textParityLayout(activity, dark, fontScale)
    check(layout.width == planned.width && layout.rowHeights == planned.rowHeights &&
        layout.viewportWidth == planned.viewportWidth && layout.viewportHeight == planned.viewportHeight &&
        layout.marginPx == planned.marginPx) { "Text fixture viewport changed during capture" }
    val configuration = layout.configuration
    val context = layout.context
    val density = layout.density
    val sharedFontResolver = createFontFamilyResolver(context)
    val nativeDensity = smallthingz.ui.unit.Density(context)
    val colors = when {
        Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    val marginPx = layout.marginPx
    val width = layout.width
    val heights = layout.rowHeights
    val height = batch.height
    check(width > 0 && height <= activity.fixtureRoot.height - marginPx * 2) {
        "Text batch ${width}x${height} exceeds physical viewport ${activity.fixtureRoot.width}x${activity.fixtureRoot.height}"
    }
    activity.fixtureRoot.removeAllViews()
    activity.fixtureRoot.setBackgroundColor(colors.surface.toArgb())
    val content = if (compose) {
        ComposeView(context).apply {
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            setContent {
                CompositionLocalProvider(
                    LocalContext provides context,
                    LocalConfiguration provides configuration,
                    LocalDensity provides density,
                    LocalLayoutDirection provides LayoutDirection.Ltr,
                ) {
                    MaterialTheme(colorScheme = colors, typography = typography) {
                        Column(Modifier.fillMaxSize().background(colors.surface)) {
                            for (index in batch.first until batch.endExclusive) {
                                val sample = textParitySamples[index]
                                Box(Modifier.fillMaxWidth().height(with(density) { heights[index].toDp() })) {
                                    Text(
                                        text = sample.text,
                                        modifier = Modifier.fillMaxWidth(),
                                        style = referenceTextStyle(typography, sample.role),
                                        color = colors.onSurface,
                                        maxLines = sample.maxLines,
                                        onTextLayout = if (collectReferenceMetrics) {
                                            { metrics[sample.name] = composeTextMetrics(it) }
                                        } else null,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    } else {
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            setBackgroundColor(colors.surface.toArgb())
            for (index in batch.first until batch.endExclusive) {
                val sample = textParitySamples[index]
                val row = FrameLayout(context)
                val label = ReverbParagraphView(
                    context,
                    nativeParagraphTextStyle(sample.role),
                    nativeDensity,
                    sharedFontResolver,
                    colors.onSurface.toArgb(),
                ).apply {
                    setText(sample.text)
                    setTextLayout(maxLines = sample.maxLines)
                    addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                        val paragraph = currentParagraph
                        if (paragraph != null && paragraph.lineCount > 0) {
                            metrics[sample.name] = JSONObject()
                                .put("width", measuredWidth).put("height", measuredHeight)
                                .put("line_count", paragraph.lineCount)
                                .put("first_baseline", paragraph.firstBaseline.toDouble())
                                .put("last_baseline", paragraph.lastBaseline.toDouble())
                                .put("alignment_first_baseline", baseline)
                                .put("alignment_last_baseline", lastTextBaseline)
                        }
                    }
                }
                row.addView(label, FrameLayout.LayoutParams(-1, -2, Gravity.TOP or Gravity.START))
                addView(row, LinearLayout.LayoutParams(-1, heights[index]))
            }
        }
    }
    activity.fixtureRoot.addView(content, FrameLayout.LayoutParams(width, height, Gravity.TOP or Gravity.START).apply {
        leftMargin = marginPx
        topMargin = marginPx
    })
    return content
}

private fun referenceTextScale(density: Density): ReverbTextScale = object : ReverbTextScale {
    override val fontScale = density.fontScale
    override fun spToPixels(sp: Float): Float = with(density) { sp.sp.toPx() }
    override fun pixelsToSp(px: Float): Float = with(density) { px.toSp().value }
}

/** Production role mapping stays independent of the reflected release Typography oracle. */
private fun nativeParagraphTextStyle(role: ReverbTextRole): smallthingz.ui.text.TextStyle = ReverbParagraphStyles[role]

private fun referenceTextStyle(typography: Typography, role: ReverbTextRole): TextStyle = when (role) {
    ReverbTextRole.HEADLINE_SMALL -> typography.headlineSmall
    ReverbTextRole.TITLE_MEDIUM -> typography.titleMedium
    ReverbTextRole.LABEL_LARGE -> typography.labelLarge
    ReverbTextRole.BODY_MEDIUM -> typography.bodyMedium
    ReverbTextRole.BODY_SMALL -> typography.bodySmall
    else -> error("Role not included in this bounded fixture: $role")
}

private fun composeTextMetrics(result: TextLayoutResult): JSONObject = JSONObject()
    .put("width", result.size.width).put("height", result.size.height)
    .put("line_count", result.lineCount)
    .put("first_baseline", result.firstBaseline.toDouble())
    .put("last_baseline", result.lastBaseline.toDouble())
    .put("alignment_first_baseline", result.firstBaseline.roundToInt())
    .put("alignment_last_baseline", result.lastBaseline.roundToInt())
