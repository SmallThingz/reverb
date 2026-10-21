package app.smallthingz.reverb

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerType
import org.junit.Assert.*
import org.junit.Test

/** Proposed source-math tests; not yet in a compiled source set. */
class NativePointerHitTestTest {
    @Test fun minimumTargetUsesInclusivePaddingAndSquaredEdgeDistance() {
        assertEquals(196f, nativeMinimumTouchDistance(Offset(-14f, 10f), Size(20f, 20f), Size(48f, 48f)), 0f)
        assertTrue(nativeMinimumTouchDistance(Offset(-14.01f, 10f), Size(20f, 20f), Size(48f, 48f)).isInfinite())
        assertTrue(nativeMinimumTouchDistance(Offset(1f, 1f), Size(48f, 48f), Size(48f, 48f)).isInfinite())
    }

    @Test fun directLowerSiblingWinsOverFrontSiblingMinimumTarget() {
        val front = node("front")
        val back = node("back")
        val root = layout(children = listOf(layout(front, x = 20f), layout(back)))
        assertEquals(listOf(back), nativePointerHitPath(root, Offset(10f, 10f), PointerType.Touch))
    }

    @Test fun equalNearTargetsKeepSourceFrontToBackOrderAndStylusDoesNotExpand() {
        val front = node("front")
        val back = node("back")
        val root = layout(children = listOf(layout(front, x = 20f), layout(back, x = 20f)))
        assertEquals(listOf(front), nativePointerHitPath(root, Offset(10f, 10f), PointerType.Touch))
        assertTrue(nativePointerHitPath(root, Offset(10f, 10f), PointerType.Stylus).isEmpty())
    }

    @Test fun inLayerNearHitWinsOverEarlierHitOutsideRoundedClip() {
        val clipped = node("clipped")
        val inLayer = node("inLayer")
        val root = layout(children = listOf(
            layout(clipped, clip = { false }), layout(inLayer, x = 20f),
        ))
        assertEquals(listOf(inLayer), nativePointerHitPath(root, Offset(10f, 10f), PointerType.Touch))
    }

    @Test fun ordinaryMissDoesNotAddOutOfBoundsParentButCanHitUnclippedChild() {
        val parent = node("parent")
        val child = node("child")
        val root = layout(parent, width = 100f, height = 100f,
            children = listOf(layout(child, x = 110f)))
        assertEquals(listOf(child), nativePointerHitPath(root, Offset(115f, 10f), PointerType.Touch))
    }

    @Test fun acceptedParentRetainsOnlyBestChildPathAndRightEdgeIsNotDirect() {
        val parent = node("parent")
        val edge = node("edge")
        val direct = node("direct")
        val root = layout(parent, width = 100f, height = 100f,
            children = listOf(layout(edge), layout(direct, x = 20f)))
        assertEquals(listOf(parent, direct), nativePointerHitPath(root, Offset(20f, 10f), PointerType.Touch))
    }

    private fun node(name: String) = object : NativePhasePointerNode {
        override val attached = true
        override fun dispatch(event: NativeDragPointerEvent, pass: NativePointerPass) = Unit
        override fun cancel() = Unit
        override fun toString() = name
    }

    private fun layout(
        node: NativePhasePointerNode? = null,
        x: Float = 0f,
        width: Float = 20f,
        height: Float = 20f,
        children: List<NativePointerHitLayout> = emptyList(),
        clip: (Offset) -> Boolean = { true },
    ) = object : NativePointerHitLayout {
        override val placed = true
        override val size = Size(width, height)
        override val minimumTouchTarget = Size(48f, 48f)
        override val nodes = listOfNotNull(node)
        override val childrenInHitOrder = children
        override fun screenToLocal(screen: Offset) = screen - Offset(x, 0f)
        override fun withinLayerBounds(local: Offset) = clip(local)
    }
}
