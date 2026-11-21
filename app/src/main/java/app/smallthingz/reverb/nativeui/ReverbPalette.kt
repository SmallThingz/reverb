package app.smallthingz.reverb.nativeui

import smallthingz.ui.components.NativeColorScheme
import smallthingz.ui.components.srgbColorWithAlpha

internal typealias ReverbPalette = NativeColorScheme

internal fun reverbColorWithAlpha(color: Int, alpha: Float): Int = srgbColorWithAlpha(color, alpha)
