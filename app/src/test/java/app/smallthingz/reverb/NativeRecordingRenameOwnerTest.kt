package app.smallthingz.reverb

import android.app.Application
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class NativeRecordingRenameOwnerTest {
    @Test fun sameAssetNameEnrichmentPreservesRawDraftAndValidationState() = Fixture().use { f ->
        f.owner.editName("  user draft  ")
        val draft = f.owner.state
        val enriched = f.recording.copy(displayName = "provider-enriched.wav", codecSummary = "PCM 24")
        f.owner.bind(enriched, true, f.callbacks)
        assertSame(draft, f.owner.state)

        f.owner.editName("")
        f.owner.submit() // Validation only; no repository mutation.
        val invalid = f.owner.state
        assertNotNull(invalid.error)
        f.owner.bind(enriched.copy(displayName = "later-enrichment.wav"), true, f.callbacks)
        assertSame(invalid, f.owner.state)
        assertFalse(f.owner.state.isRenaming)
    }

    @Test fun revokedPresentationCannotEditOrSubmitAndReadmissionRetainsDraft() = Fixture().use { f ->
        f.owner.editName("")
        val draft = f.owner.state
        f.admitted = false
        f.owner.editName("stale connection")
        f.owner.submit()
        assertSame(draft, f.owner.state) // Even validation must not run for a retired input callback.
        f.admitted = true
        f.owner.editName("current draft")
        assertEquals("current draft", f.owner.state.name)
    }

    @Test fun inactiveAndPausedOwnersRejectNewInputWithoutDiscardingTheirDraft() = Fixture().use { f ->
        f.owner.editName("")
        val draft = f.owner.state
        f.owner.bind(f.recording, false, f.callbacks)
        f.owner.editName("inactive")
        f.owner.submit()
        assertSame(draft, f.owner.state)
        f.owner.bind(f.recording, true, f.callbacks)
        f.lifecycle.currentState = Lifecycle.State.STARTED
        f.owner.editName("paused")
        f.owner.submit()
        assertSame(draft, f.owner.state)
        f.lifecycle.currentState = Lifecycle.State.RESUMED
        f.owner.submit()
        assertNotNull(f.owner.state.error)
        assertFalse(f.owner.state.isRenaming)
    }

    private class Fixture : LifecycleOwner, AutoCloseable {
        override val lifecycle = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val recording = RecordingEntity("content://fixture/A", "original.wav", "audio/wav", 1L,
            2L, 3L, "PCM", RecordingStorageType.DOCUMENT, "directory")
        val callbacks = NativeRecordingRenameCallbacks({}, { error("Unexpected rename") }, { throw it })
        var admitted = true
        val owner = NativeRecordingRenameOwner(RuntimeEnvironment.getApplication(), recording, true,
            this, scope, callbacks, { admitted }) {}
        override fun close() {
            try { owner.close() } finally { scope.cancel() }
        }
    }
}
