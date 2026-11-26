package app.smallthingz.reverb

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.provider.Settings
import android.view.Choreographer
import android.widget.FrameLayout
import androidx.activity.OnBackPressedDispatcher
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import app.smallthingz.reverb.nativeui.NativeSettingsScreenContent
import app.smallthingz.reverb.nativeui.NativeSettingsScreenInput
import app.smallthingz.reverb.nativeui.ReverbPalette
import app.smallthingz.reverb.nativeui.ReverbViewEnvironment
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings
import smallthingz.ui.platform.AndroidUiFrameClock
import smallthingz.ui.scroll.NativeVerticalNestedHost
import smallthingz.ui.scroll.NativeVerticalScrollSource
import smallthingz.ui.text.font.createFontFamilyResolver
import smallthingz.ui.text.view.NativeMergedRowAccessibilityHost
import smallthingz.ui.unit.Density

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [35],
    application = Application::class,
    shadows = [JournalHostIoShadow::class, SettingsPublicationEnvironmentShadow::class],
)
class NativeSettingsAutoStartDraftTest {
    @Test fun editPublishesUndoAndDoneWithoutPersistingAndBothDiscardPathsRestoreBaseline() = runBlocking {
        Fixture(this).use { f ->
            f.open()
            f.assertDraft(enabled = false, dirty = false)
            f.choose(true)
            f.assertDraft(enabled = true, dirty = true)
            assertNull(f.rawAutoStart)
            assertNull(AutoStartAfterRestart.admission(f.context))

            assertTrue(f.screen.topBar.navigation.visual.performClick())
            f.assertDraft(enabled = false, dirty = false)
            assertEquals(0, f.navigations)
            assertNull(f.rawAutoStart)

            f.choose(true)
            f.back.onBackPressed()
            assertEquals(0, f.navigations)
            f.assertDraft(enabled = true, dirty = true)
            f.back.onBackPressed()
            assertEquals(1, f.navigations)
            f.assertDraft(enabled = false, dirty = false)
            assertNull(f.rawAutoStart)
            assertNull(AutoStartAfterRestart.admission(f.context))
        }
    }

    @Test fun currentOverlayGrantCreatesARecreatableDraftAndOnlyDoneEnablesBoot() = runBlocking {
        Fixture(this, overlayGranted = false).use { f ->
            f.open()
            f.requestPermission()
            f.returnFromPermission(granted = true, focusedOnResume = false)
            assertFalse(f.owner.featureInputReady())
            f.assertDraft(enabled = true, dirty = true)
            assertNull(f.rawAutoStart)
            assertNull(AutoStartAfterRestart.admission(f.context))
            f.windowFocused = true

            val saved = f.owner.saveDraft()
            assertTrue(saved.isNotEmpty())
            f.open(saved)
            f.assertDraft(enabled = true, dirty = true)
            assertNull(f.rawAutoStart)
            f.clickDoneAndAwaitSuccess("restored Auto-start permission draft")
            f.assertDraft(enabled = true, dirty = false)
            assertEquals(true, f.rawAutoStart)
            assertNotNull(AutoStartAfterRestart.admission(f.context))
            assertEquals(1, f.navigations)
        }
    }

    @Test fun undoOfAnotherDirtyFieldAlsoRetiresPendingOverlayEnable() = runBlocking {
        Fixture(this, overlayGranted = false).use { f ->
            f.open()
            f.theme(AppThemeMode.DARK)
            f.requestPermission()
            assertTrue(f.owner.state.hasUnsavedChanges)
            assertFalse(f.screen.topBar.done.visual.isEnabled)
            assertTrue(f.screen.topBar.navigation.visual.performClick())
            f.assertDraft(enabled = false, dirty = false)
            assertFalse(f.owner.autoStartChoice.permissionPending)
            f.returnFromPermission(granted = true)
            f.assertDraft(enabled = false, dirty = false)
            assertNull(f.rawAutoStart)
            assertNull(AutoStartAfterRestart.admission(f.context))
        }
    }

