package app.smallthingz.reverb.quicksaveisolated

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Looper
import app.smallthingz.reverb.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLongArray
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.SendChannel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowApplication
import org.robolectric.shadows.ShadowLooper

/** Actual Quick Save click with only SystemUI and the service-start boundary modeled. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class,
    shadows = [ServiceCompatibleTileShadow::class],
    instrumentedPackages = ["app.smallthingz.reverb.quicksaveisolated"])
@LooperMode(LooperMode.Mode.PAUSED)
class QuickSaveTileHandoffTest {
    @Test fun nullThrownAndAcceptedStartsUseTheDisplayedPersistedOption() {
        val app = RuntimeEnvironment.getApplication()
        val loader = javaClass.classLoader
        check(loader === QuickSaveTileOptions::class.java.classLoader)
        check(loader === LoopingQuickSaveTileService::class.java.classLoader)
        val prefs = getRecorderPreferences(app)
        check(prefs.all.isEmpty())
        check(AppFeedbackCenter.events.value.isEmpty())
        val manager = app.getSystemService(NotificationManager::class.java)
        check(manager.activeNotifications.isEmpty())
        val appShadow = Shadow.extract<ShadowApplication>(app)
        check(appShadow.peekNextStartedActivity() == null)
        check(appShadow.boundServiceConnections.isEmpty())
        appShadow.denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        check(QuickSaveTileOptions.options.value == null)
        check(QuickSaveTileOptions.error.value == null)
        check(QuickSaveTileOptions.addedStates.value.isEmpty())
        check(QuickSaveTileOptions.addedStateError.value == null)
        val lifetimes = field(QuickSaveTileOptions::class.java, "observationLifetimes").get(QuickSaveTileOptions)
        for (name in listOf("generations", "observationGenerations")) {
            val values = field(lifetimes.javaClass, name).get(lifetimes) as AtomicLongArray
            check((0 until values.length()).all { values.get(it) == 0L })
        }
        val worker = field(QuickSaveTileOptions::class.java, "worker").get(QuickSaveTileOptions) as CoroutineScope
        val workerJob = requireNotNull(worker.coroutineContext[Job])
        val actor = workerJob.children.toSet()
        check(actor.size == 1) { "Quick Save worker is not pristine" }
        val resultScope = field(Class.forName("app.smallthingz.reverb.CaptureScreenKt"),
            "backgroundRecordingResultScope").get(null) as CoroutineScope
        val resultJob = requireNotNull(resultScope.coroutineContext[Job])
        check(resultJob.children.none())
        val starts = mutableListOf<Intent>()
        val controller = Robolectric.buildService(LoopingQuickSaveTileService::class.java)
        val tile = controller.get()
        val baseField = field(ContextWrapper::class.java, "mBase")
        val oldBase = baseField.get(tile) as Context
        var phase = 0
        var created = false
        var listening = false
        var destroyed = false
        var primary: Throwable? = null
        var unsafe = false
        val ownedEvents = mutableListOf<FeedbackEvent>()
        fun record(error: Throwable) {
            if (primary == null) primary = error else if (primary !== error) primary!!.addSuppressed(error)
        }
        try {
            val option = QuickSaveTileOptions.create(app, ReverbService.BufferSlot.ONE_SHOT, 7, QuickSaveUnit.SECONDS)
            assertEquals(0, option.slot)
            assertEquals(listOf(option), QuickSaveTileOptions.load(app))
            baseField.set(tile, object : ContextWrapper(oldBase) {
                override fun startForegroundService(intent: Intent): ComponentName? {
                    starts += Intent(intent)
                    return when (phase) {
                        0 -> null
                        1 -> throw SecurityException("fixture start denied")
                        else -> ComponentName(tile, ReverbService::class.java)
                    }
                }
            })
            created = true
            controller.create()
            Shadow.extract<ServiceCompatibleTileShadow>(tile).setLocked(false)
            tile.onStartListening()
            listening = true
            awaitMain {
                field(QuickSaveTileService::class.java, "displayed").get(tile) == option
            }
            for (index in 0..2) {
                phase = index
                tile.onClick()
                assertEquals(index + 1, starts.size)
                val intent = starts[index]
                assertEquals(ComponentName(tile, ReverbService::class.java), intent.component)
                assertEquals(ReverbService.ACTION_QUICK_SAVE, intent.action)
                assertEquals(ReverbService.BufferSlot.ONE_SHOT.storageCode.toInt(),
                    intent.getIntExtra(ReverbService.EXTRA_QUICK_TILE_BUFFER_SLOT, -1))
                assertEquals(7, intent.getIntExtra(ReverbService.EXTRA_QUICK_SAVE_SECONDS, -1))
                val events = AppFeedbackCenter.events.value
                assertEquals(if (index == 2) 2 else index + 1, events.size)
                if (index < 2) {
                    val event = events.last()
                    val operation = app.getString(R.string.save_failed)
                    val reason = app.getString(if (index == 0) R.string.failure_reason_unknown else R.string.failure_reason_permission)
                    val type = if (index == 0) "IllegalStateException" else "SecurityException"
                    val reported = if (index == 0) "Service start returned no component" else "fixture start denied"
                    val expected = listOf(operation, reason, app.getString(R.string.failure_type, type),
                        app.getString(R.string.failure_reported_detail, reported)).joinToString(" ")
                    assertEquals(FeedbackTone.ERROR, event.tone)
                    assertEquals(expected, event.message)
                    ownedEvents += event
                }
                assertEquals(ownedEvents, events)
            }
        } catch (error: Throwable) {
            record(error)
        } finally {
            fun drain(block: () -> Unit) {
                try { block() } catch (error: Throwable) { unsafe = true; record(error) }
            }
            if (listening) drain { tile.onStopListening() }
            if (created && !destroyed) drain { controller.destroy(); destroyed = true }
            drain { ShadowLooper.idleMainLooper() }
            drain {
                val serviceScope = field(QuickSaveTileService::class.java, "scope").get(tile) as CoroutineScope
                joinFinite(requireNotNull(serviceScope.coroutineContext[Job]).children.toList())
            }
            drain { joinFinite(workerJob.children.filter { it !in actor }.toList()) }
            drain { actorFence() }
            drain { joinFinite(resultJob.children.toList()) }
            drain { ShadowLooper.idleMainLooper() }
            drain {
                check(workerJob.children.toSet() == actor)
                check(resultJob.children.none())
                check(manager.activeNotifications.isEmpty())
                assertNull(appShadow.peekNextStartedActivity())
                assertTrue(appShadow.boundServiceConnections.isEmpty())
            }
            if (!unsafe && primary == null) {
                try {
                    assertEquals(3, starts.size)
                    assertEquals(2, ownedEvents.size)
                    assertEquals(ownedEvents, AppFeedbackCenter.events.value)
                    ownedEvents.forEach { AppFeedbackCenter.acknowledge(it.id) }
                    assertTrue(AppFeedbackCenter.events.value.isEmpty())
                } catch (error: Throwable) { record(error) }
            }
            if (!unsafe && (!created || destroyed)) drain { baseField.set(tile, oldBase) }
        }
        primary?.let { throw it }
    }

    private fun field(type: Class<*>, name: String) = type.getDeclaredField(name).apply { isAccessible = true }

    private fun awaitMain(predicate: () -> Boolean) {
        check(Looper.myLooper() == Looper.getMainLooper())
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (!predicate()) {
            check(System.nanoTime() < deadline) { "Timed out waiting for displayed option" }
            ShadowLooper.idleMainLooper()
            Thread.sleep(5)
        }
    }

    private fun joinFinite(jobs: List<Job>) {
        check(Looper.myLooper() == Looper.getMainLooper())
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (jobs.any { !it.isCompleted }) {
            check(System.nanoTime() < deadline) { "Timed out waiting for owned jobs" }
            ShadowLooper.idleMainLooper()
            Thread.sleep(5)
        }
    }

    private fun actorFence() {
        @Suppress("UNCHECKED_CAST")
        val queue = field(QuickSaveTileOptions::class.java, "observationQueue")
            .get(QuickSaveTileOptions) as SendChannel<() -> Unit>
        val fence = CountDownLatch(1)
        queue.trySend { fence.countDown() }.getOrThrow()
        check(fence.await(8, TimeUnit.SECONDS)) { "Quick Save observation actor did not drain" }
    }
}
