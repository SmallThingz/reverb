package app.smallthingz.reverb

import java.util.concurrent.atomic.AtomicBoolean

internal fun releaseExportLeaseOnceBestEffort(
    released: AtomicBoolean,
    release: () -> Unit,
): Exception? {
    if (!released.compareAndSet(false, true)) return null
    return try {
        release()
        null
    } catch (error: Exception) {
        error
    }
}
