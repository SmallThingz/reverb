package app.smallthingz.reverb

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SettingsSaveContinuationTest {
    @Test fun acceptedSaveAfterReopen_keepsNewPanelAndFocus() = runBlocking {
        val submittedOwner = Any()
        var visibleOwner: Any? = submittedOwner
        val acceptedIo = CompletableDeferred<Unit>()
        val releaseIo = CompletableDeferred<Unit>()
        var durableBaseline = 48_000
        var runtimeRate = 48_000
        var focusCleared = false
        var navigationCount = 0
        val save = async {
            runSettingsSaveContinuation(
                submittedOwner = submittedOwner,
                persistSettings = {
                    // Save has already passed preflight and accepted the durable transaction.
                    acceptedIo.complete(Unit)
                    releaseIo.await()
                    durableBaseline = 44_100
                    runtimeRate = 44_100
                    true
                },
                currentVisibleOwner = { visibleOwner },
                onSaved = { focusCleared = true; navigationCount++ },
            )
        }
        acceptedIo.await()
        visibleOwner = null
        visibleOwner = Any()
        releaseIo.complete(Unit)
        assertFalse(save.await())
        assertEquals(44_100, durableBaseline)
        assertEquals(44_100, runtimeRate)
        assertFalse(focusCleared)
        assertEquals(0, navigationCount)
        assertNotSame(submittedOwner, visibleOwner)
    }

    @Test fun hiddenCompletion_preservesFocusOfVisibleSurface() = runBlocking {
        val owner = Any()
        var visibleOwner: Any? = owner
        val acceptedIo = CompletableDeferred<Unit>()
        val releaseIo = CompletableDeferred<Unit>()
        var continuationCount = 0
        val save = async {
            runSettingsSaveContinuation(owner, {
                acceptedIo.complete(Unit)
                releaseIo.await()
                true
            }, { visibleOwner }, { continuationCount++ })
        }
        acceptedIo.await()
        visibleOwner = null
        releaseIo.complete(Unit)
        assertFalse(save.await())
        assertEquals(0, continuationCount)
    }

    @Test fun sameVisibleActivation_successClosesOnceWithoutReadinessRestriction() = runBlocking {
        val owner = Any()
        var continuationCount = 0
        assertTrue(runSettingsSaveContinuation(owner, { true }, { owner }, { continuationCount++ }))
        assertEquals(1, continuationCount)
    }

    @Test fun rejectedSave_neverRunsContinuationOrRequiresVisibleOwnerRead() = runBlocking {
        val owner = Any()
        assertFalse(runSettingsSaveContinuation(owner, { false },
            { error("Rejected save must not continue") },
            { error("Rejected save must not navigate") }))
        assertFalse(runSettingsSaveContinuation(owner, { false }, { Any() },
            { error("Stale rejected save must not navigate") }))
    }

    @Test fun equalButDifferentOwner_cannotNavigate() = runBlocking {
        data class Owner(val value: Int)
        val submitted = Owner(1)
        assertFalse(runSettingsSaveContinuation(submitted, { true }, { Owner(1) },
            { error("Activation identity must match exactly") }))
    }
}
