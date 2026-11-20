package app.smallthingz.reverb.nativeui

import android.content.Context
import smallthingz.ui.components.NativeDeterminateLinearProgress

internal class SettingsCalibrationProgressView(context: Context, environment: ReverbViewEnvironment) :
    NativeDeterminateLinearProgress(context, environment.palette.primary,
        environment.palette.secondaryContainer, environment.textDensity.density) {
    fun bind(value: Float, next: ReverbViewEnvironment, operationId: Long, terminal: Boolean) {
        bind(value, next.palette.primary, next.palette.secondaryContainer, next.textDensity.density,
            operationId, terminal)
    }
}
