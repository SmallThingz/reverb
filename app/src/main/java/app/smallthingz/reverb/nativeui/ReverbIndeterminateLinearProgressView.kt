package app.smallthingz.reverb.nativeui

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import smallthingz.ui.components.NativeIndeterminateLinearProgress
import smallthingz.ui.runtime.MonotonicFrameClock

internal class ReverbIndeterminateLinearProgressView(
    context: Context,
    private var environment: ReverbViewEnvironment,
    frames: MonotonicFrameClock,
    scope: CoroutineScope,
    durationScale: () -> Float,
) : NativeIndeterminateLinearProgress(context, environment.palette.primary,
    environment.palette.secondaryContainer, environment.textDensity.density, frames, scope, durationScale) {
    fun bindEnvironment(next: ReverbViewEnvironment) {
        if (environment === next) return
        environment = next
        bindColors(next.palette.primary, next.palette.secondaryContainer, next.textDensity.density)
    }
}
