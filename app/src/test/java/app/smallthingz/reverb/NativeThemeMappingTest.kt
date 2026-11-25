package app.smallthingz.reverb

import android.app.UiModeManager
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import smallthingz.ui.components.NativeThemeMode

class NativeThemeMappingTest {
    @Test fun launchMirrorStartsAtApi32AndUsesOnlyAcceptedChoice() {
        for (mode in AppThemeMode.entries) {
            assertNull(mode.toApplicationNightMode(30, true))
            assertNull(mode.toApplicationNightMode(31, true))
        }
        for (sdk in listOf(32, 36)) {
            assertEquals(UiModeManager.MODE_NIGHT_AUTO, AppThemeMode.SYSTEM.toApplicationNightMode(sdk, true))
            assertEquals(UiModeManager.MODE_NIGHT_NO, AppThemeMode.LIGHT.toApplicationNightMode(sdk, true))
            assertEquals(UiModeManager.MODE_NIGHT_YES, AppThemeMode.DARK.toApplicationNightMode(sdk, true))
        }
    }

    @Test fun unavailableIndependentSourceClearsForcedMirrorInsteadOfTreatingItAsSystem() {
        for (mode in AppThemeMode.entries) {
            assertNull(mode.toApplicationNightMode(31, false))
            assertEquals(UiModeManager.MODE_NIGHT_AUTO, mode.toApplicationNightMode(32, false))
            assertEquals(UiModeManager.MODE_NIGHT_AUTO, mode.toApplicationNightMode(36, false))
        }
    }

    @Test fun systemPreviewFollowsIndependentNightAndCancelKeepsAcceptedForcedChoice() {
        for (accepted in listOf(AppThemeMode.LIGHT, AppThemeMode.DARK)) {
            val commits = CommittedThemeState()
            commits.publishCommitted(accepted)
            val owner = ThemePresentationOwner(accepted)
            owner.observeCommitted(commits.state.value)
            val mirror = accepted.toApplicationNightMode(32, true)
            owner.preview(AppThemeMode.SYSTEM, commits.state.value)
            assertFalse(owner.mode.nativeIsDark(false))
            assertTrue(owner.mode.nativeIsDark(true))
            assertEquals(accepted, commits.state.value!!.mode)
            assertEquals(mirror, commits.state.value!!.mode.toApplicationNightMode(32, true))
            owner.preview(null, commits.state.value)
            assertEquals(accepted, owner.mode)
            assertEquals(accepted == AppThemeMode.DARK, owner.mode.nativeIsDark(false))
            assertEquals(accepted == AppThemeMode.DARK, owner.mode.nativeIsDark(true))
        }
    }

    @Test fun presentationMappingPreservesDeployedNamesAndStorageCodes() {
        val modes = listOf(Triple(AppThemeMode.SYSTEM, NativeThemeMode.SYSTEM, "system"),
            Triple(AppThemeMode.LIGHT, NativeThemeMode.LIGHT, "light"),
            Triple(AppThemeMode.DARK, NativeThemeMode.DARK, "dark"))
        modes.forEachIndexed { code, (stored, presented, legacy) ->
            assertEquals(code.toByte(), stored.storageCode)
            assertEquals(stored, AppThemeMode.fromStorageCode(code))
            assertEquals(stored, AppThemeMode.fromLegacyPrefValue(legacy))
            assertEquals(presented, stored.toNativeThemeMode())
            assertEquals(stored, presented.toAppThemeMode())
        }
    }
}
