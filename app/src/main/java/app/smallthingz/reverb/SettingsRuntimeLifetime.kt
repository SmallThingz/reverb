package app.smallthingz.reverb

import java.util.concurrent.atomic.AtomicInteger

internal class SettingsRuntimeLifetime {
    private val inFlight = AtomicInteger(0)

    fun begin() {
        inFlight.incrementAndGet()
    }

    /** Returns true only when this completion released the last accepted runtime apply. */
    fun finish(): Boolean {
        while (true) {
            val current = inFlight.get()
            check(current > 0) { "Settings runtime lifetime underflow" }
            val next = current - 1
            if (inFlight.compareAndSet(current, next)) return next == 0
        }
    }

    fun isActive(): Boolean = inFlight.get() > 0
}

internal fun settingsRuntimeKeepaliveShouldBeSticky(
    listeningIntentEnabled: Boolean,
    foregroundStartBlocked: Boolean,
    foregroundServiceTimedOut: Boolean,
    persistenceFailureBlocked: Boolean,
): Boolean = listeningIntentEnabled &&    !foregroundStartBlocked &&
    !foregroundServiceTimedOut &&
    !persistenceFailureBlocked
