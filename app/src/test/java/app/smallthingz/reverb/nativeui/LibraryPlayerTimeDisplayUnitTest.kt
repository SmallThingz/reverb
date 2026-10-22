package app.smallthingz.reverb.nativeui

import app.smallthingz.reverb.formatPlaybackTime
import app.smallthingz.reverb.formatRangeTimeInput
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryPlayerTimeDisplayUnitTest {
    @Test fun playbackKeysChangeAtWholeSecondsAndClampNegativePositions() {
        assertEquals(0L, libraryPlayerTimeDisplayUnit(Int.MIN_VALUE, false))
        assertEquals(0L, libraryPlayerTimeDisplayUnit(999, false))
        assertEquals(1L, libraryPlayerTimeDisplayUnit(1000, false))
        assertEquals(2_147_483L, libraryPlayerTimeDisplayUnit(Int.MAX_VALUE, false))
    }

    @Test fun trimKeysRetainTenthsAndHourBoundaries() {
        assertEquals(0L, libraryPlayerTimeDisplayUnit(-1, true))
        assertEquals(0L, libraryPlayerTimeDisplayUnit(99, true))
        assertEquals(1L, libraryPlayerTimeDisplayUnit(100, true))
        assertEquals(35_999L, libraryPlayerTimeDisplayUnit(3_599_999, true))
        assertEquals(36_000L, libraryPlayerTimeDisplayUnit(3_600_000, true))
        assertEquals(21_474_836L, libraryPlayerTimeDisplayUnit(Int.MAX_VALUE, true))
    }

    @Test fun equalKeysNeverMergeDifferentReleasedTextAroundRoundingBoundaries() {
        val positions = buildList {
            add(Int.MIN_VALUE)
            for (center in listOf(0, 1_000, 60_000, 3_600_000, Int.MAX_VALUE)) {
                for (delta in -1001..1001) {
                    val position = center.toLong() + delta
                    if (position in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) add(position.toInt())
                }
            }
        }
        for (trimMode in listOf(false, true)) {
            val textByUnit = HashMap<Long, String>()
            for (millis in positions) {
                val expected = if (trimMode) formatRangeTimeInput(millis / 1000.0) else formatPlaybackTime(millis)
                val unit = libraryPlayerTimeDisplayUnit(millis, trimMode)
                val prior = textByUnit.putIfAbsent(unit, expected)
                if (prior != null) assertEquals("millis=$millis trim=$trimMode", prior, expected)
            }
        }
    }
}
