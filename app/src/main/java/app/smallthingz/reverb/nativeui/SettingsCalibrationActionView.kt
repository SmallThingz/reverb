package app.smallthingz.reverb.nativeui

import android.content.Context
import android.view.View
import app.smallthingz.reverb.R
import smallthingz.ui.components.NativeProgressActionView

internal class SettingsCalibrationActionView(
    context: Context,
    environment: ReverbViewEnvironment,
    inputRoot: () -> View?,
    leadingIcon: ReverbIcon? = null,
    stableLabels: List<String> = emptyList(),
    progressSurface: Boolean = false,
    action: () -> Unit,
) : NativeProgressActionView(context, environment, inputRoot,
    leadingIcon?.let { icon -> { density, color -> ReverbIconDrawable(icon, density, color) } },
    stableLabels, progressSurface, { context.getString(R.string.silence_sampling) }, action)
