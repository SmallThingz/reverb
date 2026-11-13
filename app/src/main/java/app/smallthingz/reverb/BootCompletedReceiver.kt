package app.smallthingz.reverb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        try {
            BootResumeNotification.onBoot(context) { pending.finish() }
        } catch (error: Exception) {
            pending.finish()
            Log.w("BootCompletedReceiver", "Unable to schedule recording resume notification", error)
            RecordingIncidentStore.recordUnexpectedErrorInBackground(context,
                "Schedule recording resume notification", error)
        }
    }
}
