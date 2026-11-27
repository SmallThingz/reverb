package app.smallthingz.reverb

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.IntentSender
import android.os.Build
import android.os.Process
import android.provider.MediaStore
import android.util.Log
import androidx.core.net.toUri
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class RecordingDeletionConsentTarget(
    val recording: RecordingEntity,
    val fingerprint: PendingDeletionIntent,
    val legacySender: IntentSender? = null,
)

internal data class RecordingDeletionPlan(
    val undo: List<RecordingEntity>,
    val prompt: List<RecordingDeletionConsentTarget>,
)

internal data class RecordingDeletionConsentRequest(
    val targets: List<RecordingDeletionConsentTarget>,
    val token: String = UUID.randomUUID().toString(),
    val rename: RecordingRenameConsentWork? = null,
    val promptCompletion: CompletableDeferred<Unit>? = null,
)

internal class RecordingRenameConsentWork(
    val proof: RecordingRenameConsentRequired,
    val retry: suspend () -> RecordingEntity?,
) {
    val result = CompletableDeferred<RecordingEntity?>()
    var cancelled = false
    var granted = false
}

internal data class RecordingDeletionConsentLaunch(
    val token: String,
    val sender: IntentSender,
    val targets: List<RecordingDeletionConsentTarget>,
) {
    @androidx.annotation.MainThread
    fun launchAfterStoppingPlayback(launch: (IntentSender) -> Unit) {
        // An admitted notification may already have queued or opened this exact recording.
        targets.forEach { RecordingPlayback.stop(it.recording) }
        launch(sender)
    }
}

internal sealed interface RecordingDeletionAttempt {
    data object Deleted : RecordingDeletionAttempt
    data class Failed(val failure: RecordingDeletionFailure) : RecordingDeletionAttempt
    data class NeedsConsent(val target: RecordingDeletionConsentTarget) : RecordingDeletionAttempt
}

/** No Activity, launcher, or View is retained by this process-owned handoff. */
internal object RecordingDeletionConsent {
    private val pending = RecordingDeletionConsentState<RecordingDeletionConsentRequest> { it.token }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val changed = MutableStateFlow(0L)
    val changes = changed.asStateFlow()
    private val completed = MutableStateFlow(0L)
    val completions = completed.asStateFlow()
    private class Execution(val request: RecordingDeletionConsentRequest, val journal: RecordingDeletionJournalBatch?) {
        var nextIndex = 0
        val results = arrayOfNulls<RecordingDeletionAttempt>(request.targets.size)
        var promptIndices: List<Int> = emptyList()
        var launch: RecordingDeletionConsentLaunch? = null
        var completed = false
        var preparing = true
    }
    private var active: Execution? = null
    private val renameHost = RecordingRenameConsentHost()
    private val renames = linkedMapOf<String, RecordingRenameConsentWork>()
    private val playbackRoutes = mutableSetOf<RecordingPlaybackRoute>()

    @Synchronized fun watchPlayback(route: RecordingPlaybackRoute) { playbackRoutes.add(route) }
    @Synchronized fun retirePlayback(route: RecordingPlaybackRoute) { playbackRoutes.remove(route) }

    /** Linearize final notification admission with queue insertion and proof revocation. */
    @Synchronized fun takePlaybackIfReady(route: RecordingPlaybackRoute, foreground: Boolean): RecordingPlaybackRequest? {
        val request = route.request ?: return null
        if (!foreground || holdsPlayback(request)) return null
        return route.takeIfReady(true)?.also { playbackRoutes.remove(route) }
    }

    @Synchronized private fun offerDeletion(request: RecordingDeletionConsentRequest) {
        pending.offer(request)
        // Revoke even an in-flight scan on another Activity. Settlement requests a new scan.
        playbackRoutes.forEach { it.invalidateForDeletion(request.targets) }
        signal()
    }

    @Synchronized fun attachRenameHost(owner: Any) { renameHost.attach(owner) }
    @Synchronized fun renameLifetime(): Long = renameHost.generation

    @Synchronized fun detachRenameHost(owner: Any) {
        if (!renameHost.detach(owner)) return
        val iterator = renames.iterator()
        while (iterator.hasNext()) {
            val (token, work) = iterator.next()
            if (!work.granted) {
                work.cancelled = true
                work.result.completeExceptionally(RecordingRenameDefiniteFailure("Rename permission request was cancelled",
                    SecurityException("Rename permission request was cancelled", work.proof)))
                if (pending.removeQueued(token) != null) iterator.remove()
            }
        }
        active?.takeIf { it.request.rename?.cancelled == true && !it.preparing }?.let { execution ->
            scope.launch { completeRename(execution) }
        }
        signal()
    }

