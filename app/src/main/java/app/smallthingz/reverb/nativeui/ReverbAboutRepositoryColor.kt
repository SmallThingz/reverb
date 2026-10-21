/*
 * Color lookup follows AndroidX Material3 1.4.0 ColorScheme.kt.
 * Copyright 2021 The Android Open Source Project. Licensed under the Apache License,
 * Version 2.0 (https://www.apache.org/licenses/LICENSE-2.0).
 */
package app.smallthingz.reverb.nativeui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle

/**
 * The released repository Surface omits contentColor. Material3 1.4.0 resolves it by
 * ordered color-value equality, not by the name of the supplied background role.
 * In particular, surfaceVariant can equal surfaceContainerHighest in a dynamic palette.
 * All later cases before surfaceContainerHighest itself resolve to onSurface.
 */
internal fun reverbAboutRepositoryContentColor(palette: ReverbPalette): Int = with(palette) {
    when (surfaceContainerHighest) {
        primary -> onPrimary
        secondary -> onSecondary
        tertiary -> onTertiary
        background -> onBackground
        error -> onError
        primaryContainer -> onPrimaryContainer
        secondaryContainer -> onSecondaryContainer
        tertiaryContainer -> onTertiaryContainer
        errorContainer -> onErrorContainer
        inverseSurface -> inverseOnSurface
        surface -> onSurface
        surfaceVariant -> onSurfaceVariant
        else -> onSurface
    }
}

/** Text(color = Unspecified, style = titleSmall) honors the style before LocalContentColor. */
internal fun reverbAboutRepositoryTitleColor(palette: ReverbPalette, style: TextStyle): Int =
    if (style.color != Color.Unspecified) style.color.toArgb()
    else reverbAboutRepositoryContentColor(palette)