    @Test fun overlayReturnCannotStageAfterHideOrRecreation() = runBlocking {
        for (recreate in listOf(false, true)) {
            Fixture(this, overlayGranted = false).use { f ->
                f.open()
                val retiredActivation = f.owner.state.activation
                f.requestPermission()
                if (recreate) {
                    f.open(f.owner.saveDraft())
                } else {
                    f.setActive(false)
                    f.setActive(true)
                    f.await { f.owner.state.interactionReady }
                }
                assertNotSame(retiredActivation, f.owner.state.activation)

                f.returnFromPermission(granted = true)
                f.owner.autoStartChanged(retiredActivation, true)
                f.assertDraft(enabled = false, dirty = false)
                assertFalse(f.owner.autoStartChoice.permissionPending)
                assertNull(f.rawAutoStart)
                assertNull(AutoStartAfterRestart.admission(f.context))
            }
        }
    }

    @Test fun failedDisablePreservesIssuedTokenAndFreshBootReadsWaitForRollback() = runBlocking {
        Fixture(this).use { f ->
            assertTrue(f.preferences.edit().putBoolean(AUTO_START_KEY, true).commit())
            f.open()
            val issued = requireNotNull(AutoStartAfterRestart.admission(f.context))
            f.choose(false)
            f.assertDraft(enabled = false, dirty = true)
            assertEquals(true, f.rawAutoStart)
            assertTrue(AutoStartAfterRestart.policy.currentAdmission(issued))

            f.holdSettingsCommit(succeeds = false).use { write ->
                f.clickDone()
                assertEquals(false, f.awaitAttemptedValue(write))
                assertFalse(f.screen.topBar.done.visual.isEnabled)
                assertTrue(AutoStartAfterRestart.policy.currentAdmission(issued))

                // The boot worker may wait for stable storage or return the committed token.
                val bootThread = CompletableDeferred<Thread>()
                val freshAdmission = async(Dispatchers.IO) {
                    bootThread.complete(Thread.currentThread())
                    AutoStartAfterRestart.admission(f.context)
                }
                try {
                    val thread = withTimeout(5_000) { bootThread.await() }
                    f.await { freshAdmission.isCompleted || thread.state == Thread.State.BLOCKED }
                    if (freshAdmission.isCompleted) assertEquals(issued, freshAdmission.await())
                } finally {
                    write.close()
                }
                assertEquals(issued, withTimeout(5_000) { freshAdmission.await() })
                f.awaitSave()
            }
            assertEquals(true, f.rawAutoStart)
            assertTrue(AutoStartAfterRestart.policy.currentAdmission(issued))
            assertEquals(0, f.navigations)
            f.assertDraft(enabled = false, dirty = true)

            f.clickDoneAndAwaitSuccess("Auto-start disable retry")
            assertEquals(false, f.rawAutoStart)
            assertFalse(AutoStartAfterRestart.policy.currentAdmission(issued))
            assertNull(AutoStartAfterRestart.admission(f.context))
            assertEquals(1, f.navigations)
        }
    }

    @Test fun failedEnableRestoresWrongTypedRawPreferenceAndKeepsDraftRetryable() = runBlocking {
        Fixture(this).use { f ->
            val original = "invalid legacy opt-in"
            assertTrue(f.preferences.edit().putString(AUTO_START_KEY, original).commit())
            f.open()
            assertNull(AutoStartAfterRestart.admission(f.context))
            f.choose(true)
            f.holdSettingsCommit(succeeds = false).use { write ->
                f.clickDone()
                assertEquals(true, f.awaitAttemptedValue(write))
                assertNull(AutoStartAfterRestart.policy.admission())
                write.close()
                f.awaitSave()
            }
            assertEquals(original, f.rawAutoStart)
            assertEquals(0, f.navigations)
            assertNull(AutoStartAfterRestart.admission(f.context))
            f.assertDraft(enabled = true, dirty = true)

            f.clickDoneAndAwaitSuccess("Auto-start enable retry")
            assertEquals(true, f.rawAutoStart)
            assertNotNull(AutoStartAfterRestart.admission(f.context))
        }
    }

