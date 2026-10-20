package app.smallthingz.reverb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InlinePlaybackReattachmentTest {
    private fun submit(
        owner: InlinePlaybackRowOwner,
        playback: InlinePlayerBookkeeping,
        prepared: Boolean = true,
        resumed: Boolean = true,
        blocked: Boolean = false,
    ): Boolean = submitInlinePlaybackAutoStart(
        playback = playback,
        owner = owner,
        prepared = prepared,
        lifecycleResumed = resumed,
        blocked = blocked,
    ) {
        playback.requestSeek(0, owner)
    }

    @Test
    fun pausedRow_reattachmentCannotGrantAnotherInitialSeek() {
        val owner = InlinePlaybackRowOwner("a")
        val original = InlinePlayerBookkeeping()
        assertTrue(submit(owner, original))
        assertNull(original.seeks.completed())
        var starts = 0
        assertTrue(startInlinePlaybackForCurrentRow(original.seekResumeOwner, { it === owner }) { starts++ })
        original.revokeResume()
        original.retireExpansion {}

        val reattached = InlinePlayerBookkeeping()
        assertTrue(reattached.initialAutoStartPending)
        assertFalse(submit(owner, reattached))
        assertFalse(reattached.seeks.pending)
        assertNull(reattached.seekResumeOwner)
        assertEquals(1, starts)
    }

    @Test
    fun completedRow_reattachmentCannotReplayFromTheBeginning() {
        val owner = InlinePlaybackRowOwner("a")
        val original = InlinePlayerBookkeeping()
        assertTrue(submit(owner, original))
        assertNull(original.seeks.completed())
        original.revokeSeekResume()
        // Native completion changes the row's position/play state, not the expansion.
        original.retireExpansion {}

        val reattached = InlinePlayerBookkeeping()
        assertFalse(submit(owner, reattached))
        assertFalse(reattached.seeks.pending)
    }

    @Test
    fun hiddenPendingSeek_reattachmentCannotRecreateRevokedResume() {
        val owner = InlinePlaybackRowOwner("a")
        val original = InlinePlayerBookkeeping()
        assertTrue(submit(owner, original))
        original.revokeResume()
        assertTrue(original.seeks.pending)
        original.retireExpansion {}

        val reattached = InlinePlayerBookkeeping()
        assertFalse(submit(owner, reattached, resumed = false))
        assertFalse(submit(owner, reattached))
        assertFalse(reattached.seeks.pending)
    }

    @Test
    fun trimEntryBeforeAutoplay_consumesExpansionGrantAcrossReattachment() {
        val owner = InlinePlaybackRowOwner("a")
        val original = InlinePlayerBookkeeping()
        assertFalse(submit(owner, original, blocked = true))
        // The same production owner operation is used when entering/requesting Trim.
        owner.consumeInitialAutoStart()
        original.initialAutoStartPending = false
        original.retireExpansion {}

        val reattached = InlinePlayerBookkeeping()
        assertFalse(submit(owner, reattached))
        assertFalse(reattached.seeks.pending)
    }

    @Test
    fun freshExpansion_stillDefersWhileHiddenThenAutoplaysOnce() {
        val previousOwner = InlinePlaybackRowOwner("a")
        assertTrue(submit(previousOwner, InlinePlayerBookkeeping()))
        val freshOwner = InlinePlaybackRowOwner("a")
        val freshRow = InlinePlayerBookkeeping()
        assertFalse(submit(freshOwner, freshRow, prepared = false))
        assertFalse(submit(freshOwner, freshRow, resumed = false))
        assertFalse(submit(freshOwner, freshRow, blocked = true))
        assertTrue(submit(freshOwner, freshRow))
        assertNull(freshRow.seeks.completed())
        var starts = 0
        assertFalse(startInlinePlaybackForCurrentRow(previousOwner, { it === freshOwner }) { starts++ })
        assertTrue(startInlinePlaybackForCurrentRow(freshRow.seekResumeOwner, { it === freshOwner }) { starts++ })
        assertFalse(submit(freshOwner, freshRow))
        assertEquals(1, starts)
    }
}
