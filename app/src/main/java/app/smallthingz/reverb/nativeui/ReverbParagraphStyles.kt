/*
 * Material token construction follows AndroidX material3 1.4.0 TypographyTokens.kt.
 * Copyright 2021 The Android Open Source Project. Licensed under the Apache License,
 * Version 2.0 (https://www.apache.org/licenses/LICENSE-2.0).
 */
package app.smallthingz.reverb.nativeui

import app.smallthingz.nativeui.text.PlatformTextStyle
import app.smallthingz.nativeui.text.TextStyle
import app.smallthingz.nativeui.text.font.FontFamily
import app.smallthingz.nativeui.text.font.FontWeight
import app.smallthingz.nativeui.text.style.LineHeightStyle
import app.smallthingz.nativeui.unit.TextUnit
import app.smallthingz.nativeui.unit.sp

/**
 * One immutable TextStyle per released role. No MaterialTheme, Composition, or runtime lookup.
 * Numeric role metadata remains authoritative; this adapter preserves unspecified properties.
 */
internal object ReverbParagraphStyles {
    private val inheritedBase = TextStyle.Default.copy(
        platformStyle = PlatformTextStyle(includeFontPadding = false),
        lineHeightStyle = LineHeightStyle(
            alignment = LineHeightStyle.Alignment.Center,
            trim = LineHeightStyle.Trim.None,
        ),
    )

    private val values = Array(ReverbTextRole.entries.size) { index ->
        val role = ReverbTextRole.entries[index]
        val metadata = role.style
        when (role) {
            // These are fresh TextStyles in ReverbTheme, not copies of Material tokens.
            ReverbTextRole.TITLE_LARGE,
            ReverbTextRole.TITLE_MEDIUM,
            ReverbTextRole.BODY_LARGE,
            ReverbTextRole.BODY_MEDIUM,
            ReverbTextRole.BODY_SMALL,
            ReverbTextRole.LABEL_LARGE -> TextStyle(
                fontWeight = FontWeight(metadata.fontWeight),
                fontSize = metadata.fontSizeSp.sp,
                lineHeight = metadata.lineHeightSp.sp,
                letterSpacing = if (role == ReverbTextRole.LABEL_LARGE)
                    metadata.letterSpacingSp.sp else TextUnit.Unspecified,
            )
            // The other nine roles inherit the pinned TypographyTokens style unchanged.
            ReverbTextRole.DISPLAY_LARGE,
            ReverbTextRole.DISPLAY_MEDIUM,
            ReverbTextRole.DISPLAY_SMALL,
            ReverbTextRole.HEADLINE_LARGE,
            ReverbTextRole.HEADLINE_MEDIUM,
            ReverbTextRole.HEADLINE_SMALL,
            ReverbTextRole.TITLE_SMALL,
            ReverbTextRole.LABEL_MEDIUM,
            ReverbTextRole.LABEL_SMALL -> inheritedBase.copy(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight(metadata.fontWeight),
                fontSize = metadata.fontSizeSp.sp,
                lineHeight = metadata.lineHeightSp.sp,
                letterSpacing = metadata.letterSpacingSp.sp,
            )
        }
    }

    operator fun get(role: ReverbTextRole): TextStyle = values[role.ordinal]
}
