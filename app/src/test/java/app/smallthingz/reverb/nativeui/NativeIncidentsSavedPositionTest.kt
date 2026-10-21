package app.smallthingz.reverb.nativeui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NativeIncidentsSavedPositionTest {
    @Test fun unconsumedRestoreSurvivesLoadingAndSaveBeforeFirstLayout() {
        val restored = NativeListSavedPosition(7, 31)
        val owner = NativeIncidentsSavedPosition(restored)
        owner.leaveList() // Loading/empty branch does not create a LazyListState.
        assertEquals(restored, owner.save())
        assertEquals(restored, owner.enterList())
        assertEquals(restored, owner.save(NativeListSavedPosition(0, 0)))
        assertNull(owner.enterList()) // Metadata binds must not reapply the saved anchor.
        owner.placed(NativeListSavedPosition(7, 31))
        assertEquals(NativeListSavedPosition(8, 9), owner.save(NativeListSavedPosition(8, 9)))
    }

    @Test fun emptyOrUnmountRetiresConsumedPositionEvenBeforeFirstPlacement() {
        val owner = NativeIncidentsSavedPosition(NativeListSavedPosition(7, 31))
        owner.enterList()
        owner.leaveList()
        assertNull(owner.save())
        assertEquals(NativeListSavedPosition(0, 0), owner.enterList())
        assertEquals(NativeListSavedPosition(0, 0), owner.save())
    }

    @Test fun shorterRestoredHistoryClampsOnlyForActualPlacement() {
        val restored = NativeListSavedPosition(7, 31)
        val owner = NativeIncidentsSavedPosition(restored)
        owner.enterList()
        assertNull(owner.pendingForPlacement(0))
        assertEquals(restored, owner.save())
        assertEquals(NativeListSavedPosition(2, 0), owner.pendingForPlacement(3))
        assertEquals(restored, owner.save()) // A save before completed layout still has source input.
        owner.placed(NativeListSavedPosition(1, 17)) // Short content may back-fill preceding rows.
        assertEquals(NativeListSavedPosition(1, 17), owner.save())
        assertNull(owner.pendingForPlacement(3))
    }

    @Test fun ordinaryReopenDoesNotReuseEarlierLiveAnchor() {
        val owner = NativeIncidentsSavedPosition()
        assertNull(owner.save())
        owner.enterList()
        owner.placed(NativeListSavedPosition(4, 12))
        assertEquals(NativeListSavedPosition(4, 12), owner.save())
        owner.leaveList()
        owner.placed(NativeListSavedPosition(9, 90)) // Retired layout callback cannot revive it.
        assertNull(owner.save())
        assertEquals(NativeListSavedPosition(0, 0), owner.enterList())
    }
}
