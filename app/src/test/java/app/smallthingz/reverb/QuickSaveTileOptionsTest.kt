package app.smallthingz.reverb

import org.junit.Assert.*
import org.junit.Test

class QuickSaveTileOptionsTest {
    private fun option(slot: Int = 0, amount: Int = 2, unit: QuickSaveUnit = QuickSaveUnit.MINUTES,
                       buffer: ReverbService.BufferSlot = ReverbService.BufferSlot.LOOPING) =
        QuickSaveTileOption(slot, slot.toString(16).padStart(32, '0'), buffer, amount, unit)

    @Test fun newTilesDefaultToLoopingAndTwoMinutes() {
        val tile = QuickSaveTileOption(0, "a".repeat(32))
        assertEquals(ReverbService.BufferSlot.LOOPING, tile.buffer)
        assertEquals(120, tile.seconds)
        assertEquals("2", tile.iconText)
    }

    @Test fun independentDurationsAndUnitsSurviveStorage() {
        val options = listOf(option(0, 30, QuickSaveUnit.SECONDS),
            option(1, 2, QuickSaveUnit.MINUTES, ReverbService.BufferSlot.ONE_SHOT),
            option(2, 1, QuickSaveUnit.HOURS))
        assertEquals(options, decodeQuickSaveTileOptions(encodeQuickSaveTileOptions(options.reversed())))
        assertEquals(listOf(30, 120, 3600), options.map { it.seconds })
        assertEquals(listOf("30", "2", "1"), options.map { it.iconText })
    }

    @Test fun fixedSlotsHaveDistinctStableComponents() {
        assertEquals(QUICK_SAVE_TILE_COUNT, (0 until QUICK_SAVE_TILE_COUNT).map(::quickSaveTileClass).toSet().size)
        assertThrows(IllegalArgumentException::class.java) { quickSaveTileClass(-1) }
        assertThrows(IllegalArgumentException::class.java) { quickSaveTileClass(QUICK_SAVE_TILE_COUNT) }
    }

    @Test fun invalidAmountsNeverBecomeAnotherDuration() {
        for (n in listOf(Int.MIN_VALUE, -1, 0, 1000, Int.MAX_VALUE)) {
            for (unit in QuickSaveUnit.entries) assertNull(quickSaveTileSeconds(n, unit))
        }
        assertEquals(86400, quickSaveTileSeconds(24, QuickSaveUnit.HOURS))
        assertNull(quickSaveTileSeconds(25, QuickSaveUnit.HOURS))
        assertEquals(999, quickSaveTileSeconds(999, QuickSaveUnit.SECONDS))
    }

    @Test fun emptyConfigurationDoesNotInventTiles() {
        assertEquals(emptyList<QuickSaveTileOption>(), decodeQuickSaveTileOptions(null))
        assertEquals(emptyList<QuickSaveTileOption>(), decodeQuickSaveTileOptions("1"))
    }

    @Test fun malformedDuplicateAndOversizedSettingsFailClosed() {
        val one = encodeQuickSaveTileOptions(listOf(option()))
        for (raw in listOf("", "2", "$one\n", "$one\n${one.substringAfter('\n')}",
            one.replace("MINUTES", "UNKNOWN"), one.replace("|2|", "|0|"),
            "1\n8|${"a".repeat(32)}|1|2|MINUTES", "1\n" + "x".repeat(1025))) {
            assertThrows(IllegalArgumentException::class.java) { decodeQuickSaveTileOptions(raw) }
        }
    }
}
