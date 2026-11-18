package app.smallthingz.reverb

import java.io.IOException
import java.nio.ByteBuffer

/** Fixed allocations for the supported format range and unchanged 8/40/1000 ms read cadence. */
internal data class CapturePcmBudget(
    val frameBytes: Int,
    val maxReadBytes: Int,
    val ringBytes: Int,
    val packetCount: Int,
) {
    val totalPcmBytes: Long = ringBytes.toLong() + maxReadBytes.toLong() * 2
    init {
        require(frameBytes in 1..8 && maxReadBytes in 1..786_432 && maxReadBytes % frameBytes == 0)
        require(ringBytes > 0 && ringBytes.toLong() >= maxReadBytes.toLong() * 2 && ringBytes % frameBytes == 0)
        require(packetCount in 2..514 && totalPcmBytes <= 4_608_000L)
    }
    companion object {
        fun forFormat(rate: Int, channels: Int, bytesPerSample: Int): CapturePcmBudget {
            require(rate in 7_350..96_000 && channels in 1..2 && bytesPerSample in listOf(1, 2, 4))
            val frameBytes = channels * bytesPerSample
            val maxReadFrames = minOf(rate, (768 * 1024) / frameBytes)
            val shortestReadFrames = maxOf(1, rate * 8 / 1000)
            val ringFrames = Math.addExact(Math.multiplyExact(rate, 2), Math.multiplyExact(maxReadFrames, 2))
            val packetCount = (ringFrames + shortestReadFrames - 1) / shortestReadFrames + 2
            return CapturePcmBudget(frameBytes, maxReadFrames * frameBytes, ringFrames * frameBytes, packetCount)
        }
    }
}

/** Variable-sized FIFO reservations avoid charging an entire background batch for an 8 ms read. */
internal class CapturePcmRing(val budget: CapturePcmBudget) {
    companion object {
        // At the reader's normal 20 ms empty-read retry cadence this allows about five seconds.
        const val MAX_CONSECUTIVE_EMPTY_READS = 250
    }
    enum class Terminal { EXHAUSTED, STOP, TEARDOWN, WRITER_FAILURE, INPUT_FAILURE }
    class Lease internal constructor(
        internal val owner: CapturePcmRing, internal val start: Long,
        val sequence: Long, val identity: CaptureReadIdentity, val requestedBytes: Int,
    )
    class Packet internal constructor(
        val lease: Lease, val byteCount: Int, val endedAtNanos: Long,
        val captureStartedAtNanos: Long?, val endedAtMillis: Long,
    )
    class Fence internal constructor(
        internal val owner: CapturePcmRing, val afterSequence: Long, val pauseAdmission: Boolean,
    ) {
        internal var completed = false
        internal var admissionPaused = pauseAdmission
    }
    data class Status(val terminal: Terminal?, val occupiedBytes: Int, val highWaterBytes: Int,
        val occupiedPackets: Int, val highWaterPackets: Int, val reading: Boolean, val fence: Boolean)

    private val bytes = ByteArray(budget.ringBytes)
    private val events = arrayOfNulls<Packet>(budget.packetCount)
    private var eventHead = 0
    private var eventCount = 0
    private var tail = 0L
    private var sequence = 0L
    private var reading: Lease? = null
    private var consecutiveEmptyReads = 0
    private var writing: Packet? = null
    private var writerTransferred = false
    private var fenceCount = 0
    private var pausedFences = 0
    private var terminal: Terminal? = null
    private var exhaustion: IOException? = null
    private var highWaterBytes = 0
    private var highWaterPackets = 0

    @Synchronized fun reserve(identity: CaptureReadIdentity, requestedBytes: Int): Lease? {
        if (terminal != null || pausedFences != 0) return null
        check(reading == null)
        require(identity.frameBytes == budget.frameBytes && requestedBytes in 1..budget.maxReadBytes &&
            requestedBytes % budget.frameBytes == 0)
        val occupied = ringBytesInUse()
        val packets = eventCount + if (writing != null && !writerTransferred) 1 else 0
        val readBytes = minOf(requestedBytes, bytes.size - occupied)
        if (packets == events.size || readBytes == 0) {
            exhaustion = IOException("Capture PCM backlog exhausted: $occupied/${bytes.size} ring bytes, " +
                "$packets/${events.size} packet slots, $requestedBytes bytes requested")
            terminal = Terminal.EXHAUSTED
            return null
        }
        if (packets == 0) tail = 0
        check(sequence < Long.MAX_VALUE && tail <= Long.MAX_VALUE - readBytes)
        val lease = Lease(this, tail, ++sequence, identity, readBytes)
        tail += readBytes
        reading = lease
        highWaterBytes = maxOf(highWaterBytes, outstandingBytes())
        highWaterPackets = maxOf(highWaterPackets, outstandingPackets())
        return lease
    }

