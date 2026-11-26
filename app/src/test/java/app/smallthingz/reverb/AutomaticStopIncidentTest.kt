package app.smallthingz.reverb

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowActivityManager
import org.robolectric.shadows.ShadowProcess
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [JournalHostIoShadow::class],
    instrumentedPackages = ["app.smallthingz.reverb.automaticstopisolated"])
@LooperMode(LooperMode.Mode.PAUSED)
class AutomaticStopIncidentTest {
    class StopService : ReverbService() {
        internal var incidentContext: Context? = null
        override fun getApplicationContext(): Context = incidentContext ?: super.getApplicationContext()

        internal lateinit var recorderPreferences: SharedPreferences
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
            if (::recorderPreferences.isInitialized && name == packageName) recorderPreferences
            else super.getSharedPreferences(name, mode)
    }

    @Test fun failedStopCommitWithSuccessfulRollbackRetiresInterruptedSession() = withArmedService {
        stopWithSuccessfulRollback()
        assertRetiredAcrossIdleExit(AUTOMATIC_STOP_CAUSE)
    }

    @Test fun failedHistoryAppendPreservesAutomaticStopCauseAndTimeAcrossTimeout() = withArmedService {
        assertAutomaticStopPrecedesTimeout(history)
    }

    @Test fun failedInitialMarkerProofPreservesAutomaticStopCauseAndTimeAcrossTimeout() = withArmedService {
        assertAutomaticStopPrecedesTimeout(session)
    }

    @Test fun unreadableStopReservationRetriesAndRetiresInterruptedSession() = withArmedService {
        stopWhileMarkerIsUnreadable()
        assertRetiredAcrossIdleExit("Capture stopped because known-Stop incident state could not be persisted")
    }

