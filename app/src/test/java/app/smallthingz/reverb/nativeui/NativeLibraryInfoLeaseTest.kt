package app.smallthingz.reverb.nativeui

import android.app.Application
import android.content.res.Configuration
import android.os.LocaleList
import app.smallthingz.reverb.R
import app.smallthingz.reverb.RecordingEntity
import app.smallthingz.reverb.RecordingStorageType
import java.util.Locale
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import smallthingz.ui.text.view.ParagraphView

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class NativeLibraryInfoLeaseTest {
    @Test fun detachedPrefetchWaitsForFreshActiveBindAndRetainsCompletedLocation() = Fixture().use { f ->
        f.bind(active = false)
        f.drain()
        assertEquals(f.loading, f.location)
        assertEquals(0, f.requests.size)

        // Recycler refreshes a cached holder while detached, then attaches it and binds fresh input.
        f.lease.setPresentationAttached(true)
        f.drain()
        assertEquals(0, f.requests.size)
        f.bind()
        f.bind()
        f.drain()
        assertEquals(1, f.requests.size)

        // Queue the old same-key result on the UI dispatcher just before detach cancels the load.
        f.requests[0].finish("obsolete location")
        f.lease.setPresentationAttached(false)
        f.bind(active = false)
        f.lease.setPresentationAttached(true)
        f.bind()
        f.drain()
        assertEquals(2, f.requests.size)
        assertEquals(f.loading, f.location)
        f.requests[1].finish("fresh location")
        f.drain()
        assertEquals("fresh location", f.location)

        f.lease.setPresentationAttached(false)
        f.bind(active = false)
        f.lease.setPresentationAttached(true)
        f.bind()
        f.drain()
        assertEquals("fresh location", f.location)
        assertEquals(2, f.requests.size)
    }

    @Test fun changedRecordingAndLocaleReloadOnlyFromAnActiveBinding() = Fixture().use { f ->
        f.lease.setPresentationAttached(true)
        f.bind()
        f.drain()
        f.requests.single().finish("first folder")
        f.drain()
        assertEquals("first folder", f.location)

        val moved = f.recording.copy(directoryId = "/moved")
        f.bind(moved, active = false)
        f.drain()
        assertEquals(f.loading, f.location)
        assertEquals(1, f.requests.size)
        f.bind(moved)
        f.drain()
        assertEquals(moved, f.requests[1].recording)
        f.requests[1].finish("second folder")
        f.drain()
        assertEquals("second folder", f.location)

        f.changeLocale(Locale.FRENCH)
        f.bind(moved)
        f.drain()
        assertEquals(3, f.requests.size)
        assertEquals(f.loading, f.location)
        f.requests[2].finish("dossier")
        f.drain()
        assertEquals("dossier", f.location)
    }

    @Test fun hidingCollapsingAndClosingSuppressAnAlreadyRunningLookup() = Fixture().use { f ->
        f.lease.setPresentationAttached(true)
        f.bind()
        f.drain()
        f.bind(active = false)
        f.requests[0].finish("hidden result")
        f.drain()
        assertEquals(f.loading, f.location)

        f.bind()
        f.drain()
        f.bind(expanded = false)
        f.requests[1].finish("collapsed result")
        f.drain()
        assertEquals(f.loading, f.location)

        f.bind()
        f.drain()
        f.lease.closePresentation()
        f.requests[2].finish("closed result")
        f.drain()
        f.bind()
        f.drain()
        assertEquals(3, f.requests.size)
        assertEquals(f.loading, f.location)
    }

    private class Fixture : AutoCloseable {
        private val application = RuntimeEnvironment.getApplication()
        private val context = application.createConfigurationContext(Configuration(application.resources.configuration).apply {
            setLocales(LocaleList(Locale.ENGLISH))
        })
        private val dispatcher = QueuedDispatcher()
        private val scope = CoroutineScope(dispatcher)
        val recording = RecordingEntity("/synthetic/info.wav", "Fixture", "audio/wav", 1L, 1_000L,
            32_044L, "PCM", RecordingStorageType.FILE, "/synthetic")
        val requests = mutableListOf<Request>()
        // A platform call already in progress may finish even after the lease cancels its Job.
        val lease = NativeLibraryInfoLease(context, ReverbViewEnvironment(context, false), scope) { recording ->
            suspendCoroutine { requests.add(Request(recording, it)) }
        }
        val location: String get() = (lease.view.getChildAt(5) as ParagraphView).getText()
        val loading: String get() = context.getString(R.string.recording_details_loading)

        fun bind(recording: RecordingEntity = this.recording, active: Boolean = true, expanded: Boolean = true) {
            lease.bind(recording, owner = null, expanded = expanded, screenActive = active, trimRequested = false)
        }

        @Suppress("DEPRECATION")
        fun changeLocale(locale: Locale) {
            val resources = context.resources
            resources.updateConfiguration(Configuration(resources.configuration).apply {
                setLocales(LocaleList(locale))
            }, resources.displayMetrics)
            assertEquals(locale, resources.configuration.locales[0])
        }

        fun drain() = dispatcher.drain()

        override fun close() {
            lease.closePresentation()
            scope.cancel()
            dispatcher.drain()
        }
    }

    private class Request(val recording: RecordingEntity, private val result: Continuation<String>) {
        fun finish(location: String) = result.resume(location)
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val pending = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            pending.addLast(block)
        }
        fun drain() {
            while (pending.isNotEmpty()) pending.removeFirst().run()
        }
    }
}
