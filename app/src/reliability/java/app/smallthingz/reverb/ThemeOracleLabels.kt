package app.smallthingz.reverb

import androidx.annotation.StringRes

/** QA compatibility for the byte-pinned released renderer; no production selector dependency. */
internal val AppThemeMode.labelRes: Int
    @StringRes get() = when (this) {
        AppThemeMode.SYSTEM -> R.string.theme_system
        AppThemeMode.LIGHT -> R.string.theme_light
        AppThemeMode.DARK -> R.string.theme_dark
    }
