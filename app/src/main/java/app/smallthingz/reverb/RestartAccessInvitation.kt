package app.smallthingz.reverb

import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean

/** One process reservation bridges an accepted decision and its off-main durable publication. */
internal class RestartAccessInvitationState {
    private val accepted = AtomicBoolean(false)
    val handledInProcess: Boolean get() = accepted.get()

    fun readHandled(readStored: () -> Boolean): Boolean {
        if (accepted.get()) return true
        if (readStored()) accepted.set(true)
        // A decision may have been accepted while the disk snapshot was being read.
        return accepted.get()
    }

    fun acceptDecision(): Boolean = accepted.compareAndSet(false, true)
    fun shouldShow(loaded: Boolean, ordinaryMainReady: Boolean, overlayAllowed: Boolean): Boolean =
        loaded && ordinaryMainReady && !overlayAllowed && !accepted.get()
}

internal object RestartAccessInvitation {
    private const val HANDLED_KEY = "RESTART_ACCESS_INVITATION_HANDLED"
    val state = RestartAccessInvitationState()

    /** Both preferences operations are called on IO; this is not capture authority. */
    fun readHandled(context: Context): Boolean = state.readHandled {
        getRecorderPreferences(context).getBoolean(HANDLED_KEY, false)
    }

    fun persistHandled(context: Context): Boolean =
        getRecorderPreferences(context).edit().putBoolean(HANDLED_KEY, true).commit()
}
