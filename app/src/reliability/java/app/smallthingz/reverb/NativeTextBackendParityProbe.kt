package app.smallthingz.reverb

import android.content.Context
import android.app.Application
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.LocaleList
import android.view.View
import app.smallthingz.reverb.nativeui.ReverbParagraphView
import java.util.Locale
import app.smallthingz.reverb.nativeui.ReverbParagraphStyles
import app.smallthingz.reverb.nativeui.ReverbTextRole
import app.smallthingz.nativeui.text.Paragraph as NativeParagraph
import app.smallthingz.nativeui.text.ParagraphIntrinsics as NativeIntrinsics
import app.smallthingz.nativeui.text.TextStyle as NativeStyle
import app.smallthingz.nativeui.text.style.TextOverflow as NativeOverflow
import app.smallthingz.nativeui.unit.LayoutDirection as NativeDirection
import androidx.compose.ui.graphics.Canvas as ReferenceCanvas
import androidx.compose.ui.graphics.Color as ReferenceColor
import androidx.compose.ui.text.Paragraph as ReferenceParagraph
import androidx.compose.ui.text.ParagraphIntrinsics as ReferenceIntrinsics
import androidx.compose.ui.text.PlatformTextStyle as ReferencePlatformStyle
import androidx.compose.ui.text.TextStyle as ReferenceStyle
import androidx.compose.ui.text.font.FontFamily as ReferenceFamily
import androidx.compose.ui.text.font.FontWeight as ReferenceWeight
import androidx.compose.ui.text.style.LineHeightStyle as ReferenceLineHeight
import androidx.compose.ui.text.style.TextOverflow as ReferenceOverflow
import androidx.compose.ui.unit.TextUnit as ReferenceUnit
import androidx.compose.ui.unit.TextUnitType as ReferenceUnitType
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.ceil

/**
 * Isolated QA oracle: real released Compose Paragraph is compared with the native Android backend.
 * Never linked into production. Writes bounded failed Bitmap pairs only in the isolated QA filesDir.
 * It qualifies paragraph metrics/rasterization, not complete screen or hardware-rendering parity.
 */
