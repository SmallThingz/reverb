package app.smallthingz.reverb.nativeui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NativeLibraryScrollRestorationTest {
    @Test fun genuineReopenClearsBothDeferredRestoreSlots() {
        val restoration = NativeLibraryScrollRestoration(
            NativeLibrarySavedScrollPosition(NativeListSavedPosition(8, 37), 51))
        restoration.openedFromHome()
        assertNull(restoration.consumeList())
        assertNull(restoration.consumeEmpty())
        assertEquals(NativeLibrarySavedScrollPosition(), restoration.save(null, null))
    }

    @Test fun reopenTopRequestSurvivesEmptyCatalogAndStalePlacedRows() {
        val position = NativePendingListPosition(null)
        val old = NativeListSavedPosition(18, 43)
        position.request(NativeListSavedPosition(0, 0))
        assertNull(position.pendingForPlacement(0))
        position.placed(null)
        assertEquals(NativeListSavedPosition(0, 0), position.save(old))
        assertEquals(NativeListSavedPosition(0, 0), position.pendingForPlacement(50))
        position.placed(NativeListSavedPosition(0, 0))
        assertEquals(NativeListSavedPosition(0, 0), position.save(NativeListSavedPosition(0, 0)))
    }

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

    @Test fun pendingRevealIsSeparateFromNumericSaverAcrossRecreationAndReorder() {
        val oldViewport = NativeListSavedPosition(8, 37)
        val pending = NativePendingListPosition(null)
        pending.request(NativeListSavedPosition(2, 0), key = "target")
        val first = NativeLibraryScrollRestoration(null)
        val saved = first.save(pending.save(oldViewport), null, "target")
        assertEquals(oldViewport, saved.listPosition)
        val recreated = NativeLibraryScrollRestoration(saved)
        assertEquals(saved, recreated.save(null, null))
        assertEquals(saved, recreated.save(null, null))
        assertEquals(oldViewport, recreated.consumeList())
        val reveal = recreated.consumeReveal()
        assertEquals("target", reveal)
        assertNull(recreated.consumeReveal())
        val newPending = NativePendingListPosition(oldViewport)
        newPending.request(oldViewport, key = reveal)
        val changedCatalog = listOf("new-header", "pending-save", "other", "target")
        assertEquals(NativeListSavedPosition(3, 0),
            newPending.pendingForPlacement(changedCatalog.size, changedCatalog::indexOf))
        newPending.placed(NativeListSavedPosition(3, 0))
        assertEquals(NativeLibrarySavedScrollPosition(NativeListSavedPosition(3, 0)),
            recreated.save(newPending.save(NativeListSavedPosition(3, 0)), null))
    }

    @Test fun explicitHomeOpenRetiresSavedRevealWhileKeepingOldNumericFormatCompatible() {
        val saved = NativeLibrarySavedScrollPosition(NativeListSavedPosition(2, 17), 9, "target")
        val restoration = NativeLibraryScrollRestoration(saved)
        restoration.openedFromHome()
        assertNull(restoration.consumeReveal())
        assertNull(restoration.consumeList())
        assertNull(restoration.consumeEmpty())
        assertEquals(NativeLibrarySavedScrollPosition(), restoration.save(null, null))
        val legacy = NativeLibraryScrollRestoration(NativeLibrarySavedScrollPosition(NativeListSavedPosition(2, 17), 9))
        assertNull(legacy.consumeReveal())
        assertEquals(NativeListSavedPosition(2, 17), legacy.consumeList())
        assertEquals(9, legacy.consumeEmpty())
    }

    @Test fun delayedRevealCompletionCannotRearmAfterInputCancellation() {
        val reveal = NativeRecordingRevealRequest()
        val scanning = reveal.begin("scan")
        assertNull(reveal.pendingRecordingId)
        reveal.cancel()
        assertEquals(false, reveal.complete(scanning, "target"))
        assertNull(reveal.pendingRecordingId)
        assertNull(NativeLibraryScrollRestoration(null).save(null, null, reveal.pendingRecordingId).pendingRecordingRevealId)
    }

    @Test fun newerRevealRequestRejectsOldAndDuplicateCompletions() {
        val reveal = NativeRecordingRevealRequest()
        val old = reveal.begin("old")
        val current = reveal.begin("current")
        assertEquals(false, reveal.complete(old, "old"))
        assertEquals(true, reveal.complete(current, "new"))
        assertEquals(false, reveal.complete(old, "old"))
        assertEquals(false, reveal.complete(current, "duplicate"))
        assertEquals("new", reveal.pendingRecordingId)
    }

    @Test fun retiredRevealOwnerCannotRestoreUnacceptedIntentIntoRecreation() {
        val oldOwner = NativeRecordingRevealRequest()
        val pendingScan = oldOwner.begin("old")
        val saved = NativeLibraryScrollRestoration(null).save(null, null, oldOwner.pendingRecordingId)
        assertNull(saved.pendingRecordingRevealId)
        oldOwner.cancel()
        val recreated = NativeRecordingRevealRequest(saved.pendingRecordingRevealId)
        assertEquals(false, oldOwner.complete(pendingScan, "late"))
        assertNull(recreated.pendingRecordingId)
        val fresh = recreated.begin("fresh")
        assertEquals(true, recreated.complete(fresh, "accepted"))
        assertEquals("accepted", NativeLibraryScrollRestoration(null)
            .save(null, null, recreated.pendingRecordingId).pendingRecordingRevealId)
    }

    @Test fun cancelledPendingTokenStaysSuppressedAcrossRecreationUntilCompletion() {
        val owner = NativeRecordingRevealRequest()
        val original = owner.begin("pending-token")
        owner.cancel()
        val saved = NativeLibraryScrollRestoration(null).save(null, null,
            owner.pendingRecordingId, owner.cancelledRequestToken)
        assertNull(saved.pendingRecordingRevealId)
        assertEquals("pending-token", saved.cancelledPlaybackRevealToken)
        val restored = NativeLibraryScrollRestoration(saved)
        val recreated = NativeRecordingRevealRequest(restored.consumeReveal(), restored.consumeCancelledReveal())
        val redelivered = recreated.begin("pending-token")
        assertEquals(false, recreated.complete(redelivered, "target"))
        assertNull(recreated.pendingRecordingId)
        assertNull(recreated.cancelledRequestToken)
        assertEquals(false, owner.complete(original, "late-old-window"))
    }

    @Test fun distinctTokenClearsOldSuppressionAndPreservesAcceptedRevealSaver() {
        val owner = NativeRecordingRevealRequest(restoredCancelledToken = "old-token")
        val next = owner.begin("new-token")
        assertNull(owner.cancelledRequestToken)
        assertEquals(true, owner.complete(next, "accepted-id"))
        val saved = NativeLibraryScrollRestoration(null).save(null, null,
            owner.pendingRecordingId, owner.cancelledRequestToken)
        assertEquals("accepted-id", saved.pendingRecordingRevealId)
        assertNull(saved.cancelledPlaybackRevealToken)
    }

    @Test fun duplicatePendingTokenDoesNotReplaceOriginalVisualAuthority() {
        val owner = NativeRecordingRevealRequest()
        val first = owner.begin("same")
        assertEquals(first, owner.begin("same"))
        assertEquals(true, owner.complete(first, "accepted"))
        val cancelled = owner.begin("cancelled")
        owner.cancel()
        assertEquals(cancelled, owner.begin("cancelled"))
        assertEquals(false, owner.complete(cancelled, "must-not-rearm"))
        assertNull(owner.pendingRecordingId)
    }
}
