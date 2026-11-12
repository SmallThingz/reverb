package app.smallthingz.reverb.nativeui

import org.junit.Assert.*
import org.junit.Test
import smallthingz.ui.geometry.Offset
import smallthingz.ui.geometry.Size
import smallthingz.ui.input.phase.*
import smallthingz.ui.input.pointer.PointerType

class InlineWaveformHitTargetTest {
    private val width = 922f
    private val minimum = 48f * 421f / 160f
    private val height = 337f

    private class Node(private val owner: InlineWaveformPointerOwner? = null) : NativePhasePointerNode {
        override val attached = true
        override fun dispatch(event: NativeDragPointerEvent, pass: NativePointerPass) {
            if (pass == NativePointerPass.MAIN) owner?.dispatch(event, 922)
        }
        override fun cancel() { owner?.cancel() }
    }
    private class Layout(
        override val size: Size,
        override val minimumTouchTarget: Size = Size.Zero,
        override val nodes: List<NativePhasePointerNode> = emptyList(),
        override val childrenInHitOrder: List<NativePointerHitLayout> = emptyList(),
        private val origin: Offset = Offset.Zero,
        private val clip: Boolean = false,
    ) : NativePointerHitLayout {
        override val placed = true
        override fun screenToLocal(screen: Offset) = screen - origin
        override fun withinLayerBounds(local: Offset) = !clip ||
            (local.x >= 0f && local.y >= 0f && local.x < size.width && local.y < size.height)
    }
    private fun timeline(node: Node, trim: Boolean = true) = Layout(Size(width, height),
        inlineWaveformMinimumTouchTarget(width, minimum, trim), listOf(node))
    private fun hit(layout: NativePointerHitLayout, x: Float) =
        nativePointerHitPath(layout, Offset(x, 160f), PointerType.Touch)

    @Test fun bothBoundaryCentersAndVisibleOuterHalvesReachTheWaveformOwner() {
        val node = Node()
        val layout = timeline(node)
        for (x in listOf(-6f * 421f / 160f, 0f, width, width + 6f * 421f / 160f)) {
            assertEquals("visible boundary at $x", listOf(node), hit(layout, x))
        }
        assertTrue(hit(layout, width + minimum / 2f + 1f).isEmpty())
    }

    @Test fun endCenterDownRetainsOneOwnerWhileDraggingInwardAndReleasing() {
        val calls = mutableListOf<String>()
        val owner = InlineWaveformPointerOwner()
        owner.bind(InlineWaveformPointerBinding(InlineWaveformPointerKey("two-second", 2000, true, true),
            false, { x, w -> calls += "begin:${(2000f * (x / w).coerceIn(0f, 1f)).toInt()}" },
            { x, w -> calls += "move:${(2000f * (x / w).coerceIn(0f, 1f)).toInt()}" },
            { calls += "finish:$it" }))
        val node = Node(owner)
        val tree = NativePointerPhaseTree()
        tree.add(1L, hit(timeline(node), width))
        fun dispatch(x: Float, pressed: Boolean, previous: Boolean) {
            val point = Offset(x, 160f)
            val event = NativeDragPointerEvent(listOf(NativeDragPointerChange(1L, 1L, point,
                point, pressed, previous, PointerType.Touch, point)), Offset.Zero)
            tree.dispatch { _, _ -> event }
        }
        dispatch(width, true, false)
        dispatch(width / 2f, true, true)
        dispatch(width / 2f, false, true)
        tree.release(setOf(1L)); tree.cancel()
        assertEquals(listOf("begin:2000", "move:1000", "finish:true"), calls)
    }

    @Test fun ordinaryWaveformKeepsItsOriginalBounds() {
        val node = Node(); val layout = timeline(node, trim = false)
        assertEquals(listOf(node), hit(layout, width - 1f))
        assertTrue(hit(layout, width).isEmpty())
        assertTrue(hit(layout, -1f).isEmpty())
    }

    @Test fun directSiblingStillWinsOverExpandedTimelineNearHit() {
        val waveform = Node(); val sibling = Node()
        val root = Layout(Size(1100f, height), childrenInHitOrder = listOf(
            timeline(waveform), Layout(Size(40f, height), nodes = listOf(sibling), origin = Offset(width, 0f))))
        assertEquals(listOf(sibling), hit(root, width + 4f))
    }

    @Test fun ancestorClipStillRejectsExpandedTargetOutsideThePlayer() {
        val node = Node()
        val root = Layout(Size(width + 20f, height), childrenInHitOrder = listOf(timeline(node)), clip = true)
        assertEquals(listOf(node), hit(root, width + 10f))
        assertTrue(hit(root, width + 21f).isEmpty())
    }
}
