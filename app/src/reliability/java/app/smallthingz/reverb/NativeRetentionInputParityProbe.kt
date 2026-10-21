package app.smallthingz.reverb

import android.app.Application
import android.app.Instrumentation
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.smallthingz.reverb.nativeui.ReverbPalette
import app.smallthingz.reverb.nativeui.ReverbViewEnvironment
import app.smallthingz.reverb.nativeui.SettingsRetentionInputView
import app.smallthingz.reverb.nativeui.retentionImeContract
import java.io.File
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject

private data class RetentionCase(
    val name: String, val raw: String, val mode: RetentionMode,
    val dark: Boolean, val scale: Float, val rtl: Boolean,
)

private val retentionCases = listOf(
    RetentionCase("time-basic", "30", RetentionMode.TIME, false, 1f, false),
    RetentionCase("size-decimal", "53.1", RetentionMode.SIZE, true, 1f, false),
    RetentionCase("empty-large", "", RetentionMode.TIME, false, 2f, false),
    RetentionCase("raw-newline", "12\n34", RetentionMode.SIZE, true, 1f, false),
    RetentionCase("unicode-large-rtl", "١٢😀", RetentionMode.TIME, true, 2f, true),
    RetentionCase("overflow-rtl", "12345678901234567890.5", RetentionMode.SIZE, false, 1f, true),
)

/**
 * Integrated candidate: six unfocused leaf A/B cases, then native InputConnection diagnostics.
 * Not yet compiled or device-qualified.
 * No production preferences/backend, real IME launch, clipboard writes or external input.
 * This is not cursor/selection-handle/toolbar/focus/IME-session parity qualification.
 */
