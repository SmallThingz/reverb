package app.smallthingz.reverb

import android.content.Context
import android.content.Intent

/** Transfer a committed preference reload to the Service's existing counted runtime owner. */
internal fun startCommittedSettingsRuntimeReload(context: Context) {
    val appContext = context.applicationContext
    requireServiceStarted {
        appContext.startService(
            Intent(appContext, ReverbService::class.java).setAction(ReverbService.ACTION_APPLY_SETTINGS),
        )
    }
}