    suspend fun rename(proof: RecordingRenameConsentRequired, lifetime: Long,
        retry: suspend () -> RecordingEntity?): RecordingEntity? {
        val work = RecordingRenameConsentWork(proof, retry)
        synchronized(this) {
            if (!renameHost.accepts(lifetime)) {
                throw RecordingRenameDefiniteFailure("Rename permission request was cancelled",
                    SecurityException("Rename permission request was cancelled", proof))
            }
            val request = RecordingDeletionConsentRequest(emptyList(), rename = work)
            renames[request.token] = work
            pending.offer(request)
            signal()
        }
        return awaitCommittedRenameConsent(work.result)
    }

    @Synchronized private fun signal() { changed.value += 1 }
    @Synchronized private fun signalCompletion() { completed.value += 1 }

    /** Classify each selected identity independently. This step has no journal or delete authority. */
    suspend fun planDeletion(context: Context, recordings: List<RecordingEntity>): RecordingDeletionPlan =
        withContext(Dispatchers.IO) {
            val mayNeedPrompt = try {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !hasAllFilesAccess() &&
                    (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || !MediaStore.canManageMedia(context))
            } catch (_: RuntimeException) {
                false
            }
            if (!mayNeedPrompt) return@withContext RecordingDeletionPlan(recordings, emptyList())
            val undo = mutableListOf<RecordingEntity>()
            val prompt = mutableListOf<RecordingDeletionConsentTarget>()
            for (recording in recordings) {
                val target = try {
                    if (requiresMediaWriteConsent(context, recording))
                        RecordingRepository.snapshotDeletionConsentTarget(context, recording) else null
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null // Uncertain access or proof cannot skip Undo.
                }
                if (target == null) undo += recording else prompt += target
            }
            RecordingDeletionPlan(undo, prompt)
        }

    fun enqueueImmediate(targets: List<RecordingDeletionConsentTarget>): Deferred<Unit> {
        require(targets.isNotEmpty())
        val completion = CompletableDeferred<Unit>()
        offerDeletion(RecordingDeletionConsentRequest(targets.toList(), promptCompletion = completion))
        return completion
    }

    fun enqueue(targets: List<RecordingDeletionConsentTarget>) {
        if (targets.isEmpty()) return
        offerDeletion(RecordingDeletionConsentRequest(targets.toList()))
    }

    /** Called by a resumed host. Preparation owns no Activity and can finish while it is detached. */
    @Synchronized fun claim(context: Context): RecordingDeletionConsentLaunch? {
        active?.takeIf { it.request.rename?.cancelled == true }?.let { execution ->
            if (execution.preparing) return null
            completeRename(execution)
        }
        pending.claimPrompt()?.let { token ->
            return checkNotNull(active?.launch).also { check(it.token == token) }
        }
        var request = pending.claim() ?: return null
        while (request.rename?.cancelled == true) {
            pending.complete(request.token)
            renames.remove(request.token)
            request = pending.claim() ?: return null
        }
        val appContext = context.applicationContext
        val execution = Execution(request, if (request.rename == null)
            RecordingRepository.consentDeletionJournalBatch(appContext) else null)
        active = execution
        scope.launch {
            try { runExecution(appContext, execution) { prepare(appContext, execution) } }
            finally {
                val cancelled = synchronized(this@RecordingDeletionConsent) {
                    execution.preparing = false
                    execution.request.rename?.cancelled == true
                }
                if (cancelled) completeRename(execution)
            }
        }
        return null
    }

    /** Includes queued and active groups until their terminal journal/feedback settlement. */
    @Synchronized fun holdsPlayback(request: RecordingPlaybackRequest): Boolean = pending.anyRequest { group ->
        group.targets.any { request.matches(it.recording) }
    }

    fun owns(token: String): Boolean = pending.contains(token)

