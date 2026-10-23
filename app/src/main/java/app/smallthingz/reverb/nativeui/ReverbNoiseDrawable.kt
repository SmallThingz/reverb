package app.smallthingz.reverb.nativeui

import android.content.res.Resources
import androidx.annotation.ColorInt

internal typealias ReverbNoiseDrawable = smallthingz.ui.graphics.NoiseDrawable

// Preserve existing call sites without adding a wrapper Drawable or retaining Resources.
@Suppress("NOTHING_TO_INLINE", "UNUSED_PARAMETER")
internal inline fun ReverbNoiseDrawable(
    resources: Resources,
    @ColorInt backgroundColor: Int? = null,
    seed: Int = ReverbNoiseDrawable.SEED_BACKGROUND,
): ReverbNoiseDrawable = smallthingz.ui.graphics.NoiseDrawable(backgroundColor, seed)
