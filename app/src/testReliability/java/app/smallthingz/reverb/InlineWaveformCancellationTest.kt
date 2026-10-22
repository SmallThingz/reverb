package app.smallthingz.reverb

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class InlineWaveformCancellationTest {
    private fun up(consumed: Boolean) = PointerInputChange(
        id = PointerId(1L),
        uptimeMillis = 32L,
        position = Offset(10f, 20f),
        pressed = false,
        pressure = 1f,
        previousUptimeMillis = 16L,
        previousPosition = Offset(10f, 20f),
        previousPressed = true,
        isInitiallyConsumed = consumed,
    )

    private class PendingScrub {
        val owner = InlinePlaybackRowOwner("recording")
        var currentOwner: InlinePlaybackRowOwner? = owner
        val playback = InlinePlayerBookkeeping()
        var starts = 0
        var terminals = 0

        init {
            // Waveform DOWN can interrupt an older resumable seek before its callback arrives.
            assertEquals(1_000, playback.requestSeek(1_000, owner))
            playback.resumeAfterScrub = playback.seeks.resume
            playback.revokeSeekResume()
        }

        fun release(
            change: PointerInputChange?,
            trimEnd: Boolean = false,
            beforeSeek: () -> Unit = {},
        ) {
            finishInlineWaveformGesture(change, playback) {
                terminals++
                beforeSeek()
                val resume = playback.resumeAfterScrub
                playback.resumeAfterScrub = false
                val target = if (trimEnd && resume) {
                    inlineTrimPlaybackStartMillis(2_000, 10_000, InlineFineSeekTarget.TRIM_END)
                } else if (trimEnd) {
                    10_000
                } else {
                    2_000
                }
                // This is the same queue entry used by finishInlineSeek -> seekTo.
                assertNull(playback.requestSeek(target, owner.takeIf { resume }))
            }
        }

        fun completeNativeSeek(): Int? {
            assertTrue(playback.seeks.pending)
            val next = playback.seeks.completed()
            if (next == null) {
                val resume = playback.seeks.resume
                val admittedOwner = playback.seekResumeOwner
                playback.revokeSeekResume()
                if (resume) {
                    startInlinePlaybackForCurrentRow(admittedOwner, { it === currentOwner }) { starts++ }
                }
            }
            return next
        }
    }

    @Test
    fun consumedPlatformUp_revokesBeforeFinalSeekAndBothDelayedCompletionsStaySilent() {
        val scrub = PendingScrub()
        scrub.release(up(consumed = true)) {
            assertFalse(scrub.playback.resumeAfterScrub)
            assertFalse(scrub.playback.seeks.resume)
            assertNull(scrub.playback.seekResumeOwner)
        }
        assertEquals(1, scrub.terminals)
        assertTrue(scrub.playback.seeks.pending)
        assertFalse(scrub.playback.seeks.resume)
        assertEquals(2_000, scrub.completeNativeSeek())
        assertEquals(0, scrub.starts)
        assertNull(scrub.completeNativeSeek())
        assertEquals(0, scrub.starts)
    }

    @Test
    fun missingPointer_revokesResumeWithoutDiscardingThePendingNativeSeek() {
        val scrub = PendingScrub()
        scrub.release(null)
        assertEquals(1, scrub.terminals)
        assertTrue(scrub.playback.seeks.pending)
        assertFalse(scrub.playback.seeks.resume)
        assertEquals(2_000, scrub.completeNativeSeek())
        assertNull(scrub.completeNativeSeek())
        assertEquals(0, scrub.starts)
    }

    @Test
    fun ordinaryUnconsumedUp_preservesResumeUntilOnlyTheNewestSeekCompletes() {
        val scrub = PendingScrub()
        scrub.release(up(consumed = false)) { assertTrue(scrub.playback.resumeAfterScrub) }
        assertEquals(1, scrub.terminals)
        assertTrue(scrub.playback.seeks.resume)
        assertSame(scrub.owner, scrub.playback.seekResumeOwner)
        assertEquals(2_000, scrub.completeNativeSeek())
        assertEquals(0, scrub.starts)
        assertNull(scrub.completeNativeSeek())
        assertEquals(1, scrub.starts)
        assertFalse(scrub.playback.seeks.pending)
        assertFalse(scrub.playback.seeks.resume)
    }

    @Test
    fun pauseBeforeOrdinaryUp_cannotRestoreRevokedResume() {
        val scrub = PendingScrub()
        scrub.playback.revokeResume()
        scrub.release(up(consumed = false))
        assertEquals(2_000, scrub.completeNativeSeek())
        assertNull(scrub.completeNativeSeek())
        assertEquals(0, scrub.starts)
    }

    @Test
    fun replacementAfterOrdinaryUp_cannotBorrowTheNewRowsPlaybackAuthority() {
        val scrub = PendingScrub()
        scrub.release(up(consumed = false))
        scrub.currentOwner = InlinePlaybackRowOwner("recording")
        assertEquals(2_000, scrub.completeNativeSeek())
        assertNull(scrub.completeNativeSeek())
        assertEquals(0, scrub.starts)
        var freshStarts = 0
        assertTrue(startInlinePlaybackForCurrentRow(scrub.currentOwner, { it === scrub.currentOwner }) { freshStarts++ })
        assertEquals(1, freshStarts)
    }

    @Test
    fun trimEndCancellation_staysAtBoundaryWhileOrdinaryReleaseKeepsAuditionLeadIn() {
        val cancelled = PendingScrub()
        cancelled.release(up(consumed = true), trimEnd = true)
        assertEquals(10_000, cancelled.completeNativeSeek())
        assertNull(cancelled.completeNativeSeek())
        assertEquals(0, cancelled.starts)

        val normal = PendingScrub()
        normal.release(up(consumed = false), trimEnd = true)
        assertEquals(7_000, normal.completeNativeSeek())
        assertEquals(0, normal.starts)
        assertNull(normal.completeNativeSeek())
        assertEquals(1, normal.starts)
    }

    @Test
    fun coroutineCancellation_keepsItsFailureAndRevokesBeforeFinallySeeks() {
        val scrub = PendingScrub()
        val expected = CancellationException("pointer owner disposed")
        var observed: CancellationException? = null
        try {
            try {
                throw expected
            } finally {
                scrub.release(null) { assertFalse(scrub.playback.resumeAfterScrub) }
            }
        } catch (cancelled: CancellationException) {
            observed = cancelled
        }
        assertSame(expected, observed)
        assertEquals(1, scrub.terminals)
        assertEquals(2_000, scrub.completeNativeSeek())
        assertNull(scrub.completeNativeSeek())
        assertEquals(0, scrub.starts)
    }
}
