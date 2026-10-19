package app.smallthingz.reverb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickSettingsTileDurationTest {
    @Test fun directionalStepsCrossRepresentableUnitBoundariesInBothDirections() {
        for ((current, next) in listOf(
            998 to 999,
            999 to 1_020,
            1_020 to 1_080,
            1_200 to 1_260,
            59_940 to 61_200,
            61_200 to 64_800,
        )) {
            assertEquals(next, quickTileStepSeconds(current, 86_400, true))
            assertEquals(current, quickTileStepSeconds(next, 86_400, false))
            assertEquals(next, quickTileRepresentableSeconds(next))
            val (amount, unit) = quickTileAmountAndUnit(next)
            assertEquals(next, quickSaveTileSeconds(amount, unit))
        }
    }

    @Test fun directionalStepsStopAtQuantizedCapacityAndMinimum() {
        assertEquals(1, quickTileStepSeconds(1, 86_400, false))
        assertEquals(86_400, quickTileStepSeconds(86_400, 86_400, true))
        assertEquals(999, quickTileStepSeconds(999, 1_000, true))
        assertEquals(999, quickTileStepSeconds(999, 1_019, true))
        assertEquals(59_940, quickTileStepSeconds(59_940, 61_199, true))
        assertEquals(61_200, quickTileStepSeconds(59_940, 62_000, true))
        assertEquals(31, quickTileStepSeconds(31, 31, true))
        // A deliberate edit of an existing over-limit draft returns to the valid capacity.
        assertEquals(999, quickTileStepSeconds(1_080, 1_000, false))
    }

    @Test fun wheelDurationNeverMovesBackwardAtUnitBoundaries() {
        var previous = 0
        for (requested in 1..86_400) {
            val selected = quickTileRepresentableSeconds(requested)
            assertTrue("$requested moves backward to $selected", selected >= previous)
            assertTrue("$requested rounds beyond the duration limit", selected <= requested)
            val (amount, unit) = quickTileAmountAndUnit(selected)
            assertEquals(selected, quickSaveTileSeconds(amount, unit))
            previous = selected
        }
    }

    @Test fun amountLimitsPreserveBoundaryValuesBeforeNextRepresentableDuration() {
        assertEquals(999, quickTileRepresentableSeconds(1000))
        assertEquals(999, quickTileRepresentableSeconds(1019))
        assertEquals(1020, quickTileRepresentableSeconds(1020))
        assertEquals(59_940, quickTileRepresentableSeconds(60_000))
        assertEquals(59_940, quickTileRepresentableSeconds(61_199))
        assertEquals(61_200, quickTileRepresentableSeconds(61_200))
        assertEquals(86_400, quickTileRepresentableSeconds(86_400))
    }

    @Test fun existingExactDefinitionsSurviveThePicker() {
        for (unit in QuickSaveUnit.entries) {
            for (amount in 1..999) {
                val seconds = quickSaveTileSeconds(amount, unit) ?: continue
                assertEquals(seconds, quickTileRepresentableSeconds(seconds))
                val (newAmount, newUnit) = quickTileAmountAndUnit(seconds)
                assertEquals(seconds, quickSaveTileSeconds(newAmount, newUnit))
            }
        }
    }
}
