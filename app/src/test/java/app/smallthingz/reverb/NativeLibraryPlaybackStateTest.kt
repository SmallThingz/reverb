package app.smallthingz.reverb

import android.app.Application
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLooper
import smallthingz.ui.runtime.MonotonicFrameClock
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class NativeLibraryPlaybackStateTest {
    private val a = recording("a")
    private val b = recording("b")

    @Test fun indicatorFollowsActualPlayingRatherThanPendingTransportIntent() = Fixture(listOf(a, b)).use { f ->
        f.open()
        f.emit(playing(a).copy(playing = false, wantsPlayback = true))
        assertNull(f.latest.list.playingRecording)
        assertNull(f.latest.list.expandedOwner)

        f.emit(playing(a).copy(wantsPlayback = false))
        assertEquals(a, f.latest.list.playingRecording)
        assertNull("Playback starting during an open session must not expand a row", f.latest.list.expandedOwner)
        val catalog = f.latest.list.items
        f.emit(playing(b))
        assertEquals(b, f.latest.list.playingRecording)
        assertSame(catalog, f.latest.list.items)

        f.emit(playing(b).copy(playing = false, wantsPlayback = true))
        assertNull(f.latest.list.playingRecording)
        assertSame(catalog, f.latest.list.items)
    }

    @Test fun positionAndTransportMetadataTicksDoNotPublishRowsOrRebuildCatalog() = Fixture(listOf(a), playing(a)).use { f ->
        f.open()
        val publications = f.publications.size
        val catalog = f.latest.list.items
        val owner = f.latest.list.expandedOwner
        repeat(50) { tick ->
            f.emit(playing(a.copy(displayName = "updated label", lastSeenAtMillis = 99L)).copy(
                positionMillis = tick + 1,
                durationMillis = 3_000,
                transportIntentRevision = tick.toLong(),
                wantsPlayback = tick % 2 == 0,
                playbackAnchor = PlaybackPositionAnchor(tick + 1, tick.toLong(), true, 3_000, 1L, a.id),
            ))
        }
        assertEquals(publications, f.publications.size)
        assertSame(catalog, f.latest.list.items)
        assertSame(owner, f.latest.list.expandedOwner)
    }

    @Test fun hiddenPauseAndSourceSwitchDoNotPublishButReopenUsesLatestServiceState() = Fixture(listOf(a, b), playing(a)).use { f ->
        f.open()
        f.hide()
        val hiddenPublications = f.publications.size
        f.emit(playing(a).copy(playing = false, wantsPlayback = false))
        assertEquals("Hidden pause must not publish Library rows", hiddenPublications, f.publications.size)
        f.emit(playing(b))
        assertEquals("Hidden source switch must not publish Library rows", hiddenPublications, f.publications.size)
        f.open()
        assertEquals(b, f.latest.list.playingRecording)
        assertEquals(b.id, f.latest.list.expandedOwner?.recordingId)
        assertNoAutoStart(checkNotNull(f.latest.list.expandedOwner))

        f.hide()
        val beforePause = f.publications.size
        f.emit(playing(b).copy(playing = false, wantsPlayback = true))
        assertEquals(beforePause, f.publications.size)
        f.open()
        assertNull(f.latest.list.playingRecording)
        assertNull(f.latest.list.expandedOwner)
    }

    @Test fun reopenSamplesLatestServiceStateBeforeQueuedCollectorDelivery() = Fixture(listOf(a, b), playing(a)).use { f ->
        f.open()
        f.hide()
        f.setPlaybackWithoutDelivery(playing(b))
        f.beginOpen()
        ShadowLooper.idleMainLooper()
        assertEquals(b, f.latest.list.playingRecording)
        assertNull(f.latest.list.expandedOwner)
        f.finishOpen()
        assertEquals(b.id, f.latest.list.expandedOwner?.recordingId)
        assertNoAutoStart(checkNotNull(f.latest.list.expandedOwner))
    }

    @Test fun hideAndReopenPublishesFreshOwnerWithGrantAlreadySpentAndNeverStartsService() = Fixture(listOf(a), playing(a)).use { f ->
        var publishedOwners = 0
        f.onPublish = { presentation ->
            presentation.list.expandedOwner?.let { owner ->
                publishedOwners++
                assertEquals(a.id, owner.recordingId)
                assertNoAutoStart(owner)
            }
        }
        val ordinaryPlayback = RecordingPlayback.state.value
        f.open()
        val original = checkNotNull(f.latest.list.expandedOwner)
        assertTrue(f.controller.isCurrentPlaybackOwner(original))
        f.hide()
        assertNull(f.latest.list.expandedOwner)
        assertFalse(f.controller.isCurrentPlaybackOwner(original))
        assertEquals(a, f.latest.list.playingRecording)

        f.open()
        val restored = checkNotNull(f.latest.list.expandedOwner)
        assertNotSame(original, restored)
        assertTrue(publishedOwners >= 2)
        assertTrue(f.controller.isCurrentPlaybackOwner(restored))

        // Completion after restoration, followed by a recycled row binding, cannot replay audio.
        f.emit(playing(a).copy(playing = false, wantsPlayback = false, positionMillis = 2_000))
        assertNull(f.latest.list.playingRecording)
        assertSame(restored, f.latest.list.expandedOwner)
        assertNoAutoStart(restored)
        assertEquals(ordinaryPlayback, RecordingPlayback.state.value)
        assertNull(shadowOf(f.context).nextStartedService)
        assertFalse(RecordingPlayback.hasPendingStart())
        f.hide()
        f.open()
        assertNull(f.latest.list.expandedOwner)
    }

    @Test fun manualCollapsePersistsForSameOpenButNextOpenRestoresPlayingControls() = Fixture(listOf(a), playing(a)).use { f ->
        f.open()
        f.controller.collapse()
        f.settle()
        f.emit(playing(a).copy(positionMillis = 800))
        f.controller.bind(f.input.copy(active = true))
        f.settle()
        assertNull(f.latest.list.expandedOwner)
        assertEquals(a, f.latest.list.playingRecording)
        f.hide()
        f.open()
        assertEquals(a.id, f.latest.list.expandedOwner?.recordingId)
        assertNoAutoStart(checkNotNull(f.latest.list.expandedOwner))
    }

    @Test fun pausedPendingEndedAndStoppedPlaybackNeverRestoreOnReopen() {
        for (state in listOf(
            playing(a).copy(playing = false, wantsPlayback = false, positionMillis = 500),
            playing(a).copy(playing = false, wantsPlayback = true, prepared = false),
            playing(a).copy(playing = false, wantsPlayback = false, positionMillis = 2_000),
            RecordingPlaybackState(),
        )) Fixture(listOf(a), playing(a)).use { f ->
            f.open()
            f.hide()
            f.emit(state)
            f.open()
            assertNull("Must not restore $state", f.latest.list.expandedOwner)
            assertNull(f.latest.list.playingRecording)
        }
    }

    @Test fun reopenRequiresExactIdStorageNonblankIdentityAndSize() {
        val mismatches = listOf(
            a.copy(id = b.id),
            a.copy(storageType = RecordingStorageType.DOCUMENT),
            a.copy(fileIdentity = "replacement"),
            a.copy(fileIdentity = ""),
            a.copy(fileIdentity = "   "),
            a.copy(sizeBytes = a.sizeBytes + 1),
        )
        for (candidate in mismatches) Fixture(listOf(a), playing(candidate)).use { f ->
            f.open()
            assertEquals(listOf(a), f.latest.recordings)
            assertNull("Must not restore mismatching playback source $candidate", f.latest.list.expandedOwner)
        }
        for (identity in listOf("", "   ")) {
            val unidentified = a.copy(fileIdentity = identity)
            Fixture(listOf(unidentified), playing(unidentified)).use { f ->
                f.open()
                assertNull("Even equal blank identities cannot restore", f.latest.list.expandedOwner)
            }
        }
    }

    @Test fun catalogOnlyMetadataChangesStillRestoreMatchingPlayback() {
        val renamed = a.copy(displayName = "new label", lastSeenAtMillis = 900L)
        Fixture(listOf(renamed), playing(a)).use { f ->
            f.open()
            assertEquals(renamed.id, f.latest.list.expandedOwner?.recordingId)
            assertNoAutoStart(checkNotNull(f.latest.list.expandedOwner))
        }
    }

    @Test fun sameIdReplacementPlaybackPublishesNewIdentityWithoutCatalogWork() = Fixture(listOf(a), playing(a)).use { f ->
        f.open()
        val catalog = f.latest.list.items
        var count = f.publications.size
        for (replacement in listOf(a.copy(fileIdentity = "replacement"), a.copy(sizeBytes = a.sizeBytes + 1),
            a.copy(storageType = RecordingStorageType.DOCUMENT))) {
            f.emit(playing(replacement))
            assertEquals(++count, f.publications.size)
            assertEquals(replacement, f.latest.list.playingRecording)
            assertSame(catalog, f.latest.list.items)
        }
    }

    @Test fun userActionsBeforeReopenReadCompletesKeepTheirOwnPresentation() {
        for (action in listOf("collapse", "rename", "info", "menu", "selection", "trim", "expand")) {
            Fixture(listOf(a, b), playing(a)).use { f ->
                f.beginOpen()
                when (action) {
                    "collapse" -> f.controller.collapse()
                    "rename" -> f.controller.rename(b)
                    "info" -> f.controller.info(b)
                    "menu" -> f.controller.longClick(b)
                    "selection" -> f.controller.toggleSelection(b)
                    "trim" -> f.controller.trim(b)
                    "expand" -> f.controller.activate(b)
                }
                f.finishOpen()
                when (action) {
                    "rename" -> assertEquals(b.id, f.latest.renameRecording?.id)
                    "info" -> assertEquals(b.id, f.latest.infoRecording?.id)
                    "menu" -> assertEquals(b.id, f.latest.menuRecording?.id)
                    "selection" -> assertEquals(setOf(b.id), f.latest.list.selectedIds)
                    "trim" -> assertEquals(b.id, f.latest.list.trimRequestRecordingId)
                }
                assertEquals(action, if (action in listOf("trim", "expand")) b.id else null,
                    f.latest.list.expandedOwner?.recordingId)
                when (action) {
                    "rename" -> f.controller.dismissRename()
                    "info" -> f.controller.dismissInfo()
                    "menu" -> f.controller.dismissMenu()
                    "selection" -> f.controller.clearSelection()
                    "trim" -> f.controller.trimRequestConsumed(b.id)
                }
                f.emit(playing(a).copy(positionMillis = 850))
                assertNotEquals("Dismissing $action must not resurrect deferred restoration", a.id,
                    f.latest.list.expandedOwner?.recordingId)
            }
        }
    }

    @Test fun newlyHydratedPlayingRowSurvivesReconciliationOfPreviousVisibleCatalog() {
        for (previousRows in listOf(listOf(b), listOf(a.copy(fileIdentity = "old-revision"), b))) {
            Fixture(previousRows, playing(a)).use { f ->
                assertEquals(previousRows.map { it.id }, f.latest.recordings.map { it.id })
                f.replaceCatalog(listOf(a, b))
                var restoredPublications = 0
                f.onPublish = { presentation ->
                    presentation.list.expandedOwner?.let {
                        restoredPublications++
                        assertEquals(a.id, it.recordingId)
                        assertNoAutoStart(it)
                    }
                }
                f.beginOpen()
                // Deliver the active publication before admitting the catalog-read coroutine.
                ShadowLooper.idleMainLooper()
                assertNull("Retained metadata alone cannot restore before hydration", f.latest.list.expandedOwner)
                f.finishOpen()
                assertEquals(a.id, f.latest.list.expandedOwner?.recordingId)
                assertEquals("identity-a", f.latest.recordings.single { it.id == a.id }.fileIdentity)
                assertTrue(restoredPublications > 0)
                f.settle()
                assertEquals(a.id, f.latest.list.expandedOwner?.recordingId)
            }
        }
    }

    @Test fun manualCollapseBeforeHydrationWinsEvenWhenCatalogReconciliationAddsPlayingRow() =
        Fixture(listOf(b), playing(a)).use { f ->
            f.replaceCatalog(listOf(a, b))
            f.beginOpen()
            ShadowLooper.idleMainLooper()
            assertNull(f.latest.list.expandedOwner)
            f.controller.collapse()
            f.finishOpen()
            assertTrue(f.latest.recordings.any { it.id == a.id })
            assertNull(f.latest.list.expandedOwner)
            f.emit(playing(a).copy(positionMillis = 900))
            assertNull(f.latest.list.expandedOwner)
        }

    @Test fun restoredControlsRequestOneKeyedRevealAfterTheirPublication() = Fixture(listOf(a), playing(a)).use { f ->
        val reveals = mutableListOf<String>()
        f.onPublish = { presentation ->
            f.controller.takePlaybackRestoreReveal()?.let { id ->
                assertEquals(id, presentation.list.expandedOwner?.recordingId)
                assertTrue(presentation.recordings.any { it.id == id })
                assertNoAutoStart(checkNotNull(presentation.list.expandedOwner))
                reveals.add(id)
            }
        }
        f.open()
        assertEquals(listOf(a.id), reveals)
        assertNull(f.controller.takePlaybackRestoreReveal())
        f.controller.dateFormattingChanged()
        f.settle()
        f.emit(playing(a).copy(positionMillis = 950))
        assertEquals(listOf(a.id), reveals)
        f.hide()
        f.open()
        assertEquals(listOf(a.id, a.id), reveals)
    }

    @Test fun revealRechecksLivePlaybackIdentityAndUserCancellationBeforeConsumption() {
        for (reason in listOf("pause", "source", "identity", "size", "storage", "scroll", "collapse", "hide")) {
            Fixture(listOf(a, b), playing(a)).use { f ->
                f.open()
                assertEquals(a.id, f.latest.list.expandedOwner?.recordingId)
                when (reason) {
                    "pause" -> f.setPlaybackWithoutDelivery(playing(a).copy(playing = false, wantsPlayback = true))
                    "source" -> f.setPlaybackWithoutDelivery(playing(b))
                    "identity" -> f.setPlaybackWithoutDelivery(playing(a.copy(fileIdentity = "replacement")))
                    "size" -> f.setPlaybackWithoutDelivery(playing(a.copy(sizeBytes = a.sizeBytes + 1)))
                    "storage" -> f.setPlaybackWithoutDelivery(playing(a.copy(storageType = RecordingStorageType.DOCUMENT)))
                    "scroll" -> f.controller.cancelPlaybackRestore()
                    "collapse" -> f.controller.collapse()
                    "hide" -> f.hide()
                }
                // Deliberately do not drain the flow collector: viewport admission reads live state.
                assertNull("Stale reveal must be rejected after $reason", f.controller.takePlaybackRestoreReveal())
                f.setPlaybackWithoutDelivery(playing(a))
                assertNull("Rejected reveal cannot be retried after $reason", f.controller.takePlaybackRestoreReveal())
            }
        }
    }

    @Test fun userScrollBeforeHydrationCancelsRestoreWithoutChangingTransport() = Fixture(listOf(b), playing(a)).use { f ->
        f.replaceCatalog(listOf(a, b))
        f.beginOpen()
        f.controller.cancelPlaybackRestore()
        f.finishOpen()
        assertTrue(f.latest.recordings.any { it.id == a.id })
        assertEquals(a, f.latest.list.playingRecording)
        assertNull(f.latest.list.expandedOwner)
        assertNull(f.controller.takePlaybackRestoreReveal())
    }

    @Test fun openingTransportSupersessionBeforeHydrationNeverRestoresEitherSource() {
        for (next in listOf(
            playing(a).copy(generation = 1),
            playing(a).copy(transportIntentRevision = 1),
            playing(a).copy(playing = false, wantsPlayback = true),
            playing(b),
            playing(a.copy(fileIdentity = "replacement")),
        )) Fixture(listOf(a, b), playing(a)).use { f ->
            f.beginOpen()
            f.setPlaybackWithoutDelivery(next)
            f.finishOpen()
            assertNull("Opening intent was superseded by $next", f.latest.list.expandedOwner)
            assertNull(f.controller.takePlaybackRestoreReveal())
        }
    }

    @Test fun cheapCatalogMissRetainsOpeningIntentThroughFirstAuthoritativeResult() = Fixture(listOf(b), playing(a)).use { f ->
        f.open()
        assertNull(f.latest.list.expandedOwner)
        assertNull(f.controller.takePlaybackRestoreReveal())
        f.emit(playing(a).copy(positionMillis = 900))
        f.controller.dateFormattingChanged()
        f.settle()
        assertNull(f.latest.list.expandedOwner)
        f.publishRefreshResult(listOf(a, b))
        assertEquals(a.id, f.latest.list.expandedOwner?.recordingId)
        assertNoAutoStart(checkNotNull(f.latest.list.expandedOwner))
        assertEquals(a.id, f.controller.takePlaybackRestoreReveal())
        assertNull(f.controller.takePlaybackRestoreReveal())
    }

    @Test fun collapseOrScrollAfterCheapMissCancelsAuthoritativeRecovery() {
        for (scroll in listOf(false, true)) Fixture(listOf(b), playing(a)).use { f ->
            f.open()
            assertNull(f.latest.list.expandedOwner)
            if (scroll) f.controller.cancelPlaybackRestore() else f.controller.collapse()
            f.publishRefreshResult(listOf(a, b))
            assertTrue(f.latest.recordings.any { it.id == a.id })
            assertNull(f.latest.list.expandedOwner)
            assertNull(f.controller.takePlaybackRestoreReveal())
        }
    }

    @Test fun transportSupersessionAfterCheapMissCancelsAuthoritativeRecovery() {
        for (next in listOf(
            playing(a).copy(generation = 1),
            playing(a).copy(transportIntentRevision = 1),
            playing(a).copy(playing = false, wantsPlayback = true),
            playing(b),
            playing(a.copy(fileIdentity = "replacement")),
        )) Fixture(emptyList(), playing(a)).use { f ->
            f.open()
            assertNull(f.latest.list.expandedOwner)
            f.emit(next)
            f.publishRefreshResult(listOf(checkNotNull(next.recording)))
            assertEquals(next.recording, f.latest.recordings.single())
            assertNull("Late catalog recovery cannot restore superseded opening transport $next", f.latest.list.expandedOwner)
            assertNull(f.controller.takePlaybackRestoreReveal())
        }
    }

    @Test fun firstAuthoritativeMissOrFailureRetiresOpeningIntentBeforeLaterCatalogArrival() {
        for (result in listOf<List<RecordingEntity>?>(listOf(b), null)) Fixture(listOf(b), playing(a)).use { f ->
            f.open()
            assertNull(f.latest.list.expandedOwner)
            // The successful miss deliberately retains identical rows, exercising the terminal
            // boundary even when visible-catalog identity/equality has not changed.
            f.publishRefreshResult(result)
            assertNull(f.latest.list.expandedOwner)
            f.controller.bind(f.input.copy(active = true, initialRecordings = listOf(a, b)))
            f.settle()
            assertTrue(f.latest.recordings.any { it.id == a.id })
            assertNull("The first authoritative ${if (result == null) "failure" else "miss"} ended restoration", f.latest.list.expandedOwner)
            assertNull(f.controller.takePlaybackRestoreReveal())
        }
    }

    @Test fun pendingDeletionNeverRestoresEvenIfPlaybackSnapshotHasNotCaughtUp() = Fixture(listOf(a, b), playing(a)).use { f ->
        f.beginOpen()
        f.controller.deleteRecordings(listOf(a))
        f.finishOpen()
        assertFalse(f.latest.recordings.any { it.id == a.id })
        assertNull(f.latest.list.expandedOwner)
    }

    @Test fun deletedCatalogRowAndReusedIdCannotRestoreStalePlaybackOnReopen() {
        for (replacement in listOf<RecordingEntity?>(null, a.copy(fileIdentity = "replacement"))) {
            Fixture(listOf(a, b), playing(a)).use { f ->
                f.open()
                f.hide()
                f.replaceCatalog(listOfNotNull(replacement, b))
                f.open()
                assertEquals(replacement, f.latest.recordings.singleOrNull { it.id == a.id })
                assertTrue(f.latest.recordings.any { it.id == b.id })
                assertNull(f.latest.list.expandedOwner)
            }
        }
    }

    @Test fun mutationAndLocalBusyOwnershipSuppressRestoreWithoutDeferredExpansion() {
        for (global in listOf(false, true)) Fixture(listOf(a), playing(a)).use { f ->
            val token = if (global) checkNotNull(recordingMutations.tryBegin(a.id)) else null
            try {
                if (!global) f.controller.localBusy(a.id, true)
                f.open()
                assertNull(f.latest.list.expandedOwner)
                if (token != null) recordingMutations.finish(token) else f.controller.localBusy(a.id, false)
                f.settle()
                assertNull("Finishing mutation during the same open must not expand", f.latest.list.expandedOwner)
                f.hide()
                f.open()
                assertEquals(a.id, f.latest.list.expandedOwner?.recordingId)
            } finally {
                token?.let(recordingMutations::finish)
            }
        }
    }

    @Test fun automaticControlsWaitForCurrentActivationWaveformProofButManualExpansionDoesNot() {
        val cached = a.copy(waveformData = encodeRecordingWaveform(FloatArray(RANGE_WAVEFORM_DETAIL_BUCKETS) { .4f }),
            waveformRevision = recordingWaveformRevision(a))
        Fixture(listOf(cached), playing(cached)).use { f ->
            f.open()
            val owner = checkNotNull(f.latest.list.expandedOwner)
            assertTrue(owner.automaticRestore)
            assertNoAutoStart(owner)
            assertFalse("Cheap catalog hydration does not authorize waveform IO", f.latest.list.waveformReadAllowed)
            assertTrue(f.latest.recordings.single().waveformData.isEmpty())
            f.publishRefreshResult(listOf(cached))
            assertSame(owner, f.latest.list.expandedOwner)
            assertTrue(f.latest.list.waveformReadAllowed)
            assertEquals(cached.waveformData, f.latest.recordings.single().waveformData)
            f.hide()
            f.open()
            assertFalse("An earlier activation's proof cannot authorize this reopen", f.latest.list.waveformReadAllowed)
            assertNoAutoStart(checkNotNull(f.latest.list.expandedOwner))
        }
        Fixture(listOf(a)).use { f ->
            f.open()
            f.controller.activate(a)
            f.settle()
            assertFalse(checkNotNull(f.latest.list.expandedOwner).automaticRestore)
            assertTrue("Existing manual expansion keeps its waveform admission", f.latest.list.waveformReadAllowed)
        }
    }

    @Test fun failedAuthoritativeRefreshDoesNotAdmitAutomaticWaveform() = Fixture(listOf(a), playing(a)).use { f ->
        f.open()
        val owner = checkNotNull(f.latest.list.expandedOwner)
        assertFalse(f.latest.list.waveformReadAllowed)
        f.publishRefreshResult(null)
        assertSame(owner, f.latest.list.expandedOwner)
        assertFalse(f.latest.list.waveformReadAllowed)
        assertNoAutoStart(owner)
    }

    private fun assertNoAutoStart(owner: InlinePlaybackRowOwner) {
        val row = InlinePlayerBookkeeping()
        var seeks = 0
        assertFalse(submitInlinePlaybackAutoStart(row, owner, prepared = true,
            lifecycleResumed = true, blocked = false) { seeks++; row.requestSeek(0, owner) })
        assertEquals(0, seeks)
        assertFalse(row.seeks.pending)
        assertNull(row.seekResumeOwner)
    }

    private fun playing(recording: RecordingEntity) = RecordingPlaybackState(recording = recording,
        prepared = true, playing = true, wantsPlayback = true, positionMillis = 400, durationMillis = 2_000)

    private fun recording(name: String): RecordingEntity {
        val directory = getSavedRecordingsDirectory(RuntimeEnvironment.getApplication())
        return RecordingEntity(File(directory, "$name.wav").absolutePath, "$name.wav", "audio/wav",
            1L, 2_000L, 192_044L, "PCM", RecordingStorageType.FILE, directory.absolutePath, "identity-$name",
            createdAtMillis = 1L, lastSeenAtMillis = 1L)
    }

    private class Fixture(
        rows: List<RecordingEntity>,
        initialPlayback: RecordingPlaybackState = RecordingPlaybackState(),
    ) : LifecycleOwner, AutoCloseable {
        override val lifecycle = LifecycleRegistry(this).apply { currentState = Lifecycle.State.STARTED }
        val context: Application = RuntimeEnvironment.getApplication()
        private val dispatcher = OwnerDispatcher()
        private val parent = CoroutineScope(dispatcher)
        private val frames = HeldFrames()
        private val playback = MutableStateFlow(initialPlayback)
        private val deletionReceipt = CompletableDeferred<RecordingDeletionBatchResult>()
        private val dao = RecordingDatabase.getInstance(context).recordingDao()
        val input = NativeLibraryInput(false, rows, NativeLibraryCallbacks())
        val publications = mutableListOf<NativeLibraryPresentation>()
        var onPublish: (NativeLibraryPresentation) -> Unit = {}
        val latest: NativeLibraryPresentation get() = publications.last()
        val controller: NativeLibraryController

        init {
            replaceCatalog(rows)
            controller = NativeLibraryController(context, View(context), this, parent, frames, null, input,
                submitBackgroundDeletion = { _, _ -> deletionReceipt }, playbackState = playback,
                publish = { onPublish(it); publications.add(it) })
            settle()
        }

        fun replaceCatalog(rows: List<RecordingEntity>) = runBlocking {
            val retainedIds = rows.map { it.id }.toSet()
            dao.applyChanges(rows, dao.listAll().map { it.id }.filterNot { it in retainedIds })
            assertEquals("Fixture rows must survive real catalog decoding", rows.toSet(), dao.listAll().toSet())
        }

        fun publishRefreshResult(rows: List<RecordingEntity>?) {
            // Seed only the completed scanner result while its first frame remains parked. The
            // real queued deliver() still reconciles visible rows, validates opening transport,
            // spends the auto-start grant, and publishes. This does not test repository scanning.
            val type = NativeLibraryController::class.java
            if (rows != null) type.getDeclaredMethod("setRecordings", List::class.java)
                .apply { isAccessible = true }.invoke(controller, rows)
            type.getDeclaredMethod("setHasLoaded", java.lang.Boolean.TYPE)
                .apply { isAccessible = true }.invoke(controller, rows != null)
            type.getDeclaredMethod("setLoadFailed", java.lang.Boolean.TYPE)
                .apply { isAccessible = true }.invoke(controller, rows == null)
            type.getDeclaredField("waveformCatalogReady")
                .apply { isAccessible = true }.setBoolean(controller, rows != null)
            type.getDeclaredField("playbackRestoreRefreshFinished")
                .apply { isAccessible = true }.setBoolean(controller, true)
            controller.dateFormattingChanged()
            settle()
        }

        fun beginOpen() { controller.bind(input.copy(active = true)) }
        fun finishOpen() {
            // The real catalog read runs on IO. Drain only owner continuations until it reaches
            // the held first frame; no scan, 250 ms refresh timer, or provider work is admitted.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (frames.pending.get() == 0) {
                dispatcher.drain()
                if (frames.pending.get() != 0) break
                val remaining = deadline - System.nanoTime()
                check(remaining > 0) { "Library catalog read did not reach its first frame" }
                checkNotNull(dispatcher.tasks.poll(remaining, TimeUnit.NANOSECONDS)) {
                    "Library catalog read did not resume its owner"
                }.run()
            }
            settle()
        }
        fun open() { beginOpen(); finishOpen() }
        fun hide() { controller.bind(input); settle(); assertEquals(0, frames.pending.get()) }
        fun setPlaybackWithoutDelivery(state: RecordingPlaybackState) { playback.value = state }
        fun emit(state: RecordingPlaybackState) { playback.value = state; settle() }
        fun settle() {
            repeat(20) {
                dispatcher.drain()
                ShadowLooper.idleMainLooper()
                if (dispatcher.tasks.isEmpty()) return
            }
            error("Library owner did not settle within twenty dispatch turns")
        }
        override fun close() {
            try { controller.close() } finally {
                parent.cancel()
                deletionReceipt.cancel()
                settle()
                runBlocking { dao.applyChanges(emptyList(), dao.listAll().map { it.id }) }
                RecordingDatabase.resetAfterCorruption()
            }
        }
    }

    private class OwnerDispatcher : CoroutineDispatcher() {
        val tasks = LinkedBlockingQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.add(block) }
        fun drain() { while (true) (tasks.poll() ?: return).run() }
    }

    private class HeldFrames : MonotonicFrameClock {
        val pending = AtomicInteger()
        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R = suspendCancellableCoroutine { continuation ->
            pending.incrementAndGet()
            continuation.invokeOnCancellation { pending.decrementAndGet() }
        }
    }
}
