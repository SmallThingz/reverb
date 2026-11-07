package app.smallthingz.reverb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class CaptureCommandAdmissionRejectionTest {
    @Test fun acceptedInitialRejectionRetiresLifetimeWithoutRunningCommand() {
        val admission = CaptureCommandAdmission()
        val queue = ArrayDeque<Runnable>()
        var commands = 0
        var rejections = 0
        var idle = 0
        assertTrue(admission.submit({ true }, { queue.addLast(it); true }, { idle++ }, { rejections++ }) { commands++ })
        assertTrue(admission.isActive())
        val accepted = queue.removeFirst()
        rejectCaptureCommandWork(accepted)
        rejectCaptureCommandWork(accepted)
        accepted.run()
        assertEquals(0, commands)
        assertEquals(1, rejections)
        assertEquals(1, idle)
        assertFalse(admission.isActive())
        assertTrue(queue.isEmpty())
    }

    @Test fun acceptedCompletionRejectionRetiresLifetimeAfterCommandTail() {
        val admission = CaptureCommandAdmission()
        val queue = ArrayDeque<Runnable>()
        val events = mutableListOf<String>()
        assertTrue(admission.submit({ true }, { queue.addLast(it); true }, { events += "idle" }, { events += "rejected" }) {
            events += "command"
            queue.addLast(Runnable { events += "tail" })
        })
        val command = queue.removeFirst()
        command.run()
        rejectCaptureCommandWork(command)
        assertTrue(admission.isActive())
        assertEquals(listOf("command"), events)
        queue.removeFirst().run()
        val completion = queue.removeFirst()
        rejectCaptureCommandWork(completion)
        completion.run()
        rejectCaptureCommandWork(completion)
        assertEquals(listOf("command", "tail", "idle"), events)
        assertFalse(admission.isActive())
    }

    @Test fun rejectionCannotStealCommandWhichAlreadyStarted() {
        val admission = CaptureCommandAdmission()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val commands = AtomicInteger()
        val rejections = AtomicInteger()
        val idle = AtomicInteger()
        val queue = java.util.concurrent.LinkedBlockingQueue<Runnable>()
        assertTrue(admission.submit({ true }, { queue.add(it); true }, { idle.incrementAndGet() }, { rejections.incrementAndGet() }) {
            commands.incrementAndGet()
            started.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        })
        val command = queue.remove()
        val worker = Thread {
            try { command.run() } finally { finished.countDown() }
        }.apply { start() }
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS))
            rejectCaptureCommandWork(command)
            assertTrue(admission.isActive())
            assertEquals(0, rejections.get())
        } finally {
            release.countDown()
            assertTrue(finished.await(5, TimeUnit.SECONDS))
            worker.join(1000)
        }
        rejectCaptureCommandWork(queue.remove())
        assertEquals(1, commands.get())
        assertEquals(0, rejections.get())
        assertEquals(1, idle.get())
        assertFalse(admission.isActive())
    }

    @Test fun throwingRejectionStillSettlesTicketAndKeepsOriginalFailure() {
        val admission = CaptureCommandAdmission()
        val queue = ArrayDeque<Runnable>()
        val failure = IllegalStateException("receiver failed")
        var idle = 0
        assertTrue(admission.submit({ true }, { queue.addLast(it); true }, { idle++ }, { throw failure }) {
            fail("Rejected command executed")
        })
        val command = queue.removeFirst()
        try {
            rejectCaptureCommandWork(command)
            fail("Expected rejection callback failure")
        } catch (actual: IllegalStateException) {
            assertSame(failure, actual)
        }
        rejectCaptureCommandWork(command)
        command.run()
        assertEquals(1, idle)
        assertFalse(admission.isActive())
    }

    @Test fun synchronousRefusalTransfersNoRejectionCallbackOwnership() {
        val admission = CaptureCommandAdmission()
        var idle = 0
        var rejected = 0
        assertFalse(admission.submit({ true }, { false }, { idle++ }, { rejected++ }) {
            fail("Unaccepted command executed")
        })
        assertEquals(1, idle)
        assertEquals(0, rejected)
        assertFalse(admission.isActive())
    }

    @Test fun idleFailureDoesNotReplaceRejectionFailureOrRetainTicket() {
        val admission = CaptureCommandAdmission()
        val queue = ArrayDeque<Runnable>()
        val primary = IllegalStateException("rejection failed")
        val cleanup = IllegalArgumentException("idle failed")
        assertTrue(admission.submit({ true }, { queue.addLast(it); true }, { throw cleanup }, { throw primary }) {})
        try {
            rejectCaptureCommandWork(queue.removeFirst())
            fail("Expected callback failure")
        } catch (actual: IllegalStateException) {
            assertSame(primary, actual)
            assertEquals(1, actual.suppressed.size)
            assertSame(cleanup, actual.suppressed.single())
        }
        assertFalse(admission.isActive())
    }

    @Test fun rejectedTicketDoesNotReleaseOtherAcceptedCommands() {
        val admission = CaptureCommandAdmission()
        val queue = ArrayDeque<Runnable>()
        var idle = 0
        repeat(2) {
            assertTrue(admission.submit({ true }, { queue.addLast(it); true }, { idle++ }) {})
        }
        rejectCaptureCommandWork(queue.removeFirst())
        assertTrue(admission.isActive())
        assertEquals(0, idle)
        rejectCaptureCommandWork(queue.removeFirst())
        assertFalse(admission.isActive())
        assertEquals(1, idle)
    }
}