    @Synchronized fun finish(context: Context, token: String, granted: Boolean, launchFailed: Boolean = false,
        launchFailure: Throwable? = null) {
        val request = pending.finish(token) ?: return
        val execution = checkNotNull(active).also { check(it.request === request) }
        val appContext = context.applicationContext
        request.rename?.let { work ->
            work.granted = granted && !work.cancelled && !launchFailed
            scope.launch {
                try {
                    if (work.granted) work.result.complete(work.retry())
                    else work.result.completeExceptionally(RecordingRenameDefiniteFailure(
                        "Write permission was not granted for this rename", launchFailure ?: SecurityException(
                            "Write permission was not granted for this rename", work.proof)))
                } catch (error: Exception) {
                    work.result.completeExceptionally(error)
                } finally { completeRename(execution) }
            }
            return
        }
        scope.launch {
            runExecution(appContext, execution) {
                if (granted) {
                    // Hold the active batch until every approved member settles. A newly queued
                    // deletion cannot create another PendingIntent during this asynchronous retry.
                    for (index in execution.promptIndices) {
                        execution.results[index] = attempt(appContext) {
                            RecordingRepository.deleteAfterConsent(appContext, request.targets[index], checkNotNull(execution.journal))
                        }
                    }
                    check(pending.continueBatch(request.token))
                    prepare(appContext, execution)
                } else {
                    if (launchFailed) for (index in execution.promptIndices) {
                        execution.results[index] = RecordingDeletionAttempt.Failed(RecordingDeletionFailure(
                            RecordingDeletionFailureReason.CONSENT_UNAVAILABLE, launchFailure))
                    }
                    // Denial cancels this logical batch, including unprompted later chunks/items.
                    complete(appContext, execution)
                }
            }
        }
    }

    private suspend fun runExecution(context: Context, execution: Execution, block: suspend () -> Unit) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            execution.request.rename?.result?.completeExceptionally(cancelled)
            complete(context, execution)
            throw cancelled
        } catch (error: Exception) {
            execution.request.rename?.let {
                it.result.completeExceptionally(error)
                completeRename(execution)
                return
            }
            Log.w("RecordingDeletion", "Consent batch could not finish", error)
            RecordingIncidentStore.recordUnexpectedErrorInBackground(context, "Recording consent batch", error)
            for (index in execution.results.indices) if (execution.results[index] == null) {
                execution.results[index] = RecordingDeletionAttempt.Failed(
                    RecordingDeletionFailure(RecordingDeletionFailureReason.UNEXPECTED, error))
            }
            complete(context, execution)
        }
    }

    private suspend fun prepare(context: Context, execution: Execution) {
        val request = execution.request
        request.rename?.let { work ->
            RecordingRepository.validateRenameConsent(context, work.proof)
            synchronized(this) {
                check(active === execution && !work.cancelled) { "Rename permission request was cancelled" }
            }
            val sender = checkNotNull(pending.createRequest(request.token) {
                prepareRecordingRenameConsentSender {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        MediaStore.createWriteRequest(context.contentResolver,
                            listOf(work.proof.recording.id.toUri())).intentSender
                    } else checkNotNull(work.proof.legacySender)
                }
            })
            synchronized(this) {
                check(active === execution && !work.cancelled) { "Rename permission request was cancelled" }
                val launch = RecordingDeletionConsentLaunch(UUID.randomUUID().toString(), sender, emptyList())
                execution.launch = launch
                check(pending.publishPrompt(request.token, launch.token))
                signal()
            }
            return
        }
        val limit = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) RECORDING_CONSENT_URI_LIMIT else 1
        while (execution.nextIndex < request.targets.size) {
            val chunk = prepareRecordingConsentChunk(request.targets, execution.nextIndex, limit) { index, target ->
                when (val result = attempt(context) {
                    if (request.promptCompletion != null) {
                        // A grant acquired while queued must never turn pre-prompt verification into a delete.
                        val verified = RecordingRepository.snapshotDeletionConsentTarget(context, target.recording, target)
                        if (verified != null) RecordingDeletionAttempt.NeedsConsent(verified)
                        else RecordingDeletionAttempt.Failed(RecordingDeletionFailure(RecordingDeletionFailureReason.IDENTITY_UNVERIFIED))
                    } else RecordingRepository.prepareDeletionConsent(context, target, checkNotNull(execution.journal))
                }) {
                    is RecordingDeletionAttempt.NeedsConsent -> result.target
                    else -> { execution.results[index] = result; null }
                }
            }
            execution.nextIndex = chunk.nextIndex
            if (chunk.targets.isEmpty()) continue
            try {
                val sender = checkNotNull(pending.createRequest(request.token) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        MediaStore.createWriteRequest(context.contentResolver,
                            chunk.targets.map { it.value.recording.id.toUri() }).intentSender
                    } else {
                        // Android10 keys these actions by URI. Prepare one exact item immediately
                        // before launch; never call the unsupported API30 grouped factory here.
                        checkNotNull(chunk.targets.single().value.legacySender)
                    }
                })
                val launch = RecordingDeletionConsentLaunch(UUID.randomUUID().toString(), sender,
                    chunk.targets.map { it.value })
                synchronized(this) {
                    check(active === execution)
                    execution.promptIndices = chunk.targets.map { it.index }
                    execution.launch = launch
                    check(pending.publishPrompt(request.token, launch.token))
                    signal()
                }
                return
            } catch (cancelled: CancellationException) {
                complete(context, execution)
                throw cancelled
            } catch (error: Exception) {
                Log.w("RecordingDeletion", "Unable to prepare media access request", error)
                for (target in chunk.targets) execution.results[target.index] = RecordingDeletionAttempt.Failed(
                    RecordingDeletionFailure(RecordingDeletionFailureReason.CONSENT_UNAVAILABLE, error))
                check(pending.continueBatch(request.token))
            }
        }
        complete(context, execution)
    }

    private suspend fun attempt(context: Context, block: suspend () -> RecordingDeletionAttempt): RecordingDeletionAttempt = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Log.w("RecordingDeletion", "Recording deletion retry failed", error)
        if (error is android.database.sqlite.SQLiteDatabaseCorruptException) {
            RecordingIncidentStore.recordUnexpectedErrorInBackground(context, "Recording deletion retry", error)
        }
        RecordingDeletionAttempt.Failed(RecordingDeletionFailure(when (error) {
            is SecurityException -> RecordingDeletionFailureReason.PERMISSION_REQUIRED
            is IOException -> RecordingDeletionFailureReason.STORAGE_UNAVAILABLE
            else -> RecordingDeletionFailureReason.UNEXPECTED
        }, error))
    }

    private fun completeRename(execution: Execution) {
        synchronized(this) {
            if (active !== execution || execution.completed) return
            execution.completed = true
            val work = checkNotNull(execution.request.rename)
            if (!work.result.isCompleted) work.result.completeExceptionally(
                SecurityException("Rename permission request did not complete", work.proof))
            renames.remove(execution.request.token)
            pending.complete(execution.request.token)
            active = null
            signal()
        }
    }

    private suspend fun complete(context: Context, execution: Execution) {
        if (execution.request.rename != null) { completeRename(execution); return }
        synchronized(this) {
            if (active !== execution || execution.completed) return
            execution.completed = true
        }
        try {
            RecordingRepository.flushConsentDeletionJournal(checkNotNull(execution.journal))
            val settled = execution.results.indices.filter { execution.results[it] != null }
            val failures = settled.withIndex().mapNotNull { (index, originalIndex) ->
                (execution.results[originalIndex] as? RecordingDeletionAttempt.Failed)?.let { index to it.failure }
            }.toMap()
            val result = checkNotNull(execution.journal).result(
                settled.map { execution.results[it] == RecordingDeletionAttempt.Deleted }).copy(failures = failures)
            if (failures.isNotEmpty() || result.cleanupFailed) {
                AppFeedbackCenter.post(recordingDeletionBatchFailureMessage(context,
                    settled.map { execution.request.targets[it].recording }, result), FeedbackTone.ERROR)
            }
        } finally {
            synchronized(this) {
                if (active === execution && pending.complete(execution.request.token) != null) {
                    active = null
                    signalCompletion()
                    signal()
                    execution.request.promptCompletion?.complete(Unit)
                }
            }
        }
    }
}

