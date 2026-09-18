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
