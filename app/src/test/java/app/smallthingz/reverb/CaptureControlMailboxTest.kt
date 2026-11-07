package app.smallthingz.reverb

import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class CaptureControlMailboxTest {
    private class Receiver {
        var completed = 0
        var rejected = 0
    }

    private fun offer(
        mailbox: CaptureControlMailbox<Int>, id: Int, receiver: Receiver,
        kind: CaptureControlMailbox.Kind = CaptureControlMailbox.Kind.NORMAL,
    ) = mailbox.offer(kind, boundary = { id }, work = { receiver.completed++ }, rejected = { receiver.rejected++ })

    private fun drainRejections(mailbox: CaptureControlMailbox<Int>) {
        while (true) {
            val ticket = mailbox.claimRejection() ?: return
            try { ticket.rejected() } finally { mailbox.finish(ticket) }
        }
    }

    @Test fun dispatchFailureRejectsClaimedAndPendingCommandsExactlyOnce() {
        val mailbox = CaptureControlMailbox<Int>()
        val receivers = List(3) { Receiver() }
        receivers.forEachIndexed { id, receiver -> assertEquals(CaptureControlMailbox.Admission.ACCEPTED, offer(mailbox, id, receiver)) }
        val active = requireNotNull(mailbox.claim())
        assertSame(active, mailbox.claim())
        assertTrue(mailbox.failDispatch())
        assertFalse(mailbox.failDispatch())
        assertFalse(mailbox.begin(active))
        assertNull(mailbox.claim())
        drainRejections(mailbox)
        drainRejections(mailbox)
        assertEquals(listOf(1, 1, 1), receivers.map { it.rejected })
        assertEquals(listOf(0, 0, 0), receivers.map { it.completed })
        assertEquals(0, mailbox.occupied())
        assertFalse(mailbox.hasPending())
    }

    @Test fun runningCallbackKeepsOwnershipWhenLaterDispatchFails() {
        val mailbox = CaptureControlMailbox<Int>()
        val running = Receiver()
        val pending = Receiver()
        offer(mailbox, 1, running); offer(mailbox, 2, pending)
        val ticket = requireNotNull(mailbox.claim())
        assertTrue(mailbox.begin(ticket))
        assertTrue(mailbox.failDispatch())
        drainRejections(mailbox)
        assertEquals(1, mailbox.occupied())
        assertEquals(0, running.rejected)
        assertEquals(1, pending.rejected)
        try { ticket.work() } finally { mailbox.finish(ticket) }
        assertEquals(1, running.completed)
        assertNull(mailbox.claimRejection())
        assertEquals(0, mailbox.occupied())
    }

    @Test fun beginAndDispatchFailureRaceHasOneTerminalOwner() {
        val executor = Executors.newFixedThreadPool(2)
        try {
            repeat(32) {
                val mailbox = CaptureControlMailbox<Int>()
                val completed = AtomicInteger()
                val rejected = AtomicInteger()
                mailbox.offer(CaptureControlMailbox.Kind.NORMAL, { 1 }, { completed.incrementAndGet(); Unit }, { rejected.incrementAndGet(); Unit })
                val ticket = requireNotNull(mailbox.claim())
                val start = CountDownLatch(1)
                val writer = executor.submit<Boolean> {
                    check(start.await(5, TimeUnit.SECONDS))
                    val owns = mailbox.begin(ticket)
                    if (owns) try { ticket.work() } finally { mailbox.finish(ticket) }
                    owns
                }
                val failure = executor.submit<Boolean> {
                    check(start.await(5, TimeUnit.SECONDS))
                    mailbox.failDispatch()
                }
                start.countDown()
                val writerWon = writer.get(5, TimeUnit.SECONDS)
                assertTrue(failure.get(5, TimeUnit.SECONDS))
                drainRejections(mailbox)
                assertEquals(if (writerWon) 1 else 0, completed.get())
                assertEquals(if (writerWon) 0 else 1, rejected.get())
                assertEquals(0, mailbox.occupied())
            }
        } finally { executor.shutdownNow() }
    }

    @Test fun ordinaryCloseRejectsNewWorkWithoutRevokingAcceptedWork() {
        val mailbox = CaptureControlMailbox<Int>()
        val accepted = Receiver()
        val refused = Receiver()
        offer(mailbox, 1, accepted)
        mailbox.closeAdmission()
        assertEquals(CaptureControlMailbox.Admission.CLOSED, offer(mailbox, 2, refused))
        assertFalse(mailbox.hasFailed())
        assertEquals(1, mailbox.occupied())
        val ticket = requireNotNull(mailbox.claim())
        assertTrue(mailbox.begin(ticket))
        try { ticket.work() } finally { mailbox.finish(ticket) }
        assertEquals(1, accepted.completed)
        assertEquals(0, accepted.rejected)
        assertEquals(0, refused.completed + refused.rejected)
    }

    @Test fun closeKeepsTerminalCapacityButDispatchFailureRejectsEvenTerminalAdmission() {
        val mailbox = CaptureControlMailbox<Int>()
        mailbox.closeAdmission()
        assertEquals(CaptureControlMailbox.Admission.CLOSED, offer(mailbox, 1, Receiver(), CaptureControlMailbox.Kind.EMERGENCY))
        val terminal = Receiver()
        assertEquals(CaptureControlMailbox.Admission.ACCEPTED, offer(mailbox, 2, terminal, CaptureControlMailbox.Kind.TERMINAL))
        assertTrue(mailbox.failDispatch())
        assertEquals(CaptureControlMailbox.Admission.DISPATCH_FAILED, offer(mailbox, 3, Receiver(), CaptureControlMailbox.Kind.TERMINAL))
        drainRejections(mailbox)
        assertEquals(1, terminal.rejected)
    }

    @Test fun fullNormalCapacityIncludesClaimedAndRunningOwnerAndReservesTwoControlSlots() {
        val mailbox = CaptureControlMailbox<Int>()
        repeat(64) { assertEquals(CaptureControlMailbox.Admission.ACCEPTED, offer(mailbox, it, Receiver())) }
        val active = requireNotNull(mailbox.claim())
        assertEquals(CaptureControlMailbox.Admission.FULL, offer(mailbox, 64, Receiver()))
        assertTrue(mailbox.begin(active))
        assertEquals(CaptureControlMailbox.Admission.FULL, offer(mailbox, 64, Receiver()))
        assertEquals(CaptureControlMailbox.Admission.ACCEPTED, offer(mailbox, 64, Receiver(), CaptureControlMailbox.Kind.EMERGENCY))
        assertEquals(CaptureControlMailbox.Admission.FULL, offer(mailbox, 65, Receiver(), CaptureControlMailbox.Kind.EMERGENCY))
        assertEquals(CaptureControlMailbox.Admission.ACCEPTED, offer(mailbox, 65, Receiver(), CaptureControlMailbox.Kind.TERMINAL))
        assertEquals(66, mailbox.occupied())
        assertEquals(CaptureControlMailbox.Admission.CLOSED, offer(mailbox, 66, Receiver(), CaptureControlMailbox.Kind.TERMINAL))
        mailbox.finish(active)
        mailbox.failDispatch()
        drainRejections(mailbox)
        assertEquals(0, mailbox.occupied())
    }

    @Test fun closeAllowsOneTerminalButDuplicateBeforeClaimOwnsNothing() {
        val mailbox = CaptureControlMailbox<Int>()
        var boundaryCalls = 0
        var duplicateCallbacks = 0
        val terminal = Receiver()
        mailbox.closeAdmission()
        assertEquals(CaptureControlMailbox.Admission.ACCEPTED, offer(mailbox, 1, terminal, CaptureControlMailbox.Kind.TERMINAL))
        assertEquals(CaptureControlMailbox.Admission.CLOSED, mailbox.offer(CaptureControlMailbox.Kind.TERMINAL,
            { boundaryCalls++; 2 }, { duplicateCallbacks++ }, { duplicateCallbacks++ }))
        assertEquals(0, boundaryCalls)
        assertEquals(0, duplicateCallbacks)
        assertEquals(1, mailbox.occupied())
        val ticket = requireNotNull(mailbox.claim())
        assertTrue(mailbox.begin(ticket))
        try { ticket.work() } finally { mailbox.finish(ticket) }
        assertEquals(1, terminal.completed)
        assertEquals(0, terminal.rejected)
    }

    @Test fun runningTerminalCannotReentrantlyAcceptAnotherTerminal() {
        val mailbox = CaptureControlMailbox<Int>()
        var boundaryCalls = 0
        var duplicateCallbacks = 0
        var completed = 0
        assertEquals(CaptureControlMailbox.Admission.ACCEPTED, mailbox.offer(CaptureControlMailbox.Kind.TERMINAL,
            { 1 }, {
                assertEquals(CaptureControlMailbox.Admission.CLOSED, mailbox.offer(CaptureControlMailbox.Kind.TERMINAL,
                    { boundaryCalls++; 2 }, { duplicateCallbacks++ }, { duplicateCallbacks++ }))
                completed++
            }, { fail("First terminal unexpectedly rejected") }))
        val ticket = requireNotNull(mailbox.claim())
        assertTrue(mailbox.begin(ticket))
        try { ticket.work() } finally { mailbox.finish(ticket) }
        assertEquals(1, completed)
        assertEquals(0, boundaryCalls)
        assertEquals(0, duplicateCallbacks)
        assertEquals(0, mailbox.occupied())
    }

    @Test fun settledTerminalDoesNotReopenAdmissionWhenItsSlotBecomesFree() {
        val mailbox = CaptureControlMailbox<Int>()
        val terminal = Receiver()
        offer(mailbox, 1, terminal, CaptureControlMailbox.Kind.TERMINAL)
        val ticket = requireNotNull(mailbox.claim())
        assertTrue(mailbox.begin(ticket))
        try { ticket.work() } finally { mailbox.finish(ticket) }
        var boundaryCalls = 0
        var callbacks = 0
        for (kind in CaptureControlMailbox.Kind.values()) {
            assertEquals(CaptureControlMailbox.Admission.CLOSED, mailbox.offer(kind,
                { boundaryCalls++; 2 }, { callbacks++ }, { callbacks++ }))
        }
        assertEquals(0, boundaryCalls)
        assertEquals(0, callbacks)
        assertEquals(0, mailbox.occupied())
        assertNull(mailbox.claim())
        assertNull(mailbox.claimRejection())
        assertFalse(mailbox.hasFailed())
    }

    @Test fun refusedCommandDoesNotReserveBoundaryOrOwnCallback() {
        val mailbox = CaptureControlMailbox<Int>()
        repeat(64) { offer(mailbox, it, Receiver()) }
        var boundaryCalls = 0
        var callbacks = 0
        assertEquals(CaptureControlMailbox.Admission.FULL, mailbox.offer(CaptureControlMailbox.Kind.NORMAL,
            boundary = { boundaryCalls++; 65 }, work = { callbacks++ }, rejected = { callbacks++ }))
        mailbox.closeAdmission()
        assertEquals(CaptureControlMailbox.Admission.CLOSED, mailbox.offer(CaptureControlMailbox.Kind.NORMAL,
            boundary = { boundaryCalls++; 66 }, work = { callbacks++ }, rejected = { callbacks++ }))
        mailbox.failDispatch()
        assertEquals(CaptureControlMailbox.Admission.DISPATCH_FAILED, mailbox.offer(CaptureControlMailbox.Kind.TERMINAL,
            boundary = { boundaryCalls++; 67 }, work = { callbacks++ }, rejected = { callbacks++ }))
        assertEquals(0, boundaryCalls)
        assertEquals(0, callbacks)
        drainRejections(mailbox)
    }

    @Test fun boundaryFactoryFailureDoesNotRetainAnUnacceptedCommand() {
        val mailbox = CaptureControlMailbox<Int>()
        val failure = IllegalStateException("boundary reservation failed")
        try {
            mailbox.offer(CaptureControlMailbox.Kind.NORMAL, { throw failure }, { fail() }, { fail() })
            fail("Expected reservation failure")
        } catch (actual: IllegalStateException) { assertSame(failure, actual) }
        assertEquals(0, mailbox.occupied())
        assertFalse(mailbox.hasPending())
        assertFalse(mailbox.hasFailed())
    }

    @Test fun acceptedPauseBoundaryExistsBeforeWriterClaimsCommand() {
        val ring = CapturePcmRing(CapturePcmBudget(2, 8, 32, 8))
        val identity = CaptureReadIdentity(1, 2, 3, 0, 44_100, 1, 2, 2, 0)
        val native = requireNotNull(ring.reserve(identity, 8))
        val mailbox = CaptureControlMailbox<CapturePcmRing.Fence>()
        assertEquals(CaptureControlMailbox.Admission.ACCEPTED,
            mailbox.offer(CaptureControlMailbox.Kind.NORMAL, { ring.reserveFence(true) }, {}, {}))
        assertNull(ring.reserve(identity, 8))
        assertTrue(ring.complete(native, ByteBuffer.allocate(8), 8, 4, null, 5))
        assertNull(ring.reserve(identity, 8))
        val control = requireNotNull(mailbox.claim())
        assertEquals(native.sequence, control.boundary.afterSequence)
        assertFalse(ring.prefixDrained(control.boundary))
        val packet = requireNotNull(ring.poll())
        ring.completePacket(packet)
        assertTrue(ring.prefixDrained(control.boundary))
        assertTrue(mailbox.begin(control))
        try { control.work() } finally {
            ring.completeFence(control.boundary)
            mailbox.finish(control)
        }
        assertNotNull(ring.reserve(identity, 8))
    }

    @Test fun rejectionKeepsAcceptanceOrderWhenTerminalCommandReusesAnEarlierSlot() {
        val mailbox = CaptureControlMailbox<Int>()
        val outcomes = mutableListOf<String>()
        assertEquals(CaptureControlMailbox.Admission.ACCEPTED, mailbox.offer(CaptureControlMailbox.Kind.NORMAL,
            { 1 }, { outcomes.add("A completed"); Unit }, { outcomes.add("A rejected"); Unit }))
        assertEquals(CaptureControlMailbox.Admission.ACCEPTED, mailbox.offer(CaptureControlMailbox.Kind.NORMAL,
            { 2 }, { outcomes.add("B completed"); Unit }, { outcomes.add("B rejected"); Unit }))
        val first = requireNotNull(mailbox.claim())
        assertEquals(1, first.boundary)
        assertTrue(mailbox.begin(first))
        try { first.work() } finally { mailbox.finish(first) }
        assertEquals(CaptureControlMailbox.Admission.ACCEPTED, mailbox.offer(CaptureControlMailbox.Kind.TERMINAL,
            { 3 }, { outcomes.add("C completed"); Unit }, { outcomes.add("C rejected"); Unit }))
        assertTrue(mailbox.failDispatch())
        drainRejections(mailbox)
        drainRejections(mailbox)
        assertEquals(listOf("A completed", "B rejected", "C rejected"), outcomes)
        assertEquals(0, mailbox.occupied())
        assertNull(mailbox.claimRejection())
    }

    @Test fun boundaryRetirementVisitsOnlyWaitingAndClaimedOwners() {
        class Boundary(var visits: Int = 0)
        val mailbox = CaptureControlMailbox<Boundary>()
        val boundaries = List(3) { Boundary() }
        var callbacks = 0
        boundaries.forEach { boundary ->
            assertEquals(CaptureControlMailbox.Admission.ACCEPTED, mailbox.offer(CaptureControlMailbox.Kind.NORMAL,
                { boundary }, { callbacks++ }, { callbacks++ }))
        }
        val active = requireNotNull(mailbox.claim())
        mailbox.retireWaitingBoundaries { it.visits++ }
        assertEquals(listOf(1, 1, 1), boundaries.map { it.visits })
        assertTrue(mailbox.begin(active))
        mailbox.retireWaitingBoundaries { it.visits++ }
        assertEquals(listOf(1, 2, 2), boundaries.map { it.visits })
        assertTrue(mailbox.failDispatch())
        val rejecting = requireNotNull(mailbox.claimRejection())
        mailbox.retireWaitingBoundaries { it.visits++ }
        assertEquals(listOf(1, 2, 2), boundaries.map { it.visits })
        assertEquals(0, callbacks)
        try { active.work() } finally { mailbox.finish(active) }
        try { rejecting.rejected() } finally { mailbox.finish(rejecting) }
        val last = requireNotNull(mailbox.claimRejection())
        try { last.rejected() } finally { mailbox.finish(last) }
        assertEquals(3, callbacks)
        assertEquals(0, mailbox.occupied())
    }

    @Test fun fallbackTerminalRequiresDispatchFailureAndCanBeClaimedOnlyOnce() {
        val mailbox = CaptureControlMailbox<Int>()
        assertFalse(mailbox.claimTerminalAfterDispatchFailure())
        mailbox.closeAdmission()
        assertFalse(mailbox.claimTerminalAfterDispatchFailure())
        assertTrue(mailbox.failDispatch())
        assertTrue(mailbox.claimTerminalAfterDispatchFailure())
        assertFalse(mailbox.claimTerminalAfterDispatchFailure())
        assertEquals(0, mailbox.occupied())
    }

    @Test fun acceptedTerminalRetainsExclusiveCloseOwnershipThroughRejectionSettlement() {
        val mailbox = CaptureControlMailbox<Int>()
        val terminal = Receiver()
        assertEquals(CaptureControlMailbox.Admission.ACCEPTED,
            offer(mailbox, 1, terminal, CaptureControlMailbox.Kind.TERMINAL))
        assertFalse(mailbox.claimTerminalAfterDispatchFailure())
        assertTrue(mailbox.failDispatch())
        assertFalse(mailbox.claimTerminalAfterDispatchFailure())
        val rejecting = requireNotNull(mailbox.claimRejection())
        assertFalse(mailbox.claimTerminalAfterDispatchFailure())
        try { rejecting.rejected() } finally { mailbox.finish(rejecting) }
        assertEquals(1, terminal.rejected)
        assertEquals(0, terminal.completed)
        assertFalse(mailbox.claimTerminalAfterDispatchFailure())
        assertEquals(0, mailbox.occupied())
    }

    @Test fun rejectionClaimCannotBeDeliveredTwiceBeforeItsCallbackFinishes() {
        val mailbox = CaptureControlMailbox<Int>()
        val receiver = Receiver()
        offer(mailbox, 1, receiver)
        mailbox.failDispatch()
        val ticket = requireNotNull(mailbox.claimRejection())
        assertNull(mailbox.claimRejection())
        assertEquals(1, mailbox.occupied())
        try { ticket.rejected() } finally { mailbox.finish(ticket) }
        assertEquals(1, receiver.rejected)
        assertNull(mailbox.claimRejection())
        assertFalse(mailbox.begin(ticket))
        assertEquals(0, mailbox.occupied())
    }
}
