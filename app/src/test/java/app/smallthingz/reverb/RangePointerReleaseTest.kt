package app.smallthingz.reverb

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RangePointerReleaseTest {
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

    @Test
    fun consumedPlatformUpCancelsStationaryMarkerBeforeFinallyAndFreshTapAuditions() {
        var auditions = 0
        var cancellations = 0
        val duration = RangeDurationWheelInteraction()
        fun marker() = RangeInputGesture({ true }, { duration }, { cancellations++ })
        val cancelled = marker()
        assertTrue(cancelled.perform {})
        assertFalse(rangePointerReleaseWasNormal(up(consumed = true), cancelled::cancel))
        assertFalse(cancelled.finish { auditions++ })
        cancelled.cancel()
        assertEquals(0, auditions)
        assertEquals(1, cancellations)

        val fresh = marker()
        assertTrue(fresh.perform {})
        assertTrue(rangePointerReleaseWasNormal(up(consumed = false), fresh::cancel))
        assertTrue(fresh.finish { auditions++ })
        assertEquals(1, auditions)
        assertEquals(1, cancellations)
    }

    @Test
    fun consumedPlatformUpCancelsBodyScrubWithoutResumingOnce() {
        var resumeAfterScrub = true
        var cancellations = 0
        var resumes = 0
        val body = RangeInputGesture({ true }, { RangeDurationWheelInteraction() }) {
            cancellations++
            resumeAfterScrub = false
        }
        assertTrue(body.perform {})
        assertFalse(rangePointerReleaseWasNormal(up(consumed = true), body::cancel))
        assertFalse(body.finish { if (resumeAfterScrub) resumes++ })
        body.cancel()
        assertEquals(1, cancellations)
        assertEquals(0, resumes)
        assertFalse(resumeAfterScrub)
    }

    @Test
    fun consumedPlatformUpDiscardsPuckRemainderAndNeverCallsNormalEnd() {
        var commits = 0f
        var ends = 0
        var audioCancellations = 0
        val puck = FineSeekInputSession(
            { cancel -> RangeInputGesture({ true }, { RangeDurationWheelInteraction() }, cancel) },
            { _, stillCurrent ->
                cancelFineSeekOwnedAudio(false, true, stillCurrent) { audioCancellations++ }
            },
        )
        assertTrue(puck.perform {})
        assertTrue(puck.frame(0.25f, 10_000_000L, { commits += it }, {}))
        val releasedNormally = rangePointerReleaseWasNormal(up(consumed = true), puck::cancel)
        assertFalse(releasedNormally)
        if (releasedNormally) puck.finishFineAdjust({ commits += it }, { ends++ }) else puck.cancel()
        assertFalse(puck.finishFineAdjust({ commits += it }, { ends++ }))
        assertFalse(puck.frame(10f, 40_000_000L, { commits += it }, {}))
        assertEquals(0f, commits, 0f)
        assertEquals(0, ends)
        assertEquals(1, audioCancellations)
    }

    @Test
    fun unconsumedPuckUpFlushesExactRemainderAndEndsOnce() {
        var commits = 0f
        var ends = 0
        val puck = FineSeekInputSession(
            { cancel -> RangeInputGesture({ true }, { RangeDurationWheelInteraction() }, cancel) },
            { _, _ -> error("normal release must not cancel") },
        )
        assertTrue(puck.frame(0.25f, 10_000_000L, { commits += it }, {}))
        assertTrue(rangePointerReleaseWasNormal(up(consumed = false), puck::cancel))
        assertTrue(puck.finishFineAdjust({ commits += it }, { ends++ }))
        assertFalse(puck.finishFineAdjust({ commits += it }, { ends++ }))
        puck.cancel()
        assertEquals(0.25f, commits, 0f)
        assertEquals(1, ends)
    }

    @Test
    fun cancellationAfterWheelTakeoverCannotPauseNewAudioAndFreshPuckStillWorks() {
        var duration = RangeDurationWheelInteraction()
        var audioCancellations = 0
        var commits = 0f
        val old = FineSeekInputSession(
            { cancel -> RangeInputGesture({ true }, { duration }, cancel) },
            { _, stillCurrent ->
                cancelFineSeekOwnedAudio(true, true, stillCurrent) { audioCancellations++ }
            },
        )
        assertTrue(old.frame(5f, 10_000_000L, { commits += it }, {}))
        duration = duration.begin(RangeEditTarget.END).end()
        assertFalse(rangePointerReleaseWasNormal(up(consumed = true), old::cancel))
        assertFalse(old.finishFineAdjust({ commits += it }, { error("retired normal end") }))
        assertEquals(0, audioCancellations)
        assertEquals(0f, commits, 0f)

        val fresh = FineSeekInputSession(
            { cancel -> RangeInputGesture({ true }, { duration }, cancel) },
            { _, _ -> error("fresh release is admitted") },
        )
        assertTrue(fresh.frame(0.125f, 10_000_000L, { commits += it }, {}))
        assertTrue(rangePointerReleaseWasNormal(up(consumed = false), fresh::cancel))
        assertTrue(fresh.finishFineAdjust({ commits += it }, {}))
        assertEquals(0.125f, commits, 0f)
    }

    @Test
    fun missingTrackedPointerCancelsInsteadOfReleasing() {
        var cancellations = 0
        val gesture = RangeInputGesture({ true }, { RangeDurationWheelInteraction() }, { cancellations++ })
        assertFalse(rangePointerReleaseWasNormal(null, gesture::cancel))
        assertFalse(gesture.finish { error("missing pointer must not audition") })
        gesture.cancel()
        assertEquals(1, cancellations)
    }

    @Test
    fun ordinaryUpStillRechecksLiveReadinessBeforeNormalCompletion() {
        var ready = true
        var cancellations = 0
        val gesture = RangeInputGesture({ ready }, { RangeDurationWheelInteraction() }, { cancellations++ })
        assertTrue(gesture.perform {})
        ready = false
        assertTrue(rangePointerReleaseWasNormal(up(consumed = false), gesture::cancel))
        assertFalse(gesture.finish { error("hidden owner must not resume") })
        assertEquals(1, cancellations)
    }

    @Test
    fun childConsumedLibraryTapUpNeverPausesTheChildsPlayback() {
        var playing = true // The Library child clickable has already handled this UP in Main pass.
        var localCancellations = 0
        val parent = FineSeekInputSession(
            { cancel -> RangeInputGesture({ true }, { RangeDurationWheelInteraction() }, cancel) },
            { _, stillCurrent ->
                localCancellations++
                cancelFineSeekOwnedAudio(false, false, stillCurrent) { playing = false }
            },
        )
        assertTrue(parent.perform {})
        assertFalse(rangePointerReleaseWasNormal(up(consumed = true), parent::cancel))
        parent.cancel()
        assertFalse(parent.finish { error("parent never owns this Library tap") })
        assertEquals(1, localCancellations)
        assertTrue(playing)
    }

    @Test
    fun cancelledRangeTapStillCancelsItsOwnPreviewOnce() {
        var audioCancellations = 0
        val tap = FineSeekInputSession(
            { cancel -> RangeInputGesture({ true }, { RangeDurationWheelInteraction() }, cancel) },
            { _, stillCurrent ->
                cancelFineSeekOwnedAudio(true, false, stillCurrent) { audioCancellations++ }
            },
        )
        assertTrue(tap.perform {})
        assertFalse(rangePointerReleaseWasNormal(up(consumed = true), tap::cancel))
        assertFalse(tap.finishPointerTap { error("cancelled Range tap must not toggle") })
        tap.cancel()
        assertEquals(1, audioCancellations)
    }
}
