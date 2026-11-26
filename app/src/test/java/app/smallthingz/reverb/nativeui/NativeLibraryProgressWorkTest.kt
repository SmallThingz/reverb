package app.smallthingz.reverb.nativeui

import android.app.Application
import android.app.Activity
import android.content.Context
import android.view.Choreographer
import android.view.View
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.recyclerview.widget.RecyclerView
import app.smallthingz.reverb.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import smallthingz.ui.platform.AndroidUiFrameClock
import smallthingz.ui.text.font.createFontFamilyResolver
import smallthingz.ui.unit.Density
import kotlin.coroutines.CoroutineContext

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeLibraryProgressWorkTest {
    @Test fun progressTopologyGuardRejectsOtherStateAndCatalogChanges() = Fixture(100, true).use { f ->
        f.deliver(listOf(f.job))
        val before = f.presentation
        f.deliver(listOf(f.job.copy(progress = .5f)))
        val progress = f.presentation
        assertTrue(libraryOnlySaveProgressChanged(before, progress))
        assertFalse(libraryOnlySaveProgressChanged(null, progress))
        assertFalse(libraryOnlySaveProgressChanged(before, before))
        val row = f.recordings.first()
        val changedRows = listOf(
            progress.list.copy(screenActive = false),
            progress.list.copy(selectionActive = !progress.list.selectionActive),
            progress.list.copy(activeMutationIds = setOf(row.id)),
            progress.list.copy(localOperationBusyIds = setOf(row.id)),
            progress.list.copy(expandedOwner = InlinePlaybackRowOwner(row.id)),
            progress.list.copy(trimRequestRecordingId = row.id),
            progress.list.copy(infoRecordingId = row.id),
            progress.list.copy(renameRecordingId = row.id),
            progress.list.copy(playingRecording = row),
            progress.list.copy(waveformReadAllowed = false),
        )
        changedRows.forEach { assertFalse(libraryOnlySaveProgressChanged(before, progress.copy(list = it))) }
        assertFalse(libraryOnlySaveProgressChanged(before, progress.copy(menuRecording = row)))
        assertFalse(libraryOnlySaveProgressChanged(before, progress.copy(undo = NativeLibraryUndoNotice("Undo", 1))))
        assertFalse(libraryOnlySaveProgressChanged(before, progress.copy(loadFailed = !progress.loadFailed)))
        f.deliver(listOf(f.job.copy(progress = .5f, displayName = "Changed title")))
        assertFalse(libraryOnlySaveProgressChanged(before, f.presentation))
        f.deliver(listOf(f.job.copy(phase = SavePhase.FAILED, error = "Failure")))
        assertFalse(libraryOnlySaveProgressChanged(before, f.presentation))
        f.catalog(listOf(row.copy(displayName = "Renamed")) + f.recordings.drop(1))
        f.deliver(listOf(f.job.copy(progress = .6f)))
        assertFalse(libraryOnlySaveProgressChanged(before, f.presentation))
    }

    @Test fun progressBindsRetainRealTextLeasesWhilePhaseAndRecyclingStillRefresh() = Fixture(100, false).use { f ->
        val host = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            host.get().setContentView(f.view)
            f.deliver(listOf(f.job))
            f.place()
            f.flushNodes()
            f.bindTextOwner()
            val leases = f.textLeases()
            assertTrue("Real paragraph/merged-row leases must be installed", leases.isNotEmpty())
            val refreshes = f.nodeRefreshes
            repeat(30) { index ->
                val fraction = (index + 1) / 31f
                f.deliver(listOf(f.job.copy(progress = fraction)))
                f.place()
                f.flushNodes()
                assertEquals(fraction, f.pendingTarget(), 0f)
                assertEquals("Progress alone must not rescan the real accessibility owner", refreshes, f.nodeRefreshes)
                val current = f.textLeases()
                assertEquals(leases.keys, current.keys)
                leases.forEach { (view, lease) -> assertSame(lease, current[view]) }
            }
            // Changed text still updates the existing live semantic nodes; no text/value cache.
            f.deliver(listOf(f.job.copy(displayName = "Updated pending title", progress = .8f)))
            f.place()
            f.flushNodes()
            assertTrue(f.nodeRefreshes > refreshes)
            val labels = f.textLeases().keys.filterIsInstance<smallthingz.ui.text.view.ParagraphView>()
            assertTrue(labels.any { view ->
                val node = checkNotNull(view.createAccessibilityNodeInfo())
                try { node.text?.toString() == "Updated pending title" } finally { node.recycle() }
            })
            val afterTitle = f.nodeRefreshes
            f.deliver(listOf(f.job.copy(phase = SavePhase.FAILED, error = "Failure")))
            f.place()
            f.flushNodes()
            assertTrue("Removing spinner/adding Retry keeps topology refresh", f.nodeRefreshes > afterTitle)
            val afterFailure = f.nodeRefreshes
            f.view.restorePosition(NativeListSavedPosition(20, 11))
            f.place()
            f.flushNodes()
            assertTrue("Detached/recycled holders must retire their bindings", f.nodeRefreshes > afterFailure)
            f.deliver(listOf(f.job.copy(progress = .9f)))
            f.view.restorePosition(NativeListSavedPosition(0, 0))
            f.place()
            f.flushNodes()
            assertEquals(.9f, f.pendingTarget(), 0f)
            assertTrue(f.textLeases().isNotEmpty())
        } finally { host.pause().stop().destroy() }
    }

    @Test fun thirtyProgressDeliveriesDoNotTouchFinishedCatalogAtAnySizeVisibleOrHidden() {
        for (count in listOf(0, 100, 1000)) for (active in listOf(true, false)) {
            Fixture(count, active).use { f ->
                f.deliver(listOf(f.job))
                f.place()
                val initial = f.snapshot
                val entriesContainer = f.entries()
                val recordingsMap = f.recordingsMap()
                val entries = f.entries().toList()
                val revision = f.revision()
                val initialDiffs = f.work.diffs
                val initialCreated = f.work.created
                assertEquals(1, initialDiffs)
                assertEquals(count, initialCreated)
                f.work.binds.clear()
                f.changes.clear()
                val position = f.view.savePosition()
                repeat(30) { step ->
                    val pending = f.job.copy(progress = (step + 1) / 31f)
                    f.deliver(listOf(pending))
                    f.place()
                    assertEquals(pending, (f.snapshot.pending.single() as LibraryListItem.Pending).save)
                    assertSame(initial.finished, f.snapshot.finished)
                    assertSame(initial.structure, f.snapshot.structure)
                    assertSame(entriesContainer, f.entries())
                    assertSame(recordingsMap, f.recordingsMap())
                    assertEquals(position, f.view.savePosition())
                    assertEquals(revision, f.revision())
                    assertEquals(initialDiffs, f.work.diffs)
                    assertEquals(initialCreated, f.work.created)
                    entries.indices.drop(1).forEach { assertSame(entries[it], f.entries()[it]) }
                }
                assertEquals(List(30) { 0 to 1 }, f.changes)
                assertTrue(f.work.binds.isNotEmpty())
                assertTrue(f.work.binds.all { it == "save-job:pending" })
                assertEquals(if (count == 0) emptySet<String>() else setOf(f.recordings[0].id),
                    f.presentation.list.selectedIds)
                // Readmission samples the latest pending value without a structural rebuild.
                f.active = true
                f.deliver(listOf(f.job.copy(progress = 30 / 31f)))
                f.place()
                assertTrue(f.presentation.list.screenActive)
                assertEquals(initialDiffs, f.work.diffs)
            }
        }
    }

    @Test fun offscreenPendingHolderReturnsWithLatestProgressWithoutFinishedRebuild() = Fixture(100, false).use { f ->
        val host = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            host.get().setContentView(f.view)
            f.deliver(listOf(f.job.copy(progress = 0.1f)))
            f.place()
            assertTrue(f.view.isAttachedToWindow)
            assertEquals(0.1f, f.pendingTarget(), 0f)
            val entries = f.entries()
            val recordings = f.recordingsMap()
            val diffs = f.work.diffs
            val created = f.work.created
            f.view.restorePosition(NativeListSavedPosition(20, 11))
            f.place()
            assertNull(f.view.scrollView.findViewHolderForAdapterPosition(0))
            val position = f.view.savePosition()
            repeat(30) {
                f.deliver(listOf(f.job.copy(progress = (it + 1) / 31f)))
                f.place()
                assertEquals(position, f.view.savePosition())
                assertEquals(diffs, f.work.diffs)
                assertEquals(created, f.work.created)
                assertSame(entries, f.entries())
                assertSame(recordings, f.recordingsMap())
            }
            f.active = true
            f.deliver(listOf(f.job.copy(progress = 30 / 31f)))
            f.view.restorePosition(NativeListSavedPosition(0, 0))
            f.place()
            assertEquals(30 / 31f, f.pendingTarget(), 0f)
            assertEquals(diffs, f.work.diffs)
            assertEquals(created, f.work.created)
        } finally { host.pause().stop().destroy() }
    }

    @Test fun progressPreservesScrolledAnchorAndInlineLeaseButRealChangesStillDiff() = Fixture(100, true).use { f ->
        f.deliver(listOf(f.job))
        f.place()
        f.view.restorePosition(NativeListSavedPosition(12, 17))
        f.place()
        val anchor = f.view.savePosition()
        val anchorId = f.adapter.getItemId(anchor.firstVisibleItemIndex)
        f.infoId = f.recordings[10].id
        f.deliver(listOf(f.job.copy(progress = 0.2f))) // Progress and modal state change together.
        f.place()
        val leases = f.leases.toList()
        assertTrue(leases.isNotEmpty())
        val diffs = f.work.diffs
        repeat(30) {
            f.deliver(listOf(f.job.copy(progress = (it + 1) / 31f)))
            f.place()
            assertEquals(anchor, f.view.savePosition())
            assertEquals(anchorId, f.adapter.getItemId(anchor.firstVisibleItemIndex))
            assertEquals(leases, f.leases)
            assertEquals(diffs, f.work.diffs)
        }
        f.infoId = null
        f.deliver(listOf(f.job.copy(phase = SavePhase.FAILED, error = "retry")))
        assertEquals(diffs, f.work.diffs) // Retry membership only changes the pending prefix.
        assertTrue(f.snapshot.pending.any { it === LibraryListItem.RetryFailedSaves })
        f.deliver(listOf(f.job, f.job.copy(id = "second")))
        assertEquals(diffs, f.work.diffs)
        f.deliver(listOf(f.job.copy(id = "second"), f.job))
        assertEquals(diffs, f.work.diffs) // Same count, changed prefix order.
        val oldStructure = f.snapshot.structure
        val renamed = f.recordings[0].copy(displayName = "enriched name")
        f.catalog(listOf(renamed) + f.recordings.drop(1))
        f.deliver(listOf(f.job.copy(id = "second"), f.job))
        assertNotSame(oldStructure, f.snapshot.structure)
        assertEquals(diffs + 1, f.work.diffs)
        assertSame(renamed, f.snapshot.finished.filterIsInstance<LibraryListItem.Recording>().first().recording)
        f.set("saveJobsReadiness", DurableSaveJobs.Readiness(recoveryFailure = "unavailable"))
        f.deliver(listOf(f.job))
        assertTrue(f.snapshot.pending.first() is LibraryListItem.SaveRecoveryStatus)
        f.set("saveJobsReadiness", DurableSaveJobs.Readiness(loaded = true))
        val published = f.recording("published").copy(fileIdentity = "fixture-identity")
        f.catalog(listOf(published) + f.recordings)
        f.deliver(listOf(f.job.copy(publishedRecordingId = published.id,
            publishedRecordingIdentity = published.fileIdentity, terminallyRetired = true)))
        assertTrue(f.snapshot.pending.isEmpty())
        assertEquals(101, f.snapshot.finished.count { it is LibraryListItem.Recording })
        assertEquals(1, f.snapshot.finished.filterIsInstance<LibraryListItem.Recording>().count { it.recording.id == published.id })
        f.place()
        assertEquals(anchorId, f.adapter.getItemId(f.view.savePosition().firstVisibleItemIndex))
    }

    @Test fun pendingStructureReusesFinishedEntriesAndInvalidatesOnlyTheBoundaryHeader() =
        Fixture(1000, true).use { f ->
            f.deliver(emptyList())
            f.place()
            val initial = f.snapshot
            val entries = f.entries().toList()
            val ids = entries.indices.map { f.adapter.getItemId(it) }
            val recordings = f.recordingsMap()
            val revision = f.revision()
            val diffs = f.work.diffs
            val created = f.work.created
            assertEquals(1000, created)
            assertEquals(8, f.headerLabelTop(0))

            fun assertFinishedRetained(prefixSize: Int) {
                assertEquals(prefixSize, f.snapshot.pending.size)
                assertSame(initial.finished, f.snapshot.finished)
                assertSame(recordings, f.recordingsMap())
                assertEquals(revision, f.revision())
                assertEquals(created, f.work.created)
                assertEquals(diffs, f.work.diffs)
                val current = f.entries()
                assertEquals(entries.size + prefixSize, current.size)
                for (index in entries.indices) {
                    assertSame(entries[index], current[index + prefixSize])
                    assertEquals(ids[index], f.adapter.getItemId(index + prefixSize))
                }
            }

            f.changes.clear()
            f.deliver(listOf(f.job))
            assertFinishedRetained(1)
            assertEquals(listOf(1 to 1), f.changes)
            f.place()
            assertEquals(NativeListSavedPosition(0, 0), f.view.savePosition())
            assertEquals(28, f.headerLabelTop(1))
            val jobId = f.adapter.getItemId(0)

            f.deliver(listOf(f.job.copy(phase = SavePhase.FAILED, error = "retry")))
            assertFinishedRetained(2)
            assertSame(LibraryListItem.RetryFailedSaves, f.snapshot.pending.first())
            assertEquals(jobId, f.adapter.getItemId(1))
            val retryId = f.adapter.getItemId(0)

            f.set("saveJobsReadiness", DurableSaveJobs.Readiness(recoveryFailure = "unavailable"))
            f.deliver(listOf(f.job))
            assertFinishedRetained(3)
            assertTrue(f.snapshot.pending.first() is LibraryListItem.SaveRecoveryStatus)
            assertEquals(retryId, f.adapter.getItemId(1))
            assertEquals(jobId, f.adapter.getItemId(2))

            f.set("saveJobsReadiness", DurableSaveJobs.Readiness(loaded = true))
            val second = f.job.copy(id = "second")
            f.deliver(listOf(f.job, second))
            assertFinishedRetained(2)
            val secondId = f.adapter.getItemId(1)
            f.deliver(listOf(second, f.job))
            assertFinishedRetained(2)
            assertEquals(secondId, f.adapter.getItemId(0))
            assertEquals(jobId, f.adapter.getItemId(1))

            f.place()
            f.changes.clear()
            f.deliver(emptyList())
            assertFinishedRetained(0)
            assertEquals(listOf(0 to 1), f.changes)
            f.place()
            assertEquals(NativeListSavedPosition(0, 0), f.view.savePosition())
            assertEquals(8, f.headerLabelTop(0))

            f.deliver(listOf(f.job))
            val anchorIndex = 60
            val anchorId = ids[anchorIndex]
            assertTrue(f.view.revealRecording(f.recordings[anchorIndex - 1].id))
            f.place()
            assertEquals(anchorId, f.adapter.getItemId(f.view.savePosition().firstVisibleItemIndex))
            f.deliver(emptyList())
            f.place()
            assertFinishedRetained(0)
            assertEquals(anchorIndex, f.view.savePosition().firstVisibleItemIndex)
            assertEquals(anchorId, f.adapter.getItemId(anchorIndex))
        }

    @Test fun playingOnlyChangesRefreshAffectedCachedRowsWithoutRebindingUnrelatedRows() = Fixture(100, true).use { f ->
        f.deliver(emptyList())
        @Suppress("UNCHECKED_CAST")
        val adapter = f.adapter as RecyclerView.Adapter<RecyclerView.ViewHolder>
        val targets = f.recordings.take(3).map { it.copy(fileIdentity = "identity-${it.id}") }
        f.catalog(targets + f.recordings.drop(3))
        f.deliver(emptyList())
        val holders = targets.map { recording ->
            val index = f.entries().indexOfFirst { Fixture.get(checkNotNull(it), "recording") == recording }
            assertTrue(index >= 0)
            adapter.createViewHolder(f.view.scrollView, adapter.getItemViewType(index)).also {
                adapter.bindViewHolder(it, index)
            }
        }
        try {
            val diffs = f.work.diffs
            val created = f.work.created
            f.work.binds.clear()
            f.view.submit(f.presentation.list.copy(playingRecording = targets[0]))
            adapter.onViewAttachedToWindow(holders[2])
            assertTrue("Unrelated cached row must not fully bind for playback", f.work.binds.isEmpty())
            adapter.onViewAttachedToWindow(holders[0])
            assertEquals(1, f.work.binds.size)
            assertTrue((Fixture.get(checkNotNull(Fixture.get(holders[0], "binding")), "row") as LibraryRecordingRow).isPlaying)
            f.work.binds.clear()
            f.view.submit(f.presentation.list.copy(playingRecording = targets[1]))
            adapter.onViewAttachedToWindow(holders[2])
            assertTrue(f.work.binds.isEmpty())
            adapter.onViewAttachedToWindow(holders[0])
            adapter.onViewAttachedToWindow(holders[1])
            assertEquals(2, f.work.binds.size)
            assertFalse((Fixture.get(checkNotNull(Fixture.get(holders[0], "binding")), "row") as LibraryRecordingRow).isPlaying)
            assertTrue((Fixture.get(checkNotNull(Fixture.get(holders[1], "binding")), "row") as LibraryRecordingRow).isPlaying)
            assertEquals(diffs, f.work.diffs)
            assertEquals(created, f.work.created)
        } finally { holders.forEach(adapter::onViewRecycled) }
    }

    @Test fun waveformProofRefreshesOnlyTheExpandedCachedHolder() =
        Fixture(100, true, allowPlayback = true).use { f ->
            f.deliver(emptyList())
            val target = f.recordings[0]
            val owner = InlinePlaybackRowOwner(target.id, automaticRestore = true).apply { consumeInitialAutoStart() }
            val waiting = f.presentation.list.copy(expandedOwner = owner, waveformReadAllowed = false)
            f.view.submit(waiting)

            @Suppress("UNCHECKED_CAST")
            val adapter = f.adapter as RecyclerView.Adapter<RecyclerView.ViewHolder>
            val holders = f.recordings.take(2).map { recording ->
                val index = f.entries().indexOfFirst { Fixture.get(checkNotNull(it), "recording") == recording }
                assertTrue(index >= 0)
                adapter.createViewHolder(f.view.scrollView, adapter.getItemViewType(index)).also {
                    adapter.bindViewHolder(it, index)
                    adapter.onViewAttachedToWindow(it)
                    adapter.onViewDetachedFromWindow(it)
                }
            }
            try {
                assertEquals(listOf(false), f.playbackWaveformAdmissions)
                val bindingRevision = f.bindingRevision()
                val diffs = f.work.diffs
                val created = f.work.created
                f.work.binds.clear()

                f.view.submit(waiting.copy(waveformReadAllowed = true))
                assertEquals(bindingRevision, f.bindingRevision())
                adapter.onViewAttachedToWindow(holders[1])
                assertTrue(f.work.binds.isEmpty())
                assertEquals(listOf(false), f.playbackWaveformAdmissions)

                adapter.onViewAttachedToWindow(holders[0])
                assertEquals(listOf("recording:${target.id}"), f.work.binds)
                assertEquals(listOf(false, true), f.playbackWaveformAdmissions)
                assertEquals(diffs, f.work.diffs)
                assertEquals(created, f.work.created)
            } finally { holders.forEach(adapter::onViewRecycled) }
        }

    @Test fun keyedReopenRevealAttachesOffscreenControlsAndExistingCancellationStopsLaterJump() =
        Fixture(100, true, allowPlayback = true).use { f ->
            val host = Robolectric.buildActivity(Activity::class.java).setup()
            try {
                host.get().setContentView(f.view)
                val target = f.recordings[80].copy(fileIdentity = "playing-identity")
                f.catalog(f.recordings.map { if (it.id == target.id) target else it })
                f.deliver(emptyList())
                f.place()
                assertNull(f.view.attachedRecordingAnchor(target.id))
                val owner = InlinePlaybackRowOwner(target.id).apply { consumeInitialAutoStart() }
                f.view.submit(f.presentation.list.copy(expandedOwner = owner, playingRecording = target))
                val diffs = f.work.diffs
                assertTrue(f.view.revealRecording(target.id))
                f.place()
                assertNotNull(f.view.attachedRecordingAnchor(target.id))
                assertTrue("The restored controls must receive visible-owner admission",
                    f.playbackBindings.any { it.first == target.id && it.second })
                assertFalse("Viewport work must not renew autoplay", owner.consumeInitialAutoStart())
                assertEquals(diffs, f.work.diffs)
                f.view.openAtTop()
                f.place()
                assertNull(f.view.attachedRecordingAnchor(target.id))
                assertTrue(f.view.revealRecording(target.id))
                f.view.cancelPendingRecordingReveal()
                f.place()
                assertNull(f.view.pendingRecordingRevealId)
                assertNull(f.view.attachedRecordingAnchor(target.id))
            } finally { host.pause().stop().destroy() }
        }

    private class Work : LibraryListWorkObserver {
        var diffs = 0
        var created = 0
        val binds = mutableListOf<String>()
        override fun finishedEntryCreated() { created++ }
        override fun catalogDiff() { diffs++ }
        override fun rowBound(key: String) { binds.add(key) }
    }

    private class Fixture(count: Int, var active: Boolean, private val allowPlayback: Boolean = false) : LifecycleOwner, AutoCloseable {
        override val lifecycle = LifecycleRegistry(this)
        private val context = RuntimeEnvironment.getApplication()
        private val frames = AndroidUiFrameClock(Choreographer.getInstance())
        private val viewScope = CoroutineScope(Dispatchers.Main.immediate + frames)
        // Isolate synchronous controller delivery. Repository/collector/physical-operation effects
        // are deliberately parked; these tests do not qualify flow transport or storage refresh.
        private val ownerScope = CoroutineScope(object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) = Unit
        })
        val recordings = List(count) { recording("row-$it") }
        val job = PendingSave("pending", "Saving", 1_700_000_000_000L, 1000L, SavePhase.SAVING, 0f)
        var infoId: String? = null
        val leases = mutableListOf<LibraryInlineExpansionLease>()
        val playbackBindings = mutableListOf<Pair<String, Boolean>>()
        val playbackWaveformAdmissions = mutableListOf<Boolean>()
        val leaseCloses = java.util.IdentityHashMap<LibraryInlineExpansionLease, Int>()
        val work = Work()
        val changes = mutableListOf<Pair<Int, Int>>()
        var nodeRefreshes = 0
        private var textOwner: NativePageTextAccessibilityOwner? = null
        val view = ReverbLibraryListView(context,
            ReverbViewEnvironment(Density(1f), createFontFamilyResolver(context), ReverbPalette.fallback(false)),
            viewScope, View(context), LibraryRecordingRowActions(null, null, null), object : LibraryInlineExpansionHost {
                override fun create(context: Context, recordingId: String): LibraryInlineExpansionLease {
                    check(allowPlayback)
                    return createLease(playback = true)
                }
                override fun createInfo(context: Context, recordingId: String) = createLease(playback = false)
            }, {}, onRowNodesChanged = {
                nodeRefreshes++
                textOwner?.refresh()
            })
        private fun createLease(playback: Boolean) = object : LibraryInlineExpansionLease {
            override val view = View(context).apply { if (playback) minimumHeight = 80 }
            override fun bind(recording: RecordingEntity, owner: InlinePlaybackRowOwner?, expanded: Boolean,
                screenActive: Boolean, trimRequested: Boolean) =
                bind(recording, owner, expanded, screenActive, trimRequested, waveformReadAllowed = true)
            override fun bind(recording: RecordingEntity, owner: InlinePlaybackRowOwner?, expanded: Boolean,
                screenActive: Boolean, trimRequested: Boolean, waveformReadAllowed: Boolean) {
                if (!playback) return
                playbackBindings.add(recording.id to (expanded && screenActive))
                if (expanded && screenActive) playbackWaveformAdmissions.add(waveformReadAllowed)
            }
            override fun setEnvironment(environment: ReverbViewEnvironment) = Unit
            override fun setPresentationAttached(attached: Boolean) = Unit
            override fun closePresentation() { leaseCloses[this] = checkNotNull(leaseCloses[this]) + 1 }
        }.also { leases.add(it); leaseCloses[it] = 0 }
        val adapter get() = checkNotNull(view.scrollView.adapter)
        lateinit var presentation: NativeLibraryPresentation
        val snapshot get() = presentation.list.items as LibraryListSnapshot
        private val owner = NativeLibraryController(context, View(context), this, ownerScope, frames, null,
            NativeLibraryInput(false, recordings, NativeLibraryCallbacks())) {
                presentation = it.copy(list = it.list.copy(screenActive = active, infoRecordingId = infoId))
                view.submit(presentation.list)
            }
        init {
            view.workObserver = work
            set("saveJobsReadiness", DurableSaveJobs.Readiness(loaded = true))
            if (recordings.isNotEmpty()) {
                @Suppress("UNCHECKED_CAST")
                val selected = get(owner, "selectedIds") as MutableMap<String, RecordingEntity>
                selected[recordings[0].id] = recordings[0]
            }
            adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
                override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) {
                    changes.add(positionStart to itemCount)
                }
            })
        }
        fun set(name: String, value: Any) {
            NativeLibraryController::class.java.getDeclaredField(name).apply { isAccessible = true }.set(owner, value)
        }
        fun deliver(jobs: List<PendingSave>) {
            set("pendingSaves", jobs)
            set("pendingItemsDirty", true)
            val outputs = pendingSaveOutputIdentities(jobs)
            if (outputs != get(owner, "pendingOutputIdentities")) {
                set("pendingOutputIdentities", outputs)
                set("visibleDirty", true)
            }
            NativeLibraryController::class.java.getDeclaredMethod("deliver").apply { isAccessible = true }.invoke(owner)
        }
        fun catalog(rows: List<RecordingEntity>) {
            NativeLibraryController::class.java.getDeclaredMethod("setRecordings", List::class.java)
                .apply { isAccessible = true }.invoke(owner, rows)
        }
        fun bindTextOwner() {
            val clips = smallthingz.ui.text.view.NativeTextSemanticsClipPolicy {
                smallthingz.ui.text.view.NativeTextSemanticsClip.Unclipped
            }
            textOwner = NativeWindowTextAccessibilityOwner(view, clips).bindContent(view, clips) { true }
        }
        fun flushNodes() = org.robolectric.shadows.ShadowLooper.idleMainLooper()
        @Suppress("UNCHECKED_CAST")
        fun textLeases(): Map<View, AutoCloseable> =
            (get(checkNotNull(textOwner), "leases") as Map<View, AutoCloseable>).toMap()
        fun entries() = get(adapter, "entries") as List<*>
        fun recordingsMap() = get(adapter, "recordingsById")
        fun headerLabelTop(position: Int): Int {
            val header = checkNotNull(view.scrollView.findViewHolderForAdapterPosition(position))
                .itemView as ReverbLibraryHeaderView
            return header.getChildAt(0).top
        }
        fun pendingTarget(): Float {
            val holder = checkNotNull(view.scrollView.findViewHolderForAdapterPosition(0))
            return get(checkNotNull(get(holder, "presentation")), "target") as Float
        }
        private fun rowUpdates() = get(view, "rowUpdates") as NativeLibraryListUpdates<*>
        fun revision() = rowUpdates().revision
        fun bindingRevision() = rowUpdates().bindingRevision
        fun place() {
            view.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, 400, 500)
        }
        fun recording(id: String) = RecordingEntity("/synthetic/$id.wav", "$id.wav", "audio/wav",
            1_700_000_000_000L, 1000L, 32044L, "PCM", RecordingStorageType.FILE, "/synthetic")
        override fun close() {
            try { owner.close() } finally {
                try {
                    textOwner?.close()
                    view.dispose()
                    leases.forEach { assertEquals("Each created inline lease retires once", 1, leaseCloses[it]) }
                } finally { ownerScope.cancel(); viewScope.cancel() }
            }
        }
        companion object {
            fun get(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name)
                .apply { isAccessible = true }.get(target)
        }
    }
}
