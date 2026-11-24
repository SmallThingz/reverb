package app.smallthingz.reverb.nativeui

import android.content.Context
import smallthingz.ui.reverb.ReverbDesign

/** Host adapter for the shared Reverb status-bar appearance. */
internal fun createReverbStatusBarProtection(context: Context) = ReverbDesign.statusBarProtection(context)
