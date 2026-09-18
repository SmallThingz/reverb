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
