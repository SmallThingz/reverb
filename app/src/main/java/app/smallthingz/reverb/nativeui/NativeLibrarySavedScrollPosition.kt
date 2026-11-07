package app.smallthingz.reverb.nativeui

/** Separate rememberSaveable slots of FilesScreen's populated and empty branches. */
internal data class NativeLibrarySavedScrollPosition(
    val listPosition: NativeListSavedPosition? = null,
    val emptyScrollPosition: Int? = null,
    // Reveal-only UI intent; never a playback command or a replacement for the numeric saver.
    val pendingRecordingRevealId: String? = null,
    val cancelledPlaybackRevealToken: String? = null,
)

/**
 * Unconsumed restoration survives loading and repeated saves. Once a branch enters, its actual
 * state owns that slot; removing the branch disposes it instead of caching it for a later visit.
 */
internal class NativeLibraryScrollRestoration(restored: NativeLibrarySavedScrollPosition?) {
    private var list = restored?.listPosition
    private var empty = restored?.emptyScrollPosition
    private var reveal = restored?.pendingRecordingRevealId
    private var cancelledReveal = restored?.cancelledPlaybackRevealToken

    fun consumeList(): NativeListSavedPosition? = list.also { list = null }
    fun consumeEmpty(): Int? = empty.also { empty = null }
    fun consumeReveal(): String? = reveal.also { reveal = null }
    fun consumeCancelledReveal(): String? = cancelledReveal.also { cancelledReveal = null }

    fun openedFromHome() { list = null; empty = null; reveal = null; cancelledReveal = null }

    fun save(
        currentList: NativeListSavedPosition?,
        currentEmpty: Int?,
        currentReveal: String? = null,
        currentCancelledRevealToken: String? = null,
    ) = NativeLibrarySavedScrollPosition(currentList ?: list, currentEmpty ?: empty, currentReveal ?: reveal,
        currentCancelledRevealToken ?: cancelledReveal)
}

/** Visual completion authority only; transport remains owned by the playback request controller. */
internal class NativeRecordingRevealRequest(
    restoredId: String? = null,
    restoredCancelledToken: String? = null,
) {
    private var generation = 0L
    private var requestGeneration: Long? = null
    private var requestToken: String? = null
    var pendingRecordingId: String? = restoredId
        private set
    var cancelledRequestToken: String? = restoredCancelledToken
        private set

    fun isPending(token: String): Boolean = requestToken == token
    fun begin(token: String): Long {
        if (isPending(token)) return checkNotNull(requestGeneration)
        generation++
        pendingRecordingId = null
        if (cancelledRequestToken != token) cancelledRequestToken = null
        requestToken = token
        requestGeneration = generation
        return generation
    }
    fun cancel() {
        generation++
        pendingRecordingId = null
        requestToken?.let { cancelledRequestToken = it }
    }
    fun clearPending() { pendingRecordingId = null }

    fun complete(completedGeneration: Long, recordingId: String?): Boolean {
        if (completedGeneration != requestGeneration) return false
        val accepted = completedGeneration == generation && requestToken != cancelledRequestToken
        requestToken = null
        requestGeneration = null
        cancelledRequestToken = null
        generation++
        if (accepted) pendingRecordingId = recordingId
        return accepted
    }
}
