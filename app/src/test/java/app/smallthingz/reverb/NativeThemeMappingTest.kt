package app.smallthingz.reverb

import org.junit.Assert.assertEquals
import org.junit.Test
import smallthingz.ui.components.NativeThemeMode

class NativeThemeMappingTest {
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
