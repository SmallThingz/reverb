package app.smallthingz.reverb.nativeui

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.view.Choreographer
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.OnBackPressedDispatcher
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import app.smallthingz.reverb.DurableSaveCoordinator
import app.smallthingz.reverb.DurableSaveJobs
import app.smallthingz.reverb.DurableSaveNotifications
import app.smallthingz.reverb.IncidentHistoryAccess
import app.smallthingz.reverb.IncidentHistoryController
import app.smallthingz.reverb.IncidentPresentation
import app.smallthingz.reverb.JournalHostIoShadow
import app.smallthingz.reverb.NativeIncidentsScreenOwner
import app.smallthingz.reverb.NativeLibraryInput
import app.smallthingz.reverb.NativeMainNavigationState
import app.smallthingz.reverb.NativeMainSavedNavigation
import app.smallthingz.reverb.NativeMainScreenInput
import app.smallthingz.reverb.NativeMainScreenSavedState
import app.smallthingz.reverb.NativeMainScreenView
import app.smallthingz.reverb.RANGE_WAVEFORM_DETAIL_BUCKETS
import app.smallthingz.reverb.RecordingDatabase
import app.smallthingz.reverb.RecordingEntity
import app.smallthingz.reverb.RecordingIncident
import app.smallthingz.reverb.RecordingStorageType
import app.smallthingz.reverb.ServiceStateOsShadow
import app.smallthingz.reverb.durableSaveJournalRoot
import app.smallthingz.reverb.encodeRecordingWaveform
import app.smallthingz.reverb.getOutputDirectoryId
import app.smallthingz.reverb.listedProviderRecordingIdentity
import app.smallthingz.reverb.prepareIncidentHistory
import app.smallthingz.reverb.recordingIncidentPresentationKey
import app.smallthingz.reverb.recordingWaveformRevision
import app.smallthingz.reverb.toggleRecordingIncidentAcknowledgement
import java.time.Duration
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowContentResolver
import smallthingz.ui.MotionDurationScale
import smallthingz.ui.geometry.Offset
import smallthingz.ui.input.phase.NativeViewPhasePointerNode
import smallthingz.ui.input.phase.NativeViewPointerHitLayout
import smallthingz.ui.input.phase.NativeWindowPointerCoordinates
import smallthingz.ui.input.phase.nativePointerHitPath
import smallthingz.ui.input.pointer.PointerType
import smallthingz.ui.platform.AndroidUiFrameClock
import smallthingz.ui.reverb.ReverbIncidentCardView
import smallthingz.ui.text.font.createFontFamilyResolver
import smallthingz.ui.text.view.NativeTextSemanticsClip
import smallthingz.ui.text.view.NativeTextSemanticsClipPolicy
import smallthingz.ui.unit.Density

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeMainLibraryScrollbarGestureTest {
    @Test fun heldThumbExcludesSecondFingerEdgeDismissAndPullRefresh() = Fixture().use { f ->
        val thumb = f.wakeThumb()
        val edge = f.point(8f, 100f)
        val center = f.point(f.viewport.width / 2f, 100f)
        val initial = f.offset

        f.touch(MotionEvent.ACTION_DOWN, thumb)
        assertTrue(f.viewport.isScrollIndicatorDragging)
        f.touch(pointerDown, thumb, edge)
        f.touch(MotionEvent.ACTION_MOVE, thumb, edge + Offset(0f, 200f))
        f.touch(pointerUp, thumb, edge + Offset(0f, 200f))
        assertTrue(f.libraryOpen)

        f.touch(pointerDown, thumb, center)
        f.touch(MotionEvent.ACTION_MOVE, thumb, center + Offset(0f, 220f))
        assertEquals(initial, f.offset)
        assertEquals(0f, f.pull.distanceFraction, 0f)

        val movedThumb = thumb + Offset(0f, 160f)
        f.touch(MotionEvent.ACTION_MOVE, movedThumb, center + Offset(0f, 220f))
        f.place() // A distant thumb seek places its anchor in the next traversal.
        assertTrue(f.offset > initial)
        f.touch(MotionEvent.ACTION_POINTER_UP, movedThumb, center + Offset(0f, 220f))
        val afterThumb = f.offset
        // The remaining pointer's retained path must still end at Scaffold after thumb UP.
        f.touch(MotionEvent.ACTION_MOVE, center, firstId = 1)
        f.touch(MotionEvent.ACTION_UP, center, firstId = 1)
        assertEquals(afterThumb, f.offset)
        assertFalse(f.pull.isRefreshing)
        assertTrue(f.libraryOpen)
    }

    @Test fun aSecondFingerOnTheThumbCannotTakeOverAnExistingBodyReservation() {
        for (atEdge in listOf(true, false)) Fixture().use { f ->
            val thumb = f.wakeThumb()
            val body = f.point(if (atEdge) 8f else f.viewport.width / 2f, 170f)
            val initial = f.offset

            // Reserve the body with DOWN alone, before either edge or scroll has crossed slop.
            f.touch(MotionEvent.ACTION_DOWN, body)
            f.touch(pointerDown, body, thumb)
            assertFalse(f.viewport.isScrollIndicatorDragging)
            val draggedThumb = thumb + Offset(0f, 160f)
            f.touch(MotionEvent.ACTION_MOVE, body, draggedThumb)
            f.touch(pointerUp, body, draggedThumb)
            f.place()
            assertEquals(initial, f.offset)

            val movedBody = body + Offset(0f, if (atEdge) 140f else -140f)
            f.touch(MotionEvent.ACTION_MOVE, movedBody)
            f.touch(MotionEvent.ACTION_UP, movedBody)
            if (atEdge) {
                assertFalse(f.libraryOpen)
            } else {
                assertTrue(f.libraryOpen)
                assertTrue(f.offset > initial)
            }
        }
    }

    @Test fun incidentsKeepsTheCheckboxAndArbitratesThumbAndListInBothOrders() = Fixture(incidents = true).use { f ->
        val (checkbox, checkPress) = f.alignedIncidentCheckboxPress(f.wakeThumb().x)
        val checkboxRight = f.point(checkbox, checkbox.width.toFloat(), checkbox.height / 2f)
        assertEquals(f.point((f.viewport.width - f.viewport.paddingRight).toFloat(), 0f).x, checkboxRight.x, 1f)
        val expected = f.boundIncident(checkbox)
        assertFalse(expected.acknowledged)
        // A queued window traversal must not replace the sampled row between DOWN and UP.
        f.touch(MotionEvent.ACTION_DOWN, checkPress, idleAfter = false)
        assertFalse(f.viewport.isScrollIndicatorDragging)
        assertTrue("The actual DOWN must be admitted by the checkbox's click controller", f.hasCheckboxPress(checkbox))
        f.touch(MotionEvent.ACTION_UP, checkPress)
        f.assertAcknowledged(expected)

        // The acknowledgement can rebind and lay out the list. Sample the new frame for DOWN.
        f.place()
        val thumb = f.wakeThumb()
        val center = f.point(f.viewport.width / 2f, 200f)
        val movedCenter = center - Offset(0f, 100f)
        val initial = f.offset
        f.touch(MotionEvent.ACTION_DOWN, thumb)
        assertTrue(f.viewport.isScrollIndicatorDragging)
        f.touch(pointerDown, thumb, center)
        f.touch(MotionEvent.ACTION_MOVE, thumb, movedCenter)
        assertEquals(initial, f.offset)
        f.touch(pointerUp, thumb, movedCenter)
        f.touch(MotionEvent.ACTION_UP, thumb)

        f.place()
        val freshThumb = f.wakeThumb()
        val freshCenter = f.point(f.viewport.width / 2f, 200f)
        val beforeList = f.offset
        f.touch(MotionEvent.ACTION_DOWN, freshCenter)
        f.touch(pointerDown, freshCenter, freshThumb)
        assertFalse(f.viewport.isScrollIndicatorDragging)
        val movedThumb = freshThumb + Offset(0f, 160f)
        f.touch(MotionEvent.ACTION_MOVE, freshCenter, movedThumb)
        f.touch(pointerUp, freshCenter, movedThumb)
        f.place()
        assertEquals(beforeList, f.offset)
        val freshMovedCenter = freshCenter - Offset(0f, 100f)
        f.touch(MotionEvent.ACTION_MOVE, freshMovedCenter)
        assertTrue(f.offset > beforeList)
        f.touch(MotionEvent.ACTION_UP, freshMovedCenter)
    }

    @Test
    @Config(shadows = [JournalHostIoShadow::class])
    fun hiddenMainKeepsMetadataWhileReopenedLibraryRestoresProvenWaveform() = withCompletedSaveStartup { app ->
        val provider = LibraryScanProvider()
        provider.attachInfo(app, ProviderInfo().apply { authority = "media" })
        ShadowContentResolver.registerProviderInternal("media", provider)
        val id = provider.recordingUri.toString()
        val metadata = RecordingEntity(id, provider.name, "audio/wav", 100_000L, 1_000L,
            provider.size, "PCM", RecordingStorageType.MEDIASTORE, getOutputDirectoryId(app, null),
            fileIdentity = listedProviderRecordingIdentity(RecordingStorageType.MEDIASTORE, id,
                sizeKnown = true, sizeBytes = provider.size, revisionToken = provider.revision))
        val cached = metadata.copy(
            waveformData = encodeRecordingWaveform(FloatArray(RANGE_WAVEFORM_DETAIL_BUCKETS) { 0.5f }),
            waveformRevision = recordingWaveformRevision(metadata),
        )
        val dao = RecordingDatabase.getInstance(app).recordingDao()
        try {
            runBlocking { dao.upsert(cached) }
            Fixture(seedSyntheticLibrary = false).use { f ->
                val library = field<ReverbLibraryScreenContent>(f.root, "library")
                val mainJob = field<Job>(f.root, "job")
                fun controllerInput() = field<NativeLibraryInput>(library.owner, "input")
                fun parentSnapshot() = field<List<RecordingEntity>>(f.root, "librarySnapshot")
                fun assertMetadataSnapshots() {
                    assertEquals(metadata, parentSnapshot().single())
                    assertEquals(metadata, controllerInput().initialRecordings.single())
                }
                try {
                    assertTrue(f.root.isAttachedToWindow)
                    f.await("first active scan") { provider.entered[0].count == 0L }
                    assertEquals(metadata, library.currentState()?.recordings?.single())
                    field<NativeLibraryScreenInput>(library, "input").dismiss()
                    assertFalse(f.libraryOpen)
                    assertFalse(controllerInput().active)

                    // Closing the active controller starts Main's refresh behind the held scan.
                    provider.release[0].countDown()
                    f.await("parent scan after listKnown") { provider.entered[1].count == 0L }
                    assertMetadataSnapshots()

                    val knownSnapshot = parentSnapshot()
                    provider.release[1].countDown()
                    f.await("parent refresh assignment and controller bind") {
                        // Each read returns the cached DAO row, so normalization creates a new list.
                        val refreshedSnapshot = parentSnapshot()
                        refreshedSnapshot !== knownSnapshot &&
                            controllerInput().initialRecordings === refreshedSnapshot
                    }
                    assertMetadataSnapshots()
                    assertEquals(metadata, library.currentState()?.recordings?.single())
                    assertEquals(cached, runBlocking { dao.findById(id) })
                    assertEquals(1L, provider.entered[2].count)

                    val generation = field<Int>(f.root, "libraryRefreshGeneration")
                    f.reopenLibrary()
                    f.await("reopened Library scan") { provider.entered[2].count == 0L }
                    assertTrue(controllerInput().active)
                    assertEquals(metadata, library.currentState()?.recordings?.single())
                    provider.release[2].countDown()
                    fun reopenEvidence(): String {
                        val state = library.currentState()
                        fun describe(rows: List<RecordingEntity>?): String = rows.orEmpty().joinToString {
                            "${it.id} identity=${it.fileIdentity} duration=${it.durationMillis} " +
                                "size=${it.sizeBytes} revision=${it.waveformRevision} waveformChars=${it.waveformData.length}"
                        }
                        return "active=${controllerInput().active} loaded=${state?.hasLoaded} " +
                            "failed=${state?.loadFailed} refreshing=${state?.isRefreshing} " +
                            "saveReadiness=${DurableSaveJobs.readiness.value} " +
                            "enteredScans=${provider.scanCount} returnedCursors=${provider.returnedCursors.get()} " +
                            "visible=[${describe(state?.recordings)}] parent=[${describe(parentSnapshot())}] " +
                            "expected=[${describe(listOf(cached))}]"
                    }
                    f.await("reopened Library scan outcome", ::reopenEvidence) {
                        library.currentState()?.let { it.hasLoaded || it.loadFailed } == true
                    }
                    val reopened = checkNotNull(library.currentState())
                    assertTrue(reopenEvidence(), reopened.hasLoaded && !reopened.loadFailed)
                    assertEquals(reopenEvidence(), cached, reopened.recordings.singleOrNull())
                    assertMetadataSnapshots()
                    assertEquals(generation, field<Int>(f.root, "libraryRefreshGeneration"))
                    assertEquals(cached, runBlocking { dao.findById(id) })
                } finally {
                    provider.release.forEach { it.countDown() }
                    f.root.close()
                    f.await("Main coroutine shutdown") { mainJob.isCompleted }
                }
            }
        } finally {
            provider.release.forEach { it.countDown() }
            runBlocking { dao.deleteById(id) }
        }
    }

    private fun withCompletedSaveStartup(test: (Application) -> Unit) {
        val app = RuntimeEnvironment.getApplication()
        val journal = durableSaveJournalRoot(app)
        check(journal.parentFile?.isDirectory == true && !journal.exists()) { "Expected a fresh app journal" }
        val coordinator = DurableSaveCoordinator::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val previousCoordinator = coordinator.get(null)
        val readiness = field<MutableStateFlow<DurableSaveJobs.Readiness>>(DurableSaveJobs, "mutableReadiness")
        val previousReadiness = readiness.value
        val notifications = field<CoroutineScope>(DurableSaveNotifications, "scope")
        val notificationJob = checkNotNull(notifications.coroutineContext[Job])
        val priorNotificationJobs = notificationJob.children.toSet()
        val started = DurableSaveNotifications::class.java.getDeclaredField("started").apply { isAccessible = true }
        val previousStarted = started.getBoolean(null)
        val previousRoot = ServiceStateOsShadow.fixtureRoot
        check(previousRoot == null)
        JournalHostIoShadow.reset()
        ServiceStateOsShadow.fixtureRoot = checkNotNull(app.filesDir.parentFile).toPath()
        try {
            coordinator.set(null, null)
            DurableSaveCoordinator.get(app).hydrateStartup()
            assertEquals(DurableSaveJobs.Readiness(loaded = true), DurableSaveJobs.readiness.value)
            test(app)
        } finally {
            try {
                runBlocking {
                    withTimeout(5_000) {
                        notificationJob.children.toList().filterNot { it in priorNotificationJobs }
                            .forEach { it.cancelAndJoin() }
                    }
                }
                JournalHostIoShadow.finishFixture()?.let { throw it }
            } finally {
                coordinator.set(null, previousCoordinator)
                started.setBoolean(null, previousStarted)
                readiness.value = previousReadiness
                ServiceStateOsShadow.fixtureRoot = previousRoot
            }
        }
    }

    private class LibraryScanProvider : ContentProvider() {
        private val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val recordingUri: Uri = ContentUris.withAppendedId(collection, 731952L)
        val name = "cached-waveform.wav"
        val size = 32_044L
        val revision = 9L
        val entered = List(3) { CountDownLatch(1) }
        val release = List(3) { CountDownLatch(1) }
        private val scans = AtomicInteger()
        val scanCount get() = scans.get()
        val returnedCursors = AtomicInteger()

        override fun onCreate() = true

        override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
            check(uri == collection) { "Expected a directory scan of the seeded recording" }
            val scan = scans.getAndIncrement()
            if (scan < entered.size) {
                entered[scan].countDown()
                check(release[scan].await(15, TimeUnit.SECONDS)) { "Library scan ${scan + 1} was never released" }
            }
            val values = mapOf(
                MediaStore.MediaColumns._ID to 731952L,
                MediaStore.MediaColumns.DISPLAY_NAME to name,
                MediaStore.MediaColumns.MIME_TYPE to "audio/wav",
                MediaStore.MediaColumns.SIZE to size,
                MediaStore.MediaColumns.DATE_MODIFIED to 100L,
                MediaStore.MediaColumns.GENERATION_MODIFIED to revision,
                MediaStore.MediaColumns.IS_PENDING to 0,
            )
            val columns = requireNotNull(projection)
            return MatrixCursor(columns).apply {
                addRow(columns.map(values::getValue))
                returnedCursors.incrementAndGet()
            }
        }

        override fun getType(uri: Uri) = "audio/wav"
        override fun insert(uri: Uri, values: ContentValues?): Uri? = error("Read-only Library fixture")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
            error("Read-only Library fixture")
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
            error("Read-only Library fixture")
    }

    private class Fixture(incidents: Boolean = false, seedSyntheticLibrary: Boolean = true) : AutoCloseable {
        private val activity = Robolectric.buildActivity(Activity::class.java).setup().visible()
        private val context = activity.get()
        private val board = FrameLayout(context)
        private val lifecycle = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry(this).apply { currentState = Lifecycle.State.STARTED }
        }
        // Queue Main's first history read so the fixture can install its store before observation.
        private val dispatcher = if (incidents) Dispatchers.Main else Dispatchers.Main.immediate
        private val scope = CoroutineScope(dispatcher + AndroidUiFrameClock(Choreographer.getInstance()) +
            object : MotionDurationScale { override val scaleFactor = 0f })
        private val incidentAccess = if (incidents) FixtureIncidentHistory(context) else null
        private val environment = ReverbViewEnvironment(Density(1f), createFontFamilyResolver(context),
            ReverbPalette.fallback(false))
        val root = NativeMainScreenView.create(context, environment, NativeMainScreenInput(false, false),
            lifecycle, OnBackPressedDispatcher(), scope, null, board,
            NativeWindowTextAccessibilityOwner(board, NativeTextSemanticsClipPolicy { NativeTextSemanticsClip.Unclipped }),
            NativeMainScreenSavedState(navigation = NativeMainSavedNavigation(showLibrary = !incidents,
                showIncidents = incidents)), {}, {},
            registerDirectoryResult = { error("Library fixture never enters Settings") }, releaseInputFocus = {})
        val viewport: ReverbLibraryRecyclerView
        private val incidentHistory get() = field<NativeIncidentsScreenOwner>(root, "incidentsOwner").history
        val pull get() = field<NativeLibraryPullRefreshOwner>(field<ReverbLibraryScreenContent>(root, "library"), "pull")
        val libraryOpen get() = root.saveState().navigation.showLibrary
        val offset get() = viewport.computeVerticalScrollOffset()
        private var downTime = SystemClock.uptimeMillis()
        private var eventTime = downTime

        init {
            if (incidentAccess != null) {
                IncidentHistoryController::class.java.getDeclaredField("access").apply { isAccessible = true }
                    .set(incidentHistory, incidentAccess)
            } else if (seedSyntheticLibrary) {
                // Seed the catalog snapshot Main supplies on first open.
                NativeMainScreenView::class.java.getDeclaredField("librarySnapshot").apply { isAccessible = true }
                    .set(root, List(40) { index ->
                        RecordingEntity("/synthetic-scroll/$index.wav", "Recording $index", "audio/wav",
                            1_700_000_000_000L, 1_000L, 32_044L, "PCM", RecordingStorageType.FILE, "/synthetic-scroll")
                    })
            }
            board.addView(root, FrameLayout.LayoutParams(-1, -1))
            context.setContentView(board)
            root.start()
            viewport = if (incidents) {
                shadowOf(Looper.getMainLooper()).idle()
                check(incidentHistory.state.canMutate && incidentHistory.state.history.rows.isNotEmpty()) {
                    "The real Incidents controller must publish the fixture's history"
                }
                checkNotNull(field<NativeIncidentsScreenView>(root, "incidents").pointerContent?.pointerList)
            } else {
                val library = field<ReverbLibraryScreenContent>(root, "library")
                // The DAO-backed regression can start with a loading body before its first read.
                if (seedSyntheticLibrary) checkNotNull(library.listViewport) else library.scrollIndicatorViewport
            }
            place()
        }

        fun reopenLibrary() {
            val open: NativeMainNavigationState.() -> Unit = { openLibrary() }
            NativeMainScreenView::class.java.getDeclaredMethod("navigate", Function1::class.java)
                .apply { isAccessible = true }.invoke(root, open)
        }

        fun await(boundary: String, details: () -> String = { "" }, reached: () -> Boolean) {
            val main = shadowOf(Looper.getMainLooper())
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            do {
                main.idleFor(Duration.ofMillis(20))
                if (reached()) return
                Thread.sleep(5)
            } while (System.nanoTime() < deadline)
            error("Timed out waiting for $boundary. ${details()}")
        }

        fun place() {
            board.requestLayout()
            board.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY))
            board.layout(0, 0, 400, 600)
        }

        fun wakeThumb(): Offset {
            assertTrue(viewport.canScrollVertically(1))
            viewport.scrollBy(0, 1)
            val bitmap = Bitmap.createBitmap(viewport.width, viewport.height, Bitmap.Config.ARGB_8888)
            try { viewport.draw(Canvas(bitmap)) } finally { bitmap.recycle() }
            val style = checkNotNull(environment.appearance.scrollIndicator)
            val x = viewport.width - viewport.paddingRight / 2f
            val y = style.minThumbHeightDp / 2f
            assertTrue("The right gutter must admit a press aligned with the painted thumb",
                viewport.canStartScrollIndicatorDrag(x, y))
            return point(x, y)
        }

        fun alignedIncidentCheckboxPress(thumbX: Float): Pair<View, Offset> {
            val listOrigin = point(0f, 0f)
            val pointer = field<Any>(root, "pointer")
            val geometry = field<NativeWindowPointerCoordinates>(pointer, "coordinates")
            val layout = field<NativeViewPointerHitLayout>(pointer, "layout")
            val aligned = descendants(viewport).filter { it.javaClass.simpleName == "IncidentCheckTarget" }
                .map { it to point(it, it.width - 3f, it.height / 2f) }
                .filter { (_, point) ->
                    viewport.canStartScrollIndicatorDrag(thumbX - listOrigin.x, point.y - listOrigin.y)
                }.toList()
            for ((checkbox, point) in aligned) {
                val probe = MotionEvent.obtain(eventTime, eventTime, MotionEvent.ACTION_DOWN, point.x, point.y, 0)
                val leaf = try {
                    geometry.begin(probe)
                    nativePointerHitPath(layout, Offset(probe.rawX, probe.rawY), PointerType.Touch)
                        .lastOrNull() as? NativeViewPhasePointerNode
                } finally { probe.recycle() }
                if (leaf?.view === checkbox) return checkbox to point
            }
            error("None of ${aligned.size} thumb-aligned checkbox edge points hit their checkbox in Main")
        }

        fun hasCheckboxPress(checkbox: View): Boolean =
            field<Any?>(field<Any>(checkbox, "input"), "pointer") != null

        fun boundIncident(checkbox: View): RecordingIncident {
            val card = generateSequence(checkbox) { it.parent as? View }
                .filterIsInstance<ReverbIncidentCardView>().first()
            return field<IncidentPresentation>(field<Any>(card, "binding"), "row").incident
        }

        fun assertAcknowledged(expected: RecordingIncident) {
            val key = recordingIncidentPresentationKey(expected)
            assertEquals(listOf(key), checkNotNull(incidentAccess).acknowledgements)
            assertEquals(listOf(key), incidentHistory.state.history.rows.filter { it.acknowledged }.map { it.key })
        }

        private fun descendants(view: View): Sequence<View> = sequence {
            yield(view)
            if (view is ViewGroup) repeat(view.childCount) { yieldAll(descendants(view.getChildAt(it))) }
        }

        fun point(x: Float, y: Float): Offset = point(viewport, x, y)

        fun point(view: View, x: Float, y: Float): Offset {
            val rootLocation = IntArray(2).also(root::getLocationOnScreen)
            val location = IntArray(2).also(view::getLocationOnScreen)
            return Offset(x + location[0] - rootLocation[0], y + location[1] - rootLocation[1])
        }

        fun touch(action: Int, first: Offset, second: Offset? = null, firstId: Int = 0, idleAfter: Boolean = true) {
            eventTime += 16L
            if (action == MotionEvent.ACTION_DOWN) downTime = eventTime
            val points = listOfNotNull(first, second)
            val properties = Array(points.size) { index -> MotionEvent.PointerProperties().apply {
                id = if (index == 0) firstId else 1
                toolType = MotionEvent.TOOL_TYPE_FINGER
            } }
            val coordinates = Array(points.size) { index -> MotionEvent.PointerCoords().apply {
                x = points[index].x
                y = points[index].y
                pressure = 1f
                size = 1f
            } }
            val event = MotionEvent.obtain(downTime, eventTime, action, points.size, properties, coordinates,
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            try {
                assertTrue("Main rejected ${MotionEvent.actionToString(action)} at $first " +
                    "with viewport ${viewport.width}x${viewport.height}", root.dispatchTouchEvent(event))
            } finally { event.recycle() }
            if (idleAfter) shadowOf(Looper.getMainLooper()).idle()
        }

        override fun close() {
            try { root.close() } finally {
                try { scope.cancel() } finally { activity.pause().stop().destroy() }
            }
        }
    }

    private class FixtureIncidentHistory(private val context: Context) : IncidentHistoryAccess {
        override val revisions = MutableStateFlow(0L)
        val acknowledgements = mutableListOf<String>()
        private var incidents = List(25) { index ->
            RecordingIncident(occurredAtMillis = 1_700_000_000_000L + index * 1_000L, pid = index + 1)
        }

        override suspend fun readHistory(zone: ZoneId) =
            prepareIncidentHistory(incidents, zone = zone, resources = context.resources)

        override fun toggleAcknowledgedInBackground(incident: RecordingIncident) {
            val key = recordingIncidentPresentationKey(incident)
            acknowledgements += key
            incidents = incidents.map {
                if (recordingIncidentPresentationKey(it) == key) toggleRecordingIncidentAcknowledgement(it, 1L) else it
            }
            revisions.value++
        }

        override fun deleteInBackground(incidents: List<RecordingIncident>) = error("The gesture fixture never deletes")
    }

    private companion object {
        val pointerDown = MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        val pointerUp = MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)

        inline fun <reified T> field(owner: Any, name: String): T =
            owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner) as T
    }
}
