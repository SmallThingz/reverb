package app.smallthingz.reverb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeOnboardingNavigationStateTest {
    @Test
    fun pendingButtonNavigationBlocksFinishBeforeThePagerStartsScrolling() {
        val navigation = NativeOnboardingNavigationState()
        assertTrue(navigation.mayFinish(onLastPage = true, scrolling = false, predictiveBack = false))

        // Back has accepted its request, but its pager coroutine has not run yet. A stale
        // Get started callback still observes the last page and an idle pager.
        val backRequest = navigation.begin()

        assertFalse(navigation.mayFinish(onLastPage = true, scrolling = false, predictiveBack = false))
        navigation.complete(backRequest)
        assertTrue(navigation.mayFinish(onLastPage = true, scrolling = false, predictiveBack = false))
    }

    @Test
    fun cancelledOrDuplicateCompletionCannotReleaseAnotherNavigationRequest() {
        val navigation = NativeOnboardingNavigationState()
        val cancelledRequest = navigation.begin()
        val newerRequest = navigation.begin()

        // Both normal completion and cancellation use the same ownership release hook.
        navigation.complete(cancelledRequest)
        assertFalse(navigation.mayFinish(onLastPage = true, scrolling = false, predictiveBack = false))
        navigation.complete(cancelledRequest)
        navigation.complete(Any())
        assertFalse(navigation.mayFinish(onLastPage = true, scrolling = false, predictiveBack = false))

        navigation.complete(newerRequest)
        assertTrue(navigation.mayFinish(onLastPage = true, scrolling = false, predictiveBack = false))
    }

    @Test
    fun finishRequiresTheCurrentLastPageAndNoScrollOrPredictiveBack() {
        val navigation = NativeOnboardingNavigationState()

        assertFalse(navigation.mayFinish(onLastPage = false, scrolling = false, predictiveBack = false))
        assertFalse(navigation.mayFinish(onLastPage = true, scrolling = true, predictiveBack = false))
        assertFalse(navigation.mayFinish(onLastPage = true, scrolling = false, predictiveBack = true))
        assertFalse(navigation.mayFinish(onLastPage = true, scrolling = true, predictiveBack = true))
        assertTrue(navigation.mayFinish(onLastPage = true, scrolling = false, predictiveBack = false))
    }
}
