package app.smallthingz.reverb

import android.app.Instrumentation
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import app.smallthingz.reverb.nativeui.ReverbPalette
import app.smallthingz.reverb.nativeui.ReverbViewEnvironment
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import smallthingz.ui.common.ToggleState
import smallthingz.ui.components.NativeMaterialSwitchView
import smallthingz.ui.reverb.ReverbGlyphView
import smallthingz.ui.reverb.settings.ReverbSettingsToggle
import smallthingz.ui.reverb.settings.ReverbSettingsToggleRole
import smallthingz.ui.text.font.createFontFamilyResolver
import smallthingz.ui.text.view.ParagraphView
import smallthingz.ui.unit.Density

/** Actual shared toggle, including narrow/large-text layout and decorative icon semantics. */
internal fun verifySettingsToggleIcons(i: Instrumentation, host: NativeParityActivity, output: File): JSONArray {
    val cases = JSONArray()
    fun <T> main(block: () -> T) = nativeParityOnMain(i, block)
    val density = host.resources.displayMetrics.density
    val width = (280 * density).toInt()
    for (dark in listOf(false, true)) for (rtl in listOf(false, true)) for (scale in listOf(1f, 2f)) {
        for (role in ReverbSettingsToggleRole.entries) {
            var changes = 0
            val env = ReverbViewEnvironment(Density(density, scale), createFontFamilyResolver(host), ReverbPalette.fallback(dark))
            val state = ToggleState("${role.name.replace('_', ' ')} with a longer label", false, true,
                "A supporting description that must wrap without covering the switch.")
            val toggle = main {
                ReverbSettingsToggle(host, env.theme, state, role, { host.fixtureRoot }) { changes++ }.also {
                    host.fixtureRoot.removeAllViews()
                    host.fixtureRoot.setBackgroundColor(env.palette.surface)
                    it.view.layoutDirection = if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
                    host.fixtureRoot.addView(it.view, FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT))
                }
            }
            try {
                awaitNativeParityDraw(i, toggle.view)
                main {
                    val row = toggle.view as ViewGroup
                    val children = (0 until row.childCount).map(row::getChildAt)
                    val glyph = children.filterIsInstance<ReverbGlyphView>().single()
                    val switch = children.filterIsInstance<NativeMaterialSwitchView>().single()
                    val labels = children.filterIsInstance<ParagraphView>()
                    check(labels.size == 2 && glyph.width > 0 && glyph.height > 0)
                    check(!glyph.isClickable && !glyph.isFocusable && glyph.importantForAccessibility == View.IMPORTANT_FOR_ACCESSIBILITY_NO)
                    val textMetrics = JSONArray()
                    for ((index, label) in labels.withIndex()) {
                        check(label.width > 0 && label.height > 0 && label.top >= 0 && label.bottom <= row.height)
                        check(if (rtl) switch.right <= label.left && label.right <= glyph.left
                            else glyph.right <= label.left && label.right <= switch.left) { "Toggle overlaps at $role/$rtl/$scale" }
                        // Inspect the paragraph used by onDraw, rather than a second text layout.
                        val paragraph = checkNotNull(label.currentParagraph)
                        val expected = if (index == 0) state.label else checkNotNull(state.description)
                        val availableWidth = label.width - label.paddingLeft - label.paddingRight
                        val availableHeight = label.height - label.paddingTop - label.paddingBottom
                        val lines = JSONArray()
                        var fits = paragraph.lineCount > 0 && paragraph.height <= availableHeight + 1f
                        var complete = label.getText() == expected && !paragraph.didExceedMaxLines
                        var previousEnd = 0
                        for (line in 0 until paragraph.lineCount) {
                            val left = paragraph.getLineLeft(line)
                            val right = paragraph.getLineRight(line)
                            val top = paragraph.getLineTop(line)
                            val bottom = paragraph.getLineBottom(line)
                            val start = paragraph.getLineStart(line)
                            val end = paragraph.getLineEnd(line)
                            val ellipsized = paragraph.isLineEllipsized(line)
                            // One physical pixel permits rounding, not clipped words or missing lines.
                            fits = fits && left >= -1f && right >= left && right <= availableWidth + 1f &&
                                top >= -1f && bottom >= top && bottom <= availableHeight + 1f
                            complete = complete && start == previousEnd && end >= start && !ellipsized
                            previousEnd = end
                            lines.put(JSONObject().put("left", left).put("right", right).put("width", right - left)
                                .put("top", top).put("bottom", bottom).put("start", start).put("end", end)
                                .put("ellipsized", ellipsized))
                        }
                        complete = complete && paragraph.lineCount > 0 && previousEnd == expected.length
                        val metrics = JSONObject().put("label", if (index == 0) "title" else "description")
                            .put("expected_length", expected.length).put("available_width", availableWidth)
                            .put("available_height", availableHeight).put("paragraph_height", paragraph.height)
                            .put("did_exceed_max_lines", paragraph.didExceedMaxLines).put("lines", lines)
                            .put("text_fits", fits).put("text_complete", complete)
                        textMetrics.put(metrics)
                        check(fits && complete) { "Toggle text clipped/incomplete at $role/$dark/$rtl/$scale: $metrics" }
                    }
                    row.performClick()
                    check(changes == 1)
                    toggle.bind(state.copy(enabled = false), env.theme)
                    row.performClick()
                    check(changes == 1) { "Disabled toggle activated" }
                    toggle.bind(state, env.theme)
                    cases.put(JSONObject().put("role", role.name).put("dark", dark).put("rtl", rtl)
                        .put("font_scale", scale).put("width", row.width).put("height", row.height)
                        .put("text_metrics", textMetrics)
                        .put("single_action", true).put("decorative_icon", true).put("passed", true))
                }
                if (!rtl && scale == 1f && role == ReverbSettingsToggleRole.OUTPUT_COMPRESSION) {
                    val image = captureNativeParityContent(i, host) { toggle.view }
                    try { saveNativeParityBitmap(output, "toggle-${if (dark) "dark" else "light"}", image) }
                    finally { image.recycle() }
                }
            } finally { main { host.fixtureRoot.removeView(toggle.view); toggle.close() } }
        }
    }
    return cases
}
