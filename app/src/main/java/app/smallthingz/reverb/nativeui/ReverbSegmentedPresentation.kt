package app.smallthingz.reverb.nativeui

import android.content.Context
import android.view.View
import smallthingz.ui.components.NativeThemeMode
import smallthingz.ui.components.NativeThemeSelector
import smallthingz.ui.controls.ControlPalette

internal fun createSettingsThemeSelector(
    context: Context,
    environment: ReverbViewEnvironment,
    selected: NativeThemeMode,
    inputAllowed: () -> Boolean,
    onSelectionRequest: (NativeThemeMode) -> Boolean,
): NativeThemeSelector = environment.appearance.settingsSelectors.createThemeSelector(
    context, segmentedPalette(environment), selected, environment.textDensity, environment.sharedFontResolver,
    inputAllowed = inputAllowed,
    onPaintedSurfaceChanged = ::invalidateSettingsTextContrast,
    titlePaintedSurface = ::settingsTextSurface,
    onSelectionRequest = onSelectionRequest,
)

internal fun segmentedPalette(value: ReverbViewEnvironment): ControlPalette = value.palette.settingsControls

internal fun View.settingsInputAllowed(inputRoot: () -> View?): Boolean {
    if (inputRoot() == null) return false
    var ancestor = parent as? View
    while (ancestor != null) {
        if (ancestor is NativeSettingsScreenContent) return ancestor.inputAllowed
        ancestor = ancestor.parent as? View
    }
    return false
}
