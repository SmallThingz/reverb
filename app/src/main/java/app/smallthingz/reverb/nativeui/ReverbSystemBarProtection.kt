package app.smallthingz.reverb.nativeui

import android.content.Context
import smallthingz.ui.components.NativeUiAppearance

/** Keep status icons readable without synchronizing theme motion with SystemUI's tint animation. */
internal fun createReverbStatusBarProtection(context: Context, appearance: NativeUiAppearance = reverbAppearance) =
    appearance.modals.statusBar.let { recipe ->
        recipe.create(appearance.resolveColorScheme(context, darkTheme = !recipe.darkIcons))
    }
