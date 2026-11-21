package app.smallthingz.reverb

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.pm.ServiceInfo
import android.media.AudioRecord
import java.io.IOException
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowService
import org.robolectric.shadow.api.Shadow

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class CapturePolicyNotificationServiceTest {
    @Test fun onlyCurrentCallbackStateUpdatesTheExistingRecordingNotification() = withService { fixture ->
        val input = fixture.install(snapshotSilenced = true)
        val first = fixture.assertNotification(R.string.recording_microphone_status_unavailable)
        input.completeInitialQuery()
        assertEquals(listOf(1L), input.history)
        assertSame(first, fixture.assertNotification(R.string.recording_microphone_status_unavailable))

        input.emit(false)
        val recording = fixture.assertNotification(R.string.quick_tile_recording)
        input.emit(true)
        input.emit(false)
        assertSame(recording, fixture.assertNotification(R.string.quick_tile_recording))

        input.emit(true)
        val muted = fixture.assertNotification(R.string.recording_microphone_unavailable)
        assertTrue(muted.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        input.emit(true)
        assertSame(muted, fixture.assertNotification(R.string.recording_microphone_unavailable))

        input.emit(null)
        fixture.assertNotification(R.string.recording_microphone_status_unavailable)
        input.emit(true)
        fixture.assertNotification(R.string.recording_microphone_unavailable)
        input.fail()
        fixture.assertNotification(R.string.recording_microphone_status_unavailable)
        input.emit(false)
        fixture.assertNotification(R.string.recording_microphone_status_unavailable)

        assertSame(first, fixture.serviceShadow.lastForegroundNotification)
        assertEquals(MICROPHONE, fixture.service.foregroundServiceType)
        assertFalse(fixture.serviceShadow.isStoppedBySelf)
        assertEquals(true, fixture.field("durableListeningIntentEnabled").get(fixture.service))
        assertEquals(ReverbService.STATE_LISTENING, fixture.field("state").get(fixture.service))
    }

    @Test fun releasingAndReplacingTheInputRetiresOldQueuedPolicyState() = withService { fixture ->
        val old = fixture.install()
        old.emit(true)
        fixture.assertNotification(R.string.recording_microphone_unavailable)
        old.emit(false)
        fixture.release()
        fixture.assertNotification(R.string.recording_microphone_status_unavailable)

        val replacement = fixture.install()
        replacement.emit(true)
        val muted = fixture.assertNotification(R.string.recording_microphone_unavailable)
        old.emit(false)
        old.deliverStaleWake()
        assertSame(muted, fixture.assertNotification(R.string.recording_microphone_unavailable))

        replacement.emit(false)
        fixture.stopForeground()
        assertNull(fixture.notification())
        assertTrue(fixture.serviceShadow.isForegroundStopped)
    }

    @Test fun queuedPolicyChangeCannotOverwriteAnotherForegroundOperation() = withService { fixture ->
        val input = fixture.install()
        input.emit(true)
        fixture.promote(MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, exporting = true)
        val saving = fixture.assertNotification(R.string.saving, hasQuickSave = false)
        input.emit(false)
        input.emit(true)
        assertSame(saving, fixture.assertNotification(R.string.saving, hasQuickSave = false))

        fixture.promote(MICROPHONE)
        fixture.assertNotification(R.string.recording_microphone_unavailable)
        input.emit(false)
        fixture.assertNotification(R.string.quick_tile_recording)
    }

    private fun withService(test: (Fixture) -> Unit) {
        val app = RuntimeEnvironment.getApplication()
        val manager = checkNotNull(app.getSystemService(NotificationManager::class.java))
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(manager).setNotificationsEnabled(true)
        ReverbNotificationChannels.ensure(app, ReverbNotificationChannel.BACKGROUND)
        // Attach Android context without starting storage, capture or recovery workers.
        val fixture = Fixture(Robolectric.buildService(ReverbService::class.java).get(), manager)
        fixture.field("durableCaptureIntentAuthorityValid").set(fixture.service, true)
        fixture.field("durableListeningIntentEnabled").set(fixture.service, true)
        fixture.field("state").set(fixture.service, ReverbService.STATE_LISTENING)
        fixture.promote(MICROPHONE)
        try {
            test(fixture)
        } finally {
            fixture.release()
            fixture.stopForeground()
            ShadowLooper.idleMainLooper()
        }
    }

    private class Fixture(val service: ReverbService, private val manager: NotificationManager) {
        val serviceShadow: ShadowService = Shadow.extract(service)

        fun field(name: String) = ReverbService::class.java.getDeclaredField(name).apply { isAccessible = true }

        fun promote(types: Int, exporting: Boolean = false) {
            val booleanType = Boolean::class.javaPrimitiveType
            ReverbService::class.java.getDeclaredMethod("promoteForeground",
                Int::class.javaPrimitiveType, booleanType, booleanType, booleanType)
                .apply { isAccessible = true }.invoke(service, types, exporting, false, false)
        }

        fun install(snapshotSilenced: Boolean? = null): ObservedInput {
            val input = ObservedInput(snapshotSilenced)
            field("audioRecord").set(service, input)
            for (method in listOf("registerCapturePolicyObservation", "activateCapturePolicyObservation")) {
                ReverbService::class.java.getDeclaredMethod(method, CaptureAudioInput::class.java)
                    .apply { isAccessible = true }.invoke(service, input)
            }
            return input
        }

        fun release() {
            ReverbService::class.java.getDeclaredMethod("releaseAudioRecord")
                .apply { isAccessible = true }.invoke(service)
        }

        fun stopForeground() {
            ReverbService::class.java.getDeclaredMethod("stopForegroundTracked")
                .apply { isAccessible = true }.invoke(service)
        }

        fun notification(): Notification? {
            ShadowLooper.idleMainLooper()
            return manager.activeNotifications.singleOrNull { it.id == ReverbService.FOREGROUND_NOTIFICATION_ID }
                ?.notification
        }

        fun assertNotification(text: Int, hasQuickSave: Boolean = true): Notification = checkNotNull(notification()).also {
            assertEquals(service.getString(text), it.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
            assertEquals(service.getString(R.string.app_name), it.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
            assertEquals(if (hasQuickSave) listOf(service.getString(R.string.quick_save)) else emptyList(),
                it.actions.orEmpty().map { action -> action.title.toString() })
        }
    }

    private class ObservedInput(private val snapshotSilenced: Boolean?) : CaptureAudioInput {
        override val state = AudioRecord.STATE_INITIALIZED
        override val recordingState = AudioRecord.RECORDSTATE_RECORDING
        val history = mutableListOf<Long>()
        private lateinit var witness: CapturePolicyWitnesses
        private lateinit var wake: () -> Unit
        private var work: (() -> Unit)? = null

        override fun observeClientSilencing(
            onState: (Boolean?, Long) -> Unit,
            onFailure: (Exception) -> Unit,
            onCallbackStateChanged: () -> Unit,
            onDropoutsAvailable: () -> Unit,
        ): CapturePolicySubscription {
            wake = onCallbackStateChanged
            // Keep this fixture focused on notification delivery. History has its own isolated storage tests.
            return CapturePolicyWitnesses(execute = { work = it }, snapshot = { snapshotSilenced to 1L },
                deliver = history::add, failed = {},
                callbackStateChanged = wake).also { witness = it }
        }

        fun completeInitialQuery() { work?.invoke(); work = null }
        fun emit(silenced: Boolean?) = witness.observe(silenced, 2L)
        fun fail() = witness.report(IOException("policy observer failed"))
        fun deliverStaleWake() = wake()
        override fun startRecording(): Unit = error("Notification fixture cannot start native input")
        override fun read(buffer: ByteBuffer, sizeInBytes: Int, readMode: Int): Int = error("Unexpected PCM read")
        override fun stop() = Unit
        override fun release() = Unit
    }

    private companion object {
        const val MICROPHONE = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
    }
}
