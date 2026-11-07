package app.smallthingz.reverb.nativeui

import smallthingz.ui.scroll.NativeOverscrollAdapter

import android.content.Context
import smallthingz.ui.scroll.NativeOverscrollAxis
import smallthingz.ui.unit.Density

internal class NativeHorizontalOverscroll(
    context: Context,
    density: Density,
    glowColor: Int,
    invalidate: () -> Unit,
) : NativeOverscrollAdapter(context, density, glowColor, NativeOverscrollAxis.HORIZONTAL, invalidate)
