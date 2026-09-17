package app.smallthingz.reverb

import android.app.Application
import android.os.Process
import android.util.Log

class ReverbApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Thread(
            {
                runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
                runCatching { RecordingIncidentStore.recoverPriorSessionIfNeeded(this) }
                    .onFailure { Log.e("ReverbApplication", "Unable to recover prior recording incident", it) }
            },
            "reverb-incident-recovery",
        ).apply { isDaemon = true }.start()
    }
}
