package app.smallthingz.reverb.nativeui

import android.content.Context
import android.view.View
import app.smallthingz.reverb.R
import app.smallthingz.reverb.RetentionMode
import app.smallthingz.reverb.formatRetentionMinutesEstimate
import smallthingz.ui.reverb.settings.ReverbRetentionValueView
import kotlin.math.roundToLong

internal class SettingsRetentionValueView(
    context: Context,
    glyph: ReverbIcon,
    label: String,
    editor: View,
    environment: ReverbViewEnvironment,
) : ReverbRetentionValueView(context, glyph, label, context.getString(R.string.retention_mib_unit), editor, environment) {
    fun bind(mode: RetentionMode, computedSizeMb: Double, computedTimeSeconds: Long, next: ReverbViewEnvironment) {
        val estimate = if (mode == RetentionMode.TIME)
            context.getString(R.string.retention_size_estimate, computedSizeMb.coerceAtLeast(0.0).roundToLong().toString())
        else context.getString(R.string.retention_time_estimate, formatRetentionMinutesEstimate(computedTimeSeconds))
        bind(mode.toReverbRetentionMode(), estimate, next)
    }
}
