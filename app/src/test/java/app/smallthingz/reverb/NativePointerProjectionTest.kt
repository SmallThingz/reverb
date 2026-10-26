package app.smallthingz.reverb

import smallthingz.ui.geometry.Offset
import smallthingz.ui.input.pointer.PointerType
import org.junit.Assert.*
import org.junit.Test

class NativePointerProjectionTest {
    @Test fun subsetProjectionKeepsRootOrderAndTransformsOnlySelectedPositions() {
        val changes = listOf(change(3L), change(1L), change(2L))
        val frame = NativeRootPointerFrame(changes, primaryMouseButtonPressed = true)
        val mapped = mutableListOf<Offset>()
        val origin = Offset(9f, 10f)
        val projected = frame.project({ point -> mapped.add(point); point + Offset(100f, -10f) },
            origin, linkedSetOf(2L, 3L))

        assertEquals(listOf(3L, 2L), projected.changes.map { it.id })
        assertEquals(listOf(changes[0].screenPosition, changes[0].previousScreenPosition,
            changes[2].screenPosition, changes[2].previousScreenPosition), mapped)
        assertEquals(origin, projected.nodeOriginOnScreen)
        assertTrue(projected.primaryMouseButtonPressed)
        assertSame(frame.dispatchState, projected.dispatchState)
        for ((index, source) in listOf(changes[0], changes[2]).withIndex()) {
            val target = projected.changes[index]
            assertEquals(source.screenPosition + Offset(100f, -10f), target.position)
            assertEquals(source.previousScreenPosition + Offset(100f, -10f), target.previousPosition)
            assertEquals(source.timeMillis, target.timeMillis)
            assertEquals(source.pressed, target.pressed)
            assertEquals(source.previouslyPressed, target.previouslyPressed)
            assertEquals(source.type, target.type)
            assertEquals(source.originalPosition, target.originalPosition)
            assertSame(source.history, target.history)
            assertSame(source.consumption, target.consumption)
        }
    }

    @Test fun repeatedProjectionsShareConsumptionButKeepTheirOwnCoordinates() {
        val source = change(7L)
        val frame = NativeRootPointerFrame(listOf(source))
        val first = frame.project({ it }, Offset.Zero)
        val second = frame.project({ it * 2f }, Offset(1f, 2f), setOf(7L))
        first.changes.single().consume()
        assertTrue(second.changes.single().consumed)
        assertTrue(source.consumption.consumed)
        second.dispatchState.suppressMovementConsumption = true
        assertTrue(first.dispatchState.suppressMovementConsumption)
        assertEquals(source.screenPosition, first.changes.single().position)
        assertEquals(source.screenPosition * 2f, second.changes.single().position)
    }

    @Test fun emptyOrUnknownSelectionDoesNotInvokeTransform() {
        val frame = NativeRootPointerFrame(listOf(change(1L)))
        for (ids in listOf(emptySet(), setOf(99L))) {
            val projected = frame.project({ error("Unselected pointer was transformed") }, Offset.Zero, ids)
            assertTrue(projected.changes.isEmpty())
            assertSame(frame.dispatchState, projected.dispatchState)
        }
    }

    private fun change(id: Long) = NativeRootPointerChange(
        id, id.toInt(), 100L + id, Offset(id.toFloat(), 20f), Offset(id.toFloat() - 1f, 19f),
        pressed = true, previouslyPressed = id != 1L, type = PointerType.Touch,
        originalPosition = Offset(id.toFloat(), 5f),
        history = listOf(NativePointerHistory(90L, Offset(id.toFloat() - 2f, 4f))),
    )
}