internal fun nativeTextBackendParity(context: Context): JSONObject {
    check(context.packageName == "app.smallthingz.reverb.nativeparity.reliability")
    check(context.applicationContext.javaClass == Application::class.java)
    val results = JSONArray()
    val artifacts = ParagraphFailureArtifacts(context)
    data class Case(val label: String, val text: String, val widthDp: Int, val maxLines: Int,
                    val ellipsis: Boolean = false, val rtl: Boolean = false)
    val cases = listOf(
        Case("latin", "Output compression", 240, 1),
        Case("wrapping", "Keep the most recent audio ready to save. Short text wraps consistently.", 144, 3),
        Case("ellipsis", "A long recording name that must end with an ellipsis", 128, 1, true),
        Case("height-ellipsis", "One two three four five six seven eight nine ten.", 110, 3, true),
        Case("bidi", "Audio אבג 123 حفظ", 180, 2, rtl = true),
        Case("fallback", "音声 Ελληνικά العربية नमस्ते 😀", 160, 3),
        Case("empty", "", 120, 1),
        Case("final-newline", "Recording\n", 120, 3),
        Case("clipped-hidden-lines", "First line\nSecond line\nאבג", 120, 1),
        Case("draw-only-decoration", "Output compression", 240, 1),
    )
    val roles = listOf(ReverbTextRole.BODY_LARGE, ReverbTextRole.LABEL_LARGE,
        ReverbTextRole.HEADLINE_LARGE, ReverbTextRole.LABEL_SMALL)
    for (scale in listOf(1f, 1.3f, 2f)) {
        val configured = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
            fontScale = scale
        })
        val nativeDensity = app.smallthingz.nativeui.unit.Density(configured)
        val referenceDensity = androidx.compose.ui.unit.Density(configured)
        val nativeFonts = app.smallthingz.nativeui.text.font.createFontFamilyResolver(configured)
        val referenceFonts = androidx.compose.ui.text.font.createFontFamilyResolver(configured)
        for (role in roles) for (case in cases) {
            val entry = JSONObject().put("case", case.label).put("role", role.name).put("fontScale", scale)
            try {
                val direction = if (case.rtl) NativeDirection.Rtl else NativeDirection.Ltr
                val nativeStyle = ReverbParagraphStyles[role]
                val resolvedNative = app.smallthingz.nativeui.text.resolveDefaults(nativeStyle, direction)
                val referenceStyle = androidx.compose.ui.text.resolveDefaults(nativeStyle.referenceStyle(),
                    if (case.rtl) androidx.compose.ui.unit.LayoutDirection.Rtl else androidx.compose.ui.unit.LayoutDirection.Ltr)
                val width = (case.widthDp * nativeDensity.density).toInt()
                val maxHeight = if (case.label == "height-ellipsis")
                    (nativeDensity.density * 40).toInt() else 10000
                val native = NativeParagraph(NativeIntrinsics(case.text, resolvedNative,
                    density = nativeDensity, fontFamilyResolver = nativeFonts),
                    app.smallthingz.nativeui.unit.Constraints(maxWidth = width, maxHeight = maxHeight),
                    case.maxLines, if (case.ellipsis) NativeOverflow.Ellipsis else NativeOverflow.Clip)
                val reference = ReferenceParagraph(ReferenceIntrinsics(case.text, referenceStyle,
                    annotations = emptyList(), placeholders = emptyList(),
                    density = referenceDensity, fontFamilyResolver = referenceFonts),
                    androidx.compose.ui.unit.Constraints(maxWidth = width, maxHeight = maxHeight),
                    case.maxLines, if (case.ellipsis) ReferenceOverflow.Ellipsis else ReferenceOverflow.Clip)
                compareParagraphs(reference, native, case.text, width,
                    case.label == "draw-only-decoration", entry, artifacts)
            } catch (error: Exception) {
                entry.put("pass", false).put("error", error.javaClass.name + ": " + error.message)
            }
            results.put(entry)
        }
        // One pair of real intrinsics is retained across width and height changes, as in the View.
        // Redraw the original Paragraph after later layouts to cover shared mutable line-height spans.
        for (role in roles) {
            val text = "Retained 😀 אבג text wraps across width changes.\n"
            val dimensions = listOf(240 to 10000, 112 to 10000, 112 to
                (nativeDensity.density * 40).toInt(), 240 to 10000)
            var stage = "construction"
            try {
                val nativeStyle = ReverbParagraphStyles[role]
                val nativeIntrinsics = NativeIntrinsics(text,
                    app.smallthingz.nativeui.text.resolveDefaults(nativeStyle, NativeDirection.Ltr),
                    density = nativeDensity, fontFamilyResolver = nativeFonts)
                val referenceIntrinsics = ReferenceIntrinsics(text,
                    androidx.compose.ui.text.resolveDefaults(nativeStyle.referenceStyle(),
                        androidx.compose.ui.unit.LayoutDirection.Ltr),
                    annotations = emptyList(), placeholders = emptyList(),
                    density = referenceDensity, fontFamilyResolver = referenceFonts)
                var firstNative: NativeParagraph? = null
                var firstReference: ReferenceParagraph? = null
                for ((index, dimension) in dimensions.withIndex()) {
                    stage = "layout-$index"
                    val width = (dimension.first * nativeDensity.density).toInt()
                    val native = NativeParagraph(nativeIntrinsics,
                        app.smallthingz.nativeui.unit.Constraints(maxWidth = width,
                            maxHeight = dimension.second), 3, NativeOverflow.Ellipsis)
                    val reference = ReferenceParagraph(referenceIntrinsics,
                        androidx.compose.ui.unit.Constraints(maxWidth = width,
                            maxHeight = dimension.second), 3, ReferenceOverflow.Ellipsis)
                    if (index == 0) { firstNative = native; firstReference = reference }
                    val entry = JSONObject().put("case", "retained-intrinsics-$stage")
                        .put("role", role.name).put("fontScale", scale)
                    compareParagraphs(reference, native, text, width, false, entry, artifacts)
                    results.put(entry)
                }
                stage = "first-after-relayout"
                val entry = JSONObject().put("case", "retained-intrinsics-$stage")
                    .put("role", role.name).put("fontScale", scale)
                compareParagraphs(checkNotNull(firstReference), checkNotNull(firstNative), text,
                    (dimensions.first().first * nativeDensity.density).toInt(), false, entry, artifacts)
                results.put(entry)
            } catch (error: Exception) {
                results.put(JSONObject().put("case", "retained-intrinsics-$stage")
                    .put("role", role.name).put("fontScale", scale).put("pass", false)
                    .put("error", error.javaClass.name + ": " + error.message))
            }
        }
    }
    appendUnresolvedSemanticsCases(context, results, artifacts)
    var failures = 0
    for (index in 0 until results.length()) if (!results.getJSONObject(index).getBoolean("pass")) failures++
    return JSONObject().put("scope", "native paragraph versus real released paragraph; software bitmap")
        .put("cases", results).put("failures", failures).put("pass", failures == 0)
        .put("diagnostic_directory", artifacts.directoryPath ?: JSONObject.NULL)
        .put("captured_failure_pairs", artifacts.capturedPairs)
}

