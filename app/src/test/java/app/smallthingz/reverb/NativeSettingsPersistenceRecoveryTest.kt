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
import kotlinx.coroutines.delay
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
        private val activity = Robolectric.buildActivity(Activity::class.java).setup().visible()
        override val lifecycle = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
        val root = Files.createTempDirectory("settings-persistence-recovery-").toFile()
        val context = FixtureContext(activity.get(), root)
        val preferences = getRecorderPreferences(context)
        private val window = object : FrameLayout(context) {
            override fun hasWindowFocus(): Boolean = true
        }.also { activity.get().setContentView(it) }
        private var activeOwner: NativeSettingsOwner? = null
        private var observedPersistence = false
        val owner get() = checkNotNull(activeOwner)
        var navigations = 0
            private set

        init {
            JournalHostIoShadow.reset()
            ServiceStateOsShadow.fixtureRoot = root.toPath()
            assertTrue(preferences.edit().clear().commit())
            assertTrue(window.isAttachedToWindow)
        }

        suspend fun open() {
            activeOwner?.close()
            activeOwner = NativeSettingsOwner(context, window, lifecycle, scope, true,
                isPanelActive = { true }, onThemePreview = {}, releaseInputFocus = {},
                launchDirectory = { error("Unexpected directory launch") }, onBack = { navigations++ })
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
            activeOwner?.close()
            activity.pause().stop().destroy()
            val failure = JournalHostIoShadow.finishFixture()
            ServiceStateOsShadow.fixtureRoot = null
            assertTrue(root.deleteRecursively())
            failure?.let { throw it }
        }
    }
}
