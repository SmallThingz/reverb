package app.smallthingz.reverb

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SettingsInteractionRecoveryTest {
    @Test fun successfulSave_preservesNewerDraftAcrossRapidReopen() {
        val panel = Panel()
        panel.edit(44_100)
        val submitted = panel.beginSave()
        panel.edit(32_000)
        repeat(3) {
            panel.activate(false)
            assertFalse(panel.ready)
            panel.activate(true)
            assertFalse(panel.ready)
        }
        panel.finishSave(submitted)
        assertEquals(44_100, panel.baseline.sampleRate)
        assertEquals(32_000, panel.draft.sampleRate)
        assertTrue(panel.dirty)
        assertNotNull(panel.admission())
        panel.undo()
        assertEquals(44_100, panel.draft.sampleRate)
        assertFalse(panel.dirty)
    }

    @Test fun failedSave_preservesDraftAndBaseline() = runBlocking {
        for (throwFailure in listOf(false, true)) {
            val panel = Panel()
            panel.edit(44_100)
            val submitted = panel.beginSave()
            panel.activate(false)
            panel.activate(true)
            val committed = runDurableUiBooleanAttempt {
                if (throwFailure) throw java.io.IOException("commit failed")
                false
            }
            panel.finishSave(if (committed) submitted else null)
            assertEquals(48_000, panel.baseline.sampleRate)
            assertEquals(44_100, panel.draft.sampleRate)
            assertNotNull(panel.admission())
            panel.undo()
            assertEquals(panel.baseline, panel.draft)
        }
    }

    @Test fun preflightSuperseded_releasesCurrentActivation() = runBlocking {
        val panel = Panel()
        panel.edit(44_100)
        val admission = panel.admission()!!
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Boolean>()
        panel.beginSave()
        val preflight = async {
            readSettingsRetentionPreflight(
                admission = admission,
                read = { started.complete(Unit); runBlocking { release.await() } },
                currentAdmission = panel::admission,
            )
        }
        started.await()
        panel.activate(false)
        panel.activate(true)
        assertNotEquals(admission.panelOwner, panel.owner)
        assertFalse(panel.ready)
        release.complete(true)
        assertEquals(SettingsRetentionPreflightResult.SUPERSEDED, preflight.await())
        panel.finishSave(null)
        assertEquals(44_100, panel.draft.sampleRate)
        assertNotNull(panel.admission())
    }

    @Test fun cleanCommit_requiresHydrationAndHiddenDraftNeverUnlocks() {
        val panel = Panel()
        panel.edit(44_100)
        val submitted = panel.beginSave()
        panel.activate(false)
        panel.activate(true)
        panel.finishSave(submitted)
        assertFalse(panel.ready)
        assertTrue(panel.needsHydration)
        panel.hydrate(SettingsSnapshot(sampleRate = 32_000))
        assertTrue(panel.ready)
        assertEquals(32_000, panel.draft.sampleRate)
        panel.edit(16_000)
        panel.beginSave()
        panel.activate(false)
        panel.finishSave(null)
        assertFalse(panel.ready)
        assertNull(panel.admission())
    }

    @Test fun unhydratedPlaceholder_cannotBypassDurableRead() {
        val panel = Panel(hydrated = false)
        panel.edit(44_100)
        panel.activate(false)
        panel.activate(true)
        panel.finishSave(null)
        assertFalse(panel.ready)
        assertNull(panel.admission())
    }

    // Production policies decide recovery, hydration and admission; values expose draft loss.
    private class Panel(var hydrated: Boolean = true) {
        var active = true
        var persisting = false
        var ready = hydrated
        var owner = Any()
        var revision = 0L
        var baseline = SettingsSnapshot(sampleRate = 48_000)
        var draft = baseline
        val dirty get() = SettingsUiDraft.normalized(draft).hasUnsavedChanges(baseline)
        var needsHydration = false

        fun edit(rate: Int) { draft = draft.copy(sampleRate = rate); revision++ }
        fun beginSave(): SettingsSnapshot { persisting = true; reconcile(); return draft }
        fun activate(value: Boolean) {
            if (active != value) { active = value; owner = Any(); ready = false }
            reconcile()
        }
        fun finishSave(committed: SettingsSnapshot?) {
            if (committed != null) baseline = committed
            persisting = false
            reconcile()
        }
        fun undo() { check(admission() != null); draft = baseline; revision++ }
        fun hydrate(value: SettingsSnapshot) {
            check(needsHydration)
            baseline = value; draft = value; hydrated = true; ready = true
            needsHydration = false
        }
        fun admission() = settingsSaveAdmission(
            active = active && ready, ownerActive = true, restorationConflict = false,
            panelOwner = owner, editRevision = revision,
        )
        private fun reconcile() {
            if (settingsRetainedDraftMayReleaseInteraction(active, hydrated, persisting, dirty)) {
                ready = true
                needsHydration = false
            } else {
                needsHydration = settingsShouldRehydrate(active, persisting, dirty)
                if (needsHydration) ready = false
            }
        }
    }
}
