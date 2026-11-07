package app.smallthingz.reverb

/** Bounded control ownership, including the command currently waiting for its PCM prefix. */
internal class CaptureControlMailbox<B> {
    enum class Admission { ACCEPTED, CLOSED, FULL, DISPATCH_FAILED }
    enum class Kind { NORMAL, EMERGENCY, TERMINAL }
    internal enum class Phase { WAITING, CLAIMED, RUNNING, REJECT_READY, REJECT_RUNNING, FINISHED }
    class Ticket<B> internal constructor(
        val boundary: B, val kind: Kind, val work: () -> Unit, val rejected: () -> Unit,
        internal val sequence: Long,
    ) {
        internal var phase = Phase.WAITING
    }
    private val tickets = arrayOfNulls<Ticket<B>>(66)
    private val pending = ArrayDeque<Ticket<B>>(66)
    private var active: Ticket<B>? = null
    private var closed = false
    private var terminalAccepted = false
    private var failed = false
    private var sequence = 0L

    @Synchronized fun closeAdmission() { closed = true }

    @Synchronized fun claimTerminalAfterDispatchFailure(): Boolean {
        if (!failed || terminalAccepted) return false
        terminalAccepted = true
        return true
    }

    /** Boundary reservation and command acceptance share this lock; the factory must not perform I/O. */
    @Synchronized fun offer(kind: Kind, boundary: () -> B, work: () -> Unit, rejected: () -> Unit): Admission {
        if (failed) return Admission.DISPATCH_FAILED
        if (terminalAccepted) return Admission.CLOSED
        if (closed && kind != Kind.TERMINAL) return Admission.CLOSED
        val occupied = tickets.count { it != null }
        val capacity = when (kind) { Kind.NORMAL -> 64; Kind.EMERGENCY -> 65; Kind.TERMINAL -> 66 }
        if (occupied >= capacity) return Admission.FULL
        check(sequence < Long.MAX_VALUE)
        val ticket = Ticket(boundary(), kind, work, rejected, ++sequence)
        tickets[tickets.indexOfFirst { it == null }] = ticket
        pending.addLast(ticket)
        if (kind == Kind.TERMINAL) { closed = true; terminalAccepted = true }
        return Admission.ACCEPTED
    }

    @Synchronized fun claim(): Ticket<B>? {
        active?.let { return it }
        if (failed || pending.isEmpty()) return null
        return pending.removeFirst().also { it.phase = Phase.CLAIMED; active = it }
    }

    /** Dispatch failure may revoke CLAIMED work, but never a callback which already began. */
    @Synchronized fun begin(ticket: Ticket<B>): Boolean {
        if (ticket.phase != Phase.CLAIMED || failed) return false
        check(active === ticket)
        ticket.phase = Phase.RUNNING
        return true
    }

    @Synchronized fun finish(ticket: Ticket<B>) {
        check(ticket.phase == Phase.RUNNING || ticket.phase == Phase.REJECT_RUNNING)
        if (active === ticket) active = null
        val index = tickets.indexOfFirst { it === ticket }
        check(index >= 0)
        tickets[index] = null
        ticket.phase = Phase.FINISHED
    }

    @Synchronized fun failDispatch(): Boolean {
        if (failed) return false
        failed = true
        closed = true
        pending.clear()
        tickets.forEach { ticket ->
            if (ticket?.phase == Phase.WAITING || ticket?.phase == Phase.CLAIMED) {
                ticket.phase = Phase.REJECT_READY
                if (active === ticket) active = null
            }
        }
        return true
    }

    @Synchronized fun claimRejection(): Ticket<B>? =
        tickets.filterNotNull().filter { it.phase == Phase.REJECT_READY }.minByOrNull { it.sequence }
            ?.also { it.phase = Phase.REJECT_RUNNING }

    @Synchronized fun hasPending(): Boolean = pending.isNotEmpty() || active != null
    /** Internal memory bookkeeping only; never execute command or receiver callbacks here. */
    @Synchronized fun retireWaitingBoundaries(retire: (B) -> Unit) {
        tickets.forEach { ticket ->
            if (ticket?.phase == Phase.WAITING || ticket?.phase == Phase.CLAIMED) retire(ticket.boundary)
        }
    }
    @Synchronized fun hasFailed(): Boolean = failed
    @Synchronized fun occupied(): Int = tickets.count { it != null }
}