/** Separate original-semantics cases; the 180 resolved comparisons above remain unchanged. */
private fun appendUnresolvedSemanticsCases(
    context: Context,
    results: JSONArray,
    artifacts: ParagraphFailureArtifacts,
) {
    data class Case(val label: String, val text: String, val widthDp: Int, val maxLines: Int,
                    val ellipsis: Boolean = false)
    val previousLocales = LocaleList.getDefault()
    val previousAdjustedLocales = LocaleList.getAdjustedDefault()
    val previousLocale = Locale.getDefault()
    val previousDisplayLocale = Locale.getDefault(Locale.Category.DISPLAY)
    val previousFormatLocale = Locale.getDefault(Locale.Category.FORMAT)
    // Public setDefault restores only index zero. Do not mutate a system-selected nonfirst default.
    check(previousLocales[0] == previousLocale && previousAdjustedLocales == previousLocales) {
        "Unresolved-text QA requires a first-entry process default locale for exact public-API restoration; no locale was changed"
    }
    try {
        for (locale in listOf(Locale.ENGLISH, Locale.forLanguageTag("ar"))) {
            // Only this isolated QA process's defaults change; no Android locale setting is written.
            LocaleList.setDefault(LocaleList(locale))
            val rtl = android.text.TextUtils.getLayoutDirectionFromLocale(locale) == View.LAYOUT_DIRECTION_RTL
            val leading = if (rtl) "Recording אבג 123" else "אבג Recording 123"
            val cases = listOf(
                Case("opposite-leading", leading, 180, 3),
                Case("mixed-wrap", "$leading mixed text repeats across several narrow lines.", 96, 3),
                Case("end-ellipsis", "$leading a long recording name that must be truncated", 110, 1, true),
                Case("neutral-fallback", "123 — (456) ...", 180, 3),
            )
            for (scale in listOf(1f, 2f)) {
                val configured = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
                    fontScale = scale
                    setLocales(LocaleList(locale))
                })
                val nativeDensity = app.smallthingz.nativeui.unit.Density(configured)
                val referenceDensity = androidx.compose.ui.unit.Density(configured)
                val nativeFonts = app.smallthingz.nativeui.text.font.createFontFamilyResolver(configured)
                val referenceFonts = androidx.compose.ui.text.font.createFontFamilyResolver(configured)
                for (role in listOf(ReverbTextRole.TITLE_MEDIUM, ReverbTextRole.BODY_LARGE)) for (case in cases) {
                    val entry = JSONObject().put("case", "unresolved-${locale.language}-${case.label}")
                        .put("role", role.name).put("fontScale", scale)
                        .put("route", "measured View original semantics versus released MultiParagraph")
                    try {
                        val width = (case.widthDp * nativeDensity.density).toInt()
                        val style = ReverbParagraphStyles[role]
                        check(style.layoutDirection == null)
                        val view = ReverbParagraphView(configured, style, nativeDensity, nativeFonts,
                            android.graphics.Color.BLACK).apply {
                            layoutDirection = if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
                            setText(case.text)
                            setTextLayout(maxLines = case.maxLines,
                                overflow = if (case.ellipsis) NativeOverflow.Ellipsis else NativeOverflow.Clip)
                            measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.AT_MOST),
                                View.MeasureSpec.makeMeasureSpec(10000, View.MeasureSpec.AT_MOST))
                        }
                        val native = checkNotNull(view.createOriginalSemanticsParagraph())
                        // Deliberately no resolveDefaults: the released original-semantics route forwards style.
                        val reference = androidx.compose.ui.text.MultiParagraph(
                            androidx.compose.ui.text.MultiParagraphIntrinsics(
                                androidx.compose.ui.text.AnnotatedString(case.text), style.referenceStyle(),
                                emptyList(), referenceDensity, referenceFonts),
                            androidx.compose.ui.unit.Constraints(maxWidth = width, maxHeight = 10000),
                            case.maxLines, if (case.ellipsis) ReferenceOverflow.Ellipsis else ReferenceOverflow.Clip)
                        compareSemanticsParagraphs(reference, native, case.text, width, entry, artifacts)
                    } catch (error: Exception) {
                        entry.put("pass", false).put("error", error.javaClass.name + ": " + error.message)
                    }
                    results.put(entry)
                }
            }
        }
    } finally {
        LocaleList.setDefault(previousLocales)
        Locale.setDefault(Locale.Category.DISPLAY, previousDisplayLocale)
        Locale.setDefault(Locale.Category.FORMAT, previousFormatLocale)
        check(Locale.getDefault() == previousLocale && LocaleList.getDefault() == previousLocales &&
            LocaleList.getAdjustedDefault() == previousAdjustedLocales &&
            Locale.getDefault(Locale.Category.DISPLAY) == previousDisplayLocale &&
            Locale.getDefault(Locale.Category.FORMAT) == previousFormatLocale) {
            "QA process locale restoration failed"
        }
    }
}

