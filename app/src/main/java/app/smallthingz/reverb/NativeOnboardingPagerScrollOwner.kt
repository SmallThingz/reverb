package app.smallthingz.reverb

import app.smallthingz.nativeui.foundation.gestures.ScrollScope
import app.smallthingz.nativeui.geometry.Offset
import app.smallthingz.nativeui.unit.Velocity
import app.smallthingz.reverb.nativeui.NativeHorizontalOverscroll
import app.smallthingz.reverb.nativeui.NativeVerticalNestedHost
import app.smallthingz.reverb.nativeui.NativeVerticalScrollSource
import kotlinx.coroutines.CancellationException

/**
 * Released onboarding's actual nested topology: horizontal pager below a non-scrolling screen,
 * with vertical-only page scrollables beneath it. This is intentionally not a generic scroll bus.
 *
 * ScrollableNode.drag first calls singleAxisOffset(), so a vertical page sends x=0. The horizontal
 * DefaultPagerNestedScrollConnection's pre/post scroll and post-fling therefore consume no Y.
 * The screen has no outer scrollable. These zero continuations are derived from that exact source
 * topology, not a fallback for an unknown ancestor. A popup or another screen supplies its own host.
 *
 * Horizontal per-frame overscroll still wraps self-scroll in BOTH drag and snap-fling paths.
 * Requires adjacent pager-fling-host-delivery.patch; the pure engine's default behavior is unchanged.
 */
internal class NativeOnboardingPagerScrollOwner(
    private val pager: NativeOnboardingPager,
    val overscroll: NativeHorizontalOverscroll,
    private val isAttached: () -> Boolean,
) {
    val shouldScrollImmediately: Boolean
        get() = pager.isScrollInProgress || overscroll.isInProgress

    val pageNestedHost: NativeVerticalNestedHost = object : NativeVerticalNestedHost {
        override fun preScroll(availableY: Float, source: NativeVerticalScrollSource) = 0f
        override fun postScroll(consumedY: Float, availableY: Float, source: NativeVerticalScrollSource) = 0f
        override suspend fun preFling(availableY: Float) = 0f
        override suspend fun postFling(consumedY: Float, availableY: Float) = 0f
    }

    fun dragBy(scope: ScrollScope, physicalDelta: Offset) {
        with(scope) { scrollWithOverscroll(physicalDelta.x, NativeVerticalScrollSource.USER_INPUT) }
    }

    /** Drag mutation has already ended; caller launches this in the attached input node's scope. */
    suspend fun stopped(physicalVelocity: Velocity) {
        // Source onScrollStopped first projects the velocity onto this orientation.
        val initial = physicalVelocity.x
        val stretch = if (pager.canScrollForward || pager.canScrollBackward)
            overscroll.beginFling(initial) else null
        val admitted = stretch?.remainingVelocity ?: initial
        // No horizontal nested ancestor exists in the released onboarding tree.
        val remaining = pager.fling(admitted) { logicalDelta ->
            if (logicalDelta != 0f && !isAttached()) throw NativePagerNodeDetachedFling()
            pager.physicalToLogicalDelta(
                scrollWithOverscroll(
                    pager.physicalToLogicalDelta(logicalDelta),
                    NativeVerticalScrollSource.SIDE_EFFECT,
                ),
            )
        }
        // This is normal completion, not finally: cancellation of a suspended owner must not
        // fabricate post-fling/edge completion. A source local detach cancellation returns velocity.
        if (stretch != null) overscroll.finishFling(stretch, remaining)
    }

    private fun ScrollScope.scrollWithOverscroll(
        physicalX: Float,
        source: NativeVerticalScrollSource,
    ): Float {
        fun self(delta: Float): Float =
            pager.physicalToLogicalDelta(scrollBy(pager.physicalToLogicalDelta(delta)))
        return if (pager.canScrollForward || pager.canScrollBackward)
            overscroll.scroll(physicalX, source, ::self)
        else self(physicalX)
    }

    private class NativePagerNodeDetachedFling : CancellationException()
}
