package app.smallthingz.reverb

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

internal data class AutoStartChoice(
    val loaded: Boolean = false,
    val enabled: Boolean = false,
    val saving: Boolean = false,
    val permissionPending: Boolean = false,
    val unavailable: Boolean = false,
)
internal data class AutoStartChange(val revision: Long, val enabled: Boolean)
internal data class AutoStartWriteResult(val storedEnabled: Boolean?, val failure: Exception?)

internal fun writeAutoStartChoice(previousEnabled: Boolean, enabled: Boolean,
    commit: () -> Boolean, restore: () -> Boolean): AutoStartWriteResult {
    var restored = false
    return try {
        check(commitDurablePreferenceOrRestoreInMemory(commit, restoreInMemory = {
            check(restore()) { "Could not restore auto-start preference" }
            restored = true
        })) { "Could not save auto-start preference" }
        AutoStartWriteResult(enabled, null)
    } catch (error: Exception) {
        AutoStartWriteResult(if (restored) previousEnabled else null, error)
    }
}

/** Short control monitor only; preference IO never holds this admission boundary. */
internal class AutoStartPolicy {
    private var revision = 0L
    private val mutable = MutableStateFlow(AutoStartChoice())
    val state: StateFlow<AutoStartChoice> get() = mutable
    @Synchronized fun revision(): Long = revision
    @Synchronized fun load(expected: Long, enabled: Boolean) {
        if (expected == revision && !mutable.value.saving && !mutable.value.permissionPending && !mutable.value.unavailable)
            mutable.value = AutoStartChoice(loaded = true, enabled = enabled)
    }
    @Synchronized fun request(enabled: Boolean): AutoStartChange {
        val request = AutoStartChange(++revision, enabled)
        mutable.value = mutable.value.copy(loaded = true, enabled = enabled, saving = true, permissionPending = false)
        return request
    }
    @Synchronized fun requestPermission(): Long {
        ++revision
        mutable.value = mutable.value.copy(permissionPending = true)
        return revision
    }
    @Synchronized fun cancelPermission(expected: Long) {
        if (expected == revision && mutable.value.permissionPending) {
            ++revision
            mutable.value = mutable.value.copy(permissionPending = false)
        }
    }
    @Synchronized fun permissionReturned(expected: Long, granted: Boolean): AutoStartChange? {
        if (expected != revision || !mutable.value.permissionPending) return null
        if (!granted) { cancelPermission(expected); return null }
        return request(true)
    }
    @Synchronized fun current(change: AutoStartChange): Boolean = change.revision == revision
    @Synchronized fun complete(change: AutoStartChange, storedEnabled: Boolean?) {
        if (current(change)) mutable.value = AutoStartChoice(loaded = true, enabled = storedEnabled == true,
            unavailable = storedEnabled == null)
    }
    @Synchronized fun admission(): Long? = revision.takeIf {
        mutable.value.let { it.loaded && it.enabled && !it.saving && !it.permissionPending && !it.unavailable }
    }
    @Synchronized fun currentAdmission(expected: Long): Boolean = admission() == expected
}

/** Explicit opt-in, absent/invalid defaults OFF; no capture intent is written here. */
internal object AutoStartAfterRestart {
    val policy = AutoStartPolicy()
    private val storageLock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun read(context: Context): Boolean =
        getRecorderPreferences(context).safeBoolean(PrefKey.AUTO_START_AFTER_RESTART, false)

    fun refresh(context: Context) {
        val app = context.applicationContext
        val revision = policy.revision()
        scope.launch {
            try { synchronized(storageLock) { policy.load(revision, read(app)) } }
            catch (error: Exception) { report(app, error) }
        }
    }

    fun request(context: Context, enabled: Boolean) {
        val change = policy.request(enabled)
        if (!enabled) BootResumeNotification.dismissAutomaticStatus(context)
        persist(context, change)
    }

    fun persist(context: Context, change: AutoStartChange) {
        val app = context.applicationContext
        scope.launch {
            try {
                synchronized(storageLock) {
                    if (!policy.current(change)) return@synchronized
                    val preferences = getRecorderPreferences(app)
                    val previous = preferences.snapshotDurablePreferenceValue(PrefKey.AUTO_START_AFTER_RESTART)
                    val previousEnabled = previous.value == true
                    if (change.enabled && !BootResumeActivity.overlayAvailable(app)) {
                        policy.complete(change, previousEnabled)
                        error("Display over other apps permission is required")
                    }
                    val result = writeAutoStartChoice(previousEnabled, change.enabled,
                        commit = { preferences.edit().putBoolean(PrefKey.AUTO_START_AFTER_RESTART, change.enabled).commit() },
                        restore = { preferences.edit().restoreDurablePreferenceValue(PrefKey.AUTO_START_AFTER_RESTART, previous).commit() })
                    policy.complete(change, result.storedEnabled)
                    result.failure?.let { throw it }
                }
            } catch (error: Exception) {
                if (policy.current(change) && policy.state.value.saving) policy.complete(change, null)
                report(app, error)
            }
        }
    }

    /** Called on the boot worker, before any Activity launch is queued. */
    fun admission(context: Context): Long? = synchronized(storageLock) {
        val revision = policy.revision()
        policy.load(revision, read(context))
        policy.admission()
    }

    private fun report(context: Context, error: Exception) {
        AppFeedbackCenter.post(actionableErrorMessage(context,
            context.getString(R.string.auto_start_save_failed), error), FeedbackTone.ERROR)
    }
}
