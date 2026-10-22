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
import app.smallthingz.nativeui.MotionDurationScale as NativeMotionDurationScale
import app.smallthingz.nativeui.geometry.Offset as NativeOffset
import app.smallthingz.nativeui.geometry.Rect as NativeRect
import app.smallthingz.nativeui.geometry.Size as NativeSize
import app.smallthingz.nativeui.runtime.MonotonicFrameClock as NativeFrameClock
import app.smallthingz.nativeui.text.font.createFontFamilyResolver
import app.smallthingz.nativeui.unit.Density as NativeDensity
import app.smallthingz.nativeui.unit.Dp as NativeDp
import app.smallthingz.nativeui.unit.TextUnit as NativeTextUnit
import app.smallthingz.nativeui.unit.TextUnitType as NativeTextUnitType
import app.smallthingz.nativeui.unit.sp as nativeSp
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
internal fun androidx.compose.ui.text.TextStyle.nativeParityTextStyle(): app.smallthingz.nativeui.text.TextStyle {
    fun androidx.compose.ui.unit.TextUnit.native(): app.smallthingz.nativeui.unit.TextUnit = when (type) {
        androidx.compose.ui.unit.TextUnitType.Sp -> app.smallthingz.nativeui.unit.TextUnit(value, NativeTextUnitType.Sp)
        androidx.compose.ui.unit.TextUnitType.Em -> app.smallthingz.nativeui.unit.TextUnit(value, NativeTextUnitType.Em)
        else -> NativeTextUnit.Unspecified
    }
    val family = when (fontFamily) {
        null -> null
        androidx.compose.ui.text.font.FontFamily.Default -> app.smallthingz.nativeui.text.font.FontFamily.Default
        androidx.compose.ui.text.font.FontFamily.SansSerif -> app.smallthingz.nativeui.text.font.FontFamily.SansSerif
        androidx.compose.ui.text.font.FontFamily.Serif -> app.smallthingz.nativeui.text.font.FontFamily.Serif
        androidx.compose.ui.text.font.FontFamily.Monospace -> app.smallthingz.nativeui.text.font.FontFamily.Monospace
        androidx.compose.ui.text.font.FontFamily.Cursive -> app.smallthingz.nativeui.text.font.FontFamily.Cursive
        else -> error("Unsupported QA font family: $fontFamily")
    }
    check(brush == null && shadow == null && background == androidx.compose.ui.graphics.Color.Unspecified)
    check(fontFeatureSettings == null && baselineShift == null && textGeometricTransform == null)
    check(textIndent == null && localeList == null)
    check(textAlign == androidx.compose.ui.text.style.TextAlign.Unspecified)
    check(textDirection == androidx.compose.ui.text.style.TextDirection.Unspecified)
    check(lineBreak == androidx.compose.ui.text.style.LineBreak.Unspecified)
    check(hyphens == androidx.compose.ui.text.style.Hyphens.Unspecified)
    return app.smallthingz.nativeui.text.TextStyle(
        color = app.smallthingz.nativeui.graphics.Color(color.value),
        fontSize = fontSize.native(),
        fontWeight = fontWeight?.let { app.smallthingz.nativeui.text.font.FontWeight(it.weight) },
        fontStyle = when (fontStyle) {
            null -> null
            androidx.compose.ui.text.font.FontStyle.Normal -> app.smallthingz.nativeui.text.font.FontStyle.Normal
            androidx.compose.ui.text.font.FontStyle.Italic -> app.smallthingz.nativeui.text.font.FontStyle.Italic
            else -> error("Unsupported QA font style")
        },
        fontSynthesis = when (fontSynthesis) {
            null -> null
            androidx.compose.ui.text.font.FontSynthesis.None -> app.smallthingz.nativeui.text.font.FontSynthesis.None
            androidx.compose.ui.text.font.FontSynthesis.All -> app.smallthingz.nativeui.text.font.FontSynthesis.All
            androidx.compose.ui.text.font.FontSynthesis.Weight -> app.smallthingz.nativeui.text.font.FontSynthesis.Weight
            androidx.compose.ui.text.font.FontSynthesis.Style -> app.smallthingz.nativeui.text.font.FontSynthesis.Style
            else -> error("Unsupported QA font synthesis")
        },
        fontFamily = family,
        letterSpacing = letterSpacing.native(),
        lineHeight = lineHeight.native(),
        platformStyle = platformStyle?.paragraphStyle?.let {
            app.smallthingz.nativeui.text.PlatformTextStyle(it.includeFontPadding)
        },
        lineHeightStyle = lineHeightStyle?.let {
            app.smallthingz.nativeui.text.style.LineHeightStyle(
                alignment = when (it.alignment) {
                    androidx.compose.ui.text.style.LineHeightStyle.Alignment.Top -> app.smallthingz.nativeui.text.style.LineHeightStyle.Alignment.Top
                    androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center -> app.smallthingz.nativeui.text.style.LineHeightStyle.Alignment.Center
                    androidx.compose.ui.text.style.LineHeightStyle.Alignment.Proportional -> app.smallthingz.nativeui.text.style.LineHeightStyle.Alignment.Proportional
                    androidx.compose.ui.text.style.LineHeightStyle.Alignment.Bottom -> app.smallthingz.nativeui.text.style.LineHeightStyle.Alignment.Bottom
                    else -> error("Unsupported QA line-height alignment")
                },
                mode = when (it.mode) {
                    androidx.compose.ui.text.style.LineHeightStyle.Mode.Fixed -> app.smallthingz.nativeui.text.style.LineHeightStyle.Mode.Fixed
                    androidx.compose.ui.text.style.LineHeightStyle.Mode.Minimum -> app.smallthingz.nativeui.text.style.LineHeightStyle.Mode.Minimum
                    androidx.compose.ui.text.style.LineHeightStyle.Mode.Tight -> app.smallthingz.nativeui.text.style.LineHeightStyle.Mode.Tight
                    else -> error("Unsupported QA line-height mode")
                },
                trim = when (it.trim) {
                    androidx.compose.ui.text.style.LineHeightStyle.Trim.None -> app.smallthingz.nativeui.text.style.LineHeightStyle.Trim.None
                    androidx.compose.ui.text.style.LineHeightStyle.Trim.FirstLineTop -> app.smallthingz.nativeui.text.style.LineHeightStyle.Trim.FirstLineTop
                    androidx.compose.ui.text.style.LineHeightStyle.Trim.LastLineBottom -> app.smallthingz.nativeui.text.style.LineHeightStyle.Trim.LastLineBottom
                    androidx.compose.ui.text.style.LineHeightStyle.Trim.Both -> app.smallthingz.nativeui.text.style.LineHeightStyle.Trim.Both
                    else -> error("Unsupported QA line-height trim")
                },
            )
        },
        textDecoration = textDecoration?.let {
            var result = app.smallthingz.nativeui.text.style.TextDecoration.None
            if (androidx.compose.ui.text.style.TextDecoration.Underline in it) result += app.smallthingz.nativeui.text.style.TextDecoration.Underline
            if (androidx.compose.ui.text.style.TextDecoration.LineThrough in it) result += app.smallthingz.nativeui.text.style.TextDecoration.LineThrough
            result
        },
    )
}

