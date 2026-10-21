package app.smallthingz.reverb.nativeui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeSettingsTopBarGeometryTest {
    @Test fun oddWidthTitleCentersAgainstWholeBarAndMirrorsRelativePlacement() {
        val ltr = settingsTopBarPlacement(301, 64, 52, 48, 100, 31, 52, 48, 1f, false)
        val rtl = settingsTopBarPlacement(301, 64, 52, 48, 100, 31, 52, 48, 1f, true)
        assertEquals(101, ltr.titleX)
        assertEquals(100, rtl.titleX)
        assertEquals(16, ltr.titleY) // Source integer division, unlike center alignment's rounded X.
        assertEquals(8, ltr.navigationY)
        assertEquals(0, ltr.navigationX)
        assertEquals(249, rtl.navigationX)
        assertEquals(249, ltr.actionX)
        assertEquals(0, rtl.actionX)
    }

    @Test fun asymmetricActionsConstrainTitleAfterCenteringAgainstWholeBar() {
        val source = settingsTopBarPlacement(301, 64, 102, 48, 147, 28, 52, 48, 1f, false)
        assertEquals(102, source.titleX)
        val opposite = settingsTopBarPlacement(301, 64, 52, 48, 147, 28, 102, 48, 1f, false)
        assertEquals(52, opposite.titleX)
    }

    @Test fun sourceHeightCanGrowWithMultilineTitleAndPreservesCenterDivision() {
        val source = settingsTopBarPlacement(421, 197, 137, 126, 147, 197, 137, 126, 421f / 160f, false)
        assertEquals(0, source.titleY)
        assertEquals(35, source.navigationY)
        assertEquals(137, source.titleX)
    }

    @Test fun cutoutAndBarsUnionBeforeConsumedInsetSubtractionWithoutImeOrBottom() {
        assertEquals(NativeSettingsTopBarInsets(17, 22, 0), NativeSettingsTopBarInsets.remaining(
            barsLeft = 20, barsTop = 24, barsRight = 0,
            cutoutLeft = 12, cutoutTop = 30, cutoutRight = 3,
            consumedLeft = 3, consumedTop = 8, consumedRight = 4))
    }

    @Test fun doneEnabledTracksEverySourcePendingOwnerButNavigationRemainsIndependent() {
        fun state(changed: Boolean = true, saving: Boolean = false, calibration: Boolean = false,
                  tile: Boolean = false, duration: Boolean = false) =
            SettingsTopBarBinding(changed, saving, calibration, tile, duration, {}, {})
        assertTrue(state().doneEnabled)
        assertFalse(state(changed = false).doneEnabled)
        assertFalse(state(saving = true).doneEnabled)
        assertFalse(state(calibration = true).doneEnabled)
        assertFalse(state(tile = true).doneEnabled)
        assertFalse(state(duration = true).doneEnabled)
    }
}
