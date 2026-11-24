package app.smallthingz.reverb.nativeui

import android.content.Context
import app.smallthingz.reverb.R
import smallthingz.ui.components.NativeSpringFineSeekStyle

internal typealias ReverbSpringFineSeekView = smallthingz.ui.reverb.ReverbSpringFineSeekView
internal typealias SpringFineSeekEnvironment = smallthingz.ui.reverb.SpringFineSeekEnvironment

internal fun createReverbSpringFineSeekView(
    context: Context,
    environment: SpringFineSeekEnvironment,
    style: NativeSpringFineSeekStyle = NativeSpringFineSeekStyle.Default,
): ReverbSpringFineSeekView = smallthingz.ui.reverb.createReverbSpringFineSeekView(context, environment, style) {
    context.getString(if (it) R.string.player_pause else R.string.player_play)
}
