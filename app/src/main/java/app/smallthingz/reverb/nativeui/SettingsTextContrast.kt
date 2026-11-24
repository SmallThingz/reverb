package app.smallthingz.reverb.nativeui

import android.view.View
import smallthingz.ui.reverb.ReverbTextSurface

internal fun settingsTextSurface(view: View): Long = ReverbTextSurface.sample(view)
internal fun invalidateSettingsTextContrast(owner: View) = ReverbTextSurface.invalidate(owner)
