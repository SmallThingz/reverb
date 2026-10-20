package app.smallthingz.reverb

import java.util.concurrent.atomic.AtomicLongArray

private val quickSaveTileObservationIdPattern = Regex("[0-9a-f]{32}")

/** Observations, not a public query of SystemUI's current tile list. */
internal enum class QuickSaveTileAddedState { ADDED, NOT_ADDED, UNKNOWN }

internal data class QuickSaveTileAddedObservation(
    val revision: String,
    val lifetimeId: String,
    val state: QuickSaveTileAddedState,
) {
    init {
        require(revision.matches(quickSaveTileObservationIdPattern))
        require(lifetimeId.matches(quickSaveTileObservationIdPattern))
    }
}

internal fun quickSaveTileAddedState(
    option: QuickSaveTileOption,
    observations: Map<Int, QuickSaveTileAddedObservation>,
): QuickSaveTileAddedState = observations[option.slot]
    ?.takeIf { it.revision == option.revision }?.state ?: QuickSaveTileAddedState.UNKNOWN

internal data class QuickSaveTileAddedHistory(
    val observations: Map<Int, QuickSaveTileAddedObservation>,
    val usedSlots: Set<Int>,
) {
    init {
        require(usedSlots.all { it in 0 until QUICK_SAVE_TILE_COUNT })
        require(usedSlots.containsAll(observations.keys))
    }
}

/** An older install may already have deleted definitions; absent metadata cannot prove virgin slots. */
internal fun missingQuickSaveTileAddedHistory(definitionsPreviouslyStored: Boolean): QuickSaveTileAddedHistory =
    QuickSaveTileAddedHistory(emptyMap(), if (definitionsPreviouslyStored) (0 until QUICK_SAVE_TILE_COUNT).toSet() else emptySet())

internal fun decodeQuickSaveTileAddedStates(raw: String): QuickSaveTileAddedHistory {
    require(raw.length <= 1024) { "Quick-save tile observations exceed their limit" }
    val lines = raw.split('\n')
    require(lines.first() == "1" && lines.size <= QUICK_SAVE_TILE_COUNT + 1)
    val result = linkedMapOf<Int, QuickSaveTileAddedObservation>()
    val usedSlots = linkedSetOf<Int>()
    for (line in lines.drop(1)) {
        val fields = line.split('|')
        require(fields.size == 2 || fields.size == 4)
        val slot = fields[0].toInt()
        require(slot in 0 until QUICK_SAVE_TILE_COUNT && usedSlots.add(slot))
        if (fields.size == 2) {
            require(fields[1] == "USED")
        } else {
            result[slot] = QuickSaveTileAddedObservation(fields[1], fields[2],
                QuickSaveTileAddedState.valueOf(fields[3]))
        }
    }
    require(result.values.map { it.lifetimeId }.distinct().size == result.size)
    require(result.values.map { it.revision }.distinct().size == result.size)
    return QuickSaveTileAddedHistory(result, usedSlots)
}

internal fun encodeQuickSaveTileAddedStates(history: QuickSaveTileAddedHistory): String {
    val raw = (listOf("1") + history.usedSlots.sorted().map { slot ->
        history.observations[slot]?.let { value ->
            "$slot|${value.revision}|${value.lifetimeId}|${value.state.name}"
        } ?: "$slot|USED"
    }).joinToString("\n")
    decodeQuickSaveTileAddedStates(raw)
    return raw
}

/** Legacy definitions and mismatched metadata are unknown, never inferred absent. */
internal fun reconcileQuickSaveTileAddedStates(
    options: List<QuickSaveTileOption>,
    observations: Map<Int, QuickSaveTileAddedObservation>,
    continuity: Map<Int, QuickSaveTileAddedObservation> = emptyMap(),
): Map<Int, QuickSaveTileAddedObservation> = options.associate { option ->
    option.slot to (observations[option.slot]?.takeIf { it.revision == option.revision }
        // A matching durable definition revision proves this is still the same lifetime in
        // this process. Reuse only its identity, never the pre-failure observed status.
        ?: QuickSaveTileAddedObservation(option.revision,
            continuity[option.slot]?.takeIf { it.revision == option.revision }?.lifetimeId ?: option.revision,
            QuickSaveTileAddedState.UNKNOWN))
}

