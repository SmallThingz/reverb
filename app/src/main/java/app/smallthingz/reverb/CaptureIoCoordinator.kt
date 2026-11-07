package app.smallthingz.reverb

import android.media.AudioRecord
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.Executors

/** One service-lifetime command FIFO; input epochs may change while accepted commands retain ownership. */
internal class CaptureIoCoordinator(
    private val writer: Handler,
    private val onPacket: (Input, CapturePcmRing.Packet, ByteArray) -> Unit,
    private val onInputFailure: (Input, Throwable) -> Unit,
    private val onWriterFailure: (Throwable) -> Unit,
    private val onDispatchFailure: (Throwable) -> Unit,
    private val dispatchWriter: (Runnable) -> Boolean = writer::post,
) {
    class Storage(val budget: CapturePcmBudget) {
        val ring = CapturePcmRing(budget)
        val native = ByteBuffer.allocateDirect(budget.maxReadBytes).order(ByteOrder.nativeOrder())
        val writerScratch = ByteArray(budget.maxReadBytes)
    }
    class Input internal constructor(
        val record: CaptureAudioInput,
        val releaseGeneration: Long,
        val continuityGeneration: Long,
        val storage: Storage,
        internal val admit: (CapturePcmRing) -> CapturePcmRing.Lease?,
        internal val mayComplete: (CapturePcmRing.Lease) -> Boolean,
        internal val captureClockRequested: () -> Boolean,
    ) {
        internal val thread = HandlerThread("reverb-native-input", Process.THREAD_PRIORITY_AUDIO)
        internal lateinit var handler: Handler
        internal val readPosted = AtomicBoolean()
        internal val retired = AtomicBoolean()
        internal val externallyReleased = AtomicBoolean()
        internal val lifecycleLock = Any()
        @Volatile internal var failure: Throwable? = null
        @Volatile var processedFrames: Long = 0L
            internal set
        internal var failureDelivered = false
        @Volatile internal var started = false
        internal lateinit var read: Runnable
    }
    private class Boundary(var input: Input?, var fence: CapturePcmRing.Fence?, val allowTerminal: Boolean) {
        var healthy = true
    }
    private val commands = CaptureControlMailbox<Boundary>()
    @Volatile private var input: Input? = null
    private val writerPosted = AtomicBoolean()
    private val writerExecutionLock = Any()
    private val dispatchCleanupStarted = AtomicBoolean()

    fun install(
        record: CaptureAudioInput, releaseGeneration: Long, continuityGeneration: Long, storage: Storage,
        admit: (CapturePcmRing) -> CapturePcmRing.Lease?,
        mayComplete: (CapturePcmRing.Lease) -> Boolean,
        captureClockRequested: () -> Boolean,
    ): Input {
        check(Looper.myLooper() == writer.looper && input == null)
        val next = Input(record, releaseGeneration, continuityGeneration, storage, admit, mayComplete, captureClockRequested)
        next.read = Runnable { readOnce(next) }
        try {
            next.thread.start()
            next.handler = Handler(next.thread.looper)
            input = next
        } catch (error: Throwable) {
            next.retired.set(true)
            next.thread.quitSafely()
            throw error // The caller still owns and releases the uninstalled native record.
        }
        return next
    }

    fun resumeInput() { input?.let(::kickReader) }
    fun closeAdmission() { commands.closeAdmission() }

    fun afterDispatchFailure(work: () -> Unit) {
        check(commands.hasFailed())
        rejectionWorker.execute {
            settleDispatchFailure()
            synchronized(writerExecutionLock) { work() }
        }
    }

    fun postTerminalAfterDispatchFailure(work: () -> Unit): Boolean {
        if (!commands.claimTerminalAfterDispatchFailure()) return false
        afterDispatchFailure(work)
        return true
    }

    /** ACCEPTED transfers exactly one terminal callback; other outcomes transfer no ownership. */
    fun post(pauseInput: Boolean, allowTerminal: Boolean = false, emergency: Boolean = false,
        terminalClose: Boolean = false, rejected: () -> Unit, work: () -> Unit): CaptureControlMailbox.Admission {
        val kind = when {
            terminalClose -> CaptureControlMailbox.Kind.TERMINAL
            emergency -> CaptureControlMailbox.Kind.EMERGENCY
            else -> CaptureControlMailbox.Kind.NORMAL
        }
        val admission = commands.offer(kind, boundary = {
            val current = input
            Boundary(current, current?.storage?.ring?.reserveFence(pauseInput), allowTerminal)
        }, work = work, rejected = rejected)
        if (admission == CaptureControlMailbox.Admission.ACCEPTED) wakeWriter()
        return admission
    }

    fun stopInput(preserveInFlight: Boolean, writerFailed: Boolean = false) {
        input?.let { current ->
            if (writerFailed) current.storage.ring.writerFailed() else current.storage.ring.stop(preserveInFlight)
            wakeWriter()
        }
    }

    fun inputIsReading(): Boolean = input?.storage?.ring?.status()?.reading == true
    fun currentInput(): Input? = input

    /** Only a drained/pause-fenced owner can release native input; main timeout keeps its explicit exception. */
    fun detach(record: CaptureAudioInput?): Boolean {
        val current = input ?: return true
        if (current.record !== record && !(record == null && current.externallyReleased.get())) return false
        synchronized(current.lifecycleLock) {
            check(current.externallyReleased.get() ||
                (!current.storage.ring.status().reading && current.storage.ring.status().occupiedPackets == 0)) {
                "Input release overtook accepted PCM/native return"
            }
            input = null
            current.retired.set(true)
            commands.retireWaitingBoundaries { boundary ->
                val token = boundary.fence
                if (boundary.input === current && token != null && current.storage.ring.prefixDrained(token)) {
                    boundary.healthy = current.storage.ring.fenceMayExecute(token)
                    current.storage.ring.completeFence(token)
                    boundary.fence = null
                    boundary.input = null
                }
            }
        }
        current.storage.ring.stop(preserveInFlight = false)
        current.handler.removeCallbacks(current.read)
        current.thread.quitSafely()
        return true
    }

    /** Does not recycle native scratch; the admitted read still owns its acknowledgement. */
    fun revokeAfterForcedNativeRelease(record: CaptureAudioInput?) {
        input?.takeIf { it.record === record }?.let {
            it.externallyReleased.set(true)
            it.storage.ring.stop(preserveInFlight = false)
        }
    }

    private fun kickReader(current: Input) {
        if (current.retired.get() || input !== current) return
        // Commands accepted before a replacement input existed cannot name its ring. Run those
        // commands before admitting the first read in that new epoch.
        if (!current.started && commands.hasPending()) return
        current.started = true
        if (!current.readPosted.compareAndSet(false, true)) return
        if (!current.handler.post(current.read)) {
            current.readPosted.set(false)
            current.storage.ring.inputFailed()
            current.failure = IOException("Native input worker rejected capture read")
            wakeWriter()
        }
    }

    private fun readOnce(current: Input) {
        current.readPosted.set(false)
        if (current.retired.get() || input !== current) return
        val ring = current.storage.ring
        val lease = try { synchronized(current.lifecycleLock) {
            if (current.retired.get() || input !== current) null else current.admit(ring)
        } } catch (error: Throwable) {
            ring.inputFailed(); current.failure = error; wakeWriter(); return
        }
        if (lease == null) {
            if (ring.status().terminal == CapturePcmRing.Terminal.EXHAUSTED) {
                current.failure = IOException("Capture PCM backlog exhausted its bounded memory budget")
            }
            wakeWriter()
            return // A fence completion, not a poll/spin, owns resumption.
        }
        val buffer = current.storage.native
        var delivered = 0
        try {
            buffer.clear()
            val count = current.record.read(buffer, lease.requestedBytes, AudioRecord.READ_BLOCKING)
            delivered = count
            val endedNanos = System.nanoTime()
            val endedMillis = System.currentTimeMillis()
            if (count < 0) throw NativeReadFailure(count)
            if (count > lease.requestedBytes || count % lease.identity.frameBytes != 0) {
                throw IOException("AudioRecord returned invalid frame geometry: $count")
            }
            val captureStart = if (count > 0 && current.captureClockRequested())
                current.record.lastReadStartedAtNanos(endedNanos) else null
            if (!current.mayComplete(lease)) ring.stop(preserveInFlight = false)
            ring.complete(lease, buffer, count, endedNanos, captureStart, endedMillis)
        } catch (error: Throwable) {
            ring.inputFailed()
            current.failure = error
            // Every reserved lease reaches native-return acknowledgement, even malformed/error returns.
            if (ring.status().reading) {
                ring.complete(lease, buffer.apply { clear() }, 0, System.nanoTime(), null, System.currentTimeMillis())
            }
        }
        // A forced release may retire this input while native code is returning. Acknowledging
        // its lease is still required; waking the already-closed writer is not a new failure.
        if (current.retired.get() || input !== current) return
        wakeWriter()
        if (ring.shouldRescheduleRead()) {
            // Zero reads use the same bounded retry delay as the existing recorder.
            if (current.readPosted.compareAndSet(false, true)) {
                if (!current.handler.postDelayed(current.read, if (delivered > 0) 0L else 20L)) {
                    current.readPosted.set(false)
                    ring.inputFailed(); current.failure = IOException("Native input reschedule rejected")
                    wakeWriter()
                }
            }
        }
    }

    private val drain = Runnable { synchronized(writerExecutionLock) { drainOnce() } }

    private fun drainOnce() {
        writerPosted.set(false)
        check(Looper.myLooper() == writer.looper)
        if (commands.hasFailed()) return
        input?.let { current ->
            val failure = current.failure
            if (failure != null && !current.failureDelivered && current.storage.ring.isDrained()) {
                current.failureDelivered = true
                onInputFailure(current, failure)
            }
        }
        val control = commands.claim()
        val boundary = control?.boundary
        val prefix = boundary?.input?.storage?.ring
        if (control != null && (prefix == null || prefix.prefixDrained(checkNotNull(boundary?.fence)))) {
            if (!commands.begin(control)) return
            try {
                if (checkNotNull(boundary).allowTerminal ||
                    (prefix?.fenceMayExecute(checkNotNull(boundary.fence)) ?: boundary.healthy)) {
                    control.work()
                } else control.rejected()
            } finally {
                checkNotNull(boundary).fence?.let { prefix!!.completeFence(it) }
                commands.finish(control)
                if (control.kind == CaptureControlMailbox.Kind.TERMINAL) writer.looper.quitSafely()
                else { input?.let(::kickReader); wakeWriter() }
            }
            return
        }
        val current = boundary?.input ?: input ?: return
        val ring = current.storage.ring
        val packet = ring.poll()
        if (packet != null) {
            try {
                ring.copyPacket(packet, current.storage.writerScratch)
                onPacket(current, packet, current.storage.writerScratch)
                current.processedFrames += packet.byteCount / packet.lease.identity.frameBytes
            } catch (error: Throwable) {
                ring.writerFailed()
                onWriterFailure(error)
            } finally { ring.completePacket(packet) }
            kickReader(current)
            wakeWriter()
        } else {
            val failure = current.failure
            if (failure != null && !current.failureDelivered && ring.isDrained()) {
                current.failureDelivered = true
                onInputFailure(current, failure)
            }
        }
    }

    private fun wakeWriter(): Boolean {
        if (commands.hasFailed()) return false
        if (!writerPosted.compareAndSet(false, true)) return true
        if (dispatchWriter(drain)) return true
        writerPosted.set(false)
        input?.storage?.ring?.writerFailed()
        if (commands.failDispatch()) {
            writer.looper.quitSafely()
            // Never run service callbacks on the native reader or while holding mailbox/ring locks.
            rejectionWorker.execute { settleDispatchFailure() }
        }
        return false
    }

    private fun settleDispatchFailure() {
        var interrupted = false
        while (writer.looper.thread.isAlive) {
            try { writer.looper.thread.join() }
            catch (_: InterruptedException) { interrupted = true }
        }
        if (interrupted) Thread.currentThread().interrupt()
        // A rejected post can race the callback which is already appending. Its disk
        // ownership must end before off-thread terminal cleanup may touch the stores.
        synchronized(writerExecutionLock) {
            if (!dispatchCleanupStarted.compareAndSet(false, true)) return
            var callbackError: Throwable? = null
            try { onDispatchFailure(IOException("Capture writer rejected admitted PCM/control")) }
            catch (error: Throwable) { callbackError = error }
            while (true) {
                val ticket = commands.claimRejection() ?: break
                try { ticket.rejected() }
                catch (error: Throwable) {
                    if (callbackError == null) callbackError = error else callbackError!!.addSuppressed(error)
                } finally {
                    ticket.boundary.fence?.let { ticket.boundary.input!!.storage.ring.completeFence(it) }
                    commands.finish(ticket)
                }
            }
            callbackError?.let { android.util.Log.e("CaptureIo", "Capture rejection callback failed", it) }
        }
    }

    companion object {
        private val rejectionWorker = Executors.newSingleThreadExecutor { work ->
            Thread(work, "reverb-capture-rejection").apply { isDaemon = true }
        }
    }
}

internal class NativeReadFailure(val status: Int) : IOException("AudioRecord read failed: $status")
