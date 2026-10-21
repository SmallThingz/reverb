package app.smallthingz.reverb.nativeui

import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle

private val inheritedButtonBase = TextStyle.Default.copy(
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
).merge(ReverbParagraphStyles[ReverbTextRole.BODY_LARGE])

private val releasedButtonLabel = ReverbParagraphStyles[ReverbTextRole.LABEL_LARGE]
private val inheritedButtonLabel = inheritedButtonBase.merge(releasedButtonLabel)

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
