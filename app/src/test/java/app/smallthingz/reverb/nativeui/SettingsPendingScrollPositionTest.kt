package app.smallthingz.reverb.nativeui

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsPendingScrollPositionTest {
    @Test fun repeatedRecreationBeforeFirstLayoutPreservesRestoredAnchor() {
        val expected = NativeListSavedPosition(4, 73)
        val first = SettingsPendingScrollPosition(expected)
        first.placed(null)
        val second = SettingsPendingScrollPosition(first.save(null))
        assertEquals(expected, second.save(null))
    }

    @Test fun actualPlacementConsumesRestorationAndKeepsMeasuredClamp() {
        val state = SettingsPendingScrollPosition(NativeListSavedPosition(6, 80))
        val clamped = NativeListSavedPosition(3, 12)
        state.placed(clamped)
        assertEquals(clamped, state.save(clamped))
        assertEquals(NativeListSavedPosition(0, 0), state.save(null))
    }

    @Test fun explicitRetentionFocusWinsOverStaleChildrenUntilNextLayout() {
        val state = SettingsPendingScrollPosition(null)
        val oldChildren = NativeListSavedPosition(5, 22)
        val requested = NativeListSavedPosition(1, 0)
        state.request(requested)
        assertEquals(requested, state.save(oldChildren))
        state.placed(requested)
        assertEquals(requested, state.save(requested))
    }

    @Test fun latestRequestWinsAndNewUnmeasuredListStartsAtOrigin() {
        val state = SettingsPendingScrollPosition(null)
        assertEquals(NativeListSavedPosition(0, 0), state.save(null))
        state.request(NativeListSavedPosition(2, 10))
        state.request(NativeListSavedPosition(3, 10))
        assertEquals(NativeListSavedPosition(3, 10), state.save(null))
    }

    @Test fun vanishedItemClampsAtPlacementWithoutDestroyingPendingRestoration() {
        val requested = NativeListSavedPosition(7, 31)
        val state = SettingsPendingScrollPosition(requested)
        assertEquals(null, state.pendingForPlacement(0))
        assertEquals(NativeListSavedPosition(2, 0), state.pendingForPlacement(3))
        assertEquals(requested, state.save(null))
        // Membership can grow again before actual layout; the original request still owns it.
        assertEquals(requested, state.pendingForPlacement(10))
        val clamped = state.pendingForPlacement(3)
        state.placed(clamped)
        assertEquals(clamped, state.save(clamped))
    }

    @Test fun keyedRevealUsesCatalogAfterInsertionAndReorderBeforeLayout() {
        val state = NativePendingListPosition(null)
        state.request(NativeListSavedPosition(1, 0), key = "target")
        assertEquals("target", state.requestedKey)
        val actualPosition = NativeListSavedPosition(8, 13)
        assertEquals(actualPosition, state.save(actualPosition))
        val inserted = listOf("pending-header", "pending", "header", "target", "other")
        assertEquals(NativeListSavedPosition(3, 0), state.pendingForPlacement(inserted.size, inserted::indexOf))
        val reordered = listOf("other", "target", "header")
        assertEquals(NativeListSavedPosition(1, 0), state.pendingForPlacement(reordered.size, reordered::indexOf))
        assertEquals(true, state.hasKeyedRequest)
        state.placed(NativeListSavedPosition(1, 0))
        assertEquals(false, state.hasKeyedRequest)
        assertEquals(null, state.pendingForPlacement(reordered.size, reordered::indexOf))
    }

    @Test fun removedRevealDoesNotFallBackToTheOldNumericRow() {
        val state = NativePendingListPosition(null)
        state.request(NativeListSavedPosition(1, 0), key = "removed")
        val catalog = listOf("header", "unrelated")
        assertEquals(null, state.pendingForPlacement(catalog.size, catalog::indexOf))
        assertEquals(false, state.hasKeyedRequest)
        val placed = NativeListSavedPosition(0, 19)
        assertEquals(placed, state.save(placed))
    }

    @Test fun explicitTopRequestSupersedesPendingRecordingIdentity() {
        val state = NativePendingListPosition(null)
        state.request(NativeListSavedPosition(3, 0), key = "target")
        state.request(NativeListSavedPosition(0, 0))
        assertEquals(false, state.hasKeyedRequest)
        assertEquals(NativeListSavedPosition(0, 0), state.pendingForPlacement(5) { error("obsolete key resolved") })
    }

    @Test fun userScrollCancelsOnlyKeyedRevealAndPreservesNumericRestoration() {
        val state = NativePendingListPosition(null)
        state.request(NativeListSavedPosition(3, 0), key = "target")
        state.cancelKeyedRequest()
        assertEquals(null, state.pendingForPlacement(5) { error("canceled key resolved") })
        val restored = NativeListSavedPosition(2, 17)
        state.request(restored)
        state.cancelKeyedRequest()
        assertEquals(restored, state.pendingForPlacement(5))
    }

    @Test fun keyedTargetAtOriginStillOwnsPendingPlacement() {
        val state = NativePendingListPosition(null)
        state.request(NativeListSavedPosition(0, 0), key = "target")
        assertEquals(NativeListSavedPosition(0, 0), state.save(null))
        assertEquals(true, state.hasKeyedRequest)
        val catalog = listOf("pending", "target")
        assertEquals(NativeListSavedPosition(1, 0), state.pendingForPlacement(catalog.size, catalog::indexOf))
    }
}
