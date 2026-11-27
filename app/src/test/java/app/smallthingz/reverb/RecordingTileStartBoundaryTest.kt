package app.smallthingz.reverb

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Looper
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowApplication
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowService

/** Actual tile click; only the external SystemUI/permission/start handoff is modeled. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [ServiceCompatibleTileShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class RecordingTileStartBoundaryTest {
    @Test fun nullStartFailsClosed() = fixture(Outcome.NULL)
    @Test fun thrownStartFailsClosed() = fixture(Outcome.THROWN)
    @Test fun acceptedStartPreservesLiveCache() = fixture(Outcome.ACCEPTED)
    @Test fun newerLivePublicationSurvivesNullAndThrownStarts() {
        fixture(Outcome.NULL, cacheChange = CacheChange.LIVE)
        fixture(Outcome.THROWN, cacheChange = CacheChange.LIVE)
    }
    @Test fun coldNullStartInvalidatesEvenAfterNewerHydration() =
        fixture(Outcome.NULL, cacheChange = CacheChange.COLD_HYDRATION)
    @Test fun deferredUnlockBelongsToTheTileAddition() = fixture(Outcome.ACCEPTED, deferUnlock = true)

    private enum class Outcome { NULL, THROWN, ACCEPTED }
    private enum class CacheChange { NONE, LIVE, COLD_HYDRATION }

    private fun fixture(outcome: Outcome, cacheChange: CacheChange = CacheChange.NONE, deferUnlock: Boolean = false) {
        val app = RuntimeEnvironment.getApplication()
        val prefs = getRecorderPreferences(app)
        check(prefs.all.isEmpty()) { "Recorder preferences already owned" }
        val appShadow = Shadow.extract<ShadowApplication>(app)
        check(appShadow.peekNextStartedActivity() == null)
        check(appShadow.boundServiceConnections.isEmpty())
        drainTileWorker()
        val savedCache = snapshotCache()
        check(savedCache.getValue("runtimeOwner") == null) { "Recorder runtime already owned" }
        val priorObservation = snapshotObservation()
        val token = Any()
        val live = RecordingTileSnapshot(listening = true, activeBuffer = ReverbService.BufferSlot.LOOPING,
            oneShotEnabled = true, oneShotFull = false, loopingEnabled = true,
            oneShotSeconds = 1f, loopingSeconds = 2f, commandGeneration = 41L, durationCountersKnown = true)
        val newerLive = live.copy(oneShotSeconds = 9f, commandGeneration = 42L)
        val durationKey = PrefKey.QUICK_TILE_LOOPING_DURATION_MILLIS.name
        var hydratedGeneration: Long? = null
        val starts = mutableListOf<Intent>()
        val controller = Robolectric.buildService(LoopingRecordingTileService::class.java)
        val tile = controller.get()
        val baseField = ContextWrapper::class.java.getDeclaredField("mBase").apply { isAccessible = true }
        val previousBase = baseField.get(tile) as Context
        var baseInstalled = false
        var createCalled = false
        var listening = false
        var destroyAttempted = false
        var destroyCompleted = false
        var runtimeClaimed = false
        var primary: Throwable? = null
        var drainFailed = false
        fun fail(error: Throwable) {
            if (primary == null) primary = error else if (primary !== error) primary!!.addSuppressed(error)
        }
        fun failDrain(error: Throwable) { drainFailed = true; fail(error) }
        try {
            baseField.set(tile, object : ContextWrapper(previousBase) {
                override fun checkSelfPermission(permission: String): Int =
                    if (permission == Manifest.permission.RECORD_AUDIO) PackageManager.PERMISSION_GRANTED
                    else super.checkSelfPermission(permission)
                override fun startForegroundService(intent: Intent): ComponentName? {
                    starts += Intent(intent)
                    when (cacheChange) {
                        CacheChange.NONE -> Unit
                        CacheChange.LIVE -> {
                            check(RecordingQuickTileStateCache.publish(token, newerLive))
                            RecordingQuickTiles.refreshCachedSnapshot(tile, requestSystemRefresh = false)
                        }
                        CacheChange.COLD_HYDRATION -> {
                            val generation = RecordingQuickTileStateCache.publicationGeneration()
                            check(prefs.edit().putLong(durationKey, 6000L).commit())
                            RecordingQuickTileStateCache.hydratePersistedAsync(tile)
                            drainTileRoundTrip()
                            assertEquals(6f, RecordingQuickTileStateCache.readNonBlocking().loopingSeconds, 0f)
                            hydratedGeneration = RecordingQuickTileStateCache.publicationGeneration().also {
                                assertTrue(it > generation)
                            }
                        }
                    }
                    return when (outcome) {
                        Outcome.NULL -> null
                        Outcome.THROWN -> throw SecurityException("fixture tile start denied")
                        Outcome.ACCEPTED -> ComponentName(tile, ReverbService::class.java)
                    }
                }
            })
            baseInstalled = true
            createCalled = true
            controller.create()
            val shadow = Shadow.extract<ServiceCompatibleTileShadow>(tile)
            tile.onStartListening()
            listening = true
            drainTileRoundTrip()
            if (cacheChange != CacheChange.COLD_HYDRATION) {
                RecordingQuickTileStateCache.beginRuntime(token)
                runtimeClaimed = true
                check(RecordingQuickTileStateCache.publish(token, live))
                assertEquals(live, RecordingQuickTileStateCache.readNonBlocking())
            }
            val expectedLive = when {
                cacheChange == CacheChange.LIVE -> newerLive
                outcome == Outcome.ACCEPTED -> live
                else -> null
            }
            fun queueUnlock() {
                shadow.setLocked(true)
                tile.onClick()
            }
            if (deferUnlock) {
                queueUnlock()
                tile.onTileRemoved(); listening = false
                shadow.deliverUnlock()
                assertTrue(starts.isEmpty())

                tile.onTileAdded()
                tile.onStartListening(); listening = true
                queueUnlock()
                tile.onTileRemoved(); listening = false
                tile.onTileAdded()
                tile.onStartListening(); listening = true
                shadow.deliverUnlock()
                assertTrue(starts.isEmpty())

                queueUnlock()
                tile.onStopListening(); listening = false
                shadow.deliverUnlock()
            } else tile.onClick()
            assertEquals(1, starts.size)
            val intent = starts.single()
            assertEquals(ComponentName(tile, ReverbService::class.java), intent.component)
            assertEquals(ReverbService.ACTION_QUICK_TILE_COMMAND, intent.action)
            assertEquals(ReverbService.BufferSlot.LOOPING.storageCode.toInt(),
                intent.getIntExtra(ReverbService.EXTRA_QUICK_TILE_BUFFER_SLOT, -1))
            assertEquals(expectedLive != null, RecordingQuickTileStateCache.readNonBlocking().listening)
            if (expectedLive != null) assertEquals(expectedLive, RecordingQuickTileStateCache.readNonBlocking())
            if (cacheChange == CacheChange.LIVE) {
                assertEquals(android.service.quicksettings.Tile.STATE_ACTIVE, checkNotNull(tile.qsTile).state)
            }
            if (cacheChange == CacheChange.COLD_HYDRATION) {
                assertTrue(RecordingQuickTileStateCache.publicationGeneration() > checkNotNull(hydratedGeneration))
            }
            assertNull(appShadow.peekNextStartedActivity())
            assertTrue(appShadow.boundServiceConnections.isEmpty())
            if (listening) { tile.onStopListening(); listening = false }
            if (deferUnlock) queueUnlock()
            destroyAttempted = true; controller.destroy(); destroyCompleted = true
            if (deferUnlock) shadow.deliverUnlock()
            drainTileRoundTrip()
            assertFalse(RecordingQuickTiles.hasListeningServices())
            assertEquals(1, starts.size)
            assertEquals(expectedLive != null, RecordingQuickTileStateCache.readNonBlocking().listening)
            if (expectedLive != null) assertEquals(expectedLive, RecordingQuickTileStateCache.readNonBlocking())
            assertNull(appShadow.peekNextStartedActivity())
            assertTrue(appShadow.boundServiceConnections.isEmpty())
            assertEquals(if (cacheChange == CacheChange.COLD_HYDRATION) setOf(durationKey) else emptySet(), prefs.all.keys)
        } catch (error: Throwable) {
            if (destroyAttempted && !destroyCompleted) failDrain(error) else primary = error
        }
        finally {
            if (listening) try { tile.onStopListening(); listening = false } catch (error: Throwable) { failDrain(error) }
            if (createCalled && !destroyAttempted) try { destroyAttempted = true; controller.destroy(); destroyCompleted = true }
            catch (error: Throwable) { failDrain(error) }
            try { drainTileRoundTrip() } catch (error: Throwable) { failDrain(error) }
            if (!drainFailed) {
                var observationFailed = false
                try { if (createCalled) restoreObservation(priorObservation, tile) } catch (error: Throwable) {
                    observationFailed = true; fail(error)
                }
                try { restoreCache(savedCache, if (runtimeClaimed) token else savedCache["runtimeOwner"]) }
                catch (error: Throwable) { failDrain(error) }
                if (observationFailed) drainFailed = true
            }
            if (cacheChange == CacheChange.COLD_HYDRATION) try {
                check(prefs.edit().remove(durationKey).commit())
            } catch (error: Throwable) { failDrain(error) }
            if (!drainFailed && baseInstalled) try { baseField.set(tile, previousBase) } catch (error: Throwable) { failDrain(error) }
        }
        primary?.let { throw it }
    }

    private fun drainTileWorker() {
        val worker = cacheField("persistedReadExecutor").get(RecordingQuickTileStateCache) as ExecutorService
        worker.submit {}.get(8, TimeUnit.SECONDS)
    }
    private fun drainTileRoundTrip() {
        check(Looper.myLooper() == Looper.getMainLooper())
        repeat(2) { drainTileWorker(); ShadowLooper.idleMainLooper() }
        drainTileWorker()
    }

    private val cacheNames = listOf("cachedSnapshot", "stateGeneration", "hydrationGeneration",
        "runtimeAuthoritative", "runtimeOwner", "runtimeOwnerStopped", "oneShotFullRuntimeGeneration",
        "latestRuntimeOneShotFull", "completedClearGeneration", "oneShotClearGeneration")
    private fun cacheField(name: String) = RecordingQuickTileStateCache::class.java.getDeclaredField(name)
        .apply { isAccessible = true }
    private fun <T> withCacheLocks(block: () -> T): T {
        val full = cacheField("oneShotFullPreferenceLock").get(RecordingQuickTileStateCache)
        val state = cacheField("stateLock").get(RecordingQuickTileStateCache)
        return synchronized(full) { synchronized(state) { block() } }
    }
    private fun snapshotCache(): Map<String, Any?> = withCacheLocks {
        check((cacheField("pendingClears").get(RecordingQuickTileStateCache) as Map<*, *>).isEmpty())
        cacheNames.associateWith { cacheField(it).get(RecordingQuickTileStateCache) }
    }
    private fun restoreCache(saved: Map<String, Any?>, token: Any?) = withCacheLocks {
        check(cacheField("runtimeOwner").get(RecordingQuickTileStateCache) === token)
        check((cacheField("pendingClears").get(RecordingQuickTileStateCache) as Map<*, *>).isEmpty())
        saved.forEach { (name, value) -> cacheField(name).set(RecordingQuickTileStateCache, value) }
    }

    private data class ObservationBaseline(val other: Map<ReverbService.BufferSlot, RecordingTileObservation>,
        val previousLoop: RecordingTileObservation?, val generation: Long)
    private fun tileField(name: String) = RecordingQuickTiles::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun <T> withTileRegistry(block: (RecordingTileHandoffController, MutableMap<ReverbService.BufferSlot, RecordingTileObservation>) -> T): T {
        val services = tileField("listeningServices").get(RecordingQuickTiles) as MutableMap<*, *>
        return synchronized(services) {
            check(services.isEmpty()) { "Another tile listener is active" }
            val controller = tileField("handoffs").get(RecordingQuickTiles) as RecordingTileHandoffController
            check(controller.pending == null) { "A tile handoff is already owned" }
            @Suppress("UNCHECKED_CAST")
            val observations = RecordingTileHandoffController::class.java.getDeclaredField("observations")
                .apply { isAccessible = true }.get(controller) as MutableMap<ReverbService.BufferSlot, RecordingTileObservation>
            block(controller, observations)
        }
    }
    private fun snapshotObservation(): ObservationBaseline = withTileRegistry { controller, observations ->
        check(observations.values.none { it.active }) { "Another tile observation is active" }
        val generation = RecordingTileHandoffController::class.java.getDeclaredField("nextGeneration")
            .apply { isAccessible = true }.getLong(controller)
        ObservationBaseline(observations.filterKeys { it != ReverbService.BufferSlot.LOOPING },
            observations[ReverbService.BufferSlot.LOOPING], generation)
    }
    private fun restoreObservation(saved: ObservationBaseline, tile: LoopingRecordingTileService) = withTileRegistry { controller, observations ->
        val owned = RecordingTileService::class.java.getDeclaredField("tileObservation").apply { isAccessible = true }
            .get(tile) as RecordingTileObservation?
        if (owned == null) {
            check(observations.filterKeys { it != ReverbService.BufferSlot.LOOPING } == saved.other)
            check(observations[ReverbService.BufferSlot.LOOPING] === saved.previousLoop)
            return@withTileRegistry
        }
        check(!owned.active && observations[ReverbService.BufferSlot.LOOPING] === owned)
        check(observations.filterKeys { it != ReverbService.BufferSlot.LOOPING } == saved.other)
        check(RecordingTileHandoffController::class.java.getDeclaredField("nextGeneration")
            .apply { isAccessible = true }.getLong(controller) == saved.generation)
        if (saved.previousLoop == null) observations.remove(ReverbService.BufferSlot.LOOPING)
        else observations[ReverbService.BufferSlot.LOOPING] = saved.previousLoop
    }
}

/** SystemUI boundary with the Service lifecycle shadow Robolectric requires on teardown. */
@Implements(android.service.quicksettings.TileService::class)
class ServiceCompatibleTileShadow : ShadowService() {
    private var tile: android.service.quicksettings.Tile? = null
    private var locked = false
    private var pendingUnlock: Runnable? = null

    @Implementation fun getQsTile(): android.service.quicksettings.Tile =
        tile ?: Shadow.newInstanceOf(android.service.quicksettings.Tile::class.java).also { tile = it }
    @Implementation fun isLocked(): Boolean = locked
    @Implementation fun unlockAndRun(action: Runnable?) {
        check(pendingUnlock == null)
        pendingUnlock = checkNotNull(action)
    }
    fun setLocked(value: Boolean) { locked = value }
    fun deliverUnlock() {
        locked = false
        val action = checkNotNull(pendingUnlock)
        pendingUnlock = null
        action.run()
    }
}
