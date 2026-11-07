package app.smallthingz.reverb

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

internal const val DURABLE_SAVE_NOTIFICATION_ID = 44
private const val DURABLE_SAVE_CHANNEL = "durable-save-progress"
private const val DURABLE_SAVE_TAG_PREFIX = "reverb-save-job:"

/** A UUID remains the identity; equal numeric hashes can never replace another pending job. */
internal fun durableSaveNotificationTag(jobId: String): String = DURABLE_SAVE_TAG_PREFIX + jobId

internal object DurableSaveNotifications {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var started = false

    @Synchronized
    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        scope.launch {
            val manager = app.getSystemService(NotificationManager::class.java) ?: return@launch
            val rendered = mutableMapOf<String, RenderedSaveNotification>()
            combine(DurableSaveJobs.state, DurableSaveJobs.readiness) { jobs, readiness -> jobs to readiness }
                .collect { (jobs, readiness) ->
                    try {
                        manager.createNotificationChannel(NotificationChannel(DURABLE_SAVE_CHANNEL,
                            app.getString(R.string.saving), NotificationManager.IMPORTANCE_LOW))
                        val desired = jobs.filter(::durableSaveNeedsProgressNotification)
                            .associateBy(PendingSave::id)
                        val explicitlyFinished = jobs.filterNot(::durableSaveNeedsProgressNotification)
                            .map(PendingSave::id).toSet()
                        (explicitlyFinished + (rendered.keys - desired.keys)).forEach { id ->
                            manager.cancel(durableSaveNotificationTag(id), DURABLE_SAVE_NOTIFICATION_ID)
                            rendered.remove(id)
                        }
                        if (readiness.loaded) {
                            // Also retire notifications left by a prior process, only after authoritative hydration.
                            manager.activeNotifications.forEach { active ->
                                val tag = active.tag ?: return@forEach
                                if (active.id == DURABLE_SAVE_NOTIFICATION_ID && tag.startsWith(DURABLE_SAVE_TAG_PREFIX) &&
                                    tag.removePrefix(DURABLE_SAVE_TAG_PREFIX) !in desired) {
                                    manager.cancel(tag, DURABLE_SAVE_NOTIFICATION_ID)
                                }
                            }
                            rendered.keys.retainAll(desired.keys)
                        }
                        if (!notificationDeliveryAvailable(app, manager)) return@collect
                        for (job in desired.values) {
                            val view = renderedSaveNotification(job)
                            if (rendered[job.id] == view) continue
                            manager.notify(durableSaveNotificationTag(job.id), DURABLE_SAVE_NOTIFICATION_ID,
                                buildNotification(app, job, view))
                            rendered[job.id] = view
                        }
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        // Progress delivery is optional; the durable job and terminal feedback retain ownership.
                        Log.w("DurableSaveNotifications", "Unable to update save progress notification", error)
                    }
                }
        }
    }

    private fun notificationDeliveryAvailable(context: Context, manager: NotificationManager): Boolean =
        manager.areNotificationsEnabled() &&
            manager.getNotificationChannel(DURABLE_SAVE_CHANNEL)?.importance?.let { it != NotificationManager.IMPORTANCE_NONE } == true &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    private fun buildNotification(context: Context, job: PendingSave, view: RenderedSaveNotification): Notification {
        val failed = job.phase == SavePhase.FAILED
        val intent = Intent(context, MainActivity::class.java).apply {
            action = "${context.packageName}.SAVE_JOB.${job.id}"
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val status = context.getString(when (job.phase) {
            SavePhase.QUEUED -> R.string.save_job_queued
            SavePhase.SAVING -> R.string.saving
            SavePhase.RECOVERING -> R.string.save_job_recovering
            SavePhase.FAILED -> R.string.save_job_failed
        })
        val detail = if (failed) safeReportedErrorDetail(job.error, 512) ?: status
            else view.percent?.let { context.getString(R.string.save_job_progress, job.displayName, it) }
                ?: job.displayName
        return NotificationCompat.Builder(context, DURABLE_SAVE_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification_recording)
            .setContentTitle(status)
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(if (failed) "${job.displayName}\n$detail" else detail))
            .setContentIntent(pendingIntent)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setOngoing(!failed)
            .setAutoCancel(failed)
            .apply { if (!failed) setProgress(100, view.percent ?: 0, view.percent == null) }
            .build()
    }
}

internal data class RenderedSaveNotification(val name: String, val phase: SavePhase, val percent: Int?, val error: String?)

internal fun durableSaveNeedsProgressNotification(job: PendingSave): Boolean =
    !job.terminallyRetired || job.phase == SavePhase.FAILED

internal fun renderedSaveNotification(job: PendingSave): RenderedSaveNotification = RenderedSaveNotification(
    job.displayName, job.phase, job.progress?.takeIf { it.isFinite() }?.coerceIn(0f, .99f)?.let { (it * 100).toInt() }, job.error,
)