private fun compareSemanticsParagraphs(
    reference: androidx.compose.ui.text.MultiParagraph,
    native: app.smallthingz.nativeui.text.MultiParagraph,
    text: String,
    width: Int,
    entry: JSONObject,
    artifacts: ParagraphFailureArtifacts,
) {
    val differences = JSONArray()
    fun same(name: String, expected: Float, actual: Float) {
        if (expected.toRawBits() != actual.toRawBits()) differences.put(
            JSONObject().put("field", name).put("expected", expected).put("actual", actual))
    }
    same("height", reference.height, native.height)
    same("minIntrinsicWidth", reference.minIntrinsicWidth, native.minIntrinsicWidth)
    same("maxIntrinsicWidth", reference.maxIntrinsicWidth, native.maxIntrinsicWidth)
    same("firstBaseline", reference.firstBaseline, native.firstBaseline)
    same("lastBaseline", reference.lastBaseline, native.lastBaseline)
    if (native.lineCount != reference.lineCount) differences.put("lineCount")
    if (native.didExceedMaxLines != reference.didExceedMaxLines) differences.put("didExceedMaxLines")
    for (line in 0 until minOf(native.lineCount, reference.lineCount)) {
        same("top[$line]", reference.getLineTop(line), native.getLineTop(line))
        same("bottom[$line]", reference.getLineBottom(line), native.getLineBottom(line))
        same("baseline[$line]", reference.getLineBaseline(line), native.getLineBaseline(line))
        same("left[$line]", reference.getLineLeft(line), native.getLineLeft(line))
        same("right[$line]", reference.getLineRight(line), native.getLineRight(line))
        if (reference.getLineStart(line) != native.getLineStart(line)) differences.put("start[$line]")
        if (reference.getLineEnd(line, true) != native.getLineEnd(line, true)) differences.put("end[$line]")
    }
    for (offset in text.indices) {
        val expected = reference.getBoundingBox(offset)
        val actual = native.getBoundingBox(offset)
        same("box[$offset].left", expected.left, actual.left)
        same("box[$offset].top", expected.top, actual.top)
        same("box[$offset].right", expected.right, actual.right)
        same("box[$offset].bottom", expected.bottom, actual.bottom)
        if ((reference.getParagraphDirection(offset) == androidx.compose.ui.text.style.ResolvedTextDirection.Ltr) !=
            (native.getParagraphDirection(offset) == app.smallthingz.nativeui.text.style.ResolvedTextDirection.Ltr)) {
            differences.put("direction[$offset]")
        }
    }
    val height = maxOf(1, ceil(maxOf(native.height, reference.height)).toInt() + 16)
    val expectedBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val actualBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    try {
        reference.paint(ReferenceCanvas(Canvas(expectedBitmap)), ReferenceColor.Black)
        native.paint(Canvas(actualBitmap), app.smallthingz.nativeui.graphics.Color.Black)
        val expectedPixels = IntArray(width * height)
        val actualPixels = IntArray(width * height)
        expectedBitmap.getPixels(expectedPixels, 0, width, 0, 0, width, height)
        actualBitmap.getPixels(actualPixels, 0, width, 0, 0, width, height)
        var mismatched = 0
        for (index in expectedPixels.indices) if (expectedPixels[index] != actualPixels[index]) mismatched++
        entry.put("pixelMismatches", mismatched).put("pixelCount", expectedPixels.size)
        entry.put("metrics", differences).put("pass", differences.length() == 0 && mismatched == 0)
        if (differences.length() != 0 || mismatched != 0) {
            artifacts.capture(entry, expectedBitmap, actualBitmap, mismatched != 0)
        }
    } finally {
        expectedBitmap.recycle()
        actualBitmap.recycle()
    }
}