internal fun quickSaveTileAddedStatesAfterEdit(
    previous: List<QuickSaveTileOption>,
    updated: List<QuickSaveTileOption>,
    history: QuickSaveTileAddedHistory,
): QuickSaveTileAddedHistory {
    val current = reconcileQuickSaveTileAddedStates(previous, history.observations)
    val usedSlots = history.usedSlots + previous.map { it.slot }
    val observations = updated.associate { option ->
        option.slot to (current[option.slot]?.copy(revision = option.revision)
            ?: QuickSaveTileAddedObservation(option.revision, option.revision,
                if (option.slot in usedSlots) QuickSaveTileAddedState.UNKNOWN else QuickSaveTileAddedState.NOT_ADDED))
    }
    return QuickSaveTileAddedHistory(observations, usedSlots + updated.map { it.slot })
}

/** One TileService instance may observe edits, but must never rebind to a recycled slot. */
internal class QuickSaveTileObservationSession internal constructor(val slot: Int, val generation: Long) {
    internal var lifetimeId: String? = null
    internal var bound = false
}

internal data class QuickSaveTileAddRequest internal constructor(
    val option: QuickSaveTileOption,
    internal val observationGeneration: Long,
)

internal class QuickSaveTileObservationLifetimes {
    private val generations = AtomicLongArray(QUICK_SAVE_TILE_COUNT)
    private val observationGenerations = AtomicLongArray(QUICK_SAVE_TILE_COUNT)

    fun captureAddRequest(option: QuickSaveTileOption): QuickSaveTileAddRequest =
        QuickSaveTileAddRequest(option, observationGenerations.get(option.slot))

    fun addRequestIsCurrent(request: QuickSaveTileAddRequest, options: List<QuickSaveTileOption>): Boolean =
        request.observationGeneration == observationGenerations.get(request.option.slot) &&
            options.find { it.slot == request.option.slot } == request.option

    fun observationQueued(session: QuickSaveTileObservationSession) {
        if (session.generation == generations.get(session.slot)) {
            observationGenerations.incrementAndGet(session.slot)
        }
    }

    // Main-thread capture is memory-only; it must not wait behind a preference commit.
    fun capture(slot: Int): QuickSaveTileObservationSession {
        require(slot in 0 until QUICK_SAVE_TILE_COUNT)
        return QuickSaveTileObservationSession(slot, generations.get(slot))
    }

    // All remaining operations run under the definition store's lock.
    fun definitionsCommitted(previous: List<QuickSaveTileOption>, updated: List<QuickSaveTileOption>) {
        for (slot in 0 until QUICK_SAVE_TILE_COUNT) {
            if (previous.any { it.slot == slot } != updated.any { it.slot == slot }) {
                generations.incrementAndGet(slot)
            }
        }
    }

    fun bind(session: QuickSaveTileObservationSession, observations: Map<Int, QuickSaveTileAddedObservation>) {
        check(!session.bound)
        session.bound = true
        if (session.generation == generations.get(session.slot)) {
            session.lifetimeId = observations[session.slot]?.lifetimeId
        }
    }

    fun resolve(session: QuickSaveTileObservationSession, options: List<QuickSaveTileOption>,
                observations: Map<Int, QuickSaveTileAddedObservation>): QuickSaveTileOption? {
        if (!session.bound || session.lifetimeId == null || session.generation != generations.get(session.slot)) return null
        val option = options.find { it.slot == session.slot } ?: return null
        val observation = observations[session.slot] ?: return null
        return option.takeIf { observation.revision == it.revision && observation.lifetimeId == session.lifetimeId }
    }
}
