package app.smallthingz.reverb.nativeui

import smallthingz.ui.components.CachedPathIconDrawable
import smallthingz.ui.components.CachedPathIconFit
import smallthingz.ui.components.fitCachedPathIcon

internal typealias ReverbIconFit = CachedPathIconFit
internal fun fitReverbIcon(density: Float, width: Int, height: Int): ReverbIconFit =
    fitCachedPathIcon(density, width, height)

/** App glyph ownership; the shared renderer retains the exact mask/cache/tint contract. */
internal class ReverbIconDrawable(val icon: ReverbIcon, density: Float, tint: Int) :
    CachedPathIconDrawable(density, tint, icon.autoMirrored, { canvas, paint ->
        ReverbIconPaths.drawMask(icon, canvas, paint)
    })
