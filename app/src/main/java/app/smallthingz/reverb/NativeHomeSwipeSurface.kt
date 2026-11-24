package app.smallthingz.reverb

import android.content.Context
import android.view.View
import smallthingz.ui.input.phase.NativeClickPhaseEndpoint

internal fun nativeHomeSwipeSurface(
    context: Context,
    blob: View,
    endpoint: NativeClickPhaseEndpoint,
    detector: NativeHomeHorizontalDrag,
    minimumTarget: () -> Float,
) = smallthingz.ui.reverb.home.nativeHomeSwipeSurface(context, blob, endpoint, detector, minimumTarget)
