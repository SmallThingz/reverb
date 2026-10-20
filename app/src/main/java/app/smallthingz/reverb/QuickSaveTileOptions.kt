package app.smallthingz.reverb

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal const val QUICK_SAVE_TILE_COUNT = 8
private val quickSaveTileRevisionPattern = Regex("[0-9a-f]{32}")

internal enum class QuickSaveUnit(val seconds: Int, val symbol: String, val labelRes: Int) {
    SECONDS(1, "s", R.string.tile_seconds),
    MINUTES(60, "m", R.string.tile_minutes),
    HOURS(3600, "h", R.string.tile_hours),
}

internal fun quickSaveUnitLabel(context: Context, unit: QuickSaveUnit, amount: Int): String =
    context.getString(if (amount != 1) unit.labelRes else when (unit) {
        QuickSaveUnit.SECONDS -> R.string.tile_second
        QuickSaveUnit.MINUTES -> R.string.tile_minute
        QuickSaveUnit.HOURS -> R.string.tile_hour
    })

internal fun quickSaveTileSeconds(amount: Int, unit: QuickSaveUnit): Int? =
    (amount.toLong() * unit.seconds).takeIf { amount in 1..999 && it in 1L..86_400L }?.toInt()

internal data class QuickSaveTileOption(
    val slot: Int,
    val revision: String,
    val buffer: ReverbService.BufferSlot = ReverbService.BufferSlot.LOOPING,
    val amount: Int = 2,
    val unit: QuickSaveUnit = QuickSaveUnit.MINUTES,
) {
    init {
        require(slot in 0 until QUICK_SAVE_TILE_COUNT)
        require(revision.matches(quickSaveTileRevisionPattern))
        require(quickSaveTileSeconds(amount, unit) != null)
    }
    val seconds: Int get() = requireNotNull(quickSaveTileSeconds(amount, unit))
    val iconText: String get() = amount.toString()
}

/** A small, strict wire format. A corrupt saved action is never reinterpreted as a default save. */
internal fun decodeQuickSaveTileOptions(raw: String?): List<QuickSaveTileOption> {
    if (raw == null) return emptyList()
    require(raw.length <= 1024) { "Quick-save tile settings exceed their limit" }
    val lines = raw.split('\n')
    require(lines.first() == "1" && lines.size <= QUICK_SAVE_TILE_COUNT + 1)
    val result = lines.drop(1).map { line ->
        val fields = line.split('|')
        require(fields.size == 5)
        QuickSaveTileOption(
            fields[0].toInt(), fields[1],
            requireNotNull(ReverbService.BufferSlot.fromStorageCode(fields[2].toInt())),
            fields[3].toInt(), QuickSaveUnit.valueOf(fields[4]),
        )
    }
    require(result.map { it.slot }.distinct().size == result.size)
    require(result.map { it.revision }.distinct().size == result.size)
    return result.sortedBy { it.slot }
}

internal fun encodeQuickSaveTileOptions(options: List<QuickSaveTileOption>): String {
    val result = (listOf("1") + options.sortedBy { it.slot }.map {
        "${it.slot}|${it.revision}|${it.buffer.storageCode}|${it.amount}|${it.unit.name}"
    }).joinToString("\n")
    decodeQuickSaveTileOptions(result)
    return result
}

internal fun quickSaveTileClass(slot: Int): Class<out QuickSaveTileService> = when (slot) {
    0 -> LoopingQuickSaveTileService::class.java
    1 -> OneShotQuickSaveTileService::class.java
    2 -> QuickSaveTile3::class.java
    3 -> QuickSaveTile4::class.java
    4 -> QuickSaveTile5::class.java
    5 -> QuickSaveTile6::class.java
    6 -> QuickSaveTile7::class.java
    7 -> QuickSaveTile8::class.java
    else -> throw IllegalArgumentException("Invalid quick-save tile slot")
}

internal object QuickSaveTileOptions {
    private val lock = Any()
    private val worker = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableOptions = MutableStateFlow<List<QuickSaveTileOption>?>(null)
    private val mutableError = MutableStateFlow<String?>(null)
    private val mutableAddedStates = MutableStateFlow<Map<Int, QuickSaveTileAddedObservation>>(emptyMap())
    private val mutableAddedStateError = MutableStateFlow<String?>(null)
    private val observationLifetimes = QuickSaveTileObservationLifetimes()
    // System callbacks are infrequent and ordered. Accepted persistence outlives TileService destruction.
    private val observationQueue = Channel<() -> Unit>(Channel.UNLIMITED)
    private var observationsUnreliable = false
    // Bounded current-definition identity only; recovery never trusts its old added status.
    private var observationContinuity = emptyMap<Int, QuickSaveTileAddedObservation>()
    val options = mutableOptions.asStateFlow()
    val error = mutableError.asStateFlow()
    val addedStates = mutableAddedStates.asStateFlow()
    val addedStateError = mutableAddedStateError.asStateFlow()

