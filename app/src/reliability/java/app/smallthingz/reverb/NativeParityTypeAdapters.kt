package app.smallthingz.reverb

import android.content.Context
import androidx.compose.runtime.MonotonicFrameClock as ReferenceFrameClock
import androidx.compose.ui.MotionDurationScale as ReferenceMotionDurationScale
import androidx.compose.ui.geometry.Offset as ReferenceOffset
import androidx.compose.ui.geometry.Rect as ReferenceRect
import androidx.compose.ui.geometry.Size as ReferenceSize
import androidx.compose.ui.unit.Density as ReferenceDensity
import androidx.compose.ui.unit.dp as referenceDp
import androidx.compose.ui.unit.sp as referenceSp
import smallthingz.ui.MotionDurationScale as NativeMotionDurationScale
import smallthingz.ui.geometry.Offset as NativeOffset
import smallthingz.ui.geometry.Rect as NativeRect
import smallthingz.ui.geometry.Size as NativeSize
import smallthingz.ui.runtime.MonotonicFrameClock as NativeFrameClock
import smallthingz.ui.text.font.createFontFamilyResolver
import smallthingz.ui.unit.Density as NativeDensity
import smallthingz.ui.unit.Dp as NativeDp
import smallthingz.ui.unit.TextUnit as NativeTextUnit
import smallthingz.ui.unit.TextUnitType as NativeTextUnitType
import smallthingz.ui.unit.sp as nativeSp
import app.smallthingz.reverb.nativeui.ReverbPalette
import app.smallthingz.reverb.nativeui.ReverbViewEnvironment
import kotlin.coroutines.CoroutineContext

/**
 * QA transport boundaries only. The released renderer retains its Compose objects; native owners
 * receive separate native objects. No resolver, mutable model, renderer or layout is shared.
 */
internal fun ReferenceDensity.nativeParityDensity(): NativeDensity {
    val reference = this
    return object : NativeDensity {
        override val density: Float get() = reference.density
        override val fontScale: Float get() = reference.fontScale

        // Preserve the supplied density's conversion policy, including nonlinear context densities.
        override fun NativeTextUnit.toDp(): NativeDp {
            check(type == NativeTextUnitType.Sp)
            val sp = value
            return NativeDp(with(reference) { sp.referenceSp.toDp().value })
        }

        override fun NativeDp.toSp(): NativeTextUnit {
            val dp = value
            return with(reference) { dp.referenceDp.toSp().value }.nativeSp
        }
    }
}

internal fun nativeParityEnvironment(
    context: Context,
    density: ReferenceDensity,
    palette: ReverbPalette,
): ReverbViewEnvironment = ReverbViewEnvironment(
    density.nativeParityDensity(), createFontFamilyResolver(context), palette,
)

internal fun ReferenceRect.nativeParityRect(): NativeRect = NativeRect(left, top, right, bottom)
internal fun ReferenceOffset.nativeParityOffset(): NativeOffset = NativeOffset(x, y)
internal fun ReferenceSize.nativeParitySize(): NativeSize = NativeSize(width, height)

/** Keep the actual reference clock intact while delivering identical frame timestamps to native. */
internal fun CoroutineContext.withNativeParityClock(): CoroutineContext {
    val reference = checkNotNull(this[ReferenceFrameClock]) { "QA reference frame clock is missing" }
    var context = this + object : NativeFrameClock {
        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R =
            reference.withFrameNanos(onFrame)
    }
    this[ReferenceMotionDurationScale]?.let { referenceScale ->
        context += object : NativeMotionDurationScale {
            override val scaleFactor: Float get() = referenceScale.scaleFactor
        }
    }
    return context
}

