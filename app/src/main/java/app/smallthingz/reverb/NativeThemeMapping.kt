package app.smallthingz.reverb

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