    init {
        worker.launch { for (observation in observationQueue) observation() }
    }

    /** Await accepted writes without binding a tile or blocking its main-thread lifecycle. */
    suspend fun awaitPendingObservations() {
        val completed = CompletableDeferred<Unit>()
        observationQueue.trySend { completed.complete(Unit); Unit }.getOrThrow()
        completed.await()
    }

    private fun enqueueObservation(context: Context, observation: (Context) -> Unit) {
        val app = context.applicationContext
        observationQueue.trySend {
            synchronized(lock) {
                try { observation(app) }
                catch (error: Exception) { observationFailed(app, error) }
            }
        }.getOrThrow()
    }

    private fun observationFailed(context: Context, error: Exception) {
        val firstFailure = !observationsUnreliable
        observationsUnreliable = true
        mutableAddedStates.value = emptyMap()
        mutableAddedStateError.value = context.getString(R.string.tile_state_unavailable)
        if (firstFailure && error !is IOException) {
            RecordingIncidentStore.recordUnexpectedErrorInBackground(context, "Quick-save tile observation", error)
        }
    }

    private fun readHistory(context: Context, current: List<QuickSaveTileOption>): QuickSaveTileAddedHistory {
        val history = try {
            val prefs = getRecorderPreferences(context)
            val raw = requireDurablePreference<String?>(prefs.contains(PrefKey.QUICK_SAVE_TILE_ADDED_STATES), null,
                PrefKey.QUICK_SAVE_TILE_ADDED_STATES.name) {
                requireNotNull(prefs.getString(PrefKey.QUICK_SAVE_TILE_ADDED_STATES, null))
            }
            if (raw == null) missingQuickSaveTileAddedHistory(prefs.contains(PrefKey.QUICK_SAVE_TILE_OPTIONS))
            else decodeQuickSaveTileAddedStates(raw)
        } catch (error: Exception) {
            observationFailed(context, error)
            missingQuickSaveTileAddedHistory(definitionsPreviouslyStored = true)
        }
        val reconciled = reconcileQuickSaveTileAddedStates(current, history.observations, observationContinuity)
        observationContinuity = reconciled
        val observations = if (observationsUnreliable) reconciled.mapValues { (_, value) ->
            value.copy(state = QuickSaveTileAddedState.UNKNOWN)
        } else reconciled
        return QuickSaveTileAddedHistory(observations, history.usedSlots + current.map { it.slot })
    }

    private fun publishObservations(history: QuickSaveTileAddedHistory) {
        observationContinuity = history.observations
        observationsUnreliable = false
        mutableAddedStateError.value = null
        mutableAddedStates.value = history.observations
    }

    fun beginObservation(context: Context, slot: Int): QuickSaveTileObservationSession {
        val session = observationLifetimes.capture(slot)
        enqueueObservation(context) { app ->
            observationLifetimes.bind(session, readHistory(app, read(app)).observations)
        }
        return session
    }

    fun recordObservation(context: Context, session: QuickSaveTileObservationSession, state: QuickSaveTileAddedState) {
        require(state != QuickSaveTileAddedState.UNKNOWN)
        observationLifetimes.observationQueued(session)
        enqueueObservation(context) { app ->
            val current = read(app)
            val history = readHistory(app, current)
            val option = observationLifetimes.resolve(session, current, history.observations) ?: return@enqueueObservation
            persistObservation(app, option, history, state)
        }
    }

    fun beginAddRequest(option: QuickSaveTileOption): QuickSaveTileAddRequest = observationLifetimes.captureAddRequest(option)

    /** A declined request does not prove absence; a concurrent SystemUI add may have succeeded. */
    fun recordAddRequestResult(context: Context, request: QuickSaveTileAddRequest, added: Boolean) {
        if (!added) return
        enqueueObservation(context) { app ->
            val current = read(app)
            if (!observationLifetimes.addRequestIsCurrent(request, current)) return@enqueueObservation
            persistObservation(app, request.option, readHistory(app, current), QuickSaveTileAddedState.ADDED)
        }
    }

