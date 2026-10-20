package app.smallthingz.reverb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class InlinePlaybackRowAuthorityTest {
    @Test
    fun collapseBeforeRecomposition_rejectsRetainedPreparedAndSeekStartHandoffs() {
        val outgoing = InlinePlaybackRowOwner("a")
        var expanded: InlinePlaybackRowOwner? = outgoing
        val events = mutableListOf<String>()
        val preparedHandoff = {
            startInlinePlaybackForCurrentRow(outgoing, { expanded === it }) { events += "prepared start" }
        }
        val seekHandoff = {
            startInlinePlaybackForCurrentRow(outgoing, { expanded === it }) { events += "seek start" }
        }

        // The outgoing composition and its callbacks still hold their old token.
        expanded = null
        assertFalse(preparedHandoff())
        assertFalse(seekHandoff())
        assertEquals(emptyList<String>(), events)
    }

    @Test
    fun collapseEffect_pausesAndRevokesAllResumeIntentWithoutDiscardingPendingSeek() {
        val playback = InlinePlayerBookkeeping()
        val events = mutableListOf<String>()
        playback.requestSeek(1_000, InlinePlaybackRowOwner("a"))
        playback.resumeAfterScrub = true

        playback.retireExpansion { events += "pause" }

        assertEquals(listOf("pause"), events)
        assertFalse(playback.initialAutoStartPending)
        assertFalse(playback.resumeAfterScrub)
        assertFalse(playback.seeks.resume)
        assertNull(playback.seekResumeOwner)
        assertTrue(playback.seeks.pending)
        assertNull(playback.seeks.completed())
        assertFalse(playback.seeks.resume)
    }

    @Test
    fun replacement_keepsFreshRowUsableWhileOutgoingRowIsStillComposed() {
        val outgoing = InlinePlaybackRowOwner("a")
        val incoming = InlinePlaybackRowOwner("b")
        var expanded: InlinePlaybackRowOwner? = outgoing
        val events = mutableListOf<String>()

        expanded = incoming
        assertFalse(startInlinePlaybackForCurrentRow(outgoing, { expanded === it }) { events += "a" })
        assertTrue(startInlinePlaybackForCurrentRow(incoming, { expanded === it }) { events += "b" })
        assertEquals(listOf("b"), events)
    }

    @Test
    fun rapidReexpand_hasNewIdentityAndDrainsOldSeekBeforeFreshResume() {
        val firstA = InlinePlaybackRowOwner("a")
        var expanded: InlinePlaybackRowOwner? = firstA
        val playback = InlinePlayerBookkeeping()
        val events = mutableListOf<String>()
        playback.requestSeek(1_000, firstA)
        playback.resumeAfterScrub = true

        expanded = InlinePlaybackRowOwner("b")
        val reopenedA = InlinePlaybackRowOwner("a")
        expanded = reopenedA
        assertFalse(startInlinePlaybackForCurrentRow(firstA, { expanded === it }) { events += "old a" })

        // The retained player adopts the new expansion without opening its source again.
        playback.retireExpansion { events += "pause old intent" }
        playback.initialAutoStartPending = true
        assertTrue(submitInlinePlaybackAutoStart(
            playback = playback,
            owner = reopenedA,
            prepared = true,
            lifecycleResumed = true,
            blocked = false,
        ) {
            assertNull(playback.requestSeek(2_000, reopenedA))
        })
        assertFalse(playback.initialAutoStartPending)
        assertSame(reopenedA, playback.seekResumeOwner)
        assertEquals(2_000, playback.seeks.completed())
        assertTrue(playback.seeks.pending)
        assertEquals(listOf("pause old intent"), events)

        assertNull(playback.seeks.completed())
        assertTrue(playback.seeks.resume)
        val admittedOwner = playback.seekResumeOwner
        playback.revokeSeekResume()
        assertTrue(startInlinePlaybackForCurrentRow(admittedOwner, { expanded === it }) { events += "new a" })
        assertEquals(listOf("pause old intent", "new a"), events)
    }

    @Test
    fun queuedResumeCannotBorrowCurrentExpansionWhenItsAdmittedOwnerHasRetired() {
        val oldOwner = InlinePlaybackRowOwner("a")
        val playback = InlinePlayerBookkeeping()
        playback.requestSeek(1_000, oldOwner)
        val reopenedOwner = InlinePlaybackRowOwner("a")
        assertNull(playback.seeks.completed())
        var starts = 0
        assertFalse(startInlinePlaybackForCurrentRow(playback.seekResumeOwner, { it === reopenedOwner }) { starts++ })
        assertFalse(startInlinePlaybackForCurrentRow(null, { true }) { starts++ })
        assertEquals(0, starts)
    }

    @Test
    fun hiddenCurrentExpansionDoesNotManufactureAnotherAutoplayIntent() {
        val playback = InlinePlayerBookkeeping()
        val owner = InlinePlaybackRowOwner("a")
        var submissions = 0
        assertTrue(submitInlinePlaybackAutoStart(playback, owner, true, true, false) { submissions++ })
        playback.revokeResume()
        assertFalse(submitInlinePlaybackAutoStart(playback, owner, true, true, false) { submissions++ })
        assertEquals(1, submissions)
    }

    @Test
    fun latePreparationAfterCollapse_cannotConsumeAutoplayAndFreshExpansionCanStart() {
        val oldOwner = InlinePlaybackRowOwner("a")
        var expanded: InlinePlaybackRowOwner? = oldOwner
        val playback = InlinePlayerBookkeeping()
        val events = mutableListOf<String>()

        expanded = null
        playback.retireExpansion { events += "pause" }
        assertFalse(inlinePlaybackShouldAutoStart(
            prepared = true,
            initialAutoStartPending = playback.initialAutoStartPending,
            lifecycleResumed = true,
        ))
        assertFalse(startInlinePlaybackForCurrentRow(oldOwner, { expanded === it }) { events += "late prepare" })

        val freshOwner = InlinePlaybackRowOwner("a")
        expanded = freshOwner
        playback.initialAutoStartPending = true
        assertTrue(inlinePlaybackShouldAutoStart(
            prepared = true,
            initialAutoStartPending = playback.initialAutoStartPending,
            lifecycleResumed = true,
        ))
        assertTrue(startInlinePlaybackForCurrentRow(freshOwner, { expanded === it }) { events += "fresh play" })
        assertEquals(listOf("pause", "fresh play"), events)
    }
}
