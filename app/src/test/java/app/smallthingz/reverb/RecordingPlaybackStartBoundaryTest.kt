package app.smallthingz.reverb

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Actual public command/pending-token owner; only the external Android start result is controlled. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RecordingPlaybackStartBoundaryTest {
    @Test fun nullPlatformStartRejectsPlaybackAndRetiresOnlyItsPendingToken() = rejectedStart(false)

    @Test fun securityDeniedPlatformStartReportsFailureAndRetiresItsPendingToken() = rejectedStart(true)

    @Test fun nonnullPlatformStartTransfersExactRecordingAndPositionToOneMatchingRequest() = fixture { context, recording, ownFeedback ->
        context.result = ComponentName(context, RecordingPlaybackService::class.java)
        val before = RecordingPlayback.state.value
        val accepted = RecordingPlayback.play(context, recording, 137)
        ownFeedback()
        assertTrue(accepted)
        assertEquals(1, context.starts.size)
        assertTrue(context.pendingObservedAtBoundary)
        assertTrue(RecordingPlayback.hasPendingStart())
        val intent = context.starts.single()
        assertEquals(ComponentName(context, RecordingPlaybackService::class.java), intent.component)
        val token = intent.getLongExtra(RecordingPlaybackService.EXTRA_REQUEST, -1L)
        assertTrue(token > 0L)
        assertNull(RecordingPlayback.takeStart(token + 1L))
        assertTrue(RecordingPlayback.hasPendingStart())
        val request = requireNotNull(RecordingPlayback.takeStart(token))
        assertEquals(token, request.token)
        assertSame(recording, request.recording)
        assertEquals(137, request.positionMillis)
        assertFalse(RecordingPlayback.hasPendingStart())
        assertNull(RecordingPlayback.takeStart(token))
        assertEquals(before, RecordingPlayback.state.value)
        assertTrue(AppFeedbackCenter.events.value.isEmpty())
    }

    private fun rejectedStart(throwSecurity: Boolean) = fixture { context, recording, ownFeedback ->
        context.denied = if (throwSecurity) SecurityException("fixture playback foreground denial") else null
        val before = RecordingPlayback.state.value
        val accepted = RecordingPlayback.play(context, recording, 137)
        ownFeedback()
        assertFalse("A rejected platform handoff cannot accept playback", accepted)
        assertEquals(1, context.starts.size)
        assertTrue(context.pendingObservedAtBoundary)
        val intent = context.starts.single()
        assertEquals(ComponentName(context, RecordingPlaybackService::class.java), intent.component)
        val token = intent.getLongExtra(RecordingPlaybackService.EXTRA_REQUEST, -1L)
        assertTrue(token > 0L)
        assertFalse(RecordingPlayback.hasPendingStart())
        assertNull(RecordingPlayback.takeStart(token))
        assertEquals(before, RecordingPlayback.state.value)
        val event = AppFeedbackCenter.events.value.single()
        assertEquals(FeedbackTone.ERROR, event.tone)
        assertTrue(event.message.contains(context.getString(R.string.recording_playback_failed)))
        assertTrue(event.message.contains(if (throwSecurity) "fixture playback foreground denial"
            else "Playback service start was rejected"))
    }

    private fun fixture(test: (PlatformStartContext, RecordingEntity, () -> Unit) -> Unit) {
        val attachedService = RecordingPlayback::class.java.getDeclaredField("service").apply { isAccessible = true }
        check(attachedService.get(RecordingPlayback) == null) { "A foreign playback service still owns the command bridge" }
        check(!RecordingPlayback.hasPendingStart())
        check(RecordingPlayback.state.value.recording == null)
        check(AppFeedbackCenter.events.value.isEmpty())
        val context = PlatformStartContext(RuntimeEnvironment.getApplication())
        val recording = RecordingEntity(id = "/unused-synthetic/playback-start.wav", displayName = "playback-start.wav",
            mimeType = "audio/wav", startedAtMillis = 1L, durationMillis = 2_000L, sizeBytes = 192_044L,
            codecSummary = "PCM", storageType = RecordingStorageType.FILE, directoryId = "/unused-synthetic",
            fileIdentity = "selected-playback-revision", createdAtMillis = 1L, lastSeenAtMillis = 1L)
        var primary: Throwable? = null
        var ownedFeedbackId: Long? = null
        val captureFeedback = {
            val emitted = AppFeedbackCenter.events.value
            check(emitted.size <= 1) { "Unexpected feedback owner at synchronous playback return" }
            ownedFeedbackId = emitted.singleOrNull()?.id
        }
        try {
            test(context, recording, captureFeedback)
        } catch (error: Throwable) {
            primary = error
        } finally {
            fun cleanup(action: () -> Unit) {
                try { action() } catch (error: Throwable) {
                    if (primary == null) primary = error else if (primary !== error) primary!!.addSuppressed(error)
                }
            }
            // Only tokens issued by this exact context are retired; never clear a foreign pending owner.
            cleanup {
                context.starts.forEach { intent ->
                    RecordingPlayback.takeStart(intent.getLongExtra(RecordingPlaybackService.EXTRA_REQUEST, -1L))
                }
                check(!RecordingPlayback.hasPendingStart())
            }
            cleanup {
                val queued = AppFeedbackCenter.events.value
                val unexpectedOwner = queued.any { it.id != ownedFeedbackId }
                if (queued.firstOrNull()?.id == ownedFeedbackId) {
                    ownedFeedbackId?.let(AppFeedbackCenter::acknowledge)
                }
                check(!unexpectedOwner) { "Foreign feedback retained instead of clearing it" }
                check(AppFeedbackCenter.events.value.isEmpty())
            }
        }
        primary?.let { throw it }
    }

    private class PlatformStartContext(base: Context) : ContextWrapper(base) {
        val starts = mutableListOf<Intent>()
        var result: ComponentName? = null
        var denied: SecurityException? = null
        var pendingObservedAtBoundary = false
        override fun getApplicationContext(): Context = this
        override fun startForegroundService(service: Intent): ComponentName? {
            starts += Intent(service)
            pendingObservedAtBoundary = RecordingPlayback.hasPendingStart()
            denied?.let { throw it }
            return result
        }
    }
}
