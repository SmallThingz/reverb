package app.smallthingz.reverb

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class RecordingOperationToken(
    val recordingId: String,
    val generation: Long,
)

internal class RecordingOperationRegistry {
    private val lock = Any()
    private val mutableActiveIds = MutableStateFlow<Set<String>>(emptySet())
    private val activeGenerations = LinkedHashMap<String, Long>()
    private var nextGeneration = 0L

    val activeIds: StateFlow<Set<String>> = mutableActiveIds.asStateFlow()

    fun tryBegin(recordingId: String): RecordingOperationToken? = synchronized(lock) {
        if (recordingId.isBlank() || recordingId in activeGenerations) return@synchronized null
        val generation = ++nextGeneration
        activeGenerations[recordingId] = generation
        publishLocked()
        RecordingOperationToken(recordingId, generation)
    }

    fun finish(token: RecordingOperationToken) = synchronized(lock) {
        if (activeGenerations[token.recordingId] != token.generation) return@synchronized
        activeGenerations.remove(token.recordingId)
        publishLocked()
    }

    fun isActive(recordingId: String): Boolean = synchronized(lock) {
        recordingId in activeGenerations
    }

    private fun publishLocked() {
        mutableActiveIds.value = activeGenerations.keys.toSet()
    }
}

internal val recordingMutations = RecordingOperationRegistry()