/** Exact application-supported style inputs; unsupported source styles fail instead of degrading. */
internal fun androidx.compose.ui.text.TextStyle.nativeParityTextStyle(): smallthingz.ui.text.TextStyle {
    fun androidx.compose.ui.unit.TextUnit.native(): smallthingz.ui.unit.TextUnit = when (type) {
        androidx.compose.ui.unit.TextUnitType.Sp -> smallthingz.ui.unit.TextUnit(value, NativeTextUnitType.Sp)
        androidx.compose.ui.unit.TextUnitType.Em -> smallthingz.ui.unit.TextUnit(value, NativeTextUnitType.Em)
        else -> NativeTextUnit.Unspecified
    }
    val family = when (fontFamily) {
        null -> null
        androidx.compose.ui.text.font.FontFamily.Default -> smallthingz.ui.text.font.FontFamily.Default
        androidx.compose.ui.text.font.FontFamily.SansSerif -> smallthingz.ui.text.font.FontFamily.SansSerif
        androidx.compose.ui.text.font.FontFamily.Serif -> smallthingz.ui.text.font.FontFamily.Serif
        androidx.compose.ui.text.font.FontFamily.Monospace -> smallthingz.ui.text.font.FontFamily.Monospace
        androidx.compose.ui.text.font.FontFamily.Cursive -> smallthingz.ui.text.font.FontFamily.Cursive
        else -> error("Unsupported QA font family: $fontFamily")
    }
    check(brush == null && shadow == null && background == androidx.compose.ui.graphics.Color.Unspecified)
    check(fontFeatureSettings == null && baselineShift == null && textGeometricTransform == null)
    check(textIndent == null && localeList == null)
    check(textAlign == androidx.compose.ui.text.style.TextAlign.Unspecified)
    check(textDirection == androidx.compose.ui.text.style.TextDirection.Unspecified)
    check(lineBreak == androidx.compose.ui.text.style.LineBreak.Unspecified)
    check(hyphens == androidx.compose.ui.text.style.Hyphens.Unspecified)
    return smallthingz.ui.text.TextStyle(
        color = smallthingz.ui.graphics.Color(color.value),
        fontSize = fontSize.native(),
        fontWeight = fontWeight?.let { smallthingz.ui.text.font.FontWeight(it.weight) },
        fontStyle = when (fontStyle) {
            null -> null
            androidx.compose.ui.text.font.FontStyle.Normal -> smallthingz.ui.text.font.FontStyle.Normal
            androidx.compose.ui.text.font.FontStyle.Italic -> smallthingz.ui.text.font.FontStyle.Italic
            else -> error("Unsupported QA font style")
        },
        fontSynthesis = when (fontSynthesis) {
            null -> null
            androidx.compose.ui.text.font.FontSynthesis.None -> smallthingz.ui.text.font.FontSynthesis.None
            androidx.compose.ui.text.font.FontSynthesis.All -> smallthingz.ui.text.font.FontSynthesis.All
            androidx.compose.ui.text.font.FontSynthesis.Weight -> smallthingz.ui.text.font.FontSynthesis.Weight
            androidx.compose.ui.text.font.FontSynthesis.Style -> smallthingz.ui.text.font.FontSynthesis.Style
            else -> error("Unsupported QA font synthesis")
        },
        fontFamily = family,
        letterSpacing = letterSpacing.native(),
        lineHeight = lineHeight.native(),
        platformStyle = platformStyle?.paragraphStyle?.let {
            smallthingz.ui.text.PlatformTextStyle(it.includeFontPadding)
        },
        lineHeightStyle = lineHeightStyle?.let {
            smallthingz.ui.text.style.LineHeightStyle(
                alignment = when (it.alignment) {
                    androidx.compose.ui.text.style.LineHeightStyle.Alignment.Top -> smallthingz.ui.text.style.LineHeightStyle.Alignment.Top
                    androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center -> smallthingz.ui.text.style.LineHeightStyle.Alignment.Center
                    androidx.compose.ui.text.style.LineHeightStyle.Alignment.Proportional -> smallthingz.ui.text.style.LineHeightStyle.Alignment.Proportional
                    androidx.compose.ui.text.style.LineHeightStyle.Alignment.Bottom -> smallthingz.ui.text.style.LineHeightStyle.Alignment.Bottom
                    else -> error("Unsupported QA line-height alignment")
                },
                mode = when (it.mode) {
                    androidx.compose.ui.text.style.LineHeightStyle.Mode.Fixed -> smallthingz.ui.text.style.LineHeightStyle.Mode.Fixed
                    androidx.compose.ui.text.style.LineHeightStyle.Mode.Minimum -> smallthingz.ui.text.style.LineHeightStyle.Mode.Minimum
                    androidx.compose.ui.text.style.LineHeightStyle.Mode.Tight -> smallthingz.ui.text.style.LineHeightStyle.Mode.Tight
                    else -> error("Unsupported QA line-height mode")
                },
                trim = when (it.trim) {
                    androidx.compose.ui.text.style.LineHeightStyle.Trim.None -> smallthingz.ui.text.style.LineHeightStyle.Trim.None
                    androidx.compose.ui.text.style.LineHeightStyle.Trim.FirstLineTop -> smallthingz.ui.text.style.LineHeightStyle.Trim.FirstLineTop
                    androidx.compose.ui.text.style.LineHeightStyle.Trim.LastLineBottom -> smallthingz.ui.text.style.LineHeightStyle.Trim.LastLineBottom
                    androidx.compose.ui.text.style.LineHeightStyle.Trim.Both -> smallthingz.ui.text.style.LineHeightStyle.Trim.Both
                    else -> error("Unsupported QA line-height trim")
                },
            )
        },
        textDecoration = textDecoration?.let {
            var result = smallthingz.ui.text.style.TextDecoration.None
            if (androidx.compose.ui.text.style.TextDecoration.Underline in it) result += smallthingz.ui.text.style.TextDecoration.Underline
            if (androidx.compose.ui.text.style.TextDecoration.LineThrough in it) result += smallthingz.ui.text.style.TextDecoration.LineThrough
            result
        },
    )
}