    @Test fun restoringLegacyAndCurrentUnrelatedDraftsAdoptsNewDurableAutoStart() = runBlocking {
        for (version in 1..3) {
            Fixture(this).use { f ->
                assertTrue(f.preferences.edit().putBoolean(AUTO_START_KEY, false).commit())
                f.open()
                f.theme(AppThemeMode.DARK)
                assertFalse(f.owner.autoStartChoice.enabled)
                val currentDraft = f.owner.saveDraft()
                val restored = when (version) {
                    1 -> currentDraft.take(24).toMutableList().apply { this[0] = 1 }
                    2 -> currentDraft.take(25).toMutableList().apply { this[0] = 2 }
                    else -> currentDraft
                }
                assertTrue(f.preferences.edit().putBoolean(AUTO_START_KEY, true).commit())

                f.open(restored)
                assertEquals("Theme was lost restoring draft version $version", AppThemeMode.DARK, f.owner.snapshot.themeMode)
                f.assertDraft(enabled = true, dirty = true)
                f.clickDoneAndAwaitSuccess("restored draft version $version")
                f.assertDraft(enabled = true, dirty = false)
                assertEquals("Unrelated Done overwrote Auto-start for version $version", true, f.rawAutoStart)
                assertEquals("Done did not save the theme for version $version", AppThemeMode.DARK, getConfiguredThemeMode(f.context))
            }
        }
    }

    @Test fun failedRollbackRetiresBootAdmissionEvenWhenRawValueAppearsRestored() = runBlocking {
        Fixture(this).use { f ->
            assertTrue(f.preferences.edit().putBoolean(AUTO_START_KEY, true).commit())
            f.open()
            val issued = requireNotNull(AutoStartAfterRestart.admission(f.context))
            f.choose(false)
            f.holdSettingsCommit(succeeds = false).use { write ->
                f.clickDone()
                assertEquals(false, f.awaitAttemptedValue(write))
                f.holdSettingsCommit(succeeds = false).use { rollback ->
                    write.close()
                    assertEquals(true, f.awaitAttemptedValue(rollback))
                    assertTrue(AutoStartAfterRestart.policy.currentAdmission(issued))
                    rollback.close()
                    f.awaitSave()
                }
            }
            assertEquals(true, f.rawAutoStart)
            assertFalse(AutoStartAfterRestart.policy.currentAdmission(issued))
            assertNull(AutoStartAfterRestart.admission(f.context))
            assertEquals(0, f.navigations)
            f.assertDraft(enabled = false, dirty = true)

            f.clickDoneAndAwaitSuccess("Auto-start recovery after failed rollback")
            assertEquals(false, f.rawAutoStart)
            assertFalse(AutoStartAfterRestart.policy.state.value.unavailable)
            assertNull(AutoStartAfterRestart.admission(f.context))
            assertEquals(1, f.navigations)
        }
    }

    @Test fun unrelatedEditDuringSaveKeepsThatEditAndAdoptsCommittedAutoStart() = runBlocking {
        Fixture(this).use { f ->
            f.open()
            f.theme(AppThemeMode.DARK)
            assertTrue(f.preferences.edit().putBoolean(AUTO_START_KEY, true).commit())
            f.holdSettingsCommit(succeeds = true).use { write ->
                f.clickDone()
                assertEquals(true, f.awaitAttemptedValue(write))
                f.theme(AppThemeMode.LIGHT)
                write.close()
                f.awaitSave()
            }
            assertEquals(0, f.navigations)
            assertEquals(AppThemeMode.DARK, getConfiguredThemeMode(f.context))
            assertEquals(AppThemeMode.LIGHT, f.owner.snapshot.themeMode)
            f.assertDraft(enabled = true, dirty = true)
            f.clickDoneAndAwaitSuccess("newer theme draft")
            assertEquals(true, f.rawAutoStart)
            assertEquals(AppThemeMode.LIGHT, getConfiguredThemeMode(f.context))
            assertEquals(1, f.navigations)
        }
    }

