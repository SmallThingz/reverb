package app.smallthingz.reverb

import org.junit.Assert.*
import org.junit.Test

class RangeDurationActionTest {
    @Test fun fractionalReachableCapAndShortTimelineRemainReachable() {
        for (maximum in listOf(12.345, 0.025)) {
            var committed = -1f
            assertTrue(runRangeDurationAction(100.0, maximum, true, {}, { committed = it }, {}))
            assertEquals(maximum.toFloat(), committed, 0f)
        }
        var committed = -1f
        assertTrue(runRangeDurationAction(-10.0, 12.345, true, {}, { committed = it }, {}))
        assertEquals(0.05f, committed, 0f)
    }

    @Test fun multiDaySelectionHasNoQuickSaveDayLimit() {
        var committed = -1f
        assertTrue(runRangeDurationAction(100_001.25, 172_800.5, true, {}, { committed = it }, {}))
        assertEquals(100_001.25f, committed, 0f)
        assertTrue(runRangeDurationAction(200_000.0, 172_800.5, true, {}, { committed = it }, {}))
        assertEquals(172_800.5f, committed, 0f)
    }

    @Test fun unavailableOrMalformedActionsCannotPauseOrChangeSelection() {
        for ((request, maximum, ready) in listOf(
            Triple(2.0, 10.0, false),
            Triple(Double.NaN, 10.0, true),
            Triple(Double.POSITIVE_INFINITY, 10.0, true),
            Triple(2.0, Double.NaN, true),
            Triple(2.0, 0.0, true),
        )) {
            assertFalse(runRangeDurationAction(request, maximum, ready,
                { error("must not begin/pause") }, { error("must not commit") }, { error("must not end another edit") }))
        }
    }

    @Test fun acceptedActionPinsTargetAndReleasesEditBeforeNextAction() {
        var interaction = RangeDurationWheelInteraction()
        var lastTarget = RangeEditTarget.END
        var values = RangeEditValues(20f, 30f)
        assertTrue(runRangeDurationAction(5.5, 40.0, true,
            { interaction = interaction.begin(lastTarget); lastTarget = RangeEditTarget.START },
            { requested -> values = resizeRangeSelectionDuration(values, interaction.commitTarget()!!, requested, 60f).values },
            { interaction = interaction.end() },
        ))
        assertEquals(RangeEditValues(20f, 25.5f), values)
        assertFalse(interaction.active)
    }

    @Test fun exactWavCapRemainsExportableAfterEndpointRounding() {
        val cap = exportDurationLimitExactSeconds(
            ExportFormat.WAV, ExportCodec.PCM_16, 8_000, 1, PcmSampleFormat.PCM_16,
        )
        for (target in RangeEditTarget.values()) {
            for (start in listOf(0f, 12345.75f)) {
                val original = RangeEditValues(start, start + 300_000f)
                val resized = resizeRangeSelectionDuration(original, target, cap.toFloat(), 400_000f).values
                val bounded = boundRangeDurationActionValues(resized, target, cap)
                assertTrue(rangeSelectionDurationExactSeconds(bounded.startSeconds, bounded.endSeconds) <= cap)
                if (target == RangeEditTarget.END) assertEquals(original.startSeconds, bounded.startSeconds, 0f)
                else assertEquals(original.endSeconds, bounded.endSeconds, 0f)
                assertTrue(cap - rangeSelectionDurationExactSeconds(bounded.startSeconds, bounded.endSeconds) < 0.04)
            }
        }
    }

    @Test fun failedCommitStillReleasesItsPinnedEdit() {
        val expected = IllegalStateException("commit failed")
        var editing = false
        try {
            runRangeDurationAction(3.0, 10.0, true,
                { editing = true }, { throw expected }, { editing = false })
            fail("expected commit failure")
        } catch (error: IllegalStateException) {
            assertSame(expected, error)
        }
        assertFalse(editing)
    }
}
