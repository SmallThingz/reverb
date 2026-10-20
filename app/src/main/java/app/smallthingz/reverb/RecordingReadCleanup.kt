package app.smallthingz.reverb

import java.io.Closeable

/**
 * Optional audio reads may discard their primary failure. Capture cleanup at its own
 * operation so those callers can report it without guessing from suppressed exceptions.
 */
internal class RecordingReadCleanup(
    private val onFailure: (Throwable) -> Unit,
) {
    fun close(closeOwner: () -> Unit) {
        try {
            closeOwner()
        } catch (error: Throwable) {
            runCatching { onFailure(error) }
            throw error
        }
    }

    fun closeAfterFailure(primary: Throwable, closeOwner: () -> Unit): Throwable =
        requireNotNull(closePreservingPrimaryFailure(primary) { close(closeOwner) })

    fun <Owner : Closeable, Child> open(owner: Owner, openChild: (Owner) -> Child): Child =
        try {
            openChild(owner)
        } catch (error: Throwable) {
            throw closeAfterFailure(error, owner::close)
        }

    fun <Owner, Result> use(
        owner: Owner,
        release: (Owner) -> Unit,
        block: (Owner) -> Result,
    ): Result = withOwnedResource(
        owner = owner,
        release = { acquired -> close { release(acquired) } },
        block = block,
    )

    companion object {
        // Export, trim and verification already surface the complete primary/suppressed chain.
        val Propagate = RecordingReadCleanup {}
    }
}
