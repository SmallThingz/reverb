package app.smallthingz.reverb.nativeui

import android.content.Context
import smallthingz.ui.scroll.NativeAxisOverscroll
import smallthingz.ui.scroll.NativeOverscrollSource
import smallthingz.ui.scroll.NativeOverscrollAxis
import smallthingz.ui.unit.Density

internal class NativeVerticalOverscroll(
    context: Context,
    density: Density,
    glowColor: Int,
    invalidate: () -> Unit,
) : NativeOverscrollAdapter(context, density, glowColor, NativeOverscrollAxis.VERTICAL, invalidate)

/** Keeps the app's nested-scroll source type at the integration boundary. */
internal open class NativeOverscrollAdapter(
    context: Context,
    density: Density,
    glowColor: Int,
    axis: NativeOverscrollAxis,
    invalidate: () -> Unit,
) : NativeAxisOverscroll(context, density, glowColor, axis, invalidate) {
    fun scroll(delta: Float, source: NativeVerticalScrollSource, performScroll: (Float) -> Float): Float =
        scroll(delta, when (source) {
            NativeVerticalScrollSource.USER_INPUT -> NativeOverscrollSource.USER_INPUT
            NativeVerticalScrollSource.SIDE_EFFECT -> NativeOverscrollSource.SIDE_EFFECT
        }, performScroll)
}
