package app.smallthingz.reverb

import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger
import java.util.Random

class RecordingPlaybackClockTest {
    @Test
    fun seededForwardProjectionMatchesIndependentIntegerOracle() {
        val random = Random(0x51A7E)
        repeat(500) {
            val start = random.nextInt(1_000_000)
            val end = start + random.nextInt(1_000_000)
            val position = start + random.nextInt(end - start + 1)
            val sampledAt = random.nextLong().ushr(2)
            val now = sampledAt + random.nextInt(2_000_000).toLong()
            val playing = random.nextBoolean()
            val anchor = sample(position, sampledAt, playing, end, start = start)

            // Independent integer arithmetic over valid forward timestamps, without Long overflow.
            val elapsed = if (playing) BigInteger.valueOf(now).subtract(BigInteger.valueOf(sampledAt))
                else BigInteger.ZERO
            val expected = BigInteger.valueOf(position.toLong()).add(elapsed)
                .max(BigInteger.valueOf(start.toLong()))
                .min(BigInteger.valueOf(end.toLong())).toInt()
            assertEquals(expected, anchor.positionAt(now))
            assertEquals(playing && expected < end, anchor.isAdvancingAt(now))
        }
    }

    private fun sample(
        position: Int = 200,
        at: Long = 1_000L,
        playing: Boolean = true,
        end: Int = 1_000,
        epoch: Long = 1L,
        identity: Any = "normal:1",
        start: Int = 0,
    ) = PlaybackPositionAnchor(position, at, playing, end, epoch, identity, start)

    @Test fun delayedPublicationProjectsFromTheActualSampleAndStopsAtEnd() {
        val anchor = sample()
        assertEquals(200, anchor.positionAt(1_000L))
        assertEquals(450, anchor.positionAt(1_250L))
        assertEquals(999, anchor.positionAt(1_799L))
        assertTrue(anchor.isAdvancingAt(1_799L))
        assertEquals(1_000, anchor.positionAt(1_800L))
        assertFalse(anchor.isAdvancingAt(1_800L))
        assertEquals(1_000, anchor.positionAt(20_000L))
    }

    @Test fun shortTrimUsesSelectedEndAndPausedSamplesNeverAdvance() {
        val trim = sample(position = 350, end = 400, start = 300)
        assertEquals(375, trim.positionAt(1_025L))
        assertEquals(400, trim.positionAt(1_050L))
        assertFalse(trim.isAdvancingAt(1_050L))
        val paused = trim.copy(positionMillis = 375, playing = false)
        assertEquals(375, paused.positionAt(Long.MAX_VALUE))
        assertFalse(paused.isAdvancingAt(1_025L))
    }

    @Test fun backwardSeekAndPauseReplaceTheDisplayImmediatelyWithoutInterpolation() {
        val clock = PlaybackPositionDisplayClock()
        clock.bind("recording", false, sample(position = 800, epoch = 9L))
        assertEquals(900, clock.anchor!!.positionAt(1_100L))
        clock.bind("recording", false, sample(position = 100, at = 1_100L, playing = false, epoch = 10L))
        assertEquals(100, clock.anchor!!.positionAt(1_100L))
        assertEquals(100, clock.anchor!!.positionAt(1_900L))
        clock.bind("recording", false, sample(position = 100, at = 2_000L, epoch = 11L))
        assertEquals(150, clock.anchor!!.positionAt(2_050L))
    }

    @Test fun futureAndInvalidSampleTimesHoldWithoutSchedulingFrames() {
        val future = sample(at = 2_000L)
        assertEquals(200, future.positionAt(1_999L))
        assertFalse(future.isAdvancingAt(1_999L))
        assertEquals(201, future.positionAt(2_001L))
        val invalid = sample(at = -1L)
        assertEquals(200, invalid.positionAt(Long.MAX_VALUE))
        assertFalse(invalid.isAdvancingAt(Long.MAX_VALUE))
        assertEquals(200, sample().positionAt(Long.MIN_VALUE))
    }