    /** Caller acknowledges native return even on error; rejected bytes are never copied into live PCM. */
    @Synchronized fun complete(
        lease: Lease, source: ByteBuffer, count: Int, endedAtNanos: Long,
        captureStartedAtNanos: Long?, endedAtMillis: Long,
    ): Boolean {
        require(lease.owner === this && reading === lease)
        require(count in 0..lease.requestedBytes && count % budget.frameBytes == 0)
        require(source.limit() >= count)
        if (count > 0) consecutiveEmptyReads = 0
        else if (terminal == null && ++consecutiveEmptyReads == MAX_CONSECUTIVE_EMPTY_READS) {
            terminal = Terminal.INPUT_FAILURE
            // The native reader publishes the failure before acknowledging this final lease.
            throw IOException("AudioRecord returned zero bytes on $consecutiveEmptyReads consecutive blocking reads")
        }
        val keep = count > 0 && terminal != Terminal.STOP && terminal != Terminal.WRITER_FAILURE && terminal != Terminal.INPUT_FAILURE
        val used = if (keep) count else 0
        check(tail == lease.start + lease.requestedBytes)
        tail = lease.start + used
        reading = null
        if (keep) {
            val offset = (lease.start % bytes.size).toInt()
            val first = minOf(count, bytes.size - offset)
            source.position(0)
            source.get(bytes, offset, first)
            if (first < count) source.get(bytes, 0, count - first)
            enqueue(Packet(lease, count, endedAtNanos, captureStartedAtNanos, endedAtMillis))
        }
        return keep
    }

    /** Called during control acceptance; every earlier native reservation belongs to this prefix. */
    @Synchronized fun reserveFence(pauseAdmission: Boolean): Fence {
        check(fenceCount < 66)
        fenceCount++
        if (pauseAdmission) pausedFences++
        return Fence(this, sequence, pauseAdmission)
    }
    @Synchronized fun prefixDrained(token: Fence): Boolean {
        require(token.owner === this && !token.completed)
        val oldest = writing?.lease ?: events[eventHead]?.lease ?: reading ?: return true
        return oldest.sequence > token.afterSequence
    }
    @Synchronized fun poll(): Packet? {
        check(writing == null)
        if (eventCount == 0) return null
        val packet = checkNotNull(events[eventHead])
        events[eventHead] = null; eventHead = (eventHead + 1) % events.size; eventCount--
        writing = packet
        return packet
    }
    /** Transfer storage to the writer scratch; its sequence stays outstanding until the sink returns. */
    @Synchronized fun transferToWriter(packet: Packet, target: ByteArray) {
        require(writing === packet && target.size >= packet.byteCount)
        check(!writerTransferred)
        val offset = (packet.lease.start % bytes.size).toInt()
        val first = minOf(packet.byteCount, bytes.size - offset)
        bytes.copyInto(target, 0, offset, offset + first)
        if (first < packet.byteCount) bytes.copyInto(target, first, 0, packet.byteCount - first)
        writerTransferred = true
    }
    @Synchronized fun completePacket(packet: Packet) {
        require(writing === packet)
        writing = null
        writerTransferred = false
    }
    @Synchronized fun fenceMayExecute(token: Fence): Boolean {
        require(token.owner === this && !token.completed)
        check(prefixDrained(token))
        return terminal == null
    }
    @Synchronized fun completeFence(token: Fence) {
        require(token.owner === this && !token.completed)
        token.completed = true
        fenceCount--
        if (token.admissionPaused) pausedFences--
        check(fenceCount >= 0 && pausedFences >= 0)
    }
    /** Keep the control's FIFO ownership while releasing only its native-admission pause. */
    @Synchronized fun releaseFencePause(token: Fence) {
        require(token.owner === this && !token.completed)
        check(prefixDrained(token))
        if (token.admissionPaused) {
            token.admissionPaused = false
            pausedFences--
        }
        check(pausedFences >= 0)
    }
    @Synchronized fun stop(preserveInFlight: Boolean) {
        if (terminal == null || (terminal == Terminal.TEARDOWN && !preserveInFlight)) {
            terminal = if (preserveInFlight) Terminal.TEARDOWN else Terminal.STOP
        }
    }
    @Synchronized fun inputFailed() { if (terminal != Terminal.WRITER_FAILURE) terminal = Terminal.INPUT_FAILURE }
    @Synchronized fun writerFailed() {
        terminal = Terminal.WRITER_FAILURE
        events.fill(null); eventHead = 0; eventCount = 0
    }
    private fun outstandingPackets() = eventCount + (if (reading != null) 1 else 0) + (if (writing != null) 1 else 0)
    // Physical ownership follows capture order. A transferred packet still owns its fence in writer scratch.
    private fun ringBytesInUse(): Int {
        val writerLease = if (writerTransferred) null else writing?.lease
        val oldest = writerLease ?: events[eventHead]?.lease ?: reading ?: return 0
        return (tail - oldest.start).toInt()
    }
    private fun outstandingBytes() = ringBytesInUse() + if (writerTransferred) checkNotNull(writing).byteCount else 0
    @Synchronized fun status() = Status(terminal, outstandingBytes(), highWaterBytes,
        outstandingPackets(), highWaterPackets, reading != null, fenceCount != 0)
    @Synchronized fun exhaustionFailure() = if (terminal == Terminal.EXHAUSTED) exhaustion else null
    @Synchronized fun shouldRescheduleRead(): Boolean {
        if (terminal != null) return false
        check(reading == null) { "Native acknowledgement left its lease owned" }
        return true
    }
    @Synchronized fun isDrained() = reading == null && writing == null && eventCount == 0
    private fun enqueue(event: Packet) {
        check(eventCount < events.size)
        events[(eventHead + eventCount) % events.size] = event; eventCount++
    }
}
