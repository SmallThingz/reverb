package app.smallthingz.reverb

import android.app.Application
import android.os.Process
import android.util.Log
import kotlin.system.exitProcess

class ReverbApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        installUnexpectedErrorHandler()
        QuickSaveTileOptions.refresh(this)
        Thread(
            {
                runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
                runCatching { RecordingIncidentStore.recoverPriorSessionIfNeeded(this) }
                    .onFailure { Log.e("ReverbApplication", "Unable to recover prior recording incident", it) }
                // ExitInfo can appear after startup. Keep retries finite and silent on empty reads;
                // unresolved identities remain durable for later history reads and process starts.
                for (attempt in 0..5) {
                    val pending = runCatching {
                        RecordingIncidentStore.recoverUnexpectedProcessExitAndMarkCurrent(this)
                    }.getOrElse {
                        if (attempt == 5) Log.e("ReverbApplication", "Unable to recover prior app-process incident", it)
                        true
                    }
                    if (!pending || attempt == 5) break
                    Thread.sleep(500L shl attempt)
                }
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
