package app.smallthingz.reverb.nativeui

import android.view.View
import smallthingz.ui.components.NativeActionSheet
import smallthingz.ui.reverb.bindReverbTheme
import smallthingz.ui.reverb.createReverbActionSheet

internal fun createReverbActionSheet(
    anchor: View,
    environment: ReverbViewEnvironment,
    title: String,
    onDismiss: () -> Unit,
): NativeActionSheet = createReverbActionSheet(anchor, environment.theme, title, onDismiss)

internal fun NativeActionSheet.bindEnvironment(environment: ReverbViewEnvironment) =
    bindReverbTheme(environment.theme)
