package app.smallthingz.reverb.nativeui

import android.view.View
import smallthingz.ui.components.NativeActionSheet
import smallthingz.ui.components.NativeActionSheetColors
import smallthingz.ui.components.NativeActionSheetIcon
import smallthingz.ui.components.NativeActionSheetStyle

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
        ReverbNoiseDrawable(anchor.resources))
}

internal fun NativeActionSheet.bindEnvironment(environment: ReverbViewEnvironment) =
    updateStyle(environment.sheetStyle())

private fun ReverbViewEnvironment.sheetStyle() = NativeActionSheetStyle(
    textDensity, sharedFontResolver,
    NativeActionSheetColors(palette.surfaceContainerLow, palette.onSurface, palette.onSurfaceVariant,
        palette.scrim, palette.inverseSurface, palette.inverseOnSurface, palette.ink),
    ReverbParagraphStyles[ReverbTextRole.TITLE_LARGE], ReverbParagraphStyles[ReverbTextRole.BODY_SMALL],
)
