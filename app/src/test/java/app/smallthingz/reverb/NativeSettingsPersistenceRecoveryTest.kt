package app.smallthingz.reverb

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.widget.FrameLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class,
    shadows = [JournalHostIoShadow::class, SettingsPublicationEnvironmentShadow::class])
class NativeSettingsPersistenceRecoveryTest {
    @Test fun malformedSilenceStringSurvivesUnrelatedSaveAndCanBeReplaced() =
        malformedSilenceCanBeReplaced("2;1;broken;0")

    @Test fun wrongTypedSilenceValueSurvivesUnrelatedSaveAndCanBeReplaced() =
        malformedSilenceCanBeReplaced(17)

    private fun malformedSilenceCanBeReplaced(raw: Any) = runBlocking {
        Fixture(this).use { fixture ->
            val edit = fixture.preferences.edit()
            when (raw) {
                is String -> edit.putString(PrefKey.SILENCE_GATE, raw)
                is Int -> edit.putInt(PrefKey.SILENCE_GATE, raw)
                else -> error("Unexpected fixture value")
            }
            assertTrue(edit.commit())
            fixture.open()
            val owner = fixture.owner
            val disabled = SilenceGateConfig()
            assertEquals(disabled, decodeSilenceGate(owner.snapshot.silenceGate))
            assertTrue(owner.themeChanged(owner.state.activation, AppThemeMode.DARK))
            fixture.done()
            assertEquals(1, fixture.navigations)
            assertEquals(AppThemeMode.DARK, getConfiguredThemeMode(fixture.context))
            assertEquals(raw, fixture.preferences.all[PrefKey.SILENCE_GATE.name])

            fixture.open()
            val replacement = disabled.copy(clearanceDb = 9f)
            fixture.editSilence(disabled.copy(enabled = true))
            fixture.editSilence(replacement.copy(enabled = true))
            fixture.editSilence(replacement)
            fixture.done()
            assertEquals(2, fixture.navigations)
            assertEquals(replacement, SilenceGatePreferences.read(fixture.context).config)
            fixture.open()
            assertEquals(replacement, decodeSilenceGate(fixture.owner.snapshot.silenceGate))
        }
    }

    @Test fun thrownRetentionReadLeavesDoneDraftAvailableForRetry() = runBlocking {
        Fixture(this).use { fixture ->
            assertTrue(fixture.preferences.edit().putBoolean(PrefKey.AUDIO_MEMORY_ENABLED, true).commit())
            fixture.open()
            val owner = fixture.owner
            val previous = fixture.preferences.all.toMap()
            val silenceRevision = SilenceGatePreferences.revision.value
            val pendingFeedback = AppFeedbackCenter.events.value.map { it.id }.toSet()
            assertTrue(owner.themeChanged(owner.state.activation, AppThemeMode.DARK))
            fixture.context.retentionReadFailure.set(IOException("fixture retention preflight failure"))

            fixture.done()

            assertEquals(0, fixture.navigations)
            assertEquals(previous, fixture.preferences.all)
            assertEquals(silenceRevision, SilenceGatePreferences.revision.value)
            assertEquals(emptyList<String>(), fixture.context.serviceActions)
            assertFalse(File(fixture.root, "retention-config.v1").exists())
            assertTrue(owner.state.hasUnsavedChanges)
            assertEquals(AppThemeMode.DARK, owner.snapshot.themeMode)
            val feedback = AppFeedbackCenter.events.value.filterNot { it.id in pendingFeedback }.single()
            assertEquals(FeedbackTone.ERROR, feedback.tone)
            assertTrue(feedback.message.contains("fixture retention preflight failure"))

            fixture.done()

            assertEquals(1, fixture.navigations)
            assertEquals(AppThemeMode.DARK, getConfiguredThemeMode(fixture.context))
            assertTrue(File(fixture.root, "retention-config.v1").isFile)
            assertEquals(listOf(ReverbService.ACTION_APPLY_SETTINGS), fixture.context.serviceActions)
        }
    }

    @Test fun replacingMalformedSilenceCannotOverwriteANewerWrongTypedValue() = runBlocking {
        Fixture(this).use { fixture ->
            assertTrue(fixture.preferences.edit().putInt(PrefKey.SILENCE_GATE, 17).commit())
            fixture.open()
            fixture.editSilence(SilenceGateConfig(enabled = true))
            assertTrue(fixture.preferences.edit().putInt(PrefKey.SILENCE_GATE, 19).commit())

            fixture.done()

            assertEquals(0, fixture.navigations)
            assertEquals(19, fixture.preferences.all[PrefKey.SILENCE_GATE.name])
            assertTrue(fixture.owner.state.hasUnsavedChanges)
            assertFalse(File(fixture.root, "retention-config.v1").exists())
        }
    }

    private class FixtureContext(base: Context, private val root: File) : ContextWrapper(base) {
        val retentionReadFailure = AtomicReference<IOException?>()
        val serviceActions = mutableListOf<String?>()

        override fun getApplicationContext(): Context = this

        override fun getNoBackupFilesDir(): File {
            retentionReadFailure.getAndSet(null)?.let { throw it }
            return root
        }

        override fun bindService(service: Intent, connection: ServiceConnection, flags: Int): Boolean = false

        override fun startService(service: Intent): ComponentName {
            serviceActions += service.action
            return checkNotNull(service.component)
        }
    }

