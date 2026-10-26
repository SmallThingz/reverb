package app.smallthingz.reverb

import smallthingz.ui.runtime.getValue
import smallthingz.ui.runtime.mutableIntStateOf
import smallthingz.ui.runtime.mutableStateOf
import smallthingz.ui.runtime.setValue

/** Owns navigation from the button callback, before the pager's coroutine can start. */
internal class OnboardingNavigationState {
    private val pending = mutableSetOf<Any>()
    private val cancellation = mutableMapOf<Any, () -> Unit>()
    private var pendingCount by mutableIntStateOf(0)
    var predictiveBackTarget by mutableStateOf<OnboardingBackTarget?>(null)
        private set

    fun begin(): Any = Any().also { owner ->
        pending += owner
        pendingCount = pending.size
    }

    fun attachCancellation(owner: Any, cancel: () -> Unit) {
        if (owner !in pending || predictiveBackTarget != null) cancel()
        else cancellation[owner] = cancel
    }

    fun complete(owner: Any) {
        pending -= owner
        cancellation -= owner
        pendingCount = pending.size
    }

    fun beginPredictiveBack(sourcePage: Int): OnboardingBackTarget {
        val source = sourcePage.coerceAtLeast(0)
        val target = OnboardingBackTarget(source, (source - 1).coerceAtLeast(0))
        predictiveBackTarget = target
        // Completion may synchronously remove its cancellation callback; iterate an owned snapshot.
        cancellation.values.toList().forEach { it() }
        return target
    }

    fun ownsPredictiveBack(target: OnboardingBackTarget): Boolean = predictiveBackTarget === target

    fun endPredictiveBack(target: OnboardingBackTarget) {
        if (ownsPredictiveBack(target)) predictiveBackTarget = null
    }

    fun mayNavigate(): Boolean = predictiveBackTarget == null

    fun mayFinish(onLastPage: Boolean, scrolling: Boolean, predictiveBack: Boolean): Boolean =
        onLastPage && pendingCount == 0 && !scrolling && !predictiveBack && mayNavigate()
}
