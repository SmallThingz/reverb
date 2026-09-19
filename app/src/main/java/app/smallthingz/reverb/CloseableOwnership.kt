package app.smallthingz.reverb

import java.io.Closeable

internal inline fun <Owner : Closeable, Child> openChildOrCloseOwner(
    owner: Owner,
    open: (Owner) -> Child,
): Child = try {
    open(owner)
} catch (error: Throwable) {
    try {
        owner.close()
    } catch (closeError: Throwable) {
        if (closeError !== error) error.addSuppressed(closeError)
    }
    throw error
}

internal inline fun <Owner> configureOwnedResourceOrRelease(
    owner: Owner,
    release: (Owner) -> Unit,
    configure: (Owner) -> Unit,
): Owner = try {
    configure(owner)
    owner
} catch (error: Throwable) {
    throw requireNotNull(
        closePreservingPrimaryFailure(error) { release(owner) },
    )
}

internal inline fun <Owner, Result> withOwnedResource(
    owner: Owner,
    release: (Owner) -> Unit,
    block: (Owner) -> Result,
): Result {
    var primaryFailure: Throwable? = null
    try {
        return block(owner)
    } catch (error: Throwable) {
        primaryFailure = error
        throw error
    } finally {
        val terminalFailure = closePreservingPrimaryFailure(primaryFailure) { release(owner) }
        if (primaryFailure == null && terminalFailure != null) throw terminalFailure
    }
}

internal inline fun closePreservingPrimaryFailure(
    primaryFailure: Throwable?,
    close: () -> Unit,
): Throwable? {
    var failure = primaryFailure
    try {
        close()
    } catch (closeError: Throwable) {
        val primary = failure
        if (primary == null) {
            failure = closeError
        } else if (closeError !== primary) {
            primary.addSuppressed(closeError)
        }
    }
    return failure
}
internal inline fun throwAfterClosePreservingPrimary(
    primaryFailure: Throwable,
    close: () -> Unit,
): Nothing {
    throw requireNotNull(closePreservingPrimaryFailure(primaryFailure, close))
}
internal inline fun closeRejectedOwnerOrThrow(close: () -> Unit) {
    closePreservingPrimaryFailure(null, close)?.let { throw it }
}
