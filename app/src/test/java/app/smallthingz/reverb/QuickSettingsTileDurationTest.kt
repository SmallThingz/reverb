package app.smallthingz.reverb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickSettingsTileDurationTest {
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
