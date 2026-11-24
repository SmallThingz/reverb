package app.smallthingz.reverb.nativeui

import smallthingz.ui.text.NativeEditorLineHeightMetrics
import smallthingz.ui.text.NativeEditorTextScale
import smallthingz.ui.text.nativeEditorLineHeightMetrics
import smallthingz.ui.text.resolveEditorMetrics
import smallthingz.ui.text.NativeTypographyFontFamily
import smallthingz.ui.text.NativeTypographyLineHeightAlignment
import smallthingz.ui.text.NativeTypographyMetrics

internal typealias ReverbFontFamily = NativeTypographyFontFamily
internal typealias ReverbLineHeightAlignment = NativeTypographyLineHeightAlignment
internal typealias ReverbTextStyle = NativeTypographyMetrics
internal typealias ReverbTextRole = smallthingz.ui.reverb.ReverbTextRole

internal typealias ReverbTextScale = NativeEditorTextScale
internal typealias ReverbLineHeightMetrics = NativeEditorLineHeightMetrics

internal fun ReverbTextStyle.resolveMetrics(scale: ReverbTextScale) = resolveEditorMetrics(scale)

internal fun reverbLineHeightMetrics(
    ascent: Int,
    descent: Int,
    lineHeightPx: Float,
    alignment: ReverbLineHeightAlignment,
    trimEdges: Boolean,
) = nativeEditorLineHeightMetrics(ascent, descent, lineHeightPx, alignment, trimEdges)
