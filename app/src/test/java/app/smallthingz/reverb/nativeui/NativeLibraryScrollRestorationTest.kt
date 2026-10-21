package app.smallthingz.reverb.nativeui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NativeLibraryScrollRestorationTest {
    @Test fun loadingAndRepeatedSavesDoNotConsumeEitherRestoredSlot() {
        val saved = NativeLibrarySavedScrollPosition(NativeListSavedPosition(8, 37), 51)
        val restoration = NativeLibraryScrollRestoration(saved)
        assertEquals(saved, restoration.save(null, null))
        assertEquals(saved, restoration.save(null, null))
        assertEquals(saved.listPosition, restoration.consumeList())
        assertEquals(saved.emptyScrollPosition, restoration.consumeEmpty())
    }

    @Test fun mountedListUsesCurrentPositionWhileEmptyRestoreRemainsPending() {
        val restoration = NativeLibraryScrollRestoration(
            NativeLibrarySavedScrollPosition(NativeListSavedPosition(8, 37), 51))
        restoration.consumeList()
        assertEquals(NativeLibrarySavedScrollPosition(NativeListSavedPosition(4, 19), 51),
            restoration.save(NativeListSavedPosition(4, 19), null))
    }

    @Test fun disposedConsumedListDoesNotResurrectItsOldPosition() {
        val restoration = NativeLibraryScrollRestoration(
            NativeLibrarySavedScrollPosition(NativeListSavedPosition(8, 37), null))
        restoration.consumeList()
        assertEquals(NativeLibrarySavedScrollPosition(), restoration.save(null, null))
        assertNull(restoration.consumeList())
    }

    @Test fun emptyZeroIsARealSavedSlotAndIsConsumedOnlyOnce() {
        val restoration = NativeLibraryScrollRestoration(NativeLibrarySavedScrollPosition(null, 0))
        assertEquals(0, restoration.save(null, null).emptyScrollPosition)
        assertEquals(0, restoration.consumeEmpty())
        assertEquals(NativeLibrarySavedScrollPosition(null, 12), restoration.save(null, 12))
        assertNull(restoration.consumeEmpty())
        assertEquals(NativeLibrarySavedScrollPosition(), restoration.save(null, null))
    }

    @Test fun freshBranchPendingZeroWinsOverRetainedOldHoldersUntilPlacement() {
        val position = NativePendingListPosition(NativeListSavedPosition(0, 0))
        val staleDetachedPosition = NativeListSavedPosition(8, 37)
        assertEquals(NativeListSavedPosition(0, 0), position.save(staleDetachedPosition))
        position.placed(null)
        assertEquals(NativeListSavedPosition(0, 0), position.save(staleDetachedPosition))
        position.placed(NativeListSavedPosition(0, 0))
        assertEquals(NativeListSavedPosition(1, 12), position.save(NativeListSavedPosition(1, 12)))
    }
}