    private class Fixture(
        private val scope: CoroutineScope,
        overlayGranted: Boolean = true,
    ) : LifecycleOwner, AutoCloseable {
        private val activity = Robolectric.buildActivity(Activity::class.java).setup().visible()
        override val lifecycle = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
        private val root = Files.createTempDirectory("settings-auto-start-draft-").toFile()
        val context = FixtureContext(activity.get(), root)
        val preferences get() = context.preferences
        val rawAutoStart get() = preferences.all[AUTO_START_KEY]
        var windowFocused = true
        private val window = object : FrameLayout(context) {
            override fun hasWindowFocus() = windowFocused
        }.also { activity.get().setContentView(it) }
        private val frames = AndroidUiFrameClock(Choreographer.getInstance())
        private val animations = CoroutineScope(Dispatchers.Main.immediate + frames)
        private val environment = ReverbViewEnvironment(Density(1f), createFontFamilyResolver(context), ReverbPalette.fallback(false))
        private var panelActive = true
        lateinit var back: OnBackPressedDispatcher
            private set
        lateinit var screen: NativeSettingsScreenContent
            private set
        val owner get() = screen.owner
        var navigations = 0
            private set

        init {
            JournalHostIoShadow.reset()
            ServiceStateOsShadow.fixtureRoot = root.toPath()
            ShadowSettings.setCanDrawOverlays(overlayGranted)
            assertTrue(window.isAttachedToWindow)
        }

        suspend fun open(savedDraft: List<Any> = emptyList()) {
            if (::screen.isInitialized) {
                screen.close()
                window.removeView(screen)
            }
            panelActive = true
            back = OnBackPressedDispatcher()
            val owner = NativeSettingsOwner(context, window, lifecycle, scope, true, savedDraft = savedDraft,
                isPanelActive = { panelActive }, onThemePreview = {}, releaseInputFocus = {},
                launchDirectory = { error("Unexpected directory launch") }, onBack = { navigations++ })
            screen = NativeSettingsScreenContent(
                context, owner, NativeSettingsScreenInput(active = true), environment, this, back, window,
                frames, animations, NoNestedScroll, parentInterested = { false }, changedNodes = {},
            )
            window.addView(screen)
            screen.start()
            await { owner.state.interactionReady }
        }

        fun setActive(active: Boolean) {
            panelActive = active
            screen.bind(NativeSettingsScreenInput(active))
        }

        fun choose(enabled: Boolean) = owner.autoStartChanged(owner.state.activation, enabled)

        fun requestPermission() {
            val previous = context.overlayLaunches
            choose(true)
            assertEquals(previous + 1, context.overlayLaunches)
            assertTrue(owner.autoStartChoice.permissionPending)
            assertFalse(owner.autoStartChoice.enabled)
            assertNull(rawAutoStart)
        }

        fun returnFromPermission(granted: Boolean, focusedOnResume: Boolean = true) {
            lifecycle.currentState = Lifecycle.State.STARTED
            ShadowSettings.setCanDrawOverlays(granted)
            windowFocused = focusedOnResume
            lifecycle.currentState = Lifecycle.State.RESUMED
        }

        fun theme(mode: AppThemeMode) = assertTrue(owner.themeChanged(owner.state.activation, mode))

        fun assertDraft(enabled: Boolean, dirty: Boolean) {
            assertEquals(enabled, owner.autoStartChoice.enabled)
            assertEquals(dirty, owner.state.hasUnsavedChanges)
            val navigation = screen.topBar.navigation.visual as NativeMergedRowAccessibilityHost
            val label = navigation.mergedRowAccessibility.sourceOrderedChildren().single().contentDescription
            assertEquals(context.getString(if (dirty) R.string.undo else R.string.onboarding_back), label)
            assertEquals(dirty, screen.topBar.done.visual.isEnabled)
        }

        fun clickDone() {
            assertTrue(screen.topBar.done.visual.isEnabled)
            assertTrue(screen.topBar.done.visual.performClick())
        }

        suspend fun clickDoneAndAwaitSuccess(label: String) {
            val previousNavigations = navigations
            clickDone()
            val completedNavigations = withTimeoutOrNull(5_000) {
                while (navigations == previousNavigations) delay(1)
                navigations
            }
            assertEquals("Done did not finish saving $label", previousNavigations + 1, completedNavigations)
            assertFalse("Settings still reports saving after Done navigated for $label", owner.state.persisting)
        }

        suspend fun awaitSave() = await { !owner.state.persisting }
        suspend fun awaitAttemptedValue(write: CommitGate): Any? = withTimeout(5_000) {
            val attempted = write.attempted.await()
            assertTrue("The held Settings commit must still be in progress", owner.state.persisting)
            attempted
        }

        suspend fun await(predicate: () -> Boolean) = withTimeout(5_000) {
            while (!predicate()) delay(1)
        }

        fun holdSettingsCommit(succeeds: Boolean): CommitGate = CommitGate(succeeds).also {
            assertTrue(preferences.nextSettingsCommit.compareAndSet(null, it))
        }

        override fun close() {
            try {
                preferences.nextSettingsCommit.getAndSet(null)?.close()
                if (::screen.isInitialized) screen.close()
                // Quarantine is process-wide and a queued refresh may still read this fixture.
                withRetentionPersistenceLock {
                    assertTrue(preferences.edit().remove(AUTO_START_KEY).commit())
                    AutoStartAfterRestart.policy.publishCommitted(false)
                }
            } finally {
                animations.cancel()
                activity.pause().stop().destroy()
                ShadowSettings.setCanDrawOverlays(false)
                val failure = JournalHostIoShadow.finishFixture()
                ServiceStateOsShadow.fixtureRoot = null
                assertTrue(root.deleteRecursively())
                failure?.let { throw it }
            }
        }
    }