    @Test fun timestampAndPositionExtremesCannotOverflow() {
        val large = sample(position = Int.MAX_VALUE - 2, at = 0L, end = Int.MAX_VALUE)
        assertEquals(Int.MAX_VALUE - 1, large.positionAt(1L))
        assertEquals(Int.MAX_VALUE, large.positionAt(Long.MAX_VALUE))
        val late = sample(position = 0, at = Long.MAX_VALUE - 1, end = Int.MAX_VALUE)
        assertEquals(1, late.positionAt(Long.MAX_VALUE))
        assertEquals(0, sample(position = Int.MIN_VALUE, playing = false).positionAt(1_000L))
        assertEquals(1_000, sample(position = Int.MAX_VALUE, playing = false).positionAt(1_000L))
    }

    @Test fun boundsRemainNonnegativeEvenForMalformedOrCollapsedRanges() {
        assertEquals(0, sample(position = -1, end = -100, start = -50).positionAt(5_000L))
        val collapsed = sample(position = 100, end = 300, start = 400)
        assertEquals(400, collapsed.positionAt(1_000L))
        assertFalse(collapsed.isAdvancingAt(1_000L))
    }

    @Test fun newerEpochWinsEvenWhenItsSampleTimeIsEarlier() {
        val clock = PlaybackPositionDisplayClock()
        clock.bind("recording", false, sample(position = 800, at = 2_000L, epoch = 5L))
        val seek = sample(position = 100, at = 1_900L, playing = false, epoch = 6L)
        clock.bind("recording", false, seek)
        assertSame(seek, clock.anchor)
        clock.bind("recording", false, sample(position = 900, at = 3_000L, epoch = 5L))
        assertSame(seek, clock.anchor)
    }

    @Test fun olderSamplesAreRejectedOnlyWithinTheSameEpochAndOwner() {
        val clock = PlaybackPositionDisplayClock()
        val current = sample(position = 500, at = 2_000L, epoch = 5L)
        clock.bind("recording", false, current)
        clock.bind("recording", false, sample(position = 400, at = 1_900L, epoch = 5L))
        assertSame(current, clock.anchor)
        val sameTickPause = current.copy(playing = false)
        clock.bind("recording", false, sameTickPause)
        assertSame(sameTickPause, clock.anchor)
    }

    @Test fun normalTrimAndReplacementTransportCanEachRestartEpochs() {
        val clock = PlaybackPositionDisplayClock()
        val normal = sample(position = 500, at = 2_000L, epoch = 50L)
        val trim = sample(position = 200, at = 1_000L, epoch = 1L, identity = Any())
        clock.bind("recording", false, normal)
        clock.bind("recording", true, trim)
        assertSame(trim, clock.anchor)
        val resumed = normal.copy(epoch = 2L)
        clock.bind("recording", false, resumed)
        assertSame(resumed, clock.anchor)
        val replacement = sample(epoch = 1L, identity = "normal:2")
        clock.bind("recording", false, replacement)
        assertSame(replacement, clock.anchor)
        val revision = replacement.copy(epoch = 0L)
        clock.bind("different-revision", false, revision)
        assertSame(revision, clock.anchor)
    }

    @Test fun missingAnchorStopsFramesAndDoesNotResurrectAnOlderEpoch() {
        val clock = PlaybackPositionDisplayClock()
        clock.bind("recording", false, sample(epoch = 5L))
        clock.bind("recording", false, null)
        assertNull(clock.anchor)
        clock.bind("recording", false, sample(epoch = 4L))
        assertNull(clock.anchor)
        val next = sample(epoch = 6L, playing = false)
        clock.bind("recording", false, next)
        assertSame(next, clock.anchor)
    }

    @Test fun inactiveVisibleDisplayHoldsBothLineAndFillWhileTransportKeepsPlaying() {
        val clock = PlaybackPositionDisplayClock()
        val transport = sample()
        clock.bind("recording", false, transport)
        assertEquals(250, clock.positionAt(1_050L))
        clock.setDisplayActive(false, 1_100L)
        assertEquals(300, clock.positionAt(1_100L))
        assertEquals(300, clock.positionAt(1_500L))
        assertFalse(clock.isAdvancingAt(1_500L))
        assertSame(transport, clock.anchor)
        assertTrue(transport.playing)
        assertEquals(700, transport.positionAt(1_500L))
    }

