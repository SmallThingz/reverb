package app.smallthingz.reverb.nativeui

import android.view.View
import smallthingz.ui.geometry.Offset
import smallthingz.ui.geometry.Size
import smallthingz.ui.input.phase.NativeViewPhasePointerNode
import smallthingz.ui.input.phase.NativeViewPointerHitLayout
import smallthingz.ui.input.phase.NativeWindowPointerCoordinates
import smallthingz.ui.scroll.NativeSourceRecyclerView

/** Gives the painted thumb an exclusive path in the existing window pointer tree. */
internal class NativeScrollIndicatorHitLayout(
    private val viewport: NativeSourceRecyclerView,
    private val geometry: NativeWindowPointerCoordinates,
    private val inHitRegion: (Offset) -> Boolean,
    private val admitted: () -> Boolean,
) : NativeViewPointerHitLayout {
    private var retired = false
    val ownsPointer get() = !retired && viewport.isScrollIndicatorDragging
    private val delegate = viewport.scrollIndicatorPointerNode
    override val placed get() = !retired && admitted() && viewport.isAttachedToWindow &&
        viewport.isLaidOut && viewport.visibility == View.VISIBLE
    override val size get() = Size(viewport.width.toFloat(), viewport.height.toFloat())
    override val minimumTouchTarget = Size.Zero // The scrollbar supplies its own hit region.
    private val node = object : NativeViewPhasePointerNode by delegate {
        override val attached get() = placed && delegate.attached
        override val shareWithSiblings get() = false
        override fun cancel() { if (!retired) delegate.cancel() }
    }
    override val nodes = listOf(node)
    override val childrenInHitOrder = emptyList<NativeViewPointerHitLayout>()

    override fun screenToLocal(screen: Offset) = geometry.coordinates(viewport).local(screen)
    override fun withinLayerBounds(local: Offset) = !ownsPointer && inHitRegion(local) &&
        viewport.canStartScrollIndicatorDrag(local.x, local.y)

    fun retire() {
        if (retired) return
        retired = true
        delegate.cancel()
    }
}