    private fun persistObservation(context: Context, option: QuickSaveTileOption,
                                   history: QuickSaveTileAddedHistory, state: QuickSaveTileAddedState) {
        val observations = history.observations
        val current = requireNotNull(observations[option.slot])
        if (!observationsUnreliable && current.state == state) {
            mutableAddedStates.value = observations
            return
        }
        val updated = history.copy(observations = observations + (option.slot to current.copy(state = state)))
        val committed = commitQuickSaveTilePreferences(getRecorderPreferences(context), null, updated) {
            publishObservations(updated)
        }
        if (!committed) throw IOException(context.getString(R.string.tile_state_unavailable))
    }

    private fun read(context: Context): List<QuickSaveTileOption> {
        val prefs = getRecorderPreferences(context)
        val raw = requireDurablePreference<String?>(prefs.contains(PrefKey.QUICK_SAVE_TILE_OPTIONS), null,
            PrefKey.QUICK_SAVE_TILE_OPTIONS.name) { requireNotNull(prefs.getString(PrefKey.QUICK_SAVE_TILE_OPTIONS, null)) }
        return decodeQuickSaveTileOptions(raw)
    }

    fun refresh(context: Context) {
        val app = context.applicationContext
        worker.launch {
            try { load(app) }
            catch (_: Exception) { /* load publishes the failure while holding the state lock. */ }
        }
    }

    fun load(context: Context): List<QuickSaveTileOption> = synchronized(lock) {
        try {
            val current = read(context)
            val history = readHistory(context, current)
            if (observationsUnreliable) {
                // Retry cannot resurrect a known state from before a failed observation write.
                // Repair durably as UNKNOWN, then await a new system observation for authority.
                try {
                    val committed = commitQuickSaveTilePreferences(getRecorderPreferences(context), null, history) {
                        publishObservations(history)
                    }
                    if (!committed) throw IOException(context.getString(R.string.tile_state_unavailable))
                } catch (error: Exception) { observationFailed(context, error) }
            } else mutableAddedStates.value = history.observations
            synchronizeComponents(context, current)
            mutableError.value = null
            mutableOptions.value = current
            current
        } catch (error: Exception) {
            mutableError.value = error.message ?: context.getString(R.string.tile_save_failed)
            throw error
        }
    }

    /** Creation only fills a free slot; existing definitions are never edited in place. */
    fun create(context: Context, buffer: ReverbService.BufferSlot,
             amount: Int, unit: QuickSaveUnit): QuickSaveTileOption = synchronized(lock) {
        val current = read(context)
        val slot = (0 until QUICK_SAVE_TILE_COUNT).firstOrNull { id -> current.none { it.slot == id } }
            ?: throw IOException(context.getString(R.string.tile_limit))
        val next = QuickSaveTileOption(slot, UUID.randomUUID().toString().replace("-", ""), buffer, amount, unit)
        persist(context, current, current + next)
        next
    }

    fun remove(context: Context, expected: QuickSaveTileOption) = synchronized(lock) {
        val current = read(context)
        if (current.find { it.slot == expected.slot } != expected) throw IOException(context.getString(R.string.tile_changed))
        persist(context, current, current.filterNot { it.slot == expected.slot })
    }

    private fun persist(context: Context, current: List<QuickSaveTileOption>, updated: List<QuickSaveTileOption>) {
        val prefs = getRecorderPreferences(context)
        val observations = quickSaveTileAddedStatesAfterEdit(current, updated, readHistory(context, current))
        val committed = commitQuickSaveTilePreferences(prefs, updated, observations) {
            // Publication is durable before any component is exposed. Startup retries a partial PM update.
            observationLifetimes.definitionsCommitted(current, updated)
            publishObservations(observations)
            mutableOptions.value = updated.sortedBy { it.slot }
        }
        if (!committed) throw IOException(context.getString(R.string.tile_save_failed))
        try {
            synchronizeComponents(context, updated)
            mutableError.value = null
        } catch (error: Exception) {
            mutableError.value = context.getString(R.string.tile_sync_failed)
            // The definition is already saved. Retry reconciliation, never create a duplicate definition.
        }
    }

    private fun synchronizeComponents(context: Context, options: List<QuickSaveTileOption>) {
        for (slot in 0 until QUICK_SAVE_TILE_COUNT) {
            val component = ComponentName(context, quickSaveTileClass(slot))
            val enabled = options.any { it.slot == slot }
            val desired = if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            val observed = context.packageManager.getComponentEnabledSetting(component)
            if (observed != desired && !(observed == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && !enabled)) {
                context.packageManager.setComponentEnabledSetting(component, desired, PackageManager.DONT_KILL_APP)
            }
        }
    }

}