/** Called on IO. A denied explicit URI grant alone does not exclude the row owner's implicit access. */
internal fun requiresMediaWriteConsent(context: Context, recording: RecordingEntity): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || recording.storageType != RecordingStorageType.MEDIASTORE ||
        !mediaStoreRecordingIdIsValid(recording.id)) return false
    val uri = recording.id.toUri()
    val pid = Process.myPid()
    val uid = Process.myUid()
    val write = Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    if (context.checkUriPermission(uri, pid, uid, write) == PackageManager.PERMISSION_GRANTED) return false
    if (Build.VERSION.SDK_INT >= 35 &&
        context.checkContentUriPermissionFull(uri, pid, uid, write) != PackageManager.PERMISSION_DENIED) return false
    val column = MediaStore.MediaColumns.OWNER_PACKAGE_NAME
    return context.contentResolver.query(uri, arrayOf(column), null, null, null)?.use { row ->
        if (!row.moveToFirst()) return@use false
        val owner = row.getString(row.getColumnIndexOrThrow(column))
        if (row.moveToNext() || owner == context.packageName) return@use false
        // Before API 35 an explicit-grant denial alone cannot establish lack of implicit write access.
        Build.VERSION.SDK_INT >= 35 || !owner.isNullOrBlank()
    } == true
}