internal fun verifyNativeRetentionInputParity(instrumentation: Instrumentation, report: StringBuilder) {
    val context = instrumentation.targetContext
    check(context.packageName == "app.smallthingz.reverb.nativeparity.reliability")
    check(context.applicationContext.javaClass == Application::class.java)
    val typography = Class.forName("app.smallthingz.reverb.ReverbThemeKt")
        .getDeclaredField("ReverbTypography").apply { isAccessible = true }.get(null) as Typography
    val directory = File(context.filesDir, "native-retention-input-parity-${System.currentTimeMillis()}")
    check(directory.mkdir())
    val entries = JSONArray()
    val result = JSONObject().put("baseline", "3e16f8ddb78dca15df4b436d34d330801ce83ffd")
        .put("oracle_sha256", "ddbab4f6155ebb5400917282c7c32c5fd9f9841de26babeb4b7fc077e5b9f726")
        .put("scope", "Exact unfocused editor pixels/visible metrics and native-only raw InputConnection contract")
        .put("unqualified", JSONArray(listOf("focused cursor and blink phase", "selection handles/toolbar/magnifier",
            "hardware keys/undo", "real IME session/composition ordering", "accessibility editable actions",
            "keyed focus and keyboard show/hide", "API28 native cursor geometry")))
        .put("cases", entries).put("passed", false)
    var host: NativeParityActivity? = null
    var primary: Throwable? = null
    var completed = 0
    var mismatches = 0
    try {
        val activity = launchNativeParityActivity(instrumentation)
        host = activity
        awaitNativeParityDraw(instrumentation, activity.fixtureRoot)
        for (case in retentionCases) {
            val entry = JSONObject().put("case", case.name).put("raw", case.raw)
                .put("dark", case.dark).put("font_scale", case.scale.toDouble()).put("rtl", case.rtl)
                .put("passed", false)
            entries.put(entry)
            val bitmaps = mutableListOf<Bitmap>()
            val metrics = mutableListOf<JSONObject>()
            try {
                repeat(4) { pass ->
                    var rendered: RetentionRender? = null
                    bitmaps += captureNativeParityContent(instrumentation, activity) {
                        showRetentionCase(activity, typography, case, pass % 2 == 0)
                            .also { rendered = it }.content
                    }
                    requireNativeParityHost(instrumentation, activity)
                    metrics += nativeParityOnMain(instrumentation) {
                        retentionMetrics(checkNotNull(rendered))
                    }
                    val stem = "${case.name}-${listOf("compose", "native", "compose-repeat", "native-repeat")[pass]}"
                    saveNativeParityBitmap(directory, "$stem.png", bitmaps.last())
                    File(directory, "$stem.json").writeText(metrics.last().toString(2))
                }
                val referenceStable = compareNativeParityPixels(bitmaps[0], bitmaps[2])
                val nativeStable = compareNativeParityPixels(bitmaps[1], bitmaps[3])
                val difference = compareNativeParityPixels(bitmaps[0], bitmaps[1])
                val observableFields = listOf("raw", "visible_width", "visible_height", "baseline", "focused")
                val equalMetrics = observableFields.all { key -> metrics[0].get(key) == metrics[1].get(key) }
                val stableMetrics = observableFields.all { key ->
                    metrics[0].get(key) == metrics[2].get(key) && metrics[1].get(key) == metrics[3].get(key)
                }
                val passed = equalMetrics && stableMetrics &&
                    referenceStable.getLong("different_pixels") == 0L &&
                    nativeStable.getLong("different_pixels") == 0L &&
                    difference.getLong("different_pixels") == 0L
                entry.put("difference", difference).put("reference_stability", referenceStable)
                    .put("native_stability", nativeStable).put("visible_metrics_equal", equalMetrics)
                    .put("metrics_stable", stableMetrics).put("passed", passed)
                completed++
                if (!passed) mismatches++
                report.append(if (passed) "PASS " else "FAIL ").append("native_retention_input ")
                    .append(case.name).append(" differing_pixels=")
                    .append(difference.getLong("different_pixels")).append(" metrics_equal=")
                    .append(equalMetrics).append('\n')
            } catch (error: Throwable) {
                entry.put("error", android.util.Log.getStackTraceString(error))
                throw error
            } finally {
                bitmaps.forEach(Bitmap::recycle)
            }
        }
        requireNativeParityHost(instrumentation, activity)
        val contract = nativeParityOnMain(instrumentation) {
            verifyRetentionNativeConnectionContract(activity)
        }
        requireNativeParityHost(instrumentation, activity)
        result.put("native_connection_contract", contract)
        check(completed == retentionCases.size && mismatches == 0) {
            "Retention native editor differs in $mismatches of $completed cases; preserve failed candidate"
        }
    } catch (error: Throwable) {
        primary = error
        result.put("error", android.util.Log.getStackTraceString(error))
        throw error
    } finally {
        var cleanup: Throwable? = null
        try {
            host?.let { activity -> nativeParityOnMain(instrumentation) { activity.finish() } }
        } catch (error: Throwable) {
            cleanup = error
            primary?.addSuppressed(error)
            result.put("cleanup_error", android.util.Log.getStackTraceString(error))
        }
        result.put("completed_cases", completed).put("mismatched_cases", mismatches)
            .put("passed", primary == null && cleanup == null && completed == retentionCases.size && mismatches == 0)
        report.append("PARITY_EVIDENCE ").append(directory.name).append("/result.json\n")
        try { File(directory, "result.json").writeText(result.toString(2)) }
        catch (error: Throwable) {
            val owner = primary ?: cleanup
            if (owner == null) throw error else owner.addSuppressed(error)
        }
        if (primary == null) cleanup?.let { throw it }
    }
}

private data class RetentionRender(val content: View, val editor: SettingsRetentionInputView?)

