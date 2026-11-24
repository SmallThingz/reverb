package app.smallthingz.reverb.nativeui

import android.content.Context
import android.view.View
import app.smallthingz.reverb.R
import app.smallthingz.reverb.RetentionMode
import smallthingz.ui.reverb.settings.ReverbRetentionMode
import smallthingz.ui.reverb.settings.ReverbRetentionSection

internal typealias SettingsSectionKey = smallthingz.ui.reverb.settings.ReverbSettingsSectionKey

internal class SettingsRetentionSection(
    context: Context,
    mode: RetentionMode,
    environment: ReverbViewEnvironment,
    inputRoot: () -> View?,
    onMode: (RetentionMode) -> Boolean,
    oneShotValue: View,
    loopingValue: View,
) : ReverbRetentionSection(context, mode.toReverbRetentionMode(), environment,
    { context.getString(R.string.retention_mode_title) }, { it.settingsInputAllowed(inputRoot) },
    { selected ->
        onMode(if (selected == ReverbRetentionMode.TIME) RetentionMode.TIME else RetentionMode.SIZE)
    }, oneShotValue, loopingValue) {
    fun bind(mode: RetentionMode, message: String?, next: ReverbViewEnvironment) =
        bind(mode.toReverbRetentionMode(), message, next)
}

internal typealias SettingsInsetView = smallthingz.ui.components.NativeSingleChildInset
