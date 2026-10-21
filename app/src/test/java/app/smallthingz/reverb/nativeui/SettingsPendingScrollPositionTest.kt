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
}