    @Test fun unreadableStopReservationCannotRetargetRetryToAnotherArmInTheSameProcess() = withArmedService {
        stopWhileMarkerIsUnreadable()
        // Change only arm identity. A retry scoped merely to PID/process start would claim this marker.
        val successor = armed.copy(armedAtMillis = armed.armedAtMillis + 1,
            armedElapsedRealtimeMillis = armed.armedElapsedRealtimeMillis + 1)
        // A later arm on this boot cannot have an uptime ahead of the paused device clock.
        val remainingMillis = successor.armedElapsedRealtimeMillis - SystemClock.elapsedRealtime()
        ShadowSystemClock.advanceBy(remainingMillis.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
        assertTrue(SystemClock.elapsedRealtime() >= successor.armedElapsedRealtimeMillis)
        DataOutputStream(session.outputStream()).use { RecordingIncidentStore.writeSessionPayload(it, successor) }
        repeat(2) {
            val cards = RecordingIncidentStore.readIncidents(service)
            assertTrue(cards.size <= 1 && cards.all { it.captureArmedAtMillis == armed.armedAtMillis })
            val current = DataInputStream(session.inputStream()).use(RecordingIncidentStore::readSessionPayload)
            assertEquals(successor, current)
        }
    }

    private class Fixture(val service: ReverbService, val preferences: StopPreferences, token: KnownCaptureStopToken) {
        val armed = requireNotNull(token.marker)
        val session = File(token.root, "recording-session.bin")
        private val pending = File(token.root, "recording-incident-pending.bin")
        val history = File(token.root, "recording-incidents.bin")

        fun stop() {
            val automaticStop = ReverbService::class.java.getDeclaredMethod("pauseListeningForNoWritableBufferOnAudioThread",
                Long::class.javaPrimitiveType).apply { isAccessible = true }
            // Capture this Service's error without borrowing the process feedback queue.
            val errors = field("recorderErrors").get(service) as RecorderErrorState
            errors.captureCommand { automaticStop.invoke(service, 0L) }
            assertEquals(ReverbService.STATE_PAUSED, field("state").getInt(service))
        }

        fun stopWithSuccessfulRollback() {
            preferences.rejectStop = true
            stop()
            assertEquals(listOf(false to false, true to true), preferences.commits)
            assertEquals(true, preferences.getAll()[PrefKey.AUDIO_MEMORY_ENABLED.name])
        }

        fun assertAutomaticStopPrecedesTimeout(blockedFile: File) {
            advancePast(armed.armedAtMillis)
            val blocker = File(blockedFile.path + ".new")
            check(blocker.mkdir())
            val stopWindow = try {
                val before = System.currentTimeMillis()
                stopWithSuccessfulRollback()
                before..System.currentTimeMillis()
            } finally {
                check(blocker.delete())
            }
            // Inspect bytes directly. A store history read here would settle the automatic Stop early.
            assertFalse(history.exists())
            assertEquals(armed, DataInputStream(session.inputStream()).use(RecordingIncidentStore::readSessionPayload))
            advancePast(stopWindow.last)
            service.onTimeout(1, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            assertRetiredAcrossIdleExit(AUTOMATIC_STOP_CAUSE, stopWindow)
        }

        private fun advancePast(boundary: Long) {
            ShadowSystemClock.advanceBy(1L, TimeUnit.SECONDS)
            // The host wall clock can be independent of the shadow elapsed clock.
            if (System.currentTimeMillis() <= boundary) Thread.sleep(5L)
            check(System.currentTimeMillis() > boundary)
        }

        fun stopWhileMarkerIsUnreadable() {
            val blocker = File(session.path + ".new")
            check(blocker.mkdir())
            try {
                stop()
                assertEquals(listOf(false to true), preferences.commits)
                assertEquals(false, preferences.getAll()[PrefKey.AUDIO_MEMORY_ENABLED.name])
                assertThrows(IOException::class.java) { RecordingIncidentStore.readIncidents(service) }
                assertEquals(armed, DataInputStream(session.inputStream()).use(RecordingIncidentStore::readSessionPayload))
            } finally {
                check(blocker.delete())
            }
        }

        fun interruption(): RecordingIncident = RecordingIncidentStore.readIncidents(service).single().also {
            assertEquals(RecordingIncidentKind.UNEXPECTED_SHUTDOWN, it.kind)
            assertEquals(armed.pid, it.pid)
            assertEquals(armed.armedAtMillis, it.captureArmedAtMillis)
            assertEquals(recordingSessionProcessStartedAtMillis(armed), it.processStartedAtMillis)
            assertEquals(ApplicationExitInfo.REASON_UNKNOWN, it.exitReason)
        }

        fun assertRetiredAcrossIdleExit(description: String, stopWindow: LongRange? = null) {
            val incident = interruption()
            assertEquals(description, incident.description)
            if (stopWindow != null) {
                assertTrue("Expected outage in $stopWindow, got ${incident.occurredAtMillis}",
                    incident.occurredAtMillis in stopWindow)
            }
            assertEquals(incident, interruption())
            // readIncidents runs same-process retries and removes a durably disarmed marker.
            assertFalse(session.exists())
            assertFalse(pending.exists())
            fun onDisk() = DataInputStream(history.inputStream()).use(RecordingIncidentStore::readHistoryPayload)
            assertEquals(listOf(incident), onDisk())

            val manager = requireNotNull(service.applicationContext.getSystemService(ActivityManager::class.java))
            val at = incident.occurredAtMillis + 1_000L
            val exit = ShadowActivityManager.ApplicationExitInfoBuilder.newBuilder()
                .setPid(armed.pid).setProcessName(service.packageName).setTimestamp(at)
                .setReason(ApplicationExitInfo.REASON_LOW_MEMORY).setDescription("idle eviction").build()
            Shadow.extract<ShadowActivityManager>(manager).addApplicationExitInfo(exit)
            ShadowProcess.setPid(armed.pid + 1)
            repeat(2) { RecordingIncidentStore.recoverPriorSessionIfNeeded(service) }
            assertEquals(listOf(incident), onDisk())
            assertFalse(pending.exists())

            // Positive control: the same Android exit enriches this exact arm if recovery still owns it.
            val controlRoot = File(session.parentFile, "control").apply { check(mkdir()) }
            val control = object : ContextWrapper(service.applicationContext) {
                override fun getApplicationContext(): Context = this
                override fun getNoBackupFilesDir(): File = controlRoot
            }
            val controlHistory = history.copyTo(File(controlRoot, history.name))
            DataOutputStream(File(controlRoot, pending.name).outputStream()).use {
                RecordingIncidentStore.writePendingSessionsPayload(it, listOf(PendingRecordingSession(armed, endedByMillis = at)))
            }
            RecordingIncidentStore.recoverPriorSessionIfNeeded(control)
            val enriched = DataInputStream(controlHistory.inputStream()).use(RecordingIncidentStore::readHistoryPayload).single()
            assertEquals(armed.armedAtMillis, enriched.captureArmedAtMillis)
            assertEquals(ApplicationExitInfo.REASON_LOW_MEMORY, enriched.exitReason)
        }
    }

    /** The failed commit changes the process view while the backing preference retains its old value. */
    private class StopPreferences(private val backing: SharedPreferences) : SharedPreferences by backing {
        var rejectStop = false
        val commits = mutableListOf<Pair<Boolean, Boolean>>()
        private val enabled = PrefKey.AUDIO_MEMORY_ENABLED.name
        private var visibleOverride: Boolean? = null

        override fun getAll(): Map<String, *> = visibleOverride?.let { backing.all + (enabled to it) } ?: backing.all
        override fun getBoolean(key: String?, defValue: Boolean): Boolean =
            (if (key == enabled) visibleOverride else null) ?: backing.getBoolean(key, defValue)

        override fun edit(): SharedPreferences.Editor {
            val editor = backing.edit()
            return object : SharedPreferences.Editor by editor {
                private var listening: Boolean? = null
                override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = apply {
                    if (key == enabled) listening = value
                    editor.putBoolean(key, value)
                }
                override fun remove(key: String?): SharedPreferences.Editor = apply { editor.remove(key) }
                override fun commit(): Boolean {
                    val value = listening ?: return editor.commit()
                    val success = if (!value && rejectStop) {
                        visibleOverride = false
                        false
                    } else {
                        editor.commit().also { if (it) visibleOverride = null }
                    }
                    commits += value to success
                    return success
                }
            }
        }
    }

    private fun withArmedService(test: Fixture.() -> Unit) {
        val app = RuntimeEnvironment.getApplication()
        check(ServiceStateOsShadow.fixtureRoot == null)
        val previousPid = Process.myPid()
        // postCaptureWork posts only to this private Handler while captureIo is uninitialized.
        // Removing its callbacks never idles or clears another Handler on the shared main Looper.
        val audio = Handler(Looper.getMainLooper())
        val bootController = BootResumeNotification::class.java.getDeclaredField("controller").apply { isAccessible = true }
        check(bootController.get(BootResumeNotification) == null)
        JournalHostIoShadow.reset()
        val appRoot = requireNotNull(app.filesDir.canonicalFile.parentFile)
        val root = Files.createTempDirectory(app.noBackupFilesDir.toPath(), "automatic-stop-").toFile()
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = root
        }
        ServiceStateOsShadow.fixtureRoot = appRoot.toPath()
        try {
            val backing = app.getSharedPreferences(root.name, Context.MODE_PRIVATE)
            check(backing.edit().putBoolean(PrefKey.AUDIO_MEMORY_ENABLED, true).commit())
            val prefs = StopPreferences(backing)
            val service = Robolectric.buildService(StopService::class.java).get()
            service.incidentContext = context
            service.recorderPreferences = prefs
            ShadowProcess.setPid(4242)
            ShadowSystemClock.advanceBy((Process.getStartElapsedRealtime() - SystemClock.elapsedRealtime())
                .coerceAtLeast(0L), TimeUnit.MILLISECONDS)
            field("audioHandler").set(service, audio)
            field("state").setInt(service, ReverbService.STATE_LISTENING)
            field("durableListeningIntentEnabled").setBoolean(service, true)
            field("durableCaptureIntentAuthorityValid").setBoolean(service, true)
            val arm = ReverbService::class.java.getDeclaredMethod("armCaptureIncidentTrackingOnAudioThread",
                Boolean::class.javaPrimitiveType).apply { isAccessible = true }
            assertEquals(true, arm.invoke(service, false))
            val token = field("armedCaptureSession").get(service) as KnownCaptureStopToken
            assertTrue(token.armed)
            Fixture(service, prefs, token).test()
        } finally {
            audio.removeCallbacksAndMessages(null)
            try {
                // A committed automatic Stop can queue boot-notice dismissal with this context.
                if (bootController.get(BootResumeNotification) != null) {
                    val worker = BootResumeNotification::class.java.getDeclaredField("worker")
                        .apply { isAccessible = true }.get(BootResumeNotification) as ExecutorService
                    worker.submit {}.get(5, TimeUnit.SECONDS)
                    bootController.set(BootResumeNotification, null)
                }
                // Fault blockers have been removed in their own finally blocks. Settle this
                // root's publication/retry owners even if an assertion interrupted recovery.
                ShadowProcess.setPid(4242)
                RecordingIncidentStore.readIncidents(context)
                check(root.deleteRecursively())
                check(app.deleteSharedPreferences(root.name))
            } finally {
                ShadowProcess.setPid(previousPid)
                ServiceStateOsShadow.fixtureRoot = null
                JournalHostIoShadow.finishFixture()?.let { throw it }
            }
        }
    }

    private companion object {
        const val AUTOMATIC_STOP_CAUSE = "Capture stopped because automatic-stop persistence failed"
        fun field(name: String) = ReverbService::class.java.getDeclaredField(name).apply { isAccessible = true }
    }
}
