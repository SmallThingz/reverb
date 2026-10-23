package app.smallthingz.reverb

import smallthingz.ui.runtime.getValue
import smallthingz.ui.runtime.mutableStateOf
import smallthingz.ui.runtime.setValue

/** The parent acknowledges only this synchronous edit, including any canonicalized duration. */
internal data class NotificationDurationCommit(val seconds: Int, val owner: Long)

internal fun notificationDurationStepSeconds(seconds: Int, maximum: Int, forward: Boolean): Int =
    (seconds.coerceIn(1, 86_400) + if (forward) 1 else -1)
        .coerceIn(1, maximum.coerceIn(1, 86_400))

/** Save admission shared by the wheel and its containing Settings/tile Save action. */
internal class NotificationDurationInteractionState {
    private val owners = mutableSetOf<Any>()
    var pending by mutableStateOf(false)
        private set

    fun begin(): Any = Any().also { owner ->
        owners += owner
        pending = true
    }

    fun end(owner: Any) {
        if (owners.remove(owner)) pending = owners.isNotEmpty()
    }
}

/** A pending native settle cannot overwrite a newer Settings edit, rollback, or save. */
internal class NotificationDurationBinding(
    private val interactionState: NotificationDurationInteractionState = NotificationDurationInteractionState(),
) {
    var seconds = 1
        private set
    private var maximum = 1
    private var enabled = false
    private var renderedOwner: Long? = null
    private var revision = 0L
    private var interactionRevision: Long? = null
    private var ownerRevision: Long? = null
    private var pendingSeconds: Int? = null
    private var interactionToken: Any? = null

    fun update(seconds: Int, maximum: Int, enabled: Boolean, owner: Long) {
        val value = seconds.coerceIn(1, 86_400)
        val limit = maximum.coerceIn(1, 86_400)
        if (this.seconds != value || this.maximum != limit || this.enabled != enabled || renderedOwner != owner) {
            revision++
        }
        this.seconds = value
        this.maximum = limit
        this.enabled = enabled
        renderedOwner = owner
    }

    fun beginInteraction(owner: Long?) {
        // The native View will move even when its eventual draft commit is inadmissible.
        // Replace the visual token without a transient Save-admission gap.
        val nextToken = interactionState.begin()
        cancelInteraction()
        interactionToken = nextToken
        if (enabled && owner != null && owner == renderedOwner) {
            interactionRevision = revision
            ownerRevision = owner
        }
    }

    fun select(seconds: Int) {
        if (interactionRevision != null) pendingSeconds = seconds
    }

    fun finishInteraction(owner: Long?, apply: (Int) -> NotificationDurationCommit?): Int? =
        finishInteraction(owner, apply, resetDisplay = {})

    fun finishInteraction(
        owner: Long?,
        apply: (Int) -> NotificationDurationCommit?,
        resetDisplay: (Int) -> Unit,
    ): Int? {
        val accepted = pendingSeconds?.takeIf {
            enabled && owner != null && owner == renderedOwner &&
                owner == ownerRevision && interactionRevision == revision
        }
        val completedToken = interactionToken
        interactionToken = null
        invalidateInteraction()
        var result: Int? = null
        var failure: Throwable? = null
        try {
            result = accepted?.let { commit(it, requireNotNull(owner), apply) }
        } catch (error: Throwable) {
            failure = error
        }
        try {
            resetDisplay(seconds)
        } catch (error: Throwable) {
            if (failure == null) failure = error else if (failure !== error) failure.addSuppressed(error)
        } finally {
            // A reentrant/newer interaction owns a different token and remains pending.
            completedToken?.let(interactionState::end)
        }
        failure?.let { throw it }
        return result
    }

    /** Invalidation alone does not end the native gesture or its visible snap animation. */
    fun invalidateInteraction() {
        interactionRevision = null
        ownerRevision = null
        pendingSeconds = null
    }

    fun commitInput(seconds: Int, owner: Long?, apply: (Int) -> NotificationDurationCommit?): Int? =
        if (enabled && owner != null && owner == renderedOwner && interactionToken == null) {
            commit(seconds, owner, apply)
        } else null

    fun release() {
        enabled = false
        renderedOwner = null
        cancelInteraction()
    }

    private fun commit(value: Int, owner: Long, apply: (Int) -> NotificationDurationCommit?): Int? {
        val acceptedRevision = revision
        val accepted = apply(value.coerceIn(1, maximum)) ?: return null
        // Only an accepted callback can advance our rendered owner without a new Compose frame.
        // A reentrant outside update still owns the binding and must not be overwritten here.
        if (!enabled || revision != acceptedRevision || renderedOwner != owner ||
            accepted.seconds !in 1..maximum
        ) return null
        seconds = accepted.seconds
        renderedOwner = accepted.owner
        revision++
        return seconds
    }

    private fun cancelInteraction() {
        val cancelledToken = interactionToken
        interactionToken = null
        invalidateInteraction()
        cancelledToken?.let(interactionState::end)
    }
}
