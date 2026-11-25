package app.smallthingz.reverb

import android.app.UiModeManager
import android.content.Context
import android.os.Build
import android.util.Log
import smallthingz.ui.components.NativeThemeMode

/** Persisted bytes and deployed legacy names remain app-owned. */
internal fun AppThemeMode.toNativeThemeMode(): NativeThemeMode = when (this) {
    AppThemeMode.SYSTEM -> NativeThemeMode.SYSTEM
    AppThemeMode.LIGHT -> NativeThemeMode.LIGHT
    AppThemeMode.DARK -> NativeThemeMode.DARK
}

internal fun NativeThemeMode.toAppThemeMode(): AppThemeMode = when (this) {
    NativeThemeMode.SYSTEM -> AppThemeMode.SYSTEM
    NativeThemeMode.LIGHT -> AppThemeMode.LIGHT
    NativeThemeMode.DARK -> AppThemeMode.DARK
}

/** Below API32 keep the existing path; without an independent source clear a stale mirror. */
internal fun AppThemeMode.toApplicationNightMode(sdkInt: Int, hasIndependentSystemNight: Boolean): Int? {
    if (sdkInt < Build.VERSION_CODES.S_V2) return null
    if (!hasIndependentSystemNight) return UiModeManager.MODE_NIGHT_AUTO
    return when (this) {
        AppThemeMode.SYSTEM -> UiModeManager.MODE_NIGHT_AUTO
        AppThemeMode.LIGHT -> UiModeManager.MODE_NIGHT_NO
        AppThemeMode.DARK -> UiModeManager.MODE_NIGHT_YES
    }
}

/** Only durable commits and serialized startup repair call this own-package starting-window mirror. */
internal fun applyLaunchThemeMode(context: Context, mode: AppThemeMode) {
    val application = context.applicationContext as? ReverbApplication
    val nightMode = mode.toApplicationNightMode(Build.VERSION.SDK_INT,
        application?.displayNightObserver != null) ?: return
    try {
        requireNotNull(context.getSystemService(UiModeManager::class.java)) { "UiModeManager unavailable" }
            .setApplicationNightMode(nightMode)
    } catch (error: RuntimeException) {
        // A platform rejection cannot undo durable Settings or skip the recorder update.
        Log.w("ReverbTheme", "Unable to apply Android starting-window night mode", error)
    }
}
