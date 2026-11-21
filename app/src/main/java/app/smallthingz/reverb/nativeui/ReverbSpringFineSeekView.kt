package app.smallthingz.reverb.nativeui

import android.content.Context
import app.smallthingz.reverb.R
import smallthingz.ui.components.NativeSpringFineSeekEnvironment
import smallthingz.ui.components.NativeSpringFineSeekStyle
import smallthingz.ui.components.NativeSpringFineSeekView

internal typealias ReverbSpringFineSeekView = NativeSpringFineSeekView<SpringFineSeekBinding>
internal typealias SpringFineSeekEnvironment = NativeSpringFineSeekEnvironment

internal fun createReverbSpringFineSeekView(
    context: Context,
    environment: SpringFineSeekEnvironment,
    style: NativeSpringFineSeekStyle = NativeSpringFineSeekStyle.Default,
): ReverbSpringFineSeekView = NativeSpringFineSeekView(
    context, environment, style,
    { playing -> context.getString(if (playing) R.string.player_pause else R.string.player_play) },
    ::NativeSpringFineSeekState,
)