private fun showRetentionCase(
    host: NativeParityActivity, typography: Typography, case: RetentionCase, reference: Boolean,
): RetentionRender {
    val config = Configuration(host.resources.configuration).apply {
        fontScale = case.scale
        uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            if (case.dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
    }
    val context = host.createConfigurationContext(config)
    val density = Density(context.resources.displayMetrics.density, case.scale)
    val direction = if (case.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr
    val colors = when {
        Build.VERSION.SDK_INT >= 31 && case.dark -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(context)
        case.dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    val palette = ReverbPalette.resolve(context, case.dark)
    val environment = ReverbViewEnvironment(density, createFontFamilyResolver(context), palette)
    val width = with(density) { 240.dp.roundToPx() }.coerceAtMost(host.fixtureRoot.width)
    val height = with(density) { 96.dp.roundToPx() }
    check(width > 0 && height <= host.fixtureRoot.height)
    host.fixtureRoot.removeAllViews()
    host.fixtureRoot.isFocusableInTouchMode = true
    host.fixtureRoot.requestFocus() // Explicit unfocused leaf fixture; no keyboard show/dismiss.
    host.fixtureRoot.setBackgroundColor(palette.surface)
    var editor: SettingsRetentionInputView? = null
    val content: View = if (reference) ComposeView(context).apply {
        setContent {
            CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides config,
                LocalDensity provides density, LocalLayoutDirection provides direction) {
                MaterialTheme(colorScheme = colors, typography = typography) {
                    Box(Modifier.fillMaxSize()) {
                        ReleasedRetentionEditorOracle("One-shot", case.mode, case.raw, case.raw,
                            requestFocus = false, onTimeChange = {}, onSizeChange = {})
                    }
                }
            }
        }
    } else FrameLayout(context).apply {
        editor = SettingsRetentionInputView(context, environment).also { input ->
            input.bind(case.raw, case.mode, "One-shot", environment, false, { case.raw }, {})
            addView(input, FrameLayout.LayoutParams(-1, -2, Gravity.TOP or Gravity.START))
        }
    }
    content.layoutDirection = if (case.rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
    content.setBackgroundColor(palette.surface)
    host.fixtureRoot.addView(content, FrameLayout.LayoutParams(width, height, Gravity.TOP or Gravity.START))
    host.fixtureRoot.requestFocus()
    return RetentionRender(content, editor)
}

private fun retentionMetrics(render: RetentionRender): JSONObject {
    val native = render.editor
    if (native != null) {
        val sourceLayout = native.sourceLayout()
        val nativeLayout = checkNotNull(native.layout)
        val info = EditorInfo()
        val connection = native.onCreateInputConnection(info)
        try {
            return JSONObject().put("raw", native.text.toString())
                .put("visible_width", native.width).put("visible_height", native.height)
                .put("baseline", native.baseline).put("focused", native.isFocused)
                .put("native_layout_width", nativeLayout.width).put("native_line_count", nativeLayout.lineCount)
                .put("source_expected_first_baseline", sourceLayout.firstBaseline.toDouble())
                .put("source_expected_height", sourceLayout.size.height)
                .put("selection_start", native.selectionStart).put("selection_end", native.selectionEnd)
                .put("input_type", info.inputType).put("ime_options", info.imeOptions)
                .put("key_listener", native.keyListener?.javaClass?.name ?: JSONObject.NULL)
                .put("transformation", native.transformationMethod?.javaClass?.name ?: JSONObject.NULL)
        } finally { connection?.closeConnection() }
    }
    fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
    val root = descendants(render.content).filterIsInstance<ViewRootForTest>().single()
    check(!root.hasPendingMeasureOrLayout)
    val node = root.semanticsOwner.getAllSemanticsNodes(false).single {
        it.config.getOrNull(SemanticsProperties.EditableText) != null
    }
    val layouts = mutableListOf<TextLayoutResult>()
    check(node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts) == true)
    val text = layouts.single()
    return JSONObject().put("raw", node.config.getOrNull(SemanticsProperties.EditableText)?.text)
        .put("visible_width", node.size.width).put("visible_height", node.size.height)
        .put("baseline", text.firstBaseline.roundToInt())
        .put("focused", node.config.getOrNull(SemanticsProperties.Focused) == true)
        .put("source_layout_width", text.size.width).put("source_line_count", text.lineCount)
        .put("source_first_baseline", text.firstBaseline.toDouble())
}

/** Native editing contract only: this is NOT an injected system IME or a source-equivalence pass. */
private fun verifyRetentionNativeConnectionContract(host: NativeParityActivity): JSONArray {
    val result = JSONArray()
    val environment = ReverbViewEnvironment(
        Density(host.resources.displayMetrics.density, host.resources.configuration.fontScale),
        createFontFamilyResolver(host), ReverbPalette.resolve(host, false))
    for (mode in listOf(RetentionMode.TIME, RetentionMode.SIZE)) {
        var owner = "12"
        var accepts = true
        val callbacks = mutableListOf<String>()
        val input = SettingsRetentionInputView(host, environment)
        fun bind() = input.bind(owner, mode, "One-shot", environment, false, { owner }) {
            callbacks += it
            if (accepts) owner = it
        }
        bind()
        host.fixtureRoot.addView(input, FrameLayout.LayoutParams(240, -2))
        val info = EditorInfo()
        val connection = checkNotNull(input.onCreateInputConnection(info))
        var primary: Throwable? = null
        try {
            val expected = retentionImeContract(mode)
            check(info.inputType == expected.inputType && info.imeOptions == expected.imeOptions)
            check(connection.setSelection(owner.length, owner.length))
            check(callbacks.isEmpty()) { "Selection must not invoke raw String callback" }
            check(connection.commitText("α\n-.😀", 1))
            check(owner == "12α\n-.😀" && input.text.toString() == owner) { "Raw characters were filtered" }
            check(callbacks == listOf(owner))
            accepts = false
            val before = owner
            check(connection.commitText("rejected", 1))
            check(input.text.toString() == before && owner == before) { "Rejected owner draft leaked into editor" }
            check(connection.setComposingRegion(0, 1))
            owner = "8"
            bind()
            check(input.text.toString() == "8")
            check(BaseInputConnection.getComposingSpanStart(input.editableText) == -1)
            check(input.selectionStart in 0..1 && input.selectionEnd in 0..1)
            accepts = true
            val beforeBatchCallbacks = callbacks.size
            check(connection.beginBatchEdit())
            check(connection.setSelection(1, 1))
            check(connection.commitText("x", 1))
            check(connection.commitText("y", 1))
            check(owner == "8" && input.text.toString() == "8" && callbacks.size == beforeBatchCallbacks) {
                "IME batch leaked intermediate text before endBatchEdit"
            }
            check(!connection.endBatchEdit())
            check(owner == "8xy" && input.text.toString() == "8xy" && callbacks.size == beforeBatchCallbacks + 1)
            check(connection.beginBatchEdit())
            check(connection.commitText("discard-on-close", 1))
            connection.closeConnection()
            check(owner == "8xy" && input.text.toString() == "8xy") { "Closing an unfinished IME batch committed it" }
            check(!connection.commitText("after-close", 1))
            result.put(JSONObject().put("mode", mode.name).put("passed", true)
                .put("callbacks", JSONArray(callbacks)).put("input_type", info.inputType)
                .put("ime_options", info.imeOptions))
        } catch (error: Throwable) {
            primary = error
            throw error
        } finally {
            var cleanup: Throwable? = null
            for (close in listOf<() -> Unit>(
                { connection.closeConnection() },
                { input.releaseCallbacks() },
                { host.fixtureRoot.removeView(input) },
            )) {
                try { close() }
                catch (error: Throwable) {
                    if (cleanup == null) cleanup = error else cleanup.addSuppressed(error)
                }
            }
            if (primary == null) cleanup?.let { throw it }
            else cleanup?.let(primary::addSuppressed)
        }
    }
    return result
}