private fun compareParagraphs(
    reference: ReferenceParagraph,
    native: NativeParagraph,
    text: String,
    width: Int,
    underline: Boolean,
    entry: JSONObject,
    artifacts: ParagraphFailureArtifacts,
) {
    val differences = JSONArray()
    fun same(name: String, expected: Float, actual: Float) {
        if (expected.toRawBits() != actual.toRawBits()) differences.put(
            JSONObject().put("field", name).put("expected", expected).put("actual", actual))
    }
    same("height", reference.height, native.height)
    same("minIntrinsicWidth", reference.minIntrinsicWidth, native.minIntrinsicWidth)
    same("maxIntrinsicWidth", reference.maxIntrinsicWidth, native.maxIntrinsicWidth)
    same("firstBaseline", reference.firstBaseline, native.firstBaseline)
    same("lastBaseline", reference.lastBaseline, native.lastBaseline)
    if (native.lineCount != reference.lineCount) differences.put("lineCount")
    if (native.didExceedMaxLines != reference.didExceedMaxLines) differences.put("didExceedMaxLines")
    for (line in 0 until minOf(native.lineCount, reference.lineCount)) {
        same("top[$line]", reference.getLineTop(line), native.getLineTop(line))
        same("bottom[$line]", reference.getLineBottom(line), native.getLineBottom(line))
        same("baseline[$line]", reference.getLineBaseline(line), native.getLineBaseline(line))
        same("left[$line]", reference.getLineLeft(line), native.getLineLeft(line))
        same("right[$line]", reference.getLineRight(line), native.getLineRight(line))
        if (reference.getLineStart(line) != native.getLineStart(line)) differences.put("start[$line]")
        if (reference.getLineEnd(line, true) != native.getLineEnd(line, true)) differences.put("end[$line]")
    }
    for (offset in text.indices) {
        val expected = reference.getBoundingBox(offset)
        val actual = native.getBoundingBox(offset)
        same("box[$offset].left", expected.left, actual.left)
        same("box[$offset].top", expected.top, actual.top)
        same("box[$offset].right", expected.right, actual.right)
        same("box[$offset].bottom", expected.bottom, actual.bottom)
    }
    val height = maxOf(1, ceil(maxOf(native.height, reference.height)).toInt() + 16)
    val expectedBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val actualBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    try {
        if (underline) {
            reference.paint(ReferenceCanvas(Canvas(expectedBitmap)), ReferenceColor.Black, null,
                androidx.compose.ui.text.style.TextDecoration.Underline)
            native.paint(Canvas(actualBitmap), app.smallthingz.nativeui.graphics.Color.Black,
                app.smallthingz.nativeui.text.style.TextDecoration.Underline)
        } else {
            reference.paint(ReferenceCanvas(Canvas(expectedBitmap)), ReferenceColor.Black)
            native.paint(Canvas(actualBitmap), app.smallthingz.nativeui.graphics.Color.Black)
        }
        val expectedPixels = IntArray(width * height)
        val actualPixels = IntArray(width * height)
        expectedBitmap.getPixels(expectedPixels, 0, width, 0, 0, width, height)
        actualBitmap.getPixels(actualPixels, 0, width, 0, 0, width, height)
        var mismatched = 0
        for (index in expectedPixels.indices) if (expectedPixels[index] != actualPixels[index]) mismatched++
        entry.put("pixelMismatches", mismatched).put("pixelCount", expectedPixels.size)
        entry.put("metrics", differences).put("pass", differences.length() == 0 && mismatched == 0)
        if (differences.length() != 0 || mismatched != 0) {
            artifacts.capture(entry, expectedBitmap, actualBitmap, mismatched != 0)
        }
    } finally {
        expectedBitmap.recycle()
        actualBitmap.recycle()
    }
}

