package app.smallthingz.reverb

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** Accepted saves outlive their Activity; replacement hydration must include their terminal state. */
internal class SettingsCommitBarrier {
    private data class State(val revision: Long = 0L, val acceptedSaves: Int = 0)

    private val state = MutableStateFlow(State())

    @Synchronized
    private fun changeAcceptedSaves(delta: Int) {
        val current = state.value
        val acceptedSaves = current.acceptedSaves + delta
        check(acceptedSaves >= 0) { "Settings save ownership underflow" }
        state.value = State(current.revision + 1L, acceptedSaves)
    }

    /** Enter on the UI caller before dispatching IO; terminal includes runtime convergence. */
    suspend fun <T> runAcceptedSave(block: suspend () -> T): T = withContext(NonCancellable) {
        changeAcceptedSaves(1)
        try {
            block()
        } finally {
            changeAcceptedSaves(-1)
        }
    }

    /**
     * A read may acquire the persistence lock before an accepted save's queued IO does.
     * Await settlement only after the read releases that lock, then discard a crossed revision.
     * The caller resumes on its UI dispatcher and applies the result without another suspension.
     */
    suspend fun <T> readSettled(read: suspend () -> T): T {
        while (true) {
            val before = state.value
            val result = try {
                Result.success(read())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Result.failure(error)
            }
            val settled = state.first { it.acceptedSaves == 0 }
            if (before.revision == settled.revision) return result.getOrThrow()
        }
    }
}

internal val processSettingsCommitBarrier = SettingsCommitBarrier()
