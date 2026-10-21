package app.smallthingz.reverb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeOnboardingPredictiveNavigationStateTest {
    @Test
    fun backPinsItsPagesBeforeCancellingContinueJobsThatSettleAndCompleteSynchronously() {
        val navigation = NativeOnboardingNavigationState()
        var observedSettledPage = 1 // Buffers, with Continue to Audio accepted but unsettled.
        val firstContinue = navigation.begin()
        val secondContinue = navigation.begin()
        val seenTargets = mutableListOf<OnboardingBackTarget?>()
        val navigationAllowedDuringCancellation = mutableListOf<Boolean>()
        val finishAllowedDuringCancellation = mutableListOf<Boolean>()
        val cancelled = mutableSetOf<Any>()
        for (owner in listOf(firstContinue, secondContinue)) {
            navigation.attachCancellation(owner) {
                // Cancelling a pager mutation can synchronously settle its old destination.
                observedSettledPage = 2
                navigation.complete(owner)
                cancelled += owner
                seenTargets += navigation.predictiveBackTarget
                navigationAllowedDuringCancellation += navigation.mayNavigate()
                finishAllowedDuringCancellation += navigation.mayFinish(
                    onLastPage = true, scrolling = false, predictiveBack = false,
                )
            }
        }

        val gesture = navigation.beginPredictiveBack(observedSettledPage)

        assertEquals(setOf(firstContinue, secondContinue), cancelled)
        assertEquals(2, observedSettledPage)
        assertEquals(listOf(1, 1), seenTargets.map { it?.sourcePage })
        assertEquals(listOf(0, 0), seenTargets.map { it?.destinationPage })
        seenTargets.forEach { assertSame(gesture, it) }
        assertEquals(listOf(false, false), navigationAllowedDuringCancellation)
        assertEquals(listOf(false, false), finishAllowedDuringCancellation)
        assertSame(gesture, navigation.predictiveBackTarget)
        assertEquals(1, gesture.sourcePage)
        assertEquals(0, gesture.destinationPage)
    }

    @Test
    fun aNavigationJobAttachedAfterBackStartsIsCancelledInsteadOfResumingTheOldRequest() {
        val navigation = NativeOnboardingNavigationState()
        val queuedContinue = navigation.begin()
        val gesture = navigation.beginPredictiveBack(1)
        var cancellations = 0

        navigation.attachCancellation(queuedContinue) {
            cancellations++
            navigation.complete(queuedContinue)
        }

        assertEquals(1, cancellations)
        assertSame(gesture, navigation.predictiveBackTarget)
        assertFalse(navigation.mayNavigate())
        navigation.endPredictiveBack(gesture)
        assertTrue(navigation.mayFinish(onLastPage = true, scrolling = false, predictiveBack = false))
    }

    @Test
    fun attachingACompletedNavigationNeverResurrectsItsCancellationOrPendingOwnership() {
        val navigation = NativeOnboardingNavigationState()
        val completed = navigation.begin()
        navigation.complete(completed)
        var cancellations = 0

        navigation.attachCancellation(completed) { cancellations++ }
        assertEquals(1, cancellations)
        assertTrue(navigation.mayFinish(onLastPage = true, scrolling = false, predictiveBack = false))

        val laterGesture = navigation.beginPredictiveBack(2)
        assertEquals(1, cancellations)
        navigation.endPredictiveBack(laterGesture)
        assertTrue(navigation.mayNavigate())
        assertTrue(navigation.mayFinish(onLastPage = true, scrolling = false, predictiveBack = false))
    }

    @Test
    fun obsoleteGestureAndNavigationCompletionsCannotOwnANewerGestureWithTheSamePages() {
        val navigation = NativeOnboardingNavigationState()
        val oldContinue = navigation.begin()
        navigation.attachCancellation(oldContinue) { navigation.complete(oldContinue) }
        val oldGesture = navigation.beginPredictiveBack(1)
        val newGesture = navigation.beginPredictiveBack(1)
        assertNotSame(oldGesture, newGesture)
        assertEquals(oldGesture.sourcePage, newGesture.sourcePage)
        assertEquals(oldGesture.destinationPage, newGesture.destinationPage)

        navigation.endPredictiveBack(oldGesture)
        navigation.complete(oldContinue)

        assertFalse(navigation.ownsPredictiveBack(oldGesture))
        assertTrue(navigation.ownsPredictiveBack(newGesture))
        assertSame(newGesture, navigation.predictiveBackTarget)
        assertFalse(navigation.mayNavigate())
        assertFalse(navigation.mayFinish(onLastPage = true, scrolling = false, predictiveBack = false))
        navigation.endPredictiveBack(newGesture)
        assertTrue(navigation.mayNavigate())
    }

    @Test
    fun cancellingBackRetainsItsPinnedReturnPageAndReleasesOnlyItsOwnNavigationBlock() {
        val navigation = NativeOnboardingNavigationState()
        var observedSettledPage = 1
        val gesture = navigation.beginPredictiveBack(observedSettledPage)
        observedSettledPage = 2
        assertEquals(2, observedSettledPage)
        assertEquals(1, gesture.sourcePage) // Cancellation must restore Buffers, not the late Audio settle.
        assertEquals(0, gesture.destinationPage)

        navigation.endPredictiveBack(gesture)

        assertFalse(navigation.ownsPredictiveBack(gesture))
        assertTrue(navigation.mayNavigate())
        val nextContinue = navigation.begin()
        navigation.endPredictiveBack(gesture) // A late duplicate cannot release the new request.
        assertFalse(navigation.mayFinish(onLastPage = true, scrolling = false, predictiveBack = false))
        navigation.complete(nextContinue)
        assertTrue(navigation.mayFinish(onLastPage = true, scrolling = false, predictiveBack = false))
    }

    @Test
    fun firstPageBackKeepsBothTargetsInOnboardingInsteadOfProducingARootExit() {
        val navigation = NativeOnboardingNavigationState()
        val gesture = navigation.beginPredictiveBack(0)

        assertEquals(0, gesture.sourcePage)
        assertEquals(0, gesture.destinationPage)
        assertTrue(navigation.ownsPredictiveBack(gesture))
        assertFalse(navigation.mayNavigate())
        navigation.endPredictiveBack(gesture)
        assertTrue(navigation.mayNavigate())
    }
}