/** Exact already-rendered bitmaps, capped independently so metric failures cannot hide pixel failures. */
private class ParagraphFailureArtifacts(private val context: Context) {
    private var directory: File? = null
    private var pixelPairs = 0
    private var metricPairs = 0
    private var savedPairs = 0
    val directoryPath: String? get() = directory?.absolutePath
    val capturedPairs: Int get() = savedPairs

    fun capture(entry: JSONObject, expected: Bitmap, actual: Bitmap, pixelMismatch: Boolean) {
        if (if (pixelMismatch) pixelPairs >= 8 else metricPairs >= 2) return
        if (pixelMismatch) pixelPairs++ else metricPairs++
        try {
            val target = directory ?: File(context.filesDir,
                "native-text-backend-${System.currentTimeMillis()}").also {
                check(it.mkdir())
                directory = it
            }
            val name = "${entry.getString("role")}-${entry.getString("case")}-${entry.get("fontScale")}"
            val expectedFile = File(target, "$name-expected.png")
            val actualFile = File(target, "$name-actual.png")
            expectedFile.outputStream().use { check(expected.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            actualFile.outputStream().use { check(actual.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            entry.put("expected_png", expectedFile.absolutePath).put("actual_png", actualFile.absolutePath)
            savedPairs++
        } catch (error: Exception) {
            entry.put("diagnostic_error", error.javaClass.name + ": " + error.message)
        }
    }
}

private fun NativeStyle.referenceStyle(): ReferenceStyle {
    fun app.smallthingz.nativeui.unit.TextUnit.reference(): ReferenceUnit = when {
        isSp -> ReferenceUnit(value, ReferenceUnitType.Sp)
        isEm -> ReferenceUnit(value, ReferenceUnitType.Em)
        else -> ReferenceUnit.Unspecified
    }
    return ReferenceStyle(
        fontSize = fontSize.reference(),
        fontWeight = fontWeight?.let { ReferenceWeight(it.weight) },
        fontFamily = when (fontFamily) {
            app.smallthingz.nativeui.text.font.FontFamily.SansSerif -> ReferenceFamily.SansSerif
            app.smallthingz.nativeui.text.font.FontFamily.Serif -> ReferenceFamily.Serif
            app.smallthingz.nativeui.text.font.FontFamily.Monospace -> ReferenceFamily.Monospace
            app.smallthingz.nativeui.text.font.FontFamily.Cursive -> ReferenceFamily.Cursive
            app.smallthingz.nativeui.text.font.FontFamily.Default -> ReferenceFamily.Default
            else -> null
        },
        letterSpacing = letterSpacing.reference(),
        lineHeight = lineHeight.reference(),
        platformStyle = platformStyle?.let { ReferencePlatformStyle(includeFontPadding = it.includeFontPadding) },
        lineHeightStyle = lineHeightStyle?.let {
            ReferenceLineHeight(
                alignment = when (it.alignment) {
                    app.smallthingz.nativeui.text.style.LineHeightStyle.Alignment.Top -> ReferenceLineHeight.Alignment.Top
                    app.smallthingz.nativeui.text.style.LineHeightStyle.Alignment.Center -> ReferenceLineHeight.Alignment.Center
                    app.smallthingz.nativeui.text.style.LineHeightStyle.Alignment.Bottom -> ReferenceLineHeight.Alignment.Bottom
                    app.smallthingz.nativeui.text.style.LineHeightStyle.Alignment.Proportional -> ReferenceLineHeight.Alignment.Proportional
                },
                trim = when (it.trim) {
                    app.smallthingz.nativeui.text.style.LineHeightStyle.Trim.None -> ReferenceLineHeight.Trim.None
                    app.smallthingz.nativeui.text.style.LineHeightStyle.Trim.FirstLineTop -> ReferenceLineHeight.Trim.FirstLineTop
                    app.smallthingz.nativeui.text.style.LineHeightStyle.Trim.LastLineBottom -> ReferenceLineHeight.Trim.LastLineBottom
                    app.smallthingz.nativeui.text.style.LineHeightStyle.Trim.Both -> ReferenceLineHeight.Trim.Both
                },
            )
        },
    )
}
