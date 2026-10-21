package app.smallthingz.reverb

import android.content.Context
import androidx.compose.ui.geometry.Offset

/**
 * Only the released Home's blob area and control cluster opt into parent phase composition.
 * A standalone fixture can still dispatch normally; Main instead includes these SAME retained nodes
 * in its hit path. This adds no View, recognizer, child event redispatch or globally recursive policy.
 */
internal open class NativeHomePointerRoot(
    context: Context,
    private val geometry: NativeWindowPointerCoordinates,
    private val ownedLayout: NativeViewPointerHitLayout,
) : NativePagerTouchRoot(context, geometry::rootToScreen, geometry::coordinates,
    ownedLayout, geometry::begin) {
    fun parentPointerLayout(windowPosition: Offset): NativeViewPointerHitLayout {
        geometry.beginFromParent(windowPosition)
        return ownedLayout
    }
}
