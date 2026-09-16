package app.smallthingz.reverb

import android.app.Application

class ReverbApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        RecordingIncidentStore.recoverPriorSessionIfNeeded(this)
    }
}
