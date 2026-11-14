package app.smallthingz.reverb.nativeui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeSettingsTopBarGeometryTest {
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
