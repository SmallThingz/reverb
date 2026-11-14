package app.smallthingz.reverb.nativeui

import android.content.Context
import android.view.View
import smallthingz.ui.components.NativeStandardIconButtonView

/** Reverb glyph/palette binding for the shared circular icon action. */
internal class ReverbStandardIconButtonView(
    context: Context,
    initialEnvironment: ReverbViewEnvironment,
    inputRoot: () -> View?,
    actionSizeDp: Float = 40f,
    private val glyph: OnboardingIconView = OnboardingIconView(context, ReverbIcon.Back,
        initialEnvironment.palette.onSurface),
) : NativeStandardIconButtonView(context, initialEnvironment.textDensity.density,
    initialEnvironment.palette.onSurface, glyph,
    { window, node -> glyph.bindDescriptionAccessibility(window, { node(glyph) }, { !node(glyph).hidden }) },
    inputRoot, actionSizeDp) {
    fun bind(value: ReverbViewEnvironment, icon: ReverbIcon, description: String, enabled: Boolean,
        iconTint: Int, inheritedContentColor: Int, onClick: () -> Unit) {
        glyph.bind(icon, iconTint)
        bindAction(value.textDensity.density, description, enabled, inheritedContentColor, onClick)
    }
}
