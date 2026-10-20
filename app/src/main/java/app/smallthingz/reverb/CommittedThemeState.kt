package app.smallthingz.reverb

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class CommittedTheme(val revision: Long, val mode: AppThemeMode)

/** The pre-write baseline and completed Settings transactions publish under the retention lock. */
internal class CommittedThemeState {
    private val mutableState = MutableStateFlow<CommittedTheme?>(null)
    val state = mutableState.asStateFlow()

    /** Capture before the first attempted preference commit can expose its in-memory value. */
    @Synchronized
    fun initializeBaseline(mode: AppThemeMode) {
        if (mutableState.value == null) mutableState.value = CommittedTheme(1L, mode)
    }

    @Synchronized
    fun publishCommitted(mode: AppThemeMode) {
        mutableState.value = CommittedTheme((mutableState.value?.revision ?: 0L) + 1L, mode)
    }
}

internal val processCommittedTheme = CommittedThemeState()

/** Main-thread Activity ownership: durable replay and an optional live Settings preview. */
internal class ThemePresentationOwner(initialMode: AppThemeMode) {
    private var committedMode = initialMode
    private var committedRevision = 0L
    private var previewMode: AppThemeMode? = null
    private var closed = false

    val mode: AppThemeMode get() = previewMode ?: committedMode

    fun observeCommitted(value: CommittedTheme?) {
        if (closed || value == null || value.revision <= committedRevision) return
        committedRevision = value.revision
        committedMode = value.mode
    }

    fun preview(mode: AppThemeMode?, latestCommit: CommittedTheme?) {
        if (closed) return
        // Release directly against the latest publication, without waiting for flow dispatch.
        observeCommitted(latestCommit)
        previewMode = mode
    }

    fun close() {
        closed = true
    }
}
