package app.smallthingz.reverb.nativeui

import kotlin.math.max
import kotlin.math.roundToInt

/** Remaining physical insets; source systemBars.union(displayCutout).only(Horizontal + Top). */
internal data class NativeSettingsTopBarInsets(val left: Int, val top: Int, val right: Int) {
    companion object {
        fun remaining(
            barsLeft: Int, barsTop: Int, barsRight: Int,
            cutoutLeft: Int, cutoutTop: Int, cutoutRight: Int,
            consumedLeft: Int, consumedTop: Int, consumedRight: Int,
        ) = NativeSettingsTopBarInsets(
            (max(barsLeft, cutoutLeft) - consumedLeft).coerceAtLeast(0),
            (max(barsTop, cutoutTop) - consumedTop).coerceAtLeast(0),
            (max(barsRight, cutoutRight) - consumedRight).coerceAtLeast(0),
        )
    }
}

internal data class NativeSettingsTopBarPlacement(
    val navigationX: Int, val navigationY: Int,
    val titleX: Int, val titleY: Int,
    val actionX: Int, val actionY: Int,
)

/** Pinned M3 TopAppBarMeasurePolicy.placeTopAppBar, null scrollBehavior, Center title. */
internal fun settingsTopBarPlacement(
    width: Int, height: Int,
    navigationWidth: Int, navigationHeight: Int,
    titleWidth: Int, titleHeight: Int,
    actionWidth: Int, actionHeight: Int,
    density: Float, rtl: Boolean,
): NativeSettingsTopBarPlacement {
    val start = max((12f * density).roundToInt(), navigationWidth)
    val end = actionWidth
    // Alignment.CenterHorizontally uses Float half then roundToInt, not integer division.
    var titleStart = ((width - titleWidth) * .5f).roundToInt()
    if (titleStart < start) titleStart += start - titleStart
    else if (titleStart + titleWidth > width - end) {
        titleStart += width - end - (titleStart + titleWidth)
    }
    fun physical(start: Int, childWidth: Int) = if (rtl) width - start - childWidth else start
    return NativeSettingsTopBarPlacement(
        physical(0, navigationWidth), (height - navigationHeight) / 2,
        physical(titleStart, titleWidth), (height - titleHeight) / 2,
        physical(width - actionWidth, actionWidth), (height - actionHeight) / 2,
    )
}