    private class Fixture(private val scope: CoroutineScope) : LifecycleOwner, AutoCloseable {
        private val databaseSingleton = RecordingDatabase::class.java.getDeclaredField("instance").apply {
            isAccessible = true
        }
        private val previousDatabase = databaseSingleton.get(null)
        private val previousFeedback = AppFeedbackCenter.events.value.toList()
        private val previousShadowRoot = ServiceStateOsShadow.fixtureRoot
        private val activity = Robolectric.buildActivity(Activity::class.java).setup().visible()
        override val lifecycle = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
        val root = Files.createTempDirectory("settings-persistence-recovery-").toFile()
        val context = FixtureContext(activity.get(), root)
        val preferences = getRecorderPreferences(context)
        private val window = object : FrameLayout(context) {
            override fun hasWindowFocus(): Boolean = true
        }.also { activity.get().setContentView(it) }
        private var activeOwner: NativeSettingsOwner? = null
        private val settingsJobs = mutableListOf<Job>()
        private var ownsShadowRoot = false
        private var observedPersistence = false
        val owner get() = checkNotNull(activeOwner)
        var navigations = 0
            private set

        init {
            try {
                JournalHostIoShadow.reset()
                ServiceStateOsShadow.fixtureRoot = root.toPath()
                ownsShadowRoot = true
                assertTrue(preferences.edit().clear().commit())
                assertTrue(window.isAttachedToWindow)
            } catch (failure: Throwable) {
                try { close() } catch (cleanup: Throwable) {
                    if (cleanup !== failure) failure.addSuppressed(cleanup)
                }
                throw failure
            }
        }

        suspend fun open() {
            activeOwner?.close()
            activeOwner = NativeSettingsOwner(context, window, lifecycle, scope, true,
                isPanelActive = { true }, onThemePreview = {}, releaseInputFocus = {},
                launchDirectory = { error("Unexpected directory launch") }, onBack = { navigations++ })
            settingsJobs += checkNotNull(owner.scope.coroutineContext[Job])
            owner.start { if (owner.state.persisting) observedPersistence = true }
            await { owner.state.interactionReady }
        }

        fun editSilence(next: SilenceGateConfig) {
            val state = owner.state
            owner.silenceChanged(state.activation, decodeSilenceGate(owner.snapshot.silenceGate), state.editRevision, next)
            assertEquals(next, decodeSilenceGate(owner.snapshot.silenceGate))
        }

        suspend fun done() {
            assertTrue(owner.state.hasUnsavedChanges)
            assertFalse(owner.settingsCommitBlocked)
            observedPersistence = false
            owner.done(owner.state.activation)
            assertTrue("Done did not publish a persistence attempt", observedPersistence)
            await { !owner.state.persisting }
        }

        private suspend fun await(done: () -> Boolean) = withTimeout(5_000) {
            while (!done()) delay(1)
        }

        override fun close() {
            var failure: Throwable? = null
            fun cleanup(action: () -> Unit) {
                try { action() } catch (error: Throwable) {
                    val first = failure
                    if (first == null) failure = error else if (first !== error) first.addSuppressed(error)
                }
            }
            // AutoCloseable.use preserves a failed body assertion and suppresses this teardown's failures.
            cleanup { activeOwner?.close() }
            cleanup {
                settingsJobs.forEach { it.cancel() }
                // Include owners retired by open(): cancellation cannot interrupt an admitted catalog scan.
                runBlocking { withTimeout(10_000) { settingsJobs.joinAll() } }
            }
            cleanup { activity.pause() }
            cleanup { activity.stop() }
            cleanup { activity.destroy() }
            if (settingsJobs.all { it.isCompleted }) {
                cleanup(::resetOwnedDatabase)
                cleanup(::acknowledgeOwnedFeedback)
                if (ownsShadowRoot) {
                    cleanup { JournalHostIoShadow.finishFixture()?.let { throw it } }
                    cleanup {
                        check(ServiceStateOsShadow.fixtureRoot == root.toPath()) {
                            "Another fixture replaced the Settings shadow root"
                        }
                        ServiceStateOsShadow.fixtureRoot = previousShadowRoot
                        ownsShadowRoot = false
                    }
                }
                cleanup { assertTrue(root.deleteRecursively()) }
            }
            failure?.let { throw it }
        }

        private fun resetOwnedDatabase() = synchronized(RecordingDatabase) {
            val database = databaseSingleton.get(null) as RecordingDatabase?
            if (previousDatabase != null) {
                check(database === previousDatabase) { "Settings fixture changed a foreign database singleton" }
                return@synchronized
            }
            if (database == null) return@synchronized
            val expected = context.getDatabasePath(checkNotNull(database.databaseName)).canonicalFile
            val actual = File(database.readableDatabase.path).canonicalFile
            check(actual == expected) { "Settings fixture cannot reset RecordingDatabase owned by $actual" }
            RecordingDatabase.resetAfterCorruption()
            check(databaseSingleton.get(null) == null) { "Settings fixture database remained owned after close" }
        }

        private fun acknowledgeOwnedFeedback() {
            val previousIds = previousFeedback.map { it.id }.toSet()
            val ownedIds = AppFeedbackCenter.events.value.map { it.id }.filterNot { it in previousIds }.toSet()
            // Bound retirement to this settled fixture's new events; never acknowledge a foreign queue head.
            for (ignored in ownedIds) {
                val head = AppFeedbackCenter.events.value.firstOrNull() ?: break
                if (head.id !in ownedIds) break
                AppFeedbackCenter.acknowledge(head.id)
            }
            check(AppFeedbackCenter.events.value.none { it.id in ownedIds }) {
                "Settings feedback remains behind a foreign queue head; unrelated feedback was preserved"
            }
            assertEquals(previousFeedback, AppFeedbackCenter.events.value)
        }
    }
}
