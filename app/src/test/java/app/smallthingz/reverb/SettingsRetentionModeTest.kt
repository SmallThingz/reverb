package app.smallthingz.reverb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsRetentionModeTest {
    @Test
    fun switchingFromTimeToSize_preservesInputDrafts() {
        val change = settingsRetentionModeChange(
            currentMode = RetentionMode.TIME,
            requestedMode = RetentionMode.SIZE,
        )

        requireNotNull(change)
        assertEquals(RetentionMode.SIZE, change.mode)
        assertTrue(change.preserveInputDrafts)
    }

    @Test
    fun switchingFromSizeToTime_preservesInputDrafts() {
        val change = settingsRetentionModeChange(
            currentMode = RetentionMode.SIZE,
            requestedMode = RetentionMode.TIME,
        )

        requireNotNull(change)
        assertEquals(RetentionMode.TIME, change.mode)
        assertTrue(change.preserveInputDrafts)
    }

    @Test
    fun selectingCurrentMode_isNoOp() {
        assertNull(
            settingsRetentionModeChange(
                currentMode = RetentionMode.SIZE,
                requestedMode = RetentionMode.SIZE,
            ),
        )
    }
}
