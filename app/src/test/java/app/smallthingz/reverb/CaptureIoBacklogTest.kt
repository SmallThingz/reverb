package app.smallthingz.reverb

import android.app.Application
import android.os.Handler
import android.os.HandlerThread
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class CaptureIoBacklogTest {
    @Test fun byteCapacityExhaustionDrainsAcceptedPacketsBeforeFailure() {
        exerciseExhaustion(CapturePcmBudget(2, 8, 32, 8), listOf(
            byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), byteArrayOf(11, 12, 13, 14, 15, 16, 17, 18),
            byteArrayOf(21, 22, 23, 24, 25, 26, 27, 28), byteArrayOf(31, 32, 33, 34, 35, 36, 37, 38),
        ), expectedBytes = 32, expectedPackets = 4)
    }

    @Test fun shortReadsExhaustDescriptorsEvenWhenByteCapacityRemains() {
        exerciseExhaustion(CapturePcmBudget(2, 8, 32, 2), listOf(
            byteArrayOf(1, 2), byteArrayOf(3, 4),
        ), expectedBytes = 4, expectedPackets = 2)
    }

    private fun exerciseExhaustion(budget: CapturePcmBudget, packets: List<ByteArray>,
        expectedBytes: Int, expectedPackets: Int) {
        withFixture(budget, packets) { f ->
            f.start()
            f.await(f.writerEntered)
            f.await(f.exhausted)
            val status = f.storage.ring.status()
            assertEquals(CapturePcmRing.Terminal.EXHAUSTED, status.terminal)
            assertEquals(expectedBytes, status.occupiedBytes)
            assertEquals(expectedPackets, status.occupiedPackets)
            assertEquals(packets.size, f.source.reads.get())
            assertEquals(0, f.failures.get())
            f.releaseWriter.countDown()
            f.await(f.failureDelivered)
            f.onWriter { Unit }
            assertEquals(1, f.failures.get())
            assertEquals(packets.size, f.deliveredAtFailure.get())
            assertTrue(f.inputFailure.get() is IOException)
            assertEquals("Capture PCM backlog exhausted its bounded memory budget", f.inputFailure.get()?.message)
            assertNull(f.unexpected.get())
            assertEquals(packets.size, f.delivered.size)
            packets.indices.forEach { assertArrayEquals(packets[it], f.delivered[it]) }
            assertTrue(f.storage.ring.isDrained())
            assertEquals(packets.size, f.source.reads.get())
            f.detach()
            assertEquals(packets.size, f.source.reads.get())
            assertEquals(1, f.failures.get())
            assertEquals(packets.size, f.delivered.size)
        }
    }

    @Test fun underBudgetWriterStallResumesWithoutFailureOrLostPcm() {
        val packets = listOf(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), byteArrayOf(11, 12, 13, 14, 15, 16, 17, 18))
        withFixture(CapturePcmBudget(2, 8, 32, 8), packets, holdNextRead = true) { f ->
            f.start()
            f.await(f.writerEntered)
            f.await(f.source.extraReadEntered)
            assertNull(f.storage.ring.status().terminal)
            f.releaseWriter.countDown()
            f.await(f.allDelivered)
            f.coordinator.stopInput(preserveInFlight = false)
            f.source.releaseExtraRead.countDown()
            f.onReader { Unit }
            f.awaitDrained()
            assertNull(f.inputFailure.get())
            assertNull(f.unexpected.get())
            assertEquals(0, f.failures.get())
            assertEquals(packets.size, f.delivered.size)
            packets.indices.forEach { assertArrayEquals(packets[it], f.delivered[it]) }
            f.detach()
            assertEquals(packets.size + 1, f.source.reads.get())
            assertEquals(0, f.failures.get())
            assertEquals(packets.size, f.delivered.size)
        }
    }

    @Test fun writerFailureRevokesQueuedAndInflightPcmWithoutReusingReadOwner() {
        val deliveredPrefix = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val queued = byteArrayOf(11, 12, 13, 14, 15, 16, 17, 18)
        val inflight = byteArrayOf(21, 22, 23, 24, 25, 26, 27, 28)
        val injected = IOException("injected sink failure")
        withFixture(CapturePcmBudget(2, 8, 32, 8), listOf(deliveredPrefix, queued),
            holdNextRead = true, writerFailure = injected, heldReadBytes = inflight) { f ->
            f.start()
            f.await(f.writerEntered)
            f.await(f.source.extraReadEntered)
            assertTrue(f.storage.ring.status().reading)
            assertEquals(3, f.source.reads.get())
            f.releaseWriter.countDown()
            f.await(f.writerFailureDelivered)
            assertSame(injected, f.unexpected.get())
            assertEquals(1, f.writerFailures.get())
            assertEquals(CapturePcmRing.Terminal.WRITER_FAILURE, f.storage.ring.status().terminal)
            assertTrue(f.storage.ring.status().reading)
            assertEquals(1, f.delivered.size)
            assertArrayEquals(deliveredPrefix, f.delivered.single())
            f.source.releaseExtraRead.countDown()
            f.onReader { Unit }
            f.awaitDrained()
            assertTrue(f.storage.ring.isDrained())
            assertEquals(1, f.writerFailures.get())
            assertEquals(0, f.failures.get())
            assertEquals(3, f.source.reads.get())
            assertEquals(1, f.delivered.size)
            assertArrayEquals(deliveredPrefix, f.delivered.single())
            f.detach()
            assertEquals(1, f.writerFailures.get())
            assertEquals(3, f.source.reads.get())
            assertEquals(0, f.failures.get())
            assertEquals(1, f.delivered.size)
            assertArrayEquals(deliveredPrefix, f.delivered.single())
        }
    }

    private fun withFixture(budget: CapturePcmBudget, packets: List<ByteArray>, holdNextRead: Boolean = false,
        writerFailure: IOException? = null, heldReadBytes: ByteArray = byteArrayOf(),
        block: (Fixture) -> Unit) {
        val thread = HandlerThread("backlog-test-writer")
        var fixture: Fixture? = null
        Closeable {
            thread.quitSafely()
            thread.join(5000)
            check(!thread.isAlive) { "writer thread did not terminate" }
            fixture?.cleanupAfterWriterTermination()
        }.use {
            thread.start()
            val created = Fixture(thread, budget, packets, holdNextRead, writerFailure, heldReadBytes)
            fixture = created
            created.use(block)
        }
    }

    private class Fixture(writerThread: HandlerThread, budget: CapturePcmBudget, packets: List<ByteArray>, holdNextRead: Boolean,
        writerFailure: IOException?, heldReadBytes: ByteArray) : Closeable {
        val storage = CaptureIoCoordinator.Storage(budget)
        val writer = Handler(writerThread.looper)
        val source = Source(packets, holdNextRead, heldReadBytes)
        val writerEntered = CountDownLatch(1)
        val releaseWriter = CountDownLatch(1)
        val exhausted = CountDownLatch(1)
        val failureDelivered = CountDownLatch(1)
        val writerFailureDelivered = CountDownLatch(1)
        val allDelivered = CountDownLatch(packets.size)
        val delivered = mutableListOf<ByteArray>()
        val failures = AtomicInteger()
        val writerFailures = AtomicInteger()
        val deliveredAtFailure = AtomicInteger(-1)
        val inputFailure = AtomicReference<Throwable>()
        val unexpected = AtomicReference<Throwable>()
        private val identity = CaptureReadIdentity(1, 2, 3, 0, 8_000, 1, 2, 2, 0)
        lateinit var installed: CaptureIoCoordinator.Input
        private var detached = false
        val coordinator = CaptureIoCoordinator(writer,
            onPacket = { _, packet, bytes ->
                if (delivered.isEmpty()) {
                    writerEntered.countDown()
                    check(releaseWriter.await(5, TimeUnit.SECONDS)) { "writer release timed out" }
                }
                delivered.add(bytes.copyOf(packet.byteCount))
                allDelivered.countDown()
                if (writerFailure != null) throw writerFailure
            },
            onInputFailure = { _, error -> deliveredAtFailure.set(delivered.size); inputFailure.set(error); failures.incrementAndGet(); failureDelivered.countDown() },
            onWriterFailure = { unexpected.compareAndSet(null, it); writerFailures.incrementAndGet(); writerFailureDelivered.countDown() },
            onDispatchFailure = { unexpected.compareAndSet(null, it) },
        )
        fun start() = onWriter {
            installed = coordinator.install(source, 1, 2, storage,
                admit = { ring -> ring.reserve(identity, storage.budget.maxReadBytes).also {
                    if (it == null && ring.status().terminal == CapturePcmRing.Terminal.EXHAUSTED) exhausted.countDown()
                } }, mayComplete = { true }, captureClockRequested = { false })
            coordinator.resumeInput()
        }
        fun detach() {
            onWriter { check(coordinator.detach(source)) }
            installed.thread.join(5000)
            check(!installed.thread.isAlive) { "reader thread did not terminate" }
            detached = true
        }
        fun awaitDrained() {
            val completed = CountDownLatch(1)
            val rejected = AtomicReference<Throwable>()
            val admitted = coordinator.post(pauseInput = true, allowTerminal = true,
                rejected = { rejected.set(AssertionError("drain fence rejected")); completed.countDown() },
                work = { completed.countDown() })
            check(admitted == CaptureControlMailbox.Admission.ACCEPTED) { "drain fence not admitted: $admitted" }
            await(completed)
            rejected.get()?.let { throw it }
            check(storage.ring.isDrained()) { "drain fence left owned packets" }
        }
        fun <T> onWriter(block: () -> T): T = onHandler(writer, block)
        fun <T> onReader(block: () -> T): T = onHandler(installed.handler, block)
        fun await(latch: CountDownLatch) { assertTrue("timed out waiting for coordinator", latch.await(5, TimeUnit.SECONDS)) }
        fun cleanupAfterWriterTermination() {
            val owner = coordinator.currentInput() ?: (if (::installed.isInitialized) installed else null) ?: return
            if (!owner.thread.isAlive) return
            releaseWriter.countDown()
            source.releaseExtraRead.countDown()
            var failure: Throwable? = null
            fun attempt(work: () -> Unit) {
                try { work() } catch (error: Throwable) {
                    if (failure == null) failure = error else if (failure !== error) failure!!.addSuppressed(error)
                }
            }
            var acknowledged = false
            attempt { coordinator.stopInput(preserveInFlight = false) }
            attempt { onHandler(owner.handler) { Unit }; acknowledged = true }
            val drained = storage.ring.isDrained()
            if (acknowledged) attempt { owner.thread.quitSafely() }
            attempt {
                owner.thread.join(5000)
                check(!owner.thread.isAlive) { "reader thread did not terminate after writer" }
            }
            attempt { check(drained) { "writer stopped with retained packet ownership" } }
            failure?.let { throw it }
        }
        override fun close() {
            releaseWriter.countDown()
            source.releaseExtraRead.countDown()
            var failure: Throwable? = null
            fun attempt(work: () -> Unit) {
                try { work() } catch (error: Throwable) {
                    if (failure == null) failure = error else if (failure !== error) failure!!.addSuppressed(error)
                }
            }
            var owner = coordinator.currentInput() ?: if (::installed.isInitialized) installed else null
            attempt { onWriter { owner = coordinator.currentInput() ?: owner } }
            val cleanupOwner = coordinator.currentInput() ?: owner
            if (cleanupOwner != null && !detached) {
                attempt { coordinator.stopInput(preserveInFlight = false) }
                if (coordinator.currentInput() === cleanupOwner) {
                    attempt { onHandler(cleanupOwner.handler) { Unit } }
                    attempt { awaitDrained() }
                    if (storage.ring.isDrained()) attempt { onWriter { check(coordinator.detach(source)) } }
                    else attempt { error("reader or packet ownership remains uncertain") }
                }
                attempt {
                    cleanupOwner.thread.join(5000)
                    check(!cleanupOwner.thread.isAlive) { "reader thread did not terminate" }
                }
            }
            failure?.let { throw it }
        }
        private fun <T> onHandler(handler: Handler, block: () -> T): T {
            val task = FutureTask(Callable { block() })
            check(handler.post(task)) { "handler rejected test task" }
            return task.get(5, TimeUnit.SECONDS)
        }
    }

    private class Source(private val packets: List<ByteArray>, private val holdNextRead: Boolean,
        private val heldReadBytes: ByteArray) : CaptureAudioInput {
        val reads = AtomicInteger()
        val extraReadEntered = CountDownLatch(1)
        val releaseExtraRead = CountDownLatch(1)
        override val state = 1
        override val recordingState = 1
        override fun startRecording() = Unit
        override fun stop() = Unit
        override fun release() = Unit
        override fun read(buffer: ByteBuffer, sizeInBytes: Int, readMode: Int): Int {
            val index = reads.getAndIncrement()
            if (index < packets.size) return packets[index].also { buffer.put(it) }.size
            check(holdNextRead) { "unexpected read after accepted packets" }
            extraReadEntered.countDown()
            check(releaseExtraRead.await(5, TimeUnit.SECONDS)) { "input release timed out" }
            check(heldReadBytes.size <= sizeInBytes) { "held read exceeds requested capacity" }
            buffer.put(heldReadBytes)
            return heldReadBytes.size
        }
    }
}
