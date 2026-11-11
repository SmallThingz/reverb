package app.smallthingz.reverb.nativeui

import app.smallthingz.reverb.rangeFineTuneVerticalDragPull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

class InlineFineSeekCompactGeometryTest {
    @Test fun visualBandShrinkPreservesTheOriginalLogicalVerticalGestureGain() {
        for (density in listOf(1f, 1.5f, 2.75f)) {
            for (originalDp in listOf(104f, 120f)) {
                for (availableDp in listOf(32f, 64f, 80f, 96f, 100f, 104f, 120f, 160f)) {
                    val available = (availableDp * density).roundToInt()
                    val originalHeight = minOf((originalDp * density).roundToInt(), available)
                    val visualHeight = minOf((96f * density).roundToInt(), available)
                    val original = SpringFineSeekGeometry(300, originalHeight, density)
                    val compact = SpringFineSeekGeometry(300, visualHeight, density, originalHeight)
                    assertEquals(original.inputVerticalTravel, compact.verticalGestureTravel, 0f)
                    for (deltaDp in listOf(-120f, -30f, 0f, 45f, 240f)) {
                        val before = rangeFineTuneVerticalDragPull(.2f, deltaDp * density,
                            original.inputVerticalTravel * 2.35f)
                        val after = rangeFineTuneVerticalDragPull(.2f, deltaDp * density,
                            compact.verticalGestureTravel * 2.35f)
                        assertEquals(before, after, 0f)
                    }
                    assertEquals(minOf(visualHeight, (64f * density).roundToInt()), compact.hitHeight)
                    if (availableDp >= 64f) assertTrue(compact.hitHeight >= (48f * density).roundToInt())
                }
            }
        }
    }

    @Test fun untouchedCallersKeepTheirOriginalGainAndPuckGeometry() {
        for (height in listOf(32, 64, 104, 120, 132)) {
            val original = SpringFineSeekGeometry(320, height, 1f)
            assertEquals(original.inputVerticalTravel, original.verticalGestureTravel, 0f)
        }
        val compact = SpringFineSeekGeometry(320, 96, 1f, 120)
        assertEquals(64, compact.hitHeight)
        assertEquals(64, compact.hitWidth)
        assertEquals(14f, compact.drawVerticalTravel, 0f)
        assertEquals(14f, compact.inputVerticalTravel, 0f)
        assertEquals(26f, compact.verticalGestureTravel, 0f)
        assertEquals(16, compact.puckTop(0f, 0f))
    }
}
