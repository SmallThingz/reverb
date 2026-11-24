package app.smallthingz.reverb.nativeui

import android.content.Context

internal typealias ReverbHomeBlobControl = smallthingz.ui.reverb.home.ReverbHomeBlobControl
internal typealias ReverbHomeBlobState = smallthingz.ui.reverb.home.ReverbHomeBlobState
internal typealias ReverbHomeFlipFrame = smallthingz.ui.reverb.home.ReverbHomeFlipFrame

internal fun createHomeBlobReadout(context: Context, environment: ReverbViewEnvironment) =
    smallthingz.ui.reverb.home.createHomeBlobReadout(context, environment)
