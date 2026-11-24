package app.smallthingz.reverb.nativeui

import android.view.View
import smallthingz.ui.controls.ControlPalette

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
