package app.smallthingz.reverb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RememberedRangePositiveDurationTest {
    private val longDuration = 1_123_200f

    private fun assertPositive(range: RestoredRangeExport, available: Float) {
        assertTrue(range.startSeconds.isFinite())
        assertTrue(range.endSeconds.isFinite())
        assertTrue(range.startSeconds >= 0f)
        assertTrue(range.startSeconds < range.endSeconds)
        assertTrue(range.endSeconds <= available)
    }

    @Test
    fun savedShortTailRemainsExportableAfterTimelineGrows() {
        val remembered = requireNotNull(rememberedRangeExportFromSavedRange(
            availableSeconds = 1.0,
            startSeconds = 0.95f,
            endSeconds = 1f,
            actualSelectionMillis = 50L,
        ))
        assertEquals(RememberedRangeExport(50L, 0L), remembered)
        assertEquals(RestoredRangeExport(0.95f, 1f), restoreRememberedRangeExport(1f, remembered))

        val restored = restoreRememberedRangeExport(longDuration, remembered)
        assertEquals(RestoredRangeExport(java.lang.Math.nextDown(longDuration), longDuration), restored)
        assertPositive(restored, longDuration)
        val duration = rangeSelectionDurationExactSeconds(restored.startSeconds, restored.endSeconds)
        assertTrue(rangeExportSelectionWithinLimit(duration, 0.125))
        assertFalse(rangeExportSelectionWithinLimit(duration, 0.0))
    }

    @Test
    fun shortTailPreservesEndAcrossTheFloatPrecisionBoundary() {
        for (available in listOf(
            java.lang.Math.nextDown(1_048_576f),
            1_048_576f,
            java.lang.Math.nextUp(1_048_576f),
            longDuration,
            31_536_000f,
        )) {
            for (selectionMillis in listOf(1L, 50L)) {
                val restored = restoreRememberedRangeExport(
                    available, RememberedRangeExport(selectionMillis, 0L),
                )
                assertEquals(RestoredRangeExport(java.lang.Math.nextDown(available), available), restored)
                assertPositive(restored, available)
            }
        }
    }

    @Test
    fun shortInteriorSelectionPreservesItsRememberedEndOffset() {
        val expectedEnd = longDuration - 3f
        val restored = restoreRememberedRangeExport(longDuration, RememberedRangeExport(50L, 3_000L))
        assertEquals(RestoredRangeExport(java.lang.Math.nextDown(expectedEnd), expectedEnd), restored)
        assertPositive(restored, longDuration)
    }

    @Test
    fun longTimelineDoesNotEnlargeShortSelectionsNearTheOrigin() {
        val availableMillis = longDuration.toLong() * 1_000L
        assertEquals(RestoredRangeExport(0.01f, 0.02f), restoreRememberedRangeExport(
            longDuration, RememberedRangeExport(10L, availableMillis - 20L),
        ))
        assertEquals(RestoredRangeExport(1f, 1.05f), restoreRememberedRangeExport(
            longDuration, RememberedRangeExport(50L, availableMillis - 1_050L),
        ))
    }

    @Test
    fun ordinarySelectionAndShortenedTimelineRetainTheirExistingPlacement() {
        val remembered = RememberedRangeExport(20_000L, 15_000L)
        assertEquals(RestoredRangeExport(85f, 105f), restoreRememberedRangeExport(120f, remembered))
        assertEquals(RestoredRangeExport(0f, 20f), restoreRememberedRangeExport(25f, remembered))
        assertEquals(RestoredRangeExport(0f, 12f), restoreRememberedRangeExport(12f, remembered))
        assertEquals(RestoredRangeExport(0f, 0.02f), restoreRememberedRangeExport(
            0.02f, RememberedRangeExport(50L, 0L),
        ))
    }

    @Test
    fun missingOrInvalidMemoryAndEmptySourcesKeepTheirExistingFallback() {
        for (remembered in listOf(
            null,
            RememberedRangeExport(0L, 0L),
            RememberedRangeExport(-1L, 0L),
            RememberedRangeExport(50L, -1L),
        )) {
            assertEquals(RestoredRangeExport(0f, longDuration), restoreRememberedRangeExport(longDuration, remembered))
        }
        for (available in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            val restored = restoreRememberedRangeExport(available, RememberedRangeExport(50L, 0L))
            assertEquals(RestoredRangeExport(0f, 0f), restored)
            assertFalse(rangeExportSelectionWithinLimit(
                rangeSelectionDurationExactSeconds(restored.startSeconds, restored.endSeconds), 60.0,
            ))
        }
    }
}
