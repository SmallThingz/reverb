package app.smallthingz.reverb.nativeui

import smallthingz.ui.components.NativeMaterialSwitchPalette

internal fun ReverbPalette.switchPalette() = NativeMaterialSwitchPalette(
    surface = surface,
    onSurface = onSurface,
    primary = primary,
    onPrimary = onPrimary,
    outline = outline,
    surfaceContainerHighest = surfaceContainerHighest,
)