    private class FixtureContext(base: Context, private val root: File) : ContextWrapper(base) {
        val preferences = ControlledPreferences(base.getSharedPreferences(root.name, Context.MODE_PRIVATE))
        var overlayLaunches = 0
            private set

        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir(): File = root
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            if (name == packageName) preferences else super.getSharedPreferences(name, mode)
        override fun bindService(service: Intent, connection: ServiceConnection, flags: Int): Boolean = false
        override fun startService(service: Intent): ComponentName = requireNotNull(service.component)
        override fun startActivity(intent: Intent) {
            assertEquals(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, intent.action)
            overlayLaunches++
        }
    }

    private class CommitGate(val succeeds: Boolean) : AutoCloseable {
        val attempted = CompletableDeferred<Any?>()
        val release = CountDownLatch(1)
        override fun close() = release.countDown()
    }

    private class ControlledPreferences(private val actual: SharedPreferences) : SharedPreferences by actual {
        val nextSettingsCommit = AtomicReference<CommitGate?>()
        override fun edit(): SharedPreferences.Editor {
            val actualEditor = actual.edit()
            return object : SharedPreferences.Editor by actualEditor {
                private var submittedTheme = false
                override fun putInt(key: String, value: Int): SharedPreferences.Editor = apply {
                    if (key == PrefKey.THEME_MODE.name) submittedTheme = true
                    actualEditor.putInt(key, value)
                }
                override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor =
                    apply { actualEditor.putBoolean(key, value) }
                override fun putLong(key: String, value: Long): SharedPreferences.Editor =
                    apply { actualEditor.putLong(key, value) }
                override fun putString(key: String, value: String?): SharedPreferences.Editor =
                    apply { actualEditor.putString(key, value) }
                override fun remove(key: String): SharedPreferences.Editor = apply {
                    if (key == PrefKey.THEME_MODE.name) submittedTheme = true
                    actualEditor.remove(key)
                }

                override fun commit(): Boolean {
                    val write = if (submittedTheme) nextSettingsCommit.getAndSet(null) else null
                    val committed = actualEditor.commit()
                    if (write == null) return committed
                    // SharedPreferences may expose the attempted map before returning false.
                    write.attempted.complete(actual.all[AUTO_START_KEY])
                    check(write.release.await(10, TimeUnit.SECONDS)) { "Settings commit gate was not released" }
                    return committed && write.succeeds
                }
            }
        }
    }

    private object NoNestedScroll : NativeVerticalNestedHost {
        override fun preScroll(availableY: Float, source: NativeVerticalScrollSource) = 0f
        override fun postScroll(consumedY: Float, availableY: Float, source: NativeVerticalScrollSource) = 0f
        override suspend fun preFling(availableY: Float) = 0f
        override suspend fun postFling(consumedY: Float, availableY: Float) = 0f
    }

    private companion object {
        val AUTO_START_KEY = PrefKey.AUTO_START_AFTER_RESTART.name
    }
}