    @Test fun resumeImmediatelyUsesTheRealClockAndStopsAtTheSelectedEnd() {
        val clock = PlaybackPositionDisplayClock()
        clock.bind("recording", true, sample(position = 350, start = 300, end = 400))
        clock.setDisplayActive(false, 1_010L)
        assertEquals(360, clock.positionAt(1_030L))
        clock.setDisplayActive(true, 1_030L)
        assertEquals(380, clock.positionAt(1_030L))
        assertTrue(clock.isAdvancingAt(1_030L))
        assertEquals(400, clock.positionAt(1_050L))
        assertFalse(clock.isAdvancingAt(1_050L))
    }

    @Test fun inactiveSeekPauseAndOwnerReplacementAppearAtTheirNextBind() {
        val clock = PlaybackPositionDisplayClock()
        clock.bind("recording", false, sample(epoch = 50L))
        clock.setDisplayActive(false, 1_100L)
        val pausedSeek = sample(position = 100, at = 1_200L, playing = false, epoch = 51L)
        clock.bind("recording", false, pausedSeek)
        clock.setDisplayActive(false, 1_250L)
        assertEquals(100, clock.positionAt(2_000L))
        assertFalse(clock.isAdvancingAt(2_000L))
        clock.bind("recording", true, sample(position = 350, at = 2_000L, start = 300,
            end = 400, epoch = 1L, identity = Any()))
        clock.setDisplayActive(false, 2_010L)
        assertEquals(360, clock.positionAt(2_040L))
        clock.bind("recording", true, null)
        clock.setDisplayActive(false, 2_050L)
        assertNull(clock.positionAt(2_060L))
        assertFalse(clock.isAdvancingAt(2_060L))
    }

    @Test fun inactiveWaveformRebindsHoldEqualSamplesAndRejectStaleEpochs() {
        val clock = PlaybackPositionDisplayClock()
        val transport = sample(epoch = 5L)
        clock.bind("recording", false, transport)
        clock.setDisplayActive(false, 1_100L)
        assertEquals(300, clock.positionAt(1_100L))
        for ((index, rebound) in listOf(transport, transport.copy(), transport.copy()).withIndex()) {
            val now = 1_200L + index * 100L
            clock.bind("recording", false, rebound)
            clock.setDisplayActive(false, now)
            assertEquals(300, clock.positionAt(now))
            assertFalse(clock.isAdvancingAt(now))
        }
        clock.bind("recording", false, transport.copy(positionMillis = 800, sampledAtElapsedRealtimeMillis = 1_500L, epoch = 4L))
        clock.setDisplayActive(false, 1_500L)
        assertEquals(300, clock.positionAt(1_500L))
        assertEquals(transport, clock.anchor)
        clock.bind("recording", false, transport.copy(positionMillis = 100, sampledAtElapsedRealtimeMillis = 900L))
        clock.setDisplayActive(false, 1_550L)
        assertEquals(300, clock.positionAt(1_550L))

        val newer = transport.copy(positionMillis = 650, sampledAtElapsedRealtimeMillis = 1_600L)
        clock.bind("recording", false, newer)
        clock.setDisplayActive(false, 1_650L)
        assertEquals(700, clock.positionAt(1_700L))
        clock.bind("recording", false, newer.copy())
        clock.setDisplayActive(false, 1_750L)
        assertEquals(700, clock.positionAt(1_750L))
    }

    @Test fun equivalentAnchorOnNewOwnerAndRestoredAfterNullRefreshesInactiveHold() {
        val clock = PlaybackPositionDisplayClock()
        val transport = sample()
        clock.bind("recording", false, transport)
        clock.setDisplayActive(false, 1_100L)
        clock.bind("new-revision", false, transport.copy())
        clock.setDisplayActive(false, 1_200L)
        assertEquals(400, clock.positionAt(1_200L))
        clock.bind("new-revision", true, transport.copy())
        clock.setDisplayActive(false, 1_300L)
        assertEquals(500, clock.positionAt(1_300L))
        clock.bind("new-revision", true, null)
        clock.setDisplayActive(false, 1_400L)
        assertNull(clock.positionAt(1_400L))
        clock.bind("new-revision", true, transport.copy())
        clock.setDisplayActive(false, 1_500L)
        assertEquals(700, clock.positionAt(1_500L))
        clock.bind("new-revision", true, transport.copy())
        clock.setDisplayActive(false, 1_600L)
        assertEquals(700, clock.positionAt(1_600L))
        clock.setDisplayActive(true, 1_650L)
        assertEquals(850, clock.positionAt(1_650L))
        assertTrue(clock.isAdvancingAt(1_650L))
    }
}
