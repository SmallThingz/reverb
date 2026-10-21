package app.smallthingz.reverb

import android.content.Context
import androidx.annotation.MainThread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext

/** The store transfers accepted mutations to its process owner before these methods return. */
internal interface IncidentHistoryAccess {
    val revisions: StateFlow<Long>
    suspend fun readHistory(): IncidentHistoryPresentation
    fun toggleAcknowledgedInBackground(incident: RecordingIncident)
    fun deleteInBackground(incident: RecordingIncident)
}

/**
 * Main-thread presentation owner for the MainScreen lifetime, including while the panel is hidden.
 * A caller owns/cancels observeHistory's coroutine; accepted mutations belong to the store instead.
 * There is no View, Compose state, renderer, navigation state or motion clock here.
 */
@MainThread
internal class IncidentHistoryController(
    private val access: IncidentHistoryAccess,
    private val waitBeforeRetry: suspend (Long) -> Unit = { delay(it) },
) {
    constructor(context: Context) : this(StoredIncidentHistoryAccess(context.applicationContext))

    var state: IncidentHistoryUiState = IncidentHistoryUiState()
        private set
    private var observing = false

    /**
     * One observer per controller. Invoke from the existing LaunchedEffect or a native root's
     * main-thread coroutine. Do not restart/gate this on panel visibility or Activity STARTED.
     */
    suspend fun observeHistory(onStateChanged: (IncidentHistoryUiState) -> Unit) {
        check(!observing) { "Incident history already has an observer" }
        observing = true
        try {
            onStateChanged(state)
            // Same immediate StateFlow load and latest-only cancellation as released MainScreen.
            access.revisions.collectLatest {
                publish(state.loadStarted(), onStateChanged)
                var retryDelayMillis = DURABLE_UI_RETRY_INITIAL_MILLIS
                while (!loadIncidents(onStateChanged)) {
                    waitBeforeRetry(retryDelayMillis)
                    retryDelayMillis = nextDurableUiRetryDelayMillis(retryDelayMillis)
                }
            }
        } finally {
            observing = false
        }
    }

    fun toggleAcknowledged(incident: RecordingIncident) {
        if (!state.canMutate) return
        access.toggleAcknowledgedInBackground(incident)
    }

    fun delete(incident: RecordingIncident) {
        if (!state.canMutate) return
        access.deleteInBackground(incident)
    }

    private suspend fun loadIncidents(onStateChanged: (IncidentHistoryUiState) -> Unit): Boolean {
        val loaded = try {
            access.readHistory()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            publish(state.loadFailed(), onStateChanged)
            return false
        }
        publish(state.loadSucceeded(loaded), onStateChanged)
        return true
    }

    private fun publish(next: IncidentHistoryUiState, onStateChanged: (IncidentHistoryUiState) -> Unit) {
        // Matches mutableStateOf's released structural-equality policy for repeated failures.
        if (state == next) return
        state = next
        onStateChanged(next)
    }
}

private class StoredIncidentHistoryAccess(private val appContext: Context) : IncidentHistoryAccess {
    override val revisions: StateFlow<Long>
        get() = RecordingIncidentStore.historyRevision

    override suspend fun readHistory(): IncidentHistoryPresentation = withContext(Dispatchers.IO) {
        prepareIncidentHistory(RecordingIncidentStore.readIncidents(appContext))
    }

    override fun toggleAcknowledgedInBackground(incident: RecordingIncident) {
        RecordingIncidentStore.toggleIncidentAcknowledgedInBackground(appContext, incident)
    }

    override fun deleteInBackground(incident: RecordingIncident) {
        RecordingIncidentStore.deleteIncidentInBackground(appContext, incident)
    }
}
