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
        assertEquals(diffs + 1, f.work.diffs) // Retry membership is structural.
        assertTrue(f.snapshot.pending.any { it === LibraryListItem.RetryFailedSaves })
        f.deliver(listOf(f.job, f.job.copy(id = "second")))
        assertEquals(diffs + 2, f.work.diffs)
        f.deliver(listOf(f.job.copy(id = "second"), f.job))
        assertEquals(diffs + 3, f.work.diffs) // Same count, changed order.
        val oldStructure = f.snapshot.structure
        val renamed = f.recordings[0].copy(displayName = "enriched name")
        f.catalog(listOf(renamed) + f.recordings.drop(1))
        f.deliver(listOf(f.job.copy(id = "second"), f.job))
        assertNotSame(oldStructure, f.snapshot.structure)
        assertEquals(diffs + 4, f.work.diffs)
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

    private class Work : LibraryListWorkObserver {
        var diffs = 0
        var created = 0
        val binds = mutableListOf<String>()
        override fun finishedEntryCreated() { created++ }
        override fun catalogDiff() { diffs++ }
        override fun rowBound(key: String) { binds.add(key) }
    }

    private class Fixture(count: Int, var active: Boolean) : LifecycleOwner, AutoCloseable {
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
        val leaseCloses = java.util.IdentityHashMap<LibraryInlineExpansionLease, Int>()
        val work = Work()
        val changes = mutableListOf<Pair<Int, Int>>()
        val view = ReverbLibraryListView(context,
            ReverbViewEnvironment(Density(1f), createFontFamilyResolver(context), ReverbPalette.fallback(false)),
            viewScope, View(context), LibraryRecordingRowActions(null, null, null), object : LibraryInlineExpansionHost {
                override fun create(context: Context, recordingId: String): LibraryInlineExpansionLease = error("No playback")
                override fun createInfo(context: Context, recordingId: String) = object : LibraryInlineExpansionLease {
                    override val view = View(context)
                    override fun bind(recording: RecordingEntity, owner: InlinePlaybackRowOwner?, expanded: Boolean,
                        screenActive: Boolean, trimRequested: Boolean) = Unit
                    override fun setEnvironment(environment: ReverbViewEnvironment) = Unit
                    override fun setPresentationAttached(attached: Boolean) = Unit
                    override fun closePresentation() { leaseCloses[this] = checkNotNull(leaseCloses[this]) + 1 }
                }.also { leases.add(it); leaseCloses[it] = 0 }
            }, {})
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
        fun entries() = get(adapter, "entries") as List<*>
        fun recordingsMap() = get(adapter, "recordingsById")
        fun pendingTarget(): Float {
            val holder = checkNotNull(view.scrollView.findViewHolderForAdapterPosition(0))
            return get(checkNotNull(get(holder, "presentation")), "target") as Float
        }
        fun revision() = (get(view, "rowUpdates") as NativeLibraryListUpdates<*>).revision
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
