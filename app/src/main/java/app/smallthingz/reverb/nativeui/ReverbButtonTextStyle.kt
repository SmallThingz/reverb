package app.smallthingz.reverb.nativeui

import smallthingz.ui.text.TextStyle

internal fun reverbDefaultTextStyle(): TextStyle = reverbAppearance.typography.defaultTextStyle

internal fun reverbButtonTextStyle(labelLarge: TextStyle = ReverbParagraphStyles[ReverbTextRole.LABEL_LARGE]): TextStyle =
    reverbAppearance.typography.buttonLabelStyle(labelLarge)
