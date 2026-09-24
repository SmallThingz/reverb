package app.smallthingz.reverb

import android.app.Application
import android.os.Process
import android.util.Log
import kotlin.system.exitProcess

class ReverbApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        installUnexpectedErrorHandler()
        Thread(
            {
                runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
                runCatching { RecordingIncidentStore.recoverUnexpectedProcessExitAndMarkCurrent(this) }
                    .onFailure { Log.e("ReverbApplication", "Unable to recover prior app-process incident", it) }
                runCatching { RecordingIncidentStore.recoverPriorSessionIfNeeded(this) }
                    .onFailure { Log.e("ReverbApplication", "Unable to recover prior recording incident", it) }
            },
            "reverb-incident-recovery",
        ).apply { isDaemon = true }.start()
    }

    private fun installUnexpectedErrorHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                RecordingIncidentStore.recordUnexpectedError(
                    context = this,
                    source = "Uncaught exception on ${thread.name}",
                    error = error,
                )
            } catch (recordingFailure: Throwable) {
                Log.e("ReverbApplication", "Unable to persist uncaught-exception incident", recordingFailure)
            } finally {
                if (previous != null) {
                    previous.uncaughtException(thread, error)
                } else {
                    Process.killProcess(Process.myPid())
                    exitProcess(10)
                }
            }
        }
    }
}
