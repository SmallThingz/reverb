package app.smallthingz.reverb

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
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
import kotlin.math.ceil

/**
 * Isolated QA oracle: real released Compose Paragraph is compared with the native Android backend.
 * Never linked into production. This draws owned Bitmaps only and does not touch app/device state.
 * It qualifies paragraph metrics/rasterization, not complete screen or hardware-rendering parity.
 */
internal fun nativeTextBackendParity(context: Context): JSONObject {
    val results = JSONArray()
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
                for (offset in case.text.indices) {
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
                } finally {
                    expectedBitmap.recycle()
                    actualBitmap.recycle()
                }
            } catch (error: Exception) {
                entry.put("pass", false).put("error", error.javaClass.name + ": " + error.message)
            }
            results.put(entry)
        }
    }
    var failures = 0
    for (index in 0 until results.length()) if (!results.getJSONObject(index).getBoolean("pass")) failures++
    return JSONObject().put("scope", "native paragraph versus real released paragraph; software bitmap")
        .put("cases", results).put("failures", failures).put("pass", failures == 0)
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
