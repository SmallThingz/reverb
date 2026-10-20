package app.smallthingz.reverb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RangeEndpointActionTest {
    private class Editor {
        var values = RangeEditValues(0f, 60f)
        var duration = 60f
        var selected = RangeEditTarget.START
        var wheel = RangeDurationWheelInteraction()
        var ready = true
        var playing = true
        var pauses = 0
        val keys = RangeEndpointKeyOwner()

        fun gesture() = RangeInputGesture({ ready }, { wheel }, {})

        fun edit(target: RangeEditTarget, request: Double?, gesture: RangeInputGesture = gesture()) =
            runRangeEndpointAction(values, duration, target, request, gesture,
                pause = { pauses++; playing = false },
                apply = { selected = target; values = it.values },
            )

        fun key(target: RangeEditTarget, request: Double?, repeat: Int, keyCode: Int = 22): Boolean =
            keys.keyDown(keyCode, repeat, ::gesture)?.let { edit(target, request, it) } ?: false
    }

    @Test
    fun eitherMarkerCanSelectAndSetAnArbitraryIntervalWithoutDurationWheel() {
        val editor = Editor()
        assertTrue(editor.edit(RangeEditTarget.END, null))
        assertEquals(RangeEditTarget.END, editor.selected)
        assertEquals(RangeEditValues(0f, 60f), editor.values)
        assertFalse(editor.playing)
        assertTrue(editor.edit(RangeEditTarget.END, 20.0))
        assertTrue(editor.edit(RangeEditTarget.START, 10.0))
        assertEquals(RangeEditValues(10f, 20f), editor.values)
        assertEquals(RangeEditTarget.START, editor.selected)
        assertFalse(editor.wheel.active)
        assertEquals(0L, editor.wheel.generation)
    }

    @Test
    fun endpointActionsKeepFractionalValuesAndPushCollapsedCornersLikeMarkers() {
        val editor = Editor()
        editor.values = RangeEditValues(0f, 0.05f)
        assertTrue(editor.edit(RangeEditTarget.START, 1.0))
        assertEquals(1f, editor.values.startSeconds, 0f)
        assertEquals(1.05f, editor.values.endSeconds, 0f)

        editor.values = RangeEditValues(59.95f, 60f)
        assertTrue(editor.edit(RangeEditTarget.END, 59.0))
        assertEquals(58.95f, editor.values.startSeconds, 0f)
        assertEquals(59f, editor.values.endSeconds, 0f)

        assertTrue(editor.edit(RangeEditTarget.START, 12.345))
        assertEquals(12.345f, editor.values.startSeconds, 0f)
        assertEquals(59f, editor.values.endSeconds, 0f)
    }

    @Test
    fun advertisedHomeEndBoundsMatchPointerBoundsAndShortClipsRemainUsable() {
        for (duration in listOf(0.025f, 60f, 172_800.5f)) {
            for (target in RangeEditTarget.values()) {
                val editor = Editor().apply { this.duration = duration; values = RangeEditValues(0f, duration) }
                val limits = requireNotNull(rangeEndpointLimits(duration, target))
                assertTrue(editor.edit(target, -Double.MAX_VALUE))
                val atHome = if (target == RangeEditTarget.START) editor.values.startSeconds else editor.values.endSeconds
                assertEquals(limits.start, atHome, 0f)
                assertTrue(editor.edit(target, Double.MAX_VALUE))
                val atEnd = if (target == RangeEditTarget.START) editor.values.startSeconds else editor.values.endSeconds
                assertEquals(limits.endInclusive, atEnd, 0f)
                assertTrue(editor.values.startSeconds >= 0f)
                assertTrue(editor.values.endSeconds <= duration)
                assertTrue(editor.values.endSeconds > editor.values.startSeconds)
            }
        }
        assertEquals(0f..0f, rangeEndpointLimits(0.025f, RangeEditTarget.START))
        assertEquals(0.025f..0.025f, rangeEndpointLimits(0.025f, RangeEditTarget.END))
    }

    @Test
    fun multiDayEndpointsDoNotAcquireADayCapOrRelaxExactExportLimit() {
        val editor = Editor().apply { duration = 400_000f; values = RangeEditValues(0f, duration) }
        assertTrue(editor.edit(RangeEditTarget.END, 172_800.5))
        assertTrue(editor.edit(RangeEditTarget.START, 100_001.25))
        assertEquals(RangeEditValues(100_001.25f, 172_800.5f), editor.values)

        val cap = exportDurationLimitExactSeconds(
            ExportFormat.WAV, ExportCodec.PCM_16, 8_000, 1, PcmSampleFormat.PCM_16,
        )
        val rounded = cap.toFloat()
        val atOrBelow = if (rounded.toDouble() > cap) java.lang.Math.nextDown(rounded) else rounded
        val above = java.lang.Math.nextUp(atOrBelow)
        assertTrue(editor.edit(RangeEditTarget.START, 0.0))
        assertTrue(editor.edit(RangeEditTarget.END, above.toDouble()))
        assertEquals(above, editor.values.endSeconds, 0f)
        assertFalse(rangeExportSelectionWithinLimit(
            rangeSelectionDurationExactSeconds(editor.values.startSeconds, editor.values.endSeconds), cap,
        ))
        assertTrue(editor.edit(RangeEditTarget.END, atOrBelow.toDouble()))
        assertTrue(rangeExportSelectionWithinLimit(
            rangeSelectionDurationExactSeconds(editor.values.startSeconds, editor.values.endSeconds), cap,
        ))
    }

    @Test
    fun pendingWheelRejectsSelectionProgressAndKeysWithoutInvalidatingItsCommit() {
        val editor = Editor()
        editor.wheel = editor.wheel.begin(RangeEditTarget.START)
        assertFalse(editor.edit(RangeEditTarget.END, null))
        assertFalse(editor.edit(RangeEditTarget.END, 20.0))
        assertFalse(editor.key(RangeEditTarget.END, 20.0, repeat = 0))
        assertEquals(RangeEditValues(0f, 60f), editor.values)
        assertEquals(RangeEditTarget.START, editor.selected)
        assertEquals(RangeEditTarget.START, editor.wheel.commitTarget())
        assertEquals(0, editor.pauses)
        assertTrue(editor.playing)

        editor.wheel = editor.wheel.end()
        assertFalse(editor.key(RangeEditTarget.END, 20.0, repeat = 1))
        assertTrue(editor.edit(RangeEditTarget.END, null))
        assertTrue(editor.edit(RangeEditTarget.END, 20.0))
        assertEquals(RangeEditValues(0f, 20f), editor.values)
    }

    @Test
    fun repeatsWorkUntilACompleteWheelTransactionRetiresTheHeldKeyAndFreshKeysWork() {
        for (wheelChangesValues in listOf(false, true)) {
            val editor = Editor()
            assertTrue(editor.key(RangeEditTarget.START, 1.0, repeat = 0))
            assertTrue(editor.key(RangeEditTarget.START, 2.0, repeat = 1))
            assertTrue(editor.key(RangeEditTarget.START, 3.0, repeat = 2))
            assertEquals(3f, editor.values.startSeconds, 0f)
            editor.wheel = editor.wheel.begin(RangeEditTarget.END)
            if (wheelChangesValues) editor.values = RangeEditValues(3f, 40f)
            editor.wheel = editor.wheel.end()
            editor.playing = true
            val wheelValues = editor.values
            val pauses = editor.pauses

            assertFalse(editor.key(RangeEditTarget.START, 4.0, repeat = 3))
            assertFalse(editor.key(RangeEditTarget.START, 5.0, repeat = 4))
            editor.keys.keyUp(22)
            assertEquals(wheelValues, editor.values)
            assertEquals(pauses, editor.pauses)
            assertTrue(editor.playing)

            assertTrue(editor.key(RangeEditTarget.END, wheelValues.endSeconds.toDouble() - 1.0, repeat = 0))
            assertEquals(wheelValues.endSeconds - 1f, editor.values.endSeconds, 0f)
            assertEquals(RangeEditTarget.END, editor.selected)
            assertFalse(editor.playing)
        }
    }

    @Test
    fun liveReadinessLossRetiresAKeyWithoutPausingAndFreshActionsRecover() {
        val editor = Editor()
        val stale = editor.gesture()
        assertTrue(editor.key(RangeEditTarget.START, 1.0, repeat = 0))
        editor.ready = false
        editor.playing = true
        val pauses = editor.pauses
        assertFalse(editor.edit(RangeEditTarget.END, 20.0, stale))
        assertFalse(editor.edit(RangeEditTarget.END, null))
        assertFalse(editor.key(RangeEditTarget.START, 2.0, repeat = 1))
        assertEquals(RangeEditValues(1f, 60f), editor.values)
        assertTrue(editor.playing)
        assertEquals(pauses, editor.pauses)

        editor.ready = true
        assertFalse(editor.key(RangeEditTarget.START, 2.0, repeat = 2))
        assertFalse(editor.edit(RangeEditTarget.END, 20.0, stale))
        assertTrue(editor.key(RangeEditTarget.START, 2.0, repeat = 0))
        assertTrue(editor.edit(RangeEditTarget.END, 20.0))
        assertEquals(RangeEditValues(2f, 20f), editor.values)
    }

    @Test
    fun focusLossAndKeyReleaseCannotCleanUpNewAudioOrTurnOrphanRepeatsIntoFreshInput() {
        val editor = Editor()
        val held = requireNotNull(editor.keys.keyDown(22, 0, editor::gesture))
        assertTrue(editor.edit(RangeEditTarget.START, 1.0, held))
        editor.wheel = editor.wheel.begin(RangeEditTarget.END).end()
        editor.playing = true
        editor.keys.clear()
        editor.keys.keyUp(22)
        assertNull(editor.keys.keyDown(22, 1, editor::gesture))
        assertFalse(editor.edit(RangeEditTarget.START, 2.0, held))
        assertTrue(editor.playing)
        assertTrue(editor.key(RangeEditTarget.START, 2.0, repeat = 0))
        editor.keys.keyUp(22)
        assertNull(editor.keys.keyDown(22, 1, editor::gesture))
        assertTrue(editor.key(RangeEditTarget.START, 3.0, repeat = 0))
    }

    @Test
    fun malformedRequestsAndTimelinesNeverPauseOrEdit() {
        for (request in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val editor = Editor()
            assertFalse(editor.edit(RangeEditTarget.END, request))
            assertEquals(0, editor.pauses)
            assertTrue(editor.playing)
        }
        for (duration in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            val editor = Editor().apply { this.duration = duration }
            assertNull(rangeEndpointLimits(duration, RangeEditTarget.END))
            assertFalse(editor.edit(RangeEditTarget.END, null))
            assertFalse(editor.edit(RangeEditTarget.END, 20.0))
            assertEquals(0, editor.pauses)
        }
    }
}
