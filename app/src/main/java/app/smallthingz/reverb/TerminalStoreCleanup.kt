package app.smallthingz.reverb

/**
 * A terminal cleanup owns its failures independently of the retiring Service/UI binding.
 * Attempt every stage before reporting once; cleanup failures cannot replace the first cause.
 */
internal fun runTerminalStoreCleanup(
    seal: () -> Unit = {},
    releaseInput: () -> Unit = {},
    closeLoopingStore: () -> Unit = {},
    closeOneShotStore: () -> Unit = {},
    onFailure: (Throwable) -> Unit,
) {
    var failure: Throwable? = null
    fun attempt(action: () -> Unit) {
        try {
            action()
        } catch (error: Throwable) {
            val primary = failure
            if (primary == null) failure = error
            else if (primary !== error) primary.addSuppressed(error)
        }
    }
    attempt(seal)
    attempt(releaseInput)
    attempt(closeLoopingStore)
    attempt(closeOneShotStore)
    failure?.let(onFailure)
}
