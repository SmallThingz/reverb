package app.smallthingz.reverb.nativeui

import android.content.Context
import android.view.View
import smallthingz.ui.components.NativeMaterialSwitchView
import smallthingz.ui.components.NativeToggleRow
import smallthingz.ui.text.view.ParagraphView

/** Applies Reverb typography and palette to the shared library toggle row. */
internal class SettingsToggleRowView private constructor(
    context: Context,
    initialChecked: Boolean,
    initialEnabled: Boolean,
    environment: ReverbViewEnvironment,
    inputRoot: () -> View?,
    private val dimDisabledText: Boolean,
    changed: ((Boolean) -> Unit)?,
    private val title: ParagraphView,
    private val summary: ParagraphView?,
) : NativeToggleRow(context, title, summary,
    NativeMaterialSwitchView(context, environment.palette.switchPalette(), initialChecked, initialEnabled),
    inputRoot, changed) {
    constructor(context: Context, text: String, initialChecked: Boolean, initialEnabled: Boolean,
        environment: ReverbViewEnvironment, inputRoot: () -> View?, dimDisabledText: Boolean,
        changed: ((Boolean) -> Unit)?, summary: String? = null,
    ) : this(context, initialChecked, initialEnabled, environment, inputRoot, dimDisabledText, changed,
        environment.paragraph(context, text, ReverbTextRole.BODY_LARGE),
        summary?.let { environment.paragraph(context, it, ReverbTextRole.BODY_SMALL, environment.palette.muted) })

    init { bind(initialChecked, initialEnabled, environment) }

    fun bind(checked: Boolean, enabled: Boolean, next: ReverbViewEnvironment, summaryText: String? = null) {
        val palette = next.palette
        next.bindParagraphStyle(title, ReverbTextRole.BODY_LARGE,
            if (!enabled && dimDisabledText) palette.muted else palette.ink)
        summary?.let {
            if (summaryText != null) it.setText(summaryText)
            next.bindParagraphStyle(it, ReverbTextRole.BODY_SMALL, palette.muted)
        }
        bindToggle(checked, enabled, next.textDensity.density, palette.ink, palette.switchPalette())
    }
}
