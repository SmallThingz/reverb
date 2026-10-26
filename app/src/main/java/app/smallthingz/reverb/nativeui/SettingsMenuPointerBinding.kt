package app.smallthingz.reverb.nativeui

import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import smallthingz.ui.geometry.Offset
import smallthingz.ui.geometry.Size
import smallthingz.ui.unit.Density
import app.smallthingz.reverb.NativeClickPhaseNode
import app.smallthingz.reverb.NativeDragPointerEvent
import app.smallthingz.reverb.NativePagerTouchRoot
import app.smallthingz.reverb.NativePointerPass
import app.smallthingz.reverb.NativeVerticalPhaseBinding
import app.smallthingz.reverb.NativeViewPhasePointerNode
import app.smallthingz.reverb.NativeViewPointerHitLayout
import app.smallthingz.reverb.NativeWindowPointerCoordinates
import app.smallthingz.reverb.ReverbClickPhaseEndpoint
import java.util.IdentityHashMap
import kotlin.math.roundToInt

/** Direct source modifier/child order for one menu, using the shared per-window phase router. */
internal class SettingsMenuPointerBinding(
    private val content: SettingsMenuContent,
    private val density: Density,
) : AutoCloseable {
    private var root: NativePagerTouchRoot? = null
    val geometry = NativeWindowPointerCoordinates { checkNotNull(root) }
    private val config = ViewConfiguration.get(content.context)
    private val vertical = NativeVerticalPhaseBinding(
        content.viewport,
        touchSlop = { config.scaledTouchSlop.toFloat() },
        maximumVelocity = { config.scaledMaximumFlingVelocity.toFloat() },
        parentInterested = { false }, // A sub-panel has no parent scroll/drag connection.
    )
    private val rowLayouts = IdentityHashMap<SettingsMenuItemView, Layout>()
    private var reversedRows: List<NativeViewPointerHitLayout> = emptyList()
    private val columnLayout = Layout(content.column,emptyList(),{ reversedRows })
    private val viewportChildren = listOf(columnLayout)
    private val viewportLayout = Layout(
        content.viewport,vertical.nodes,{ viewportChildren },
        clip = { local ->
            val extra = (30f*density.density).roundToInt()
            local.x >= -extra && local.x < content.viewport.width+extra &&
                local.y >= 0 && local.y < content.viewport.height
        },
    )
    private val shield = object : NativeViewPhasePointerNode {
        override val view: View get() = content
        override fun dispatch(event: NativeDragPointerEvent, pass: NativePointerPass) = Unit
        override fun cancel() = Unit
    }
    private val surfaceChildren = listOf(viewportLayout)
    private val surfaceLayout = Layout(content,listOf(shield),{ surfaceChildren },clip = {
        reverbGroupGeometry(content.width.toFloat(),content.height.toFloat(),density.density,18f)
            .contains(it.x,it.y)
    })
    val layout: NativeViewPointerHitLayout = object : NativeViewPointerHitLayout {
        override val placed get() = root?.isAttachedToWindow == true
        override val size get() = Size((root?.width ?: 0).toFloat(),(root?.height ?: 0).toFloat())
        override val minimumTouchTarget get() = Size(48f*density.density,48f*density.density)
        override val nodes = emptyList<NativeViewPhasePointerNode>()
        override val childrenInHitOrder = listOf(surfaceLayout)
        override fun screenToLocal(screen: Offset) = geometry.coordinates(checkNotNull(root)).local(screen)
        override fun withinLayerBounds(local: Offset) = true
    }

    fun bindRoot(root: NativePagerTouchRoot) {
        check(this.root == null)
        this.root = root
        refreshRows()
    }

    fun refreshRows() {
        val retained = content.column.rows
        rowLayouts.keys.removeAll { it !in retained }
        for (row in retained) {
            row.pointerRoot = root
            rowLayouts.getOrPut(row) {
                val node = NativeClickPhaseNode(row,ReverbClickPhaseEndpoint(row.input),vertical::interested)
                Layout(row,listOf(node),{ emptyList() },clip = {
                    reverbGroupGeometry(row.width.toFloat(),row.height.toFloat(),density.density,12f)
                        .contains(it.x,it.y)
                })
            }
        }
        reversedRows = retained.asReversed().map { checkNotNull(rowLayouts[it]) }
    }

    // Touch receives current ancestor interest explicitly through NativeClickPhaseNode. The
    // ordinary View fallback has no synthetic captured MotionEvent pretending to be that change.
    fun interestedParent(event: MotionEvent): Boolean = false

    private inner class Layout(
        private val view: View,
        override val nodes: List<NativeViewPhasePointerNode>,
        private val children: () -> List<NativeViewPointerHitLayout>,
        private val clip: (Offset) -> Boolean = { true },
    ) : NativeViewPointerHitLayout {
        override val placed get() = view.isAttachedToWindow && view.isLaidOut && view.visibility == View.VISIBLE
        override val size get() = Size(view.width.toFloat(),view.height.toFloat())
        override val minimumTouchTarget get() = Size(48f*density.density,48f*density.density)
        override val childrenInHitOrder get() = children()
        override fun screenToLocal(screen: Offset) = geometry.coordinates(view).local(screen)
        override fun withinLayerBounds(local: Offset) = clip(local)
    }

    override fun close() {
        root?.cancelPointerInput()
        vertical.close()
        rowLayouts.clear()
        reversedRows=emptyList()
    }
}
