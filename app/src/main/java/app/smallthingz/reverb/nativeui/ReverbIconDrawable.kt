package app.smallthingz.reverb.nativeui

import smallthingz.ui.components.CachedPathIconDrawable

/** App glyph ownership; the shared renderer retains the exact mask/cache/tint contract. */
internal class ReverbIconDrawable(val icon: ReverbIcon, density: Float, tint: Int) :
    CachedPathIconDrawable({ icon.mask }, density, tint, icon.autoMirrored)
