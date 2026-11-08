package app.smallthingz.reverb.nativeui

import android.content.Context
import android.view.View
import smallthingz.ui.components.NativeMenuColors
import smallthingz.ui.components.NativeMenuIcons

internal fun ReverbPalette.menuColors() = NativeMenuColors(raised, surfaceContainerHigh, ink, muted, primary)
internal object ReverbMenuIcons : NativeMenuIcons<ReverbIcon> {
    override val check = ReverbIcon.Check
    override fun create(context: Context, icon: ReverbIcon, color: Int): View = OnboardingIconView(context, icon, color)
    override fun bind(view: View, icon: ReverbIcon, color: Int) = (view as OnboardingIconView).bind(icon, color)
}
