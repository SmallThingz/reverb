package app.smallthingz.reverb.nativeui

import android.content.Context
import android.view.View
import smallthingz.ui.components.NativeSegmentedPresentation
import smallthingz.ui.controls.ControlPalette

internal class ReverbSegmentedPresentation(context: Context) : NativeSegmentedPresentation {
    override val surface = ReverbGroupLayout(context, 30f)
    override fun setContent(content: View) = surface.addRow(content, ReverbGroupLayout.LayoutParams(-1, -2))
    override fun bindSurface(palette: ControlPalette) = surface.setPalette(palette.surface, palette.outline)
    override val paintedSurfaceColor: Int get() = surface.paintedFillColor
}

internal fun segmentedPalette(value: ReverbViewEnvironment): ControlPalette = value.palette.let {
    ControlPalette(it.field, it.field, it.ink, it.muted, it.primary, it.settingsOnSelection, it.settingsSelection, it.border)
}

internal fun View.settingsInputAllowed(inputRoot: () -> View?): Boolean {
    if (inputRoot() == null) return false
    var ancestor = parent as? View
    while (ancestor != null) {
        if (ancestor is NativeSettingsScreenContent) return ancestor.inputAllowed
        ancestor = ancestor.parent as? View
    }
    return false
}
