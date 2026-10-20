package app.smallthingz.reverb

import android.annotation.SuppressLint
import android.content.Context
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class SilenceGatePreferenceSnapshot(val raw: String?, val config: SilenceGateConfig)

internal object SilenceGatePreferences {
    private val lock = Any()
    private val mutableRevision = MutableStateFlow(0L)
    val revision = mutableRevision.asStateFlow()

    fun read(context: Context): SilenceGatePreferenceSnapshot = synchronized(lock) {
        val prefs = getRecorderPreferences(context)
        val raw = requireDurablePreference(prefs.contains(PrefKey.SILENCE_GATE), null, "SILENCE_GATE") {
            requireNotNull(prefs.getString(PrefKey.SILENCE_GATE, null)) { "Null silence curve" }
        }
        SilenceGatePreferenceSnapshot(raw, decodeSilenceGate(raw))
    }

    fun readForOnboarding(context: Context): OnboardingSilenceConfiguration = synchronized(lock) {
        decodeOnboardingSilence(getRecorderPreferences(context).all)
    }

    /** Finish owns the fresh curve and its commit/rollback under the same lock as Settings. */
    fun withOnboardingTransaction(
        context: Context,
        block: (OnboardingSilenceConfiguration) -> Boolean,
    ): Boolean = synchronized(lock) {
        val committed = block(readForOnboarding(context))
        if (committed) mutableRevision.value += 1
        committed
    }

    /**
     * Joins silence into Settings' existing all-or-rollback preference transaction.
     * The caller runs on IO under the retention gate; the block owns the whole commit/rollback
     * and returns true only after that transaction has committed. No separate write occurs here.
     */
    fun withSettingsTransaction(
        context: Context,
        expectedRaw: String?,
        block: () -> Boolean,
    ): Boolean = synchronized(lock) {
        if (read(context).raw != expectedRaw) {
            throw IOException(context.getString(R.string.silence_settings_changed))
        }
        val committed = block()
        if (committed) mutableRevision.value += 1
        committed
    }

    @SuppressLint("UseKtx") // commit success and raw rollback are the transaction boundary.
    fun save(context: Context, expected: SilenceGatePreferenceSnapshot, config: SilenceGateConfig) = synchronized(lock) {
        val prefs = getRecorderPreferences(context)
        val current = read(context)
        if (current.raw != expected.raw) throw IOException(context.getString(R.string.silence_settings_changed))
        val snapshot = prefs.snapshotDurablePreferenceValue(PrefKey.SILENCE_GATE)
        val encoded = encodeSilenceGate(config)
        val saved = commitDurablePreferenceOrRestoreInMemory(
            commit = { prefs.edit().putString(PrefKey.SILENCE_GATE, encoded).commit() },
            restoreInMemory = { prefs.edit().restoreDurablePreferenceValue(PrefKey.SILENCE_GATE, snapshot).commit() },
        )
        if (!saved) throw IOException(context.getString(R.string.silence_save_failed))
        // The store owns convergence, including observers created while an old editor is saving.
        // Failed commits and rollback never announce a new durable configuration.
        mutableRevision.value += 1
        SilenceGatePreferenceSnapshot(encoded, config.copy(points = config.points.toList()))
    }
}
