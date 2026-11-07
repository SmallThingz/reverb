package app.smallthingz.reverb

import smallthingz.ui.input.phase.NativeViewPointerHitLayout
import smallthingz.ui.input.phase.NativeWindowPointerCoordinates
import smallthingz.ui.input.phase.PhasePointerRoot

import android.content.Context
import smallthingz.ui.geometry.Offset

/**
 * Only the released Home's blob area and control cluster opt into parent phase composition.
 * A standalone fixture can still dispatch normally; Main instead includes these SAME retained nodes
 * in its hit path. This adds no View, recognizer, child event redispatch or globally recursive policy.
 */
internal open class NativeHomePointerRoot(
    context: Context,
    private val geometry: NativeWindowPointerCoordinates,
    private val ownedLayout: NativeViewPointerHitLayout,
) : PhasePointerRoot(context, geometry::rootToScreen, geometry::coordinates,
    ownedLayout, geometry::begin) {
    fun parentPointerLayout(windowPosition: Offset): NativeViewPointerHitLayout {
        geometry.beginFromParent(windowPosition)
        return ownedLayout
    }
}
