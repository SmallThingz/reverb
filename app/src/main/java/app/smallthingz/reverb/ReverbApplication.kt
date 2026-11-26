package app.smallthingz.reverb

import android.app.Application
import android.content.res.Configuration
import android.os.Process
import android.util.Log
import kotlin.system.exitProcess

class ReverbApplication : Application() {
    // One main-thread-created token for this Application/process; never an Activity owner.
    internal var displayNightObserver: smallthingz.ui.components.NativeDisplayNightObserver? = null
        private set

    override fun onCreate() {
        super.onCreate()
        installUnexpectedErrorHandler()
        try {
            displayNightObserver = smallthingz.ui.components.NativeDisplayNightObserver.create(this)
        } catch (error: RuntimeException) {
            // Reconciliation clears any old forced mirror when independence is unavailable.
            Log.w("ReverbTheme", "Independent display night observation unavailable", error)
        }
        ReverbNotificationChannels.refreshExisting(this)
        QuickSaveTileOptions.refresh(this)
        Thread({
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S_V2) {
                // Serialize startup repair with accepted saves so an older read cannot win.
                runCatching {
                    withRetentionPersistenceLock { applyLaunchThemeMode(this, getConfiguredThemeMode(this)) }
                }.onFailure { Log.w("ReverbTheme", "Unable to reconcile starting-window theme", it) }
            }
            runCatching { DurableSaveCoordinator.get(this).recover() }
                .onFailure { Log.e("ReverbApplication", "Unable to recover pending saves", it) }
        }, "reverb-save-recovery").apply { isDaemon = true }.start()
        Thread(
            {
                runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
                var captureRecovery = runCatching { RecordingIncidentStore.recoverPriorSessionIfNeeded(this) }
                    .onFailure { Log.e("ReverbApplication", "Unable to recover prior recording incident", it) }
                // ExitInfo can appear after startup. Keep retries finite and silent on empty reads;
                // unresolved identities remain durable for later history reads and process starts.
                for (attempt in 0..5) {
                    captureRecovery = RecordingIncidentStore.retryStartupCaptureRecovery(this, captureRecovery)
                        .onFailure {
                            if (attempt == 5) Log.e("ReverbApplication", "Unable to recover pending capture exit evidence", it)
                        }
                    val pending = runCatching {
                        RecordingIncidentStore.recoverUnexpectedProcessExitAndMarkCurrent(this)
                    }.getOrElse {
                        if (attempt == 5) Log.e("ReverbApplication", "Unable to recover prior app-process incident", it)
                        true
                    }
                    if ((!pending && !captureRecovery.getOrDefault(true)) || attempt == 5) break
                    Thread.sleep(500L shl attempt)
                }
            },
            "reverb-incident-recovery",
        ).apply { isDaemon = true }.start()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        ReverbNotificationChannels.refreshExisting(this)
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
