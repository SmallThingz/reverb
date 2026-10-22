package app.smallthingz.reverb

import app.smallthingz.nativeui.runtime.mutableStateOf
import app.smallthingz.nativeui.platform.AccessibilityManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureSaveFeedbackVisibilityTest {
    @Test
    fun visibleReceiverKeepsTheSavedCardAndCompletesItsExactOwnerOnce() {
        val host = Host { true }
        val recording = recording("visible")
        host.receiver.fileReady(recording)

        assertEquals(CaptureSaveStatus.Saved(recording), host.status)
        assertFalse(host.saving)
        assertEquals(listOf("committed", "status:Saved", "saving:false", "saved", "terminal"), host.events)
        assertEquals(1, host.terminals)
        assertFalse(host.gate.attached)
        assertTrue(host.savedFallback.isEmpty())
        assertTrue(host.queuedSuccess.isEmpty())
        host.receiver.fileReady(recording)
        assertEquals(1, host.terminals)
        assertEquals(1, host.refreshes)
    }

    @Test
    fun attachedHiddenReceiverReadsLiveAdmissionBeforeRecomposition() {
        for (overlay in listOf("Settings", "Library", "Incidents", "About", "panel transition")) {
            val visible = mutableStateOf(true)
            val host = Host { visible.value }
            val recording = recording(overlay)
            // The callback is already installed. Navigation changes before any recomposition.
            visible.value = false
            host.receiver.fileReady(recording)

            assertNull(host.status)
            assertFalse(host.saving)
            assertEquals(listOf(recording), host.queuedSuccess)
            assertTrue(host.savedFallback.isEmpty())
            assertEquals(1, host.refreshes)
            assertEquals(1, host.terminals)
            assertFalse(host.gate.attached)
            visible.value = true
            host.receiver.fileReady(recording)
            host.receiver.fileCancelled()
            assertNull(host.status)
            assertEquals(listOf(recording), host.queuedSuccess)
            assertTrue(host.savedFallback.isEmpty())
            assertEquals(1, host.terminals)
        }
    }

    @Test
    fun liveReopenBeforeCompletionAllowsTheCurrentVisibleResult() {
        val visible = mutableStateOf(false)
        val host = Host { visible.value }
        visible.value = true
        val recording = recording("reopened")
        host.receiver.fileReady(recording)
        assertEquals(CaptureSaveStatus.Saved(recording), host.status)
        assertTrue(host.savedFallback.isEmpty())
        assertTrue(host.queuedSuccess.isEmpty())
        assertEquals(1, host.terminals)
    }

    @Test
    fun detachedReceiverDropsEveryUiCallbackAndUsesTheExistingFallbackOnce() {
        var visibilityQueries = 0
        val host = Host { visibilityQueries++; true }
        host.receiver.detachUi()
        val recording = recording("detached")
        host.receiver.fileReady(recording)
        host.receiver.fileReady(recording)

        assertEquals(listOf("committed", "terminal"), host.events)
        assertEquals(listOf(recording), host.savedFallback)
        assertEquals(0, host.refreshes)
        assertEquals(0, visibilityQueries)
        assertTrue(host.queuedSuccess.isEmpty())
        assertEquals(1, host.terminals)
    }

    @Test
    fun hiddenFailureAndCancellationNeverBecomeSavedFeedback() {
        val failed = Host { false }
        val error = IllegalStateException("export failed")
        failed.receiver.fileFailed("disk full", error)
        failed.receiver.fileReady(recording("must-not-succeed"))
        assertEquals(listOf("disk full"), failed.failedFallback)
        assertTrue(failed.savedFallback.isEmpty())
        assertTrue(failed.queuedSuccess.isEmpty())
        assertEquals(0, failed.refreshes)
        assertNull(failed.status)
        assertFalse(failed.saving)
        assertEquals(1, failed.terminals)

        val cancelled = Host { false }
        cancelled.receiver.fileCancelled()
        cancelled.receiver.fileReady(recording("cancelled"))
        assertTrue(cancelled.savedFallback.isEmpty())
        assertTrue(cancelled.queuedSuccess.isEmpty())
        assertTrue(cancelled.failedFallback.isEmpty())
        assertEquals(0, cancelled.refreshes)
        assertNull(cancelled.status)
        assertFalse(cancelled.saving)
        assertEquals(1, cancelled.terminals)
    }

    @Test
    fun visibleFailureRetainsItsErrorCallbackAndNeverPostsSuccess() {
        val host = Host { true }
        host.receiver.fileFailed("", null)
        assertEquals(listOf("saving:false", "status:null", "error:Save failed", "terminal"), host.events)
        assertTrue(host.savedFallback.isEmpty())
        assertTrue(host.failedFallback.isEmpty())
        assertEquals(1, host.terminals)
    }

    @Test
    fun retiredReceiverCannotOverwriteANewerExportOnReveal() {
        var visible = false
        var status: CaptureSaveStatus? = CaptureSaveStatus.Saving(cancellable = true)
        val fallbackIds = mutableListOf<String>()
        var terminals = 0
        fun newReceiver() = SaveResultReceiver(
            uiCallbacks = SaveUiCallbackGate({}, { status = it }, {}, {}, { visible }),
            fallback = object : ReverbService.AudioFileReceiver {
                override fun fileReady(recording: RecordingEntity) { fallbackIds += recording.id }
                override fun fileFailed(message: String, error: Throwable?) = Unit
                override fun fileCancelled() = Unit
            },
            defaultFailureMessage = { "Save failed" },
            onTerminal = { terminals++ },
        )
        val first = newReceiver()
        first.detachUi()
        val second = newReceiver()
        val saving = CaptureSaveStatus.Saving(cancellable = true)
        status = saving
        visible = true
        first.fileReady(recording("old"))
        first.fileReady(recording("late-old"))
        first.fileFailed("late failure", null)
        assertSame(saving, status)
        assertEquals(listOf("old"), fallbackIds)
        val latest = recording("new")
        second.fileReady(latest)
        assertEquals(CaptureSaveStatus.Saved(latest), status)
        assertEquals(listOf("old"), fallbackIds)
        assertEquals(2, terminals)
    }

    @Test
    fun throwingVisibleDeliveryStillFallsBackAndRetiresTheReceiver() {
        val primary = IllegalStateException("callback failed")
        val fallback = mutableListOf<RecordingEntity>()
        var terminals = 0
        val gate = SaveUiCallbackGate({}, { throw primary }, {}, {}, canDisplay = { true })
        val receiver = SaveResultReceiver(
            uiCallbacks = gate,
            fallback = object : ReverbService.AudioFileReceiver {
                override fun fileReady(recording: RecordingEntity) { fallback += recording }
                override fun fileFailed(message: String, error: Throwable?) = Unit
                override fun fileCancelled() = Unit
            },
            defaultFailureMessage = { "Save failed" },
            onTerminal = { terminals++ },
        )
        val recording = recording("throwing")
        val observed = runCatching { receiver.fileReady(recording) }.exceptionOrNull()
        assertSame(primary, observed)
        assertEquals(listOf(recording), fallback)
        assertEquals(1, terminals)
        assertFalse(gate.attached)
        receiver.fileReady(recording)
        assertEquals(1, fallback.size)
    }

    @Test
    fun throwingVisibleFailureStillFallsBackOnceAndRetiresTheReceiver() {
        val primary = IllegalStateException("Failure callback failed")
        val exportFailure = java.io.IOException("Disk full")
        val failures = mutableListOf<Pair<String, Throwable?>>()
        var terminals = 0
        val gate = SaveUiCallbackGate({}, {}, { throw primary }, {}, canDisplay = { true })
        val receiver = SaveResultReceiver(
            uiCallbacks = gate,
            fallback = object : ReverbService.AudioFileReceiver {
                override fun fileReady(recording: RecordingEntity) = Unit
                override fun fileFailed(message: String, error: Throwable?) {
                    failures += message to error
                }
            },
            defaultFailureMessage = { "Save failed" },
            onTerminal = { terminals++ },
        )

        val observed = runCatching { receiver.fileFailed("Disk full", exportFailure) }.exceptionOrNull()

        assertSame(primary, observed)
        assertEquals(listOf("Disk full" to exportFailure), failures)
        assertEquals(1, terminals)
        assertFalse(gate.attached)
        receiver.fileFailed("late duplicate", exportFailure)
        receiver.fileCancelled()
        assertEquals(listOf("Disk full" to exportFailure), failures)
        assertEquals(1, terminals)
    }

    @Test
    fun hiddenSavedNoticeDoesNotStartItsVisibleDeadline() = runBlocking {
        val expected = CaptureSaveStatus.Saved(recording("pending"))
        dismissSavedFeedbackAfterTimeout(
            expected, null, currentStatus = { expected },
            dismiss = { throw AssertionError("Hidden notice was dismissed") },
            waitForDuration = { throw AssertionError("Hidden notice started a deadline") },
            canDisplay = { false },
            awaitVisibleFrame = { throw AssertionError("Hidden notice awaited a frame") },
        )
        var visible = true
        dismissSavedFeedbackAfterTimeout(
            expected, null, currentStatus = { expected },
            dismiss = { throw AssertionError("Notice hidden before its frame was dismissed") },
            waitForDuration = { throw AssertionError("Unrendered notice started a deadline") },
            canDisplay = { visible },
            awaitVisibleFrame = { visible = false },
        )
    }

    @Test
    fun hiddenAtDeadlineStaysPendingAndReopenReceivesTheFullAccessibleDuration() = runBlocking {
        withTimeout(5_000L) {
            val clock = ManualClock()
            val manager = ExtendedTimeout()
            val expected = CaptureSaveStatus.Saved(recording("paused"))
            var status: CaptureSaveStatus? = expected
            var visible = true
            var frames = 0
            fun dismiss() { status = null }
            val first = launch(start = CoroutineStart.UNDISPATCHED) {
                dismissSavedFeedbackAfterTimeout(
                    expected, manager, { status }, ::dismiss, clock::wait,
                    canDisplay = { visible }, awaitVisibleFrame = { frames++ },
                )
            }
            clock.advance(10_000L)
            visible = false
            // Even if the visibility collector has not yet cancelled, the terminal guard is live.
            clock.advance(20_000L)
            first.join()
            assertSame(expected, status)
            visible = true
            val resumed = launch(start = CoroutineStart.UNDISPATCHED) {
                dismissSavedFeedbackAfterTimeout(
                    expected, manager, { status }, ::dismiss, clock::wait,
                    canDisplay = { visible }, awaitVisibleFrame = { frames++ },
                )
            }
            clock.advance(29_999L)
            yield()
            assertSame(expected, status)
            clock.advance(1L)
            resumed.join()
            assertNull(status)
            assertEquals(listOf(30_000L, 30_000L), clock.requests)
            assertEquals(2, frames)
            assertEquals(listOf(1_500L, 1_500L), manager.originals)
        }
    }

    @Test
    fun cancelledVisibleDeadlineCannotConsumeAReopenedOrEqualNewerStatus() = runBlocking {
        withTimeout(5_000L) {
            val expected = CaptureSaveStatus.Saved(recording("exact-owner"))
            var status: CaptureSaveStatus? = expected
            val release = CompletableDeferred<Unit>()
            val timer = launch(start = CoroutineStart.UNDISPATCHED) {
                dismissSavedFeedbackAfterTimeout(
                    expected, null, { status }, { status = null },
                    waitForDuration = { withContext(NonCancellable) { release.await() } },
                )
            }
            timer.cancel()
            release.complete(Unit)
            timer.join()
            assertSame(expected, status)
            val newer = expected.copy()
            status = newer
            dismissSavedFeedbackAfterTimeout(
                expected, null, { status }, { throw AssertionError("Old timer cleared an equal new owner") },
                waitForDuration = {},
            )
            assertSame(newer, status)
        }
    }

    private class Host(canDisplay: () -> Boolean) {
        var saving = true
        var status: CaptureSaveStatus? = CaptureSaveStatus.Saving(cancellable = true)
        var refreshes = 0
        var terminals = 0
        val events = mutableListOf<String>()
        val savedFallback = mutableListOf<RecordingEntity>()
        val queuedSuccess = mutableListOf<RecordingEntity>()
        val failedFallback = mutableListOf<String>()
        val gate = SaveUiCallbackGate(
            setSaving = { saving = it; events += "saving:$it" },
            onStatus = { status = it; events += "status:${it?.javaClass?.simpleName ?: "null"}" },
            onError = { events += "error:$it" },
            onSaved = { refreshes++; events += "saved" },
            canDisplay = canDisplay,
            onHiddenSaved = { queuedSuccess += it },
        )
        val receiver = SaveResultReceiver(
            uiCallbacks = gate,
            fallback = object : ReverbService.AudioFileReceiver {
                override fun fileReady(recording: RecordingEntity) { savedFallback += recording }
                override fun fileFailed(message: String, error: Throwable?) { failedFallback += message }
                override fun fileCancelled() { throw AssertionError("Cancellation should not invoke fallback") }
            },
            defaultFailureMessage = { "Save failed" },
            onCommitted = { events += "committed" },
            onTerminal = { terminals++; events += "terminal" },
        )
    }

    private class ManualClock {
        private val now = MutableStateFlow(0L)
        val requests = mutableListOf<Long>()
        suspend fun wait(duration: Long) {
            requests += duration
            val deadline = now.value + duration
            now.first { it >= deadline }
        }
        fun advance(duration: Long) { now.value += duration }
    }

    private class ExtendedTimeout : AccessibilityManager {
        val originals = mutableListOf<Long>()
        override fun calculateRecommendedTimeoutMillis(
            originalTimeoutMillis: Long,
            containsIcons: Boolean,
            containsText: Boolean,
            containsControls: Boolean,
        ): Long {
            originals += originalTimeoutMillis
            assertTrue(containsIcons)
            assertTrue(containsText)
            assertFalse(containsControls)
            return 30_000L
        }
    }

    private fun recording(id: String) = RecordingEntity(
        id = id,
        displayName = "$id.wav",
        mimeType = "audio/wav",
        startedAtMillis = 1L,
        durationMillis = 1L,
        sizeBytes = 1L,
        codecSummary = "pcm",
        storageType = RecordingStorageType.FILE,
        directoryId = "dir",
    )
}
