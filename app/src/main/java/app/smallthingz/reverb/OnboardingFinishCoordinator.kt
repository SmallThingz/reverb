package app.smallthingz.reverb

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class OnboardingFinishState { IDLE, RUNNING, SUCCEEDED }

internal fun onboardingPendingForUi(
    finishState: OnboardingFinishState,
    readDurablePending: () -> Boolean,
): Boolean = when (finishState) {
    OnboardingFinishState.IDLE -> readDurablePending()
    // SharedPreferences can expose its new map before the durable commit has returned.
    OnboardingFinishState.RUNNING -> true
    OnboardingFinishState.SUCCEEDED -> false
}

/** A runtime handoff failure cannot undo an already accepted durable preference commit. */
internal fun commitOnboardingAndReloadRuntime(
    commit: () -> Boolean,
    reloadRuntime: () -> Unit,
    onRuntimeFailure: (Exception) -> Unit,
): Boolean {
    if (!commit()) return false
    try {
        reloadRuntime()
    } catch (error: Exception) {
        runCatching { onRuntimeFailure(error) }
    }
    return true
}

internal class OnboardingFinishCoordinator(
    private val submitAttempt: (suspend () -> Boolean, (Boolean) -> Unit) -> Unit =
        ::submitCommittedDurableUiBooleanAttempt,
) {
    private val mutableState = MutableStateFlow(OnboardingFinishState.IDLE)
    val state = mutableState.asStateFlow()

    fun submit(
        block: suspend () -> Boolean,
        onFailure: (Exception?) -> Unit,
    ): Boolean {
        if (!mutableState.compareAndSet(OnboardingFinishState.IDLE, OnboardingFinishState.RUNNING)) {
            return false
        }
        val terminal = AtomicBoolean(false)
        val failure = AtomicReference<Exception?>(null)
        val complete: (Boolean) -> Unit = { succeeded ->
            if (terminal.compareAndSet(false, true)) {
                if (succeeded) {
                    // Replay durable success to whichever Activity next owns the resumed UI.
                    mutableState.value = OnboardingFinishState.SUCCEEDED
                } else {
                    try {
                        onFailure(failure.get())
                    } finally {
                        mutableState.value = OnboardingFinishState.IDLE
                    }
                }
            }
        }
        try {
            submitAttempt({
                val result = runDurableUiAttempt(block)
                failure.set(result.failure)
                result.succeeded
            }, complete)
        } catch (error: Exception) {
            failure.compareAndSet(null, error)
            complete(false)
            return false
        }
        return true
    }
}

internal val processOnboardingFinish = OnboardingFinishCoordinator()
