package app.smallthingz.reverb.nativeui

import smallthingz.ui.components.srgbColorWithAlpha

internal typealias ReverbPalette = smallthingz.ui.reverb.ReverbPalette

internal fun reverbColorWithAlpha(color: Int, alpha: Float): Int = srgbColorWithAlpha(color, alpha)
