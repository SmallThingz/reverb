package app.smallthingz.reverb

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
    enum class Terminal { EXHAUSTED, STOP, TEARDOWN, WRITER_FAILURE, INPUT_FAILURE }
    private enum class State { FREE, READING, QUEUED, WRITING }
    private class Slot { var state = State.FREE; var lease: Lease? = null }
    class Lease internal constructor(
        internal val owner: CapturePcmRing, internal val slot: Int, internal val start: Long,
        val sequence: Long, val identity: CaptureReadIdentity, val requestedBytes: Int,
    )
    class Packet internal constructor(
        val lease: Lease, val byteCount: Int, val endedAtNanos: Long,
        val captureStartedAtNanos: Long?, val endedAtMillis: Long,
    )
    class Fence internal constructor(
        internal val owner: CapturePcmRing, val afterSequence: Long, val pauseAdmission: Boolean,
    ) { internal var completed = false }
    data class Status(val terminal: Terminal?, val occupiedBytes: Int, val highWaterBytes: Int,
        val occupiedPackets: Int, val highWaterPackets: Int, val reading: Boolean, val fence: Boolean)

    private val bytes = ByteArray(budget.ringBytes)
    private val slots = Array(budget.packetCount) { Slot() }
    private val events = arrayOfNulls<Packet>(budget.packetCount)
    private var eventHead = 0
    private var eventCount = 0
    private var tail = 0L
    private var byteHead = 0L
    private var sequence = 0L
    private var reading: Lease? = null
    private var writing: Packet? = null
    private var fenceCount = 0
    private var pausedFences = 0
    private var terminal: Terminal? = null
    private var highWaterBytes = 0
    private var highWaterPackets = 0

    @Synchronized fun reserve(identity: CaptureReadIdentity, requestedBytes: Int): Lease? {
        if (terminal != null || pausedFences != 0) return null
        check(reading == null)
        require(identity.frameBytes == budget.frameBytes && requestedBytes in 1..budget.maxReadBytes &&
            requestedBytes % budget.frameBytes == 0)
        val index = slots.indexOfFirst { it.state == State.FREE }
        if (index < 0 || tail - byteHead + requestedBytes > bytes.size) {
            terminal = Terminal.EXHAUSTED
            return null
        }
        if (slots.all { it.state == State.FREE }) { tail = 0; byteHead = 0 }
        check(sequence < Long.MAX_VALUE && tail <= Long.MAX_VALUE - requestedBytes)
        val lease = Lease(this, index, tail, ++sequence, identity, requestedBytes)
        tail += requestedBytes
        slots[index].apply { state = State.READING; this.lease = lease }
        reading = lease
        highWaterBytes = maxOf(highWaterBytes, (tail - byteHead).toInt())
        highWaterPackets = maxOf(highWaterPackets, slots.count { it.state != State.FREE })
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
            slots[lease.slot].state = State.QUEUED
            enqueue(Packet(lease, count, endedAtNanos, captureStartedAtNanos, endedAtMillis))
        } else free(lease)
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
        return slots.none { it.lease?.sequence?.let { sequence -> sequence <= token.afterSequence } == true }
    }
    @Synchronized fun poll(): Packet? {
        check(writing == null)
        if (eventCount == 0) return null
        val packet = checkNotNull(events[eventHead])
        events[eventHead] = null; eventHead = (eventHead + 1) % events.size; eventCount--
        slots[packet.lease.slot].state = State.WRITING
        writing = packet
        return packet
    }
    @Synchronized fun copyPacket(packet: Packet, target: ByteArray) {
        require(writing === packet && target.size >= packet.byteCount)
        val offset = (packet.lease.start % bytes.size).toInt()
        val first = minOf(packet.byteCount, bytes.size - offset)
        bytes.copyInto(target, 0, offset, offset + first)
        if (first < packet.byteCount) bytes.copyInto(target, first, 0, packet.byteCount - first)
    }
    @Synchronized fun completePacket(packet: Packet) {
        require(writing === packet)
        writing = null; free(packet.lease)
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
        if (token.pauseAdmission) pausedFences--
        check(fenceCount >= 0 && pausedFences >= 0)
    }
    @Synchronized fun stop(preserveInFlight: Boolean) {
        if (terminal == null || (terminal == Terminal.TEARDOWN && !preserveInFlight)) {
            terminal = if (preserveInFlight) Terminal.TEARDOWN else Terminal.STOP
        }
    }
    @Synchronized fun inputFailed() { if (terminal != Terminal.WRITER_FAILURE) terminal = Terminal.INPUT_FAILURE }
    @Synchronized fun writerFailed() {
        terminal = Terminal.WRITER_FAILURE
        repeat(eventCount) { offset ->
            val event = events[(eventHead + offset) % events.size]
            if (event != null) free(event.lease)
        }
        events.fill(null); eventHead = 0; eventCount = 0
    }
    @Synchronized fun status() = Status(terminal, (tail - byteHead).toInt(), highWaterBytes,
        slots.count { it.state != State.FREE }, highWaterPackets, reading != null, fenceCount != 0)
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
    private fun free(lease: Lease) {
        val slot = slots[lease.slot]
        check(slot.lease === lease && slot.state != State.FREE)
        slot.lease = null; slot.state = State.FREE
        var oldest = tail
        for (candidate in slots) candidate.lease?.let { oldest = minOf(oldest, it.start) }
        byteHead = oldest
    }
}
