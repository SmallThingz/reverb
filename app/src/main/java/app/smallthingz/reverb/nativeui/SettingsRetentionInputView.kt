package app.smallthingz.reverb.nativeui

import android.content.Context
import app.smallthingz.reverb.RetentionMode
import smallthingz.ui.reverb.settings.ReverbRetentionInputView
import smallthingz.ui.reverb.settings.ReverbRetentionMode
import smallthingz.ui.reverb.settings.retentionImeContract as libraryRetentionImeContract

internal fun RetentionMode.toReverbRetentionMode(): ReverbRetentionMode = when (this) {
    RetentionMode.TIME -> ReverbRetentionMode.TIME
    RetentionMode.SIZE -> ReverbRetentionMode.SIZE
}

internal class SettingsRetentionInputView(context: Context, environment: ReverbViewEnvironment) :
    ReverbRetentionInputView(context, environment) {
    fun bind(
        rawString: String,
        mode: RetentionMode,
        bufferLabel: String,
        environment: ReverbViewEnvironment,
        requestFocus: Boolean,
        readCurrentRaw: () -> String,
        onRawChanged: (String) -> Unit,
    ) = bind(rawString, mode.toReverbRetentionMode(), bufferLabel, environment, requestFocus, readCurrentRaw, onRawChanged)
}

internal fun retentionImeContract(mode: RetentionMode) = libraryRetentionImeContract(mode.toReverbRetentionMode())
