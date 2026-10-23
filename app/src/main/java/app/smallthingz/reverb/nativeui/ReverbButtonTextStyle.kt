package app.smallthingz.reverb.nativeui

import smallthingz.ui.text.PlatformTextStyle
import smallthingz.ui.text.TextStyle
import smallthingz.ui.text.style.LineHeightStyle

private val inheritedButtonBase = TextStyle.Default.copy(
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
).merge(ReverbParagraphStyles[ReverbTextRole.BODY_LARGE])

private val releasedButtonLabel = ReverbParagraphStyles[ReverbTextRole.LABEL_LARGE]
private val inheritedButtonLabel = inheritedButtonBase.merge(releasedButtonLabel)

/** Omitted Text style directly under ReverbTheme, before a component provides another role. */
internal fun reverbDefaultTextStyle(): TextStyle = inheritedButtonBase

/**
 * Text with omitted style inside the released Material3 Button/TextButton inherits this merge:
 * DefaultTextStyle -> MaterialTheme's bodyLarge -> Button's labelLarge.
 *
 * Explicit Text(style = typography.labelLarge) must keep the fresh role style instead. In particular,
 * the inherited line-height trim/platform flags affect paragraph/node bounds even when glyphs match.
 */
internal fun reverbButtonTextStyle(
    labelLarge: TextStyle = releasedButtonLabel,
): TextStyle = if (labelLarge === releasedButtonLabel) inheritedButtonLabel
    else inheritedButtonBase.merge(labelLarge)
