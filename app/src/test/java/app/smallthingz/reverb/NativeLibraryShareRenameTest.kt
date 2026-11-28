package app.smallthingz.reverb

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.MediaStore
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowLooper
import smallthingz.ui.runtime.MonotonicFrameClock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class NativeLibraryShareRenameTest {
    @Test fun acceptedRenameRetainsEditorAfterOldShareJobTerminates() = Fixture().use { f ->
        val share = f.beginShare()
        f.controller.rename(f.otherRecording)
        f.publish()
        assertEquals(f.otherRecording, f.latest.renameRecording)

        f.finishShare(share)
        assertTrue(share.isCancelled)
        assertEquals(f.otherRecording, f.latest.renameRecording)
        assertTrue(f.controller.navigationAllowed())
        assertNull(f.takeStartedActivity())
        assertTrue(f.newFeedback.isEmpty())
    }

    @Test fun rejectedRenameKeepsPendingShareEligibleForChooser() = Fixture().use { f ->
        f.controller.activate(f.otherRecording)
        val share = f.beginShare()
        f.controller.localBusy(f.otherRecording.id, true)
        f.publish()
        val expanded = checkNotNull(f.latest.list.expandedOwner)
        assertEquals(f.otherRecording.id, expanded.recordingId)

        f.controller.rename(f.otherRecording)
        f.publish()
        assertNull(f.latest.renameRecording)
        assertTrue(expanded === f.latest.list.expandedOwner)

        f.finishShare(share)
        assertFalse(share.isCancelled)
        assertNull(f.latest.renameRecording)
        assertTrue(expanded === f.latest.list.expandedOwner)
        f.assertChooser()
        assertTrue(f.newFeedback.isEmpty())
    }

    @Test fun ordinaryShareDeliversChooser() = Fixture().use { f ->
        val share = f.beginShare()
        f.finishShare(share)
        assertFalse(share.isCancelled)
        f.assertChooser()
        assertTrue(f.newFeedback.isEmpty())
    }

    @Test fun ordinaryShareReportsUnavailableRecording() = Fixture(available = false).use { f ->
        val share = f.beginShare()
        f.finishShare(share)
        assertFalse(share.isCancelled)
        assertNull(f.takeStartedActivity())
        val feedback = f.newFeedback.single()
        assertEquals(FeedbackTone.ERROR, feedback.tone)
        assertTrue(feedback.message.startsWith(f.activity.getString(R.string.share_recording_failed)))
    }

    @Test fun acceptedRenameDiscardsShareFailureParkedWithoutFocus() = Fixture(available = false).use { f ->
        val share = f.beginShare()
        f.windowFocused = false
        assertFalse(f.controller.navigationAllowed())
        f.finishShare(share)
        assertFalse(share.isCancelled)
        assertNull(f.takeStartedActivity())
        assertTrue(f.newFeedback.isEmpty())

        f.controller.rename(f.otherRecording)
        f.publish()
        assertEquals(f.otherRecording, f.latest.renameRecording)
        f.renameEditor().use { editor ->
            f.windowFocused = true
            editor.editName("My recording draft")
            f.dispatchWindowFocus()
            f.publish()
            assertEquals(f.otherRecording, f.latest.renameRecording)
            assertEquals("My recording draft", editor.state.name)
            assertNull(f.takeStartedActivity())
            assertTrue(f.newFeedback.isEmpty())
        }
    }

    @Test fun pausingStillReportsShareFailureParkedWithoutFocus() = Fixture(available = false).use { f ->
        val share = f.beginShare()
        f.windowFocused = false
        f.finishShare(share)
        assertTrue(f.newFeedback.isEmpty())

        f.lifecycle.currentState = Lifecycle.State.STARTED
        f.publish()
        assertNull(f.takeStartedActivity())
        val feedback = f.newFeedback.single()
        assertEquals(FeedbackTone.ERROR, feedback.tone)
        assertTrue(feedback.message.startsWith(f.activity.getString(R.string.share_recording_failed)))
    }

    private class Fixture(available: Boolean = true) : LifecycleOwner, AutoCloseable {
        override val lifecycle = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
        private val activityOwner = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val activity: Activity = activityOwner.get()
        var windowFocused = true
        private val host = object : View(activity) {
            override fun hasWindowFocus() = windowFocused
        }
        private val tasks = LinkedBlockingQueue<Runnable>()
        private val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                tasks.add(block)
            }
        }
        private val parent = Job()
        private val scope = CoroutineScope(parent + dispatcher)
        private val provider = DelayedShareProvider(available)
        private val firstFrame = CountDownLatch(1)
        private val priorFeedbackIds = AppFeedbackCenter.events.value.map { it.id }.toSet()
        val newFeedback get() = AppFeedbackCenter.events.value.filterNot { it.id in priorFeedbackIds }
        private val recording = RecordingEntity(
            provider.uri.toString(), "share.wav", "audio/wav", 1L, 1000L, SIZE, "PCM",
            RecordingStorageType.MEDIASTORE, MEDIA_STORE_DIRECTORY_ID,
            providerRecordingIdentity(RecordingStorageType.MEDIASTORE, provider.uri.toString(), SIZE, GENERATION),
        )
        val otherRecording = recording.copy(
            id = "content://media/external/audio/media/731953",
            displayName = "rename.wav",
            fileIdentity = providerRecordingIdentity(
                RecordingStorageType.MEDIASTORE, "content://media/external/audio/media/731953", SIZE, GENERATION,
            ),
        )
        lateinit var latest: NativeLibraryPresentation
            private set
        val controller: NativeLibraryController

        init {
            activity.setContentView(host)
            provider.attachInfo(activity, ProviderInfo().apply { authority = "media" })
            ShadowContentResolver.registerProviderInternal("media", provider)
            controller = NativeLibraryController(
                activity, host, this, scope,
                object : MonotonicFrameClock {
                    override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
                        firstFrame.countDown()
                        awaitCancellation()
                    }
                },
                null, NativeLibraryInput(true, listOf(recording, otherRecording), NativeLibraryCallbacks()),
                playbackState = MutableStateFlow(RecordingPlaybackState()),
                publish = { latest = it },
            )
        }

        fun beginShare(): Job {
            // Let the real catalog read settle, then hold its first frame to keep scans out of this test.
            runUntil("Library's first frame") { firstFrame.count == 0L }
            assertTrue(controller.navigationAllowed())
            controller.share(recording)
            // Capture the exact job before Rename can clear the slot. A null slot alone proves no delivery.
            val share = (NativeLibraryController::class.java.getDeclaredField("shareJob")
                .apply { isAccessible = true }.get(controller) as Array<*>).single() as Job
            publish()
            assertTrue("Share must reach the provider", provider.entered.await(5, TimeUnit.SECONDS))
            assertFalse(share.isCompleted)
            assertNull(takeStartedActivity())
            return share
        }

        fun finishShare(share: Job) {
            provider.release.countDown()
            runUntil("old Share job completion") { share.isCompleted }
            assertTrue("The provider query must return and its cursor must close", provider.cursor?.isClosed == true)
        }

        fun takeStartedActivity(): Intent? = shadowOf(activity).nextStartedActivity

        fun renameEditor() = NativeRecordingRenameOwner(
            activity, checkNotNull(latest.renameRecording), true, this, scope,
            NativeRecordingRenameCallbacks(
                controller::dismissRename,
                { renamed -> controller.renamed(otherRecording, renamed) },
                controller::renameUncertain,
            ),
            acceptsInput = controller::navigationAllowed,
            publish = {},
        )

        fun dispatchWindowFocus() {
            host.viewTreeObserver.javaClass
                .getDeclaredMethod("dispatchOnWindowFocusChange", java.lang.Boolean.TYPE)
                .apply { isAccessible = true }
                .invoke(host.viewTreeObserver, windowFocused)
            assertTrue(controller.navigationAllowed())
        }

        fun assertChooser() {
            val chooser = checkNotNull(takeStartedActivity()) { "Expected the Share chooser" }
            assertEquals(Intent.ACTION_CHOOSER, chooser.action)
            val send = checkNotNull(chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java))
            assertEquals(Intent.ACTION_SEND, send.action)
            assertEquals(recording.mimeType, send.type)
            val stream = checkNotNull(send.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
            assertEquals(recording.id, decodeVerifiedProviderPathSegments(stream.pathSegments)?.sourceId)
            assertNull(takeStartedActivity())
        }

        fun publish() {
            repeat(20) {
                while (true) (tasks.poll() ?: break).run()
                ShadowLooper.idleMainLooper()
                if (tasks.isEmpty()) return
            }
            error("Library controller did not settle within twenty dispatch turns")
        }

        private fun runUntil(boundary: String, reached: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (true) {
                publish()
                if (reached()) return
                check(System.nanoTime() < deadline) { "Timed out waiting for $boundary" }
                tasks.poll(10, TimeUnit.MILLISECONDS)?.run()
            }
        }

        override fun close() {
            provider.release.countDown()
            try {
                controller.close()
            } finally {
                parent.cancel()
                try {
                    runUntil("controller teardown") { parent.isCompleted }
                } finally {
                    try {
                        RecordingDatabase.resetAfterCorruption()
                    } finally {
                        newFeedback.forEach { AppFeedbackCenter.acknowledge(it.id) }
                        activityOwner.pause().stop().destroy()
                    }
                }
            }
        }
    }

    private class DelayedShareProvider(private val available: Boolean) : ContentProvider() {
        val uri: Uri = Uri.parse("content://media/external/audio/media/731952")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        @Volatile var cursor: MatrixCursor? = null
            private set

        override fun onCreate() = true

        override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
            check(uri == this.uri)
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS)) { "Share provider was never released" }
            val columns = requireNotNull(projection)
            val values = mapOf(
                MediaStore.MediaColumns.SIZE to SIZE,
                MediaStore.MediaColumns.DATE_MODIFIED to 100L,
                MediaStore.MediaColumns.GENERATION_MODIFIED to GENERATION,
                MediaStore.MediaColumns.RELATIVE_PATH to "Music/Reverb/",
            )
            return MatrixCursor(columns).also {
                if (available) it.addRow(columns.map(values::getValue))
                cursor = it
            }
        }

        override fun getType(uri: Uri) = "audio/wav"
        override fun insert(uri: Uri, values: ContentValues?): Uri? = error("Read-only test provider")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
            error("Read-only test provider")
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
            error("Read-only test provider")
    }

    private companion object {
        const val SIZE = 32_044L
        const val GENERATION = 9L
    }
}
