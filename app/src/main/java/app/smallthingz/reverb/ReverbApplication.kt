package app.smallthingz.reverb

import android.app.Application
import android.util.Log

class ReverbApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        runCatching { RecordingIncidentStore.recoverPriorSessionIfNeeded(this) }
            .onFailure { Log.e("ReverbApplication", "Unable to recover prior recording incident", it) }
    }
}
