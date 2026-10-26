package app.smallthingz.reverb

/** Retain the first fixture failure without self-suppression when owners share an exception. */
internal fun appendNativeParityFailure(primary: Throwable?, error: Throwable): Throwable {
    if (primary == null) return error
    if (primary !== error) primary.addSuppressed(error)
    return primary
}

/** Attempt one independent release; callers must propagate the returned failure after all releases. */
internal inline fun captureNativeParityCleanup(primary: Throwable?, action: () -> Unit): Throwable? =
    try {
        action()
        primary
    } catch (error: Throwable) {
        appendNativeParityFailure(primary, error)
    }
