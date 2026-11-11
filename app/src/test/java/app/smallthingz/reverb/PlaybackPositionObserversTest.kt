package app.smallthingz.reverb

import org.junit.Assert.*
import org.junit.Test

class PlaybackPositionObserversTest {
    @Test fun repeatedPresentationCannotRepostProgressTick() {
        var changes = 0
        val observers = PlaybackPositionObservers { changes++ }
        val row = Any()
        repeat(100) { observers.observe(row, false) }
        assertEquals(0, changes)
        observers.observe(row, true)
        repeat(100) { observers.observe(row, true) }
        assertEquals(1, changes)
        assertTrue(observers.observed)
        observers.observe(row, false)
        repeat(100) { observers.observe(row, false) }
        assertEquals(2, changes)
        assertFalse(observers.observed)
    }

    @Test fun distinctEqualOwnersRetainIndependentObservationLifetimes() {
        data class Row(val id: Int)
        var changes = 0
        val observers = PlaybackPositionObservers { changes++ }
        val first = Row(1)
        val second = Row(1)
        observers.observe(first, true)
        observers.observe(second, true)
        assertEquals(2, changes)
        observers.observe(first, false)
        assertTrue(observers.observed)
        observers.observe(second, false)
        assertFalse(observers.observed)
        assertEquals(4, changes)
    }
}
