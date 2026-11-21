package app.smallthingz.reverb.nativeui

import android.view.View
import smallthingz.ui.components.NativeActionSheet
import smallthingz.ui.components.NativeActionSheetIcon

/** Reverb's appearance binding; all window/input/state ownership lives in NativeActionSheet. */
internal fun createReverbActionSheet(
    anchor: View,
    environment: ReverbViewEnvironment,
    title: String,
    onDismiss: () -> Unit,
): NativeActionSheet {
    val glyph = OnboardingIconView(anchor.context, ReverbIcon.Back, environment.palette.onSurface)
    return NativeActionSheet(anchor, environment.sheetStyle(), title, onDismiss,
        NativeActionSheetIcon(glyph,
            { window, node -> glyph.bindDescriptionAccessibility(window, { node(glyph) }, { !node(glyph).hidden }) },
            { color -> glyph.bind(ReverbIcon.Close, color) }),
        contentBackground = null,
        statusBarProtection = createReverbStatusBarProtection(anchor.context, environment.appearance))
}

internal fun NativeActionSheet.bindEnvironment(environment: ReverbViewEnvironment) =
    updateStyle(environment.sheetStyle())

private fun ReverbViewEnvironment.sheetStyle() = appearance.modals.actionSheetStyle(textDensity, sharedFontResolver, palette)
