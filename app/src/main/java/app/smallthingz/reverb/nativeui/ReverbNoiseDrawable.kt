package app.smallthingz.reverb.nativeui

import android.content.res.Resources
import androidx.annotation.ColorInt
import android.view.View
import smallthingz.ui.animation.view.ViewColorTransition

internal typealias ReverbNoiseDrawable = smallthingz.ui.graphics.NoiseDrawable

// Preserve existing call sites without adding a wrapper Drawable or retaining Resources.
@Suppress("NOTHING_TO_INLINE", "UNUSED_PARAMETER")
internal inline fun ReverbNoiseDrawable(
    resources: Resources,
    @ColorInt backgroundColor: Int? = null,
    seed: Int = ReverbNoiseDrawable.SEED_BACKGROUND,
): ReverbNoiseDrawable = smallthingz.ui.graphics.NoiseDrawable(backgroundColor, seed)

/** Animate only the retained background paint; procedural grain stays cached. */
internal fun ReverbNoiseDrawable.colorTransition(view:View,initial:Int):ViewColorTransition {
    lateinit var motion:ViewColorTransition
    motion=ViewColorTransition(view,intArrayOf(initial),{setBackgroundColor(motion.color(0))})
    return motion
}
