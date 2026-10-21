/*
 * Source-derived from AndroidX Material3 1.4.0 MenuPosition.kt and Menu.kt.
 * Copyright 2023 The Android Open Source Project.
 * Licensed under the Apache License, Version 2.0:
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package app.smallthingz.reverb.nativeui

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal data class SettingsMenuBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
    val centerX get() = left + width / 2
    val centerY get() = top + height / 2
}
internal data class SettingsMenuPlacement(val x: Int, val y: Int, val pivotX: Float, val pivotY: Float)

/** Released DropdownMenuPositionProvider with the caller's fixed zero DpOffset. */
internal fun settingsMenuPlacement(
    anchor: SettingsMenuBounds,
    windowWidth: Int,
    windowHeight: Int,
    menuWidth: Int,
    menuHeight: Int,
    rtl: Boolean,
    verticalMargin: Int,
): SettingsMenuPlacement {
    // Keep source candidate order and Float bias-alignment rounding, including oversized windows.
    fun centered(space: Int, size: Int) = ((space - size) / 2f).roundToInt()
    val windowX = if (menuWidth >= windowWidth) centered(windowWidth, menuWidth)
        else if (anchor.centerX < windowWidth / 2) 0 else windowWidth - menuWidth
    val starts = if (rtl) anchor.right - menuWidth else anchor.left
    val ends = if (rtl) anchor.left else anchor.right - menuWidth
    val x = when {
        starts >= 0 && starts + menuWidth <= windowWidth -> starts
        ends >= 0 && ends + menuWidth <= windowWidth -> ends
        else -> windowX
    }
    val below = anchor.bottom
    val above = anchor.top - menuHeight
    val centeredOnTop = anchor.top - (menuHeight / 2f).roundToInt()
    val windowY = if (menuHeight >= windowHeight - 2 * verticalMargin)
        centered(windowHeight, menuHeight)
    else if (anchor.centerY < windowHeight / 2) verticalMargin
    else windowHeight - verticalMargin - menuHeight
    fun fits(y: Int) = y >= verticalMargin && y + menuHeight <= windowHeight - verticalMargin
    val y = when {
        fits(below) -> below
        fits(above) -> above
        fits(centeredOnTop) -> centeredOnTop
        else -> windowY
    }
    val right = x + menuWidth
    val bottom = y + menuHeight
    val pivotX = when {
        x >= anchor.right -> 0f
        right <= anchor.left -> 1f
        menuWidth == 0 -> 0f
        else -> ((max(anchor.left, x) + min(anchor.right, right)) / 2 - x).toFloat() / menuWidth
    }
    val pivotY = when {
        y >= anchor.bottom -> 0f
        bottom <= anchor.top -> 1f
        menuHeight == 0 -> 0f
        else -> ((max(anchor.top, y) + min(anchor.bottom, bottom)) / 2 - y).toFloat() / menuHeight
    }
    return SettingsMenuPlacement(x, y, pivotX, pivotY)
}
