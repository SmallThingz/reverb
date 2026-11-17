package app.smallthingz.reverb

import android.app.Application
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowService
import org.robolectric.shadow.api.Shadow

/** Actual state delivery; initialization, Android teardown and microphone admission are outside scope. */
@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [ServiceStateOsShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class ReverbServiceStateDeliveryTest {
    @Test
    fun rejectedAudioQueueTerminatesEachPublicStateReadAndPreservesHealthyStorage() = withFixture { fixture ->
        fixture.proveHealthyThenReset()
        val originalPcm = fixture.loopingSourceBytes()
        val handlerField = ReverbService::class.java.getDeclaredField("audioHandler").apply { isAccessible = true }
        val originalHandler = handlerField.get(fixture.service) as Handler
        val stoppedThread = HandlerThread("service-rejected-state-queue")
        var stoppedHandler: Handler? = null
        var failure: Throwable? = null
        fun record(error: Throwable) {
            val first = failure
            if (first == null) failure = error else if (first !== error) first.addSuppressed(error)
        }
        try {
            stoppedThread.start()
            stoppedHandler = Handler(stoppedThread.looper)
        } catch (error: Throwable) {
            record(error)
        } finally {
            try { check(stoppedThread.quitSafely()) { "Rejected queue fixture refused quit" } }
            catch (error: Throwable) { record(error) }
            try {
                stoppedThread.join(8_000L)
                check(!stoppedThread.isAlive) { "Rejected queue fixture did not stop" }
            } catch (error: Throwable) { record(error) }
        }
        failure?.let { throw it }
        val first = States()
        val second = States()
        try {
            fixture.setField("audioHandler", checkNotNull(stoppedHandler))
            fixture.service.getState(first)
            fixture.service.getState(second)
            assertTrue(first.values.isEmpty())
            assertTrue(second.values.isEmpty())
            ShadowLooper.idleMainLooper()
            ShadowLooper.idleMainLooper()
            assertEquals(0, first.awaiting)
            assertEquals(0, second.awaiting)
            assertEquals(listOf(unavailableState()), first.values)
            assertEquals(listOf(unavailableState()), second.values)
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            try { handlerField.set(fixture.service, originalHandler) }
            catch (error: Throwable) {
                val firstFailure = failure
                if (firstFailure == null) throw error
                if (firstFailure !== error) firstFailure.addSuppressed(error)
            }
        }
        fixture.proveHealthyThenReset()
        assertEquals(originalPcm, fixture.loopingSourceBytes())
        // A restored queue must not generate delayed duplicates for earlier read owners.
        assertEquals(1, first.values.size)
        assertEquals(1, second.values.size)
    }

    @Test
    fun sampledStateAfterTerminalBoundaryDeliversExactlyOneUnavailable() = withFixture { fixture ->
        val callback = fixture.proveHealthyThenReset()
        fixture.service.getState(callback)
        fixture.onAudio { }
        assertTrue(callback.values.isEmpty())

        fixture.setField("serviceDestroying", true)
        fixture.onAudio { fixture.closeStores() }
        fixture.pumpDelivery()

        assertEquals(0, callback.awaiting)
        assertEquals(listOf(unavailableState()), callback.values)
    }

    @Test
    fun sampledStateBeforeWinningSelectionResamplesBeforeSingleDelivery() = withFixture { fixture ->
        val callback = fixture.proveHealthyThenReset()
        fixture.service.getState(callback)
        fixture.onAudio { }
        assertTrue(callback.values.isEmpty())

        val selected = fixture.service.selectCaptureBuffer(ReverbService.BufferSlot.LOOPING)
        assertTrue(selected.accepted)
        assertEquals(ReverbService.BufferSlot.LOOPING.storageCode.toInt(),
            getRecorderPreferences(fixture.service).getInt(PrefKey.CAPTURE_BUFFER_SLOT, -1))
        fixture.pumpDelivery()

        assertEquals(0, callback.awaiting)
        assertEquals(listOf(healthyState(selected.generation, ReverbService.BufferSlot.LOOPING)), callback.values)
    }

    @Test
    fun sampledSnapshotAfterTerminalBoundaryRetiresOffMainAndDeliversOnlyNull() {
        val release = SnapshotReleaseProbe()
        withFixture(onLoopMaintenanceNeeded = release::maintenance) { fixture ->
            val expected = ByteArray(512) { (it * 13 + 1).toByte() }
            val healthy = mutableListOf<ReverbService.TimelineSnapshot?>()
            val healthyOwners = SnapshotOwners()
            // Own delivered parents before any healthy-control assertion can fail.
            fixture.beforeStoreClose = {
                release.releaseGate()
                var failure: Throwable? = null
                try { fixture.onAudio { healthyOwners.closeAll() } } catch (error: Throwable) { failure = error }
                try { release.finish() } catch (error: Throwable) {
                    val first = failure
                    if (first == null) failure = error else if (first !== error) first.addSuppressed(error)
                }
                failure?.let { throw it }
            }
            fixture.service.acquireTimelineSnapshot(ReverbService.BufferSlot.LOOPING) {
                healthyOwners.accept(it)
                healthy.add(it)
            }
            fixture.pumpDelivery()
            assertEquals(1, healthy.size)
            val live = requireNotNull(healthy.single())
            fixture.onAudio {
                assertEquals(0.032, live.durationSeconds, 0.0)
                requireNotNull(live.acquireRange(0.0, live.durationSeconds)).use { child ->
                    assertArrayEquals(expected, readStoreTestPcm16Mono8Khz(child))
                }
                healthyOwners.closeAll()
            }
            assertEquals(0, fixture.loopingLeaseCount())
            assertEquals(0, release.wakes.get())

            val delivered = mutableListOf<ReverbService.TimelineSnapshot?>()
            fixture.service.acquireTimelineSnapshot(ReverbService.BufferSlot.LOOPING) {
                healthyOwners.accept(it)
                delivered.add(it)
            }
            fixture.onAudio { }
            assertTrue(delivered.isEmpty())
            assertEquals(1, fixture.loopingLeaseCount())
            fixture.onAudio {
                requireNotNull(fixture.loop.acquireRange(0.0, fixture.loop.durationSeconds())).use { lease ->
                    assertArrayEquals(expected, readStoreTestPcm16Mono8Khz(lease))
                }
                fixture.loop.configure(RetentionMode.SIZE, 128L, 8_000, 1, PcmSampleFormat.PCM_16,
                    deferRetentionCleanup = true)
                assertTrue(fixture.loop.retentionMaintenanceNeeded())
            }
            assertEquals(1, fixture.loopingLeaseCount())
            assertEquals(0, release.wakes.get())
            val sourceBefore = fixture.loopingSourceBytes()
            fixture.setField("serviceDestroying", true)
            fixture.pumpDelivery()

            // The real IO close is held after ref retirement while Main has already delivered null.
            release.awaitHeld()
            assertEquals(listOf(null), delivered)
            assertEquals(1, release.wakes.get())
            assertEquals(0, fixture.loopingLeaseCount())
            assertEquals(sourceBefore, fixture.loopingSourceBytes())
            release.finish()
            fixture.onAudio {
                fixture.loop.configure(RetentionMode.SIZE, 4096L, 8_000, 1, PcmSampleFormat.PCM_16)
                requireNotNull(fixture.loop.acquireRange(0.0, fixture.loop.durationSeconds())).use { lease ->
                    assertArrayEquals(expected, readStoreTestPcm16Mono8Khz(lease))
                }
                assertFalse(fixture.loop.hasRangeLeaseCloseFailure())
            }
            fixture.pumpDelivery()
            assertEquals(listOf(null), delivered)
            assertEquals(1, release.wakes.get())
            assertEquals(0, fixture.loopingLeaseCount())
        }
    }

    @Test
    fun nullSettingsStartRollsBackActualOwnerBeforeNewerKeepalive() = rejectedSettingsStart { null }

    @Test
    fun thrownSettingsStartRollsBackActualOwnerBeforeNewerKeepalive() = rejectedSettingsStart {
        throw IllegalStateException("Injected platform start failure")
    }

    private fun rejectedSettingsStart(response: () -> ComponentName?) = withFixture { fixture ->
        fixture.proveHealthyThenReset()
        val stopped = Shadow.extract<ShadowService>(fixture.service)
        assertFalse(stopped.isStoppedBySelf)
        assertEquals(0, stopped.stopSelfResultId)
        val starts = fixture.installSettingsLifetime(response)
        val writer = fixture.pauseAudio()
        writer.awaitEntered()

        assertFalse(fixture.service.applyUpdatedPreferences())
        fixture.assertSettingsStarts(starts, 1)
        assertFalse(stopped.isStoppedBySelf)
        assertEquals(Service.START_NOT_STICKY, fixture.deliverUiKeepalive(71))
        assertTrue(stopped.isStoppedBySelf)
        assertEquals(71, stopped.stopSelfResultId)
        assertEquals(0, stopped.stopSelfId)

        writer.release()
        writer.awaitFinished()
        fixture.pumpDelivery()
        assertEquals(71, stopped.stopSelfResultId)
    }

    @Test
    fun newerKeepaliveRetainsActualSettingsOwnerUntilSecondCoordinatorTerminal() = withFixture { fixture ->
        fixture.proveHealthyThenReset()
        val stopped = Shadow.extract<ShadowService>(fixture.service)
        assertFalse(stopped.isStoppedBySelf)
        assertEquals(0, stopped.stopSelfResultId)
        val starts = fixture.installSettingsLifetime { ComponentName(fixture.service, ReverbService::class.java) }
        val beforeBoth = fixture.pauseAudio()
        beforeBoth.awaitEntered()
        assertTrue(fixture.service.applyUpdatedPreferences())
        assertTrue(fixture.service.applyUpdatedPreferences())
        fixture.assertSettingsStarts(starts, 2)
        assertEquals(Service.START_NOT_STICKY, fixture.deliverUiKeepalive(71))
        assertFalse(stopped.isStoppedBySelf)
        assertEquals(0, stopped.stopSelfResultId)

        // One coordinator drain precedes this sentinel; its repost for apply #2 follows it.
        val afterFirst = fixture.pauseAudio()
        beforeBoth.release()
        beforeBoth.awaitFinished()
        afterFirst.awaitEntered()
        ShadowLooper.idleMainLooper()
        assertEquals(Service.START_NOT_STICKY, fixture.deliverUiKeepalive(72))
        assertFalse(stopped.isStoppedBySelf)
        assertEquals(0, stopped.stopSelfResultId)

        afterFirst.release()
        afterFirst.awaitFinished()
        fixture.pumpDelivery()
        assertTrue(stopped.isStoppedBySelf)
        assertEquals(0, stopped.stopSelfId)
        assertEquals(0, stopped.stopSelfResultId) // Last Settings terminal uses ordinary stopSelf().
    }

    private class States : ReverbService.StateCallback {
        val values = mutableListOf<State>()
        var awaiting = 0
        override fun awaitingStorage() { awaiting++ }
        override fun state(
            commandGeneration: Long,
            listeningEnabled: Boolean,
            activeBufferSlot: ReverbService.BufferSlot?,
            oneShotSeconds: Float,
            oneShotBytes: Long,
            loopingSeconds: Float,
            loopingBytes: Long,
            oneShotIsEnabled: Boolean,
            oneShotIsFull: Boolean,
            loopingIsEnabled: Boolean,
            retentionMode: RetentionMode?,
            exporting: Boolean,
        ) {
            values += State(commandGeneration, listeningEnabled, activeBufferSlot,
                oneShotSeconds, oneShotBytes, loopingSeconds, loopingBytes,
                oneShotIsEnabled, oneShotIsFull, loopingIsEnabled, retentionMode, exporting)
        }
    }

    private data class State(
        val commandGeneration: Long,
        val listeningEnabled: Boolean,
        val activeBufferSlot: ReverbService.BufferSlot?,
        val oneShotSeconds: Float,
        val oneShotBytes: Long,
        val loopingSeconds: Float,
        val loopingBytes: Long,
        val oneShotIsEnabled: Boolean,
        val oneShotIsFull: Boolean,
        val loopingIsEnabled: Boolean,
        val retentionMode: RetentionMode?,
        val exporting: Boolean,
    )

    private class SnapshotOwners {
        private val parents = mutableListOf<ReverbService.TimelineSnapshot>()
        fun accept(snapshot: ReverbService.TimelineSnapshot?) {
            if (snapshot != null && parents.none { it === snapshot }) parents += snapshot
        }
        fun closeAll() {
            val owned = parents.toList()
            parents.clear() // Every uncertain terminal owner gets one attempt, never a retry.
            var failure: Throwable? = null
            for (parent in owned) try { parent.close() } catch (error: Throwable) {
                val first = failure
                if (first == null) failure = error else if (first !== error) first.addSuppressed(error)
            }
            failure?.let { throw it }
        }
    }

    private class SnapshotReleaseProbe {
        val wakes = AtomicInteger()
        private val thread = AtomicReference<Thread>()
        private val entered = CountDownLatch(1)
        private val permit = CountDownLatch(1)
        private val returned = CountDownLatch(1)
        private val timedOut = AtomicBoolean()
        private var job: Job? = null
        private val preexistingJobs = cleanupJobs().toSet()
        fun maintenance() {
            wakes.incrementAndGet()
            thread.compareAndSet(null, Thread.currentThread())
            entered.countDown()
            try {
                if (Thread.currentThread() !== Looper.getMainLooper().thread &&
                    !permit.await(8, TimeUnit.SECONDS)) timedOut.set(true)
            } finally { returned.countDown() }
        }
        private fun cleanupJobs(): List<Job> {
            val scope = Class.forName("app.smallthingz.reverb.TimelineSnapshotWorkKt")
                .getDeclaredField("timelineSnapshotCleanupScope").apply { isAccessible = true }
                .get(null) as CoroutineScope
            return requireNotNull(scope.coroutineContext[Job]).children.toList()
        }
        fun awaitHeld() {
            check(entered.await(8, TimeUnit.SECONDS)) { "Actual snapshot release did not reach maintenance" }
            assertTrue(thread.get() !== Looper.getMainLooper().thread)
            assertEquals(1L, returned.count)
            job = cleanupJobs().filterNot { it in preexistingJobs }.single()
        }
        fun releaseGate() { permit.countDown() }
        fun finish() {
            releaseGate()
            // Main/audio are drained before this hook; every accepted release child is now registered.
            val owned = (listOfNotNull(job) + cleanupJobs().filterNot { it in preexistingJobs }).distinct()
            runBlocking { withTimeout(8_000L) { owned.forEach { it.join() } } }
            if (entered.count == 0L) {
                check(returned.await(8, TimeUnit.SECONDS)) { "Snapshot maintenance callback did not return" }
            }
            check(!timedOut.get()) { "Snapshot maintenance observation timed out" }
        }
    }

    private class AudioPause(audio: Handler) {
        private val entered = CountDownLatch(1)
        private val released = CountDownLatch(1)
        private val task = FutureTask(Callable {
            entered.countDown()
            check(released.await(8, TimeUnit.SECONDS)) { "Fixture audio pause timed out" }
        })
        init { check(audio.post(task)) { "Fixture audio Handler rejected pause" } }
        fun awaitEntered() { check(entered.await(8, TimeUnit.SECONDS)) { "Fixture audio pause never entered" } }
        fun release() { released.countDown() }
        fun awaitFinished() { task.get(8, TimeUnit.SECONDS) }
    }

    private class Fixture(val service: ReverbService, private val audio: Handler,
        private val one: PersistentAudioChunkStore, val loop: PersistentAudioChunkStore) {
        var beforeStoreClose: () -> Unit = {}

        private val audioPauses = mutableListOf<AudioPause>()
        private var originalContext: Context? = null
        fun pauseAudio() = AudioPause(audio).also { audioPauses += it }
        fun releaseAudioPauses() { audioPauses.forEach { it.release() } }
        fun restoreContext() {
            originalContext?.let { contextBase().set(service, it) }
            originalContext = null
        }
        private fun contextBase() = ContextWrapper::class.java.getDeclaredField("mBase").apply { isAccessible = true }
        fun installSettingsLifetime(response: () -> ComponentName?): List<Intent> {
            val starts = mutableListOf<Intent>()
            val prior = contextBase().get(service) as Context
            originalContext = prior
            contextBase().set(service, object : ContextWrapper(prior) {
                override fun startService(intent: Intent): ComponentName? {
                    starts += Intent(intent)
                    return response()
                }
            })
            setField("captureStoresAdmitted", false) // Ownership only; configuration is outside this test.
            setField("captureIo", CaptureIoCoordinator(audio,
                onPacket = { _, _, _ -> error("Unexpected PCM in Settings lifetime test") },
                onInputFailure = { _, error -> throw AssertionError("Unexpected input", error) },
                onWriterFailure = { error -> throw AssertionError("Unexpected writer failure", error) },
                onDispatchFailure = { error -> throw AssertionError("Unexpected dispatch failure", error) },
            ))
            return starts
        }
        fun assertSettingsStarts(starts: List<Intent>, count: Int) {
            assertEquals(List(count) { ReverbService.ACTION_SETTINGS_RUNTIME_KEEPALIVE }, starts.map { it.action })
            assertEquals(List(count) { ComponentName(service, ReverbService::class.java) }, starts.map { it.component })
        }
        fun deliverUiKeepalive(startId: Int): Int = service.onStartCommand(
            Intent(service, ReverbService::class.java).setAction("app.smallthingz.reverb.UI_CAPTURE_KEEPALIVE"),
            0, startId,
        )
        fun loopingLeaseCount(): Int = onAudio {
            synchronized(loop) {
                PersistentAudioChunkStore::class.java.getDeclaredField("activeRangeLeases")
                    .apply { isAccessible = true }.getInt(loop)
            }
        }
        fun loopingSourceBytes(): Map<String, List<Byte>> = onAudio {
            val chunks = requireNotNull(ServiceStateOsShadow.fixtureRoot).resolve("loop")
                .resolve(BUFFER_CHUNKS_FOLDER_NAME).toFile()
            val files = requireNotNull(chunks.listFiles())
            check(files.isNotEmpty() && files.all { it.isFile })
            files.associate { it.name to it.readBytes().toList() }
        }
        private var storesCloseAttempted = false
        var storesClosedSuccessfully = false
            private set
        fun setField(name: String, value: Any?) {
            ReverbService::class.java.getDeclaredField(name).apply { isAccessible = true }.set(service, value)
        }
        fun <T> onAudio(work: () -> T): T {
            val task = FutureTask(Callable { work() })
            check(audio.post(task)) { "Fixture audio Handler rejected work" }
            return task.get(8, TimeUnit.SECONDS)
        }
        fun pumpDelivery() {
            // A queued Main callback can post a fresh audio sample. Drain that finite round trip.
            repeat(3) {
                onAudio { }
                ShadowLooper.idleMainLooper()
            }
        }
        fun proveHealthyThenReset(): States {
            val callback = States()
            service.getState(callback)
            pumpDelivery()
            assertEquals(0, callback.awaiting)
            assertEquals(listOf(healthyState(0L, ReverbService.BufferSlot.ONE_SHOT)), callback.values)
            callback.values.clear()
            return callback
        }
        fun closeStores() {
            if (storesCloseAttempted) return
            storesCloseAttempted = true
            var failure: Throwable? = null
            try { one.close() } catch (error: Throwable) { failure = error }
            try { loop.close() } catch (error: Throwable) {
                if (failure == null) failure = error else if (failure !== error) failure.addSuppressed(error)
            }
            failure?.let { throw it }
            storesClosedSuccessfully = true
        }
    }

    private fun withFixture(onLoopMaintenanceNeeded: () -> Unit = {}, body: (Fixture) -> Unit) {
        val app = RuntimeEnvironment.getApplication()
        check(ServiceStateOsShadow.fixtureRoot == null) { "Prior Service fixture still owns its OS shadow" }
        val root = Files.createTempDirectory(app.cacheDir.toPath(), "service-state-delivery-").toFile()
        ServiceStateOsShadow.fixtureRoot = root.toPath().toAbsolutePath().normalize()
        val one = PersistentAudioChunkStore(File(root, "one"), overwriteOldest = false, directorySync = {})
        val loop = PersistentAudioChunkStore(File(root, "loop"), directorySync = {},
            onMaintenanceNeeded = onLoopMaintenanceNeeded)
        val thread = HandlerThread("service-state-test-audio")
        var fixture: Fixture? = null
        var failure: Throwable? = null
        var drained = false
        var storesClosed = false
        var joined = false
        fun record(error: Throwable) {
            val first = failure
            if (first == null) failure = error else if (first !== error) first.addSuppressed(error)
        }
        try {
            assertEquals(StoragePathState.PRESENT, storageDirectoryState(root))
            assertEquals(StoragePathState.MISSING, storageDirectoryState(File(root, "one")))
            val link = root.toPath().resolve("nofollow-check")
            Files.createSymbolicLink(link, root.toPath())
            try { assertEquals(StoragePathState.UNAVAILABLE, storageDirectoryState(link.toFile())) }
            finally { Files.delete(link) }
            for ((store, payloadBytes) in listOf(one to 256, loop to 512)) {
                store.configure(RetentionMode.SIZE, 4096L, 8_000, 1, PcmSampleFormat.PCM_16)
                val bytes = ByteArray(payloadBytes) { (it * 13 + 1).toByte() }
                assertEquals(payloadBytes, store.append(bytes, 0, bytes.size))
                requireNotNull(store.acquireRange(0.0, store.durationSeconds())).use { lease ->
                    assertArrayEquals(bytes, readStoreTestPcm16Mono8Khz(lease))
                }
            }
            thread.start()
            // ServiceController attaches Android context; onCreate is deliberately not invoked.
            val service = Robolectric.buildService(ReverbService::class.java).get()
            val current = Fixture(service, Handler(thread.looper), one, loop)
            fixture = current
            current.setField("audioHandler", Handler(thread.looper))
            current.setField("oneShotAudioChunkStore", one)
            current.setField("loopingAudioChunkStore", loop)
            current.setField("captureStoresReady", true)
            current.setField("captureInitializationPending", false)
            current.setField("configuredRetentionMode", RetentionMode.SIZE)
            current.setField("durableCaptureIntentAuthorityValid", true)
            current.setField("durableListeningIntentEnabled", false)
            current.setField("durableCaptureBufferSlot", ReverbService.BufferSlot.ONE_SHOT)
            assertTrue(getRecorderPreferences(service).edit()
                .putBoolean(PrefKey.AUDIO_MEMORY_ENABLED, false)
                .putInt(PrefKey.CAPTURE_BUFFER_SLOT, ReverbService.BufferSlot.ONE_SHOT.storageCode.toInt()).commit())
            body(current)
        } catch (error: Throwable) {
            failure = error
        } finally {
            val current = fixture
            if (current == null) {
                // No audio work is submitted before Fixture exists; close early setup owners here.
                drained = true
                var closed = true
                try { one.close() } catch (error: Throwable) { closed = false; record(error) }
                try { loop.close() } catch (error: Throwable) { closed = false; record(error) }
                storesClosed = closed
            } else {
                current.releaseAudioPauses()
                try { current.restoreContext() } catch (error: Throwable) { record(error) }
                try {
                    current.setField("serviceDestroying", true)
                    current.pumpDelivery()
                    current.beforeStoreClose()
                    drained = true
                    current.onAudio { current.closeStores() }
                    storesClosed = current.storesClosedSuccessfully
                } catch (error: Throwable) { record(error) }
            }
            if (thread.isAlive && drained) {
                try {
                    check(thread.quitSafely()) { "Fixture audio thread refused quit" }
                    thread.join(8_000L)
                    check(!thread.isAlive) { "Fixture audio thread did not finish; retaining $root" }
                    joined = true
                } catch (error: Throwable) { record(error) }
            } else if (!thread.isAlive) joined = true
            if (drained && storesClosed && joined) {
                try {
                    check(root.deleteRecursively()) { "Cannot delete owned fixture $root" }
                    ServiceStateOsShadow.fixtureRoot = null
                }
                catch (error: Throwable) { record(error) }
            } else {
                record(AssertionError("Incomplete fixture cleanup; retaining owners and $root"))
            }
            // A partial lifecycle has no onCreate-owned work; never call controller.destroy().
        }
        failure?.let { throw it }
    }

    private companion object {
        fun unavailableState() = State(
            commandGeneration = 0L,
            listeningEnabled = false,
            activeBufferSlot = ReverbService.BufferSlot.ONE_SHOT,
            oneShotSeconds = 0f,
            oneShotBytes = 0L,
            loopingSeconds = 0f,
            loopingBytes = 0L,
            oneShotIsEnabled = false,
            oneShotIsFull = false,
            loopingIsEnabled = false,
            retentionMode = null,
            exporting = false,
        )

        fun healthyState(generation: Long, slot: ReverbService.BufferSlot) = State(
            commandGeneration = generation,
            listeningEnabled = false,
            activeBufferSlot = slot,
            oneShotSeconds = 0.016f,
            oneShotBytes = 256L,
            loopingSeconds = 0.032f,
            loopingBytes = 512L,
            oneShotIsEnabled = true,
            oneShotIsFull = false,
            loopingIsEnabled = true,
            retentionMode = RetentionMode.SIZE,
            exporting = false,
        )
    }
}
