package app.smallthingz.reverb.nativeui

import android.content.Context
import smallthingz.ui.components.NativeStatusBarProtection

/** Keep status icons readable without synchronizing theme motion with SystemUI's tint animation. */
internal fun createReverbStatusBarProtection(context: Context) = NativeStatusBarProtection(
    ReverbNoiseDrawable(
        context.resources,
        ReverbPalette.resolve(context, darkTheme = true).surface,
        ReverbNoiseDrawable.SEED_TOP_BAR,
    ),
    darkIcons = false,
)
