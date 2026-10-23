package app.smallthingz.reverb.nativeui

import smallthingz.ui.util.fastRoundToInt
import kotlin.math.abs

/**
 * LazyListState.onScroll's physical-axis fractional consumption, with actual measured native
 * consumption replacing forceRemeasure. No estimated content extent or duplicate item positions.
 */
internal class NativeLibraryScrollAccumulator {
    private var pending = 0f
    fun dispatch(distance: Float, canScrollForward: Boolean, canScrollBackward: Boolean,
                 consumeInteger: (Int) -> Int): Float {
        if (distance.isNaN()) return 0f // DefaultScrollableState's caller guard.
        if (distance < 0f && !canScrollForward || distance > 0f && !canScrollBackward) return 0f
        check(abs(pending) <= .5f) { "entered drag with non-zero pending scroll" }
        pending += distance
        if (abs(pending) > .5f) pending -= consumeInteger(pending.fastRoundToInt())
        if (abs(pending) <= .5f) return distance
        val consumed = distance - pending
        pending = 0f
        return consumed
    }
}
