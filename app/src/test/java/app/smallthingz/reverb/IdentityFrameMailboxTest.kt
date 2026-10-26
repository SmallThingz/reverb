package app.smallthingz.reverb

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class IdentityFrameMailboxTest {
    @Test fun replacementAfterProducerAdmissionCannotReceiveTheOldFrame() {
        val mailbox = IdentityFrameMailbox<Any, String>()
        val firstOwner = Any()
        val replacement = Any()
        val first = requireNotNull(mailbox.register(firstOwner))
        retireWhileProducerWaits(mailbox, first) {
            val successor = requireNotNull(mailbox.register(replacement))
            assertTrue(mailbox.publish(successor, "replacement"))
        }
        val delivered = mutableListOf<Pair<Any, String>>()
        mailbox.dispatchPending { owner, frame -> delivered += owner to frame }
        assertEquals(1, delivered.size)
        assertSame(replacement, delivered.single().first)
        assertEquals("replacement", delivered.single().second)
        assertFalse(mailbox.hasPending())
    }

    @Test fun sameCallbackReRegistrationHasANewEpoch() {
        val mailbox = IdentityFrameMailbox<Any, String>()
        val owner = Any()
        val first = requireNotNull(mailbox.register(owner))
        retireWhileProducerWaits(mailbox, first) {
            assertTrue(mailbox.unregister(owner))
            val replacement = requireNotNull(mailbox.register(owner))
            assertNotSame(first, replacement)
            assertTrue(mailbox.publish(replacement, "reopened"))
        }
        val delivered = mutableListOf<String>()
        mailbox.dispatchPending { actual, frame ->
            assertSame(owner, actual)
            delivered += frame
        }
        assertEquals(listOf("reopened"), delivered)
        assertFalse(mailbox.hasPending())
    }

    @Test fun teardownRetiresAnAdmittedProducerAndAllOwners() {
        val mailbox = IdentityFrameMailbox<Any, String>()
        val first = requireNotNull(mailbox.register(Any()))
        retireWhileProducerWaits(mailbox, first) { mailbox.clearAll() }
        assertNull(mailbox.current())
        assertNull(mailbox.currentRegistration())
        assertEquals(0, mailbox.size())
        assertFalse(mailbox.hasPending())
        mailbox.dispatchPending { _, _ -> fail("Retired frame escaped teardown") }
    }

    @Test fun fallbackGetsAFreshSlotAndRetiredOwnersCannotClearTheNewOwner() {
        val mailbox = IdentityFrameMailbox<Any, String>()
        val firstOwner = Any()
        val secondOwner = Any()
        val first = requireNotNull(mailbox.register(firstOwner))
        assertTrue(mailbox.publish(first, "old-first"))
        val second = requireNotNull(mailbox.register(secondOwner))
        assertTrue(mailbox.publish(second, "old-second"))
        assertTrue(mailbox.unregister(secondOwner))
        val fallback = requireNotNull(mailbox.currentRegistration())
        assertSame(firstOwner, fallback.owner)
        assertNotSame(first, fallback)
        assertFalse(mailbox.hasPending())
        assertFalse(mailbox.publish(first, "stale-first"))
        assertFalse(mailbox.publish(second, "stale-second"))
        assertTrue(mailbox.publish(fallback, "current-first"))
        assertFalse(mailbox.unregister(secondOwner))
        val delivered = mutableListOf<String>()
        mailbox.dispatchPending { owner, frame -> assertSame(firstOwner, owner); delivered += frame }
        assertEquals(listOf("current-first"), delivered)
        assertEquals(1, mailbox.size())
    }

    @Test fun unchangedRegistrationPreservesPendingAndInactiveRemovalPreservesCurrent() {
        val mailbox = IdentityFrameMailbox<Any, String>()
        val inactive = Any()
        val current = Any()
        mailbox.register(inactive)
        val registration = requireNotNull(mailbox.register(current))
        assertTrue(mailbox.publish(registration, "pending"))
        assertNull(mailbox.register(current))
        assertSame(registration, mailbox.currentRegistration())
        assertEquals(2, mailbox.size())
        assertFalse(mailbox.unregister(inactive))
        assertSame(registration, mailbox.currentRegistration())
        assertTrue(mailbox.hasPending())
        assertEquals(1, mailbox.size())
        mailbox.dispatchPending { owner, frame -> assertSame(current, owner); assertEquals("pending", frame) }
    }

    @Test fun newestFrameCoalescesAndPublicationDuringDeliveryRemainsPending() {
        val mailbox = IdentityFrameMailbox<Any, String>()
        val owner = Any()
        val registration = requireNotNull(mailbox.register(owner))
        assertTrue(mailbox.publish(registration, "superseded"))
        assertTrue(mailbox.publish(registration, "latest"))
        val delivered = mutableListOf<String>()
        mailbox.dispatchPending { actual, frame ->
            assertSame(owner, actual)
            assertFalse(Thread.holdsLock(mailbox))
            delivered += frame
            assertTrue(mailbox.publish(registration, "next"))
        }
        assertTrue(mailbox.hasPending())
        mailbox.dispatchPending { _, frame -> delivered += frame }
        mailbox.dispatchPending { _, _ -> fail("Frame delivered twice") }
        assertEquals(listOf("latest", "next"), delivered)
        assertFalse(mailbox.hasPending())
    }

    @Test fun callbackCanChangeOwnershipWithoutHoldingTheMailboxMonitor() {
        val mailbox = IdentityFrameMailbox<Any, String>()
        val first = requireNotNull(mailbox.register(Any()))
        assertTrue(mailbox.publish(first, "first"))
        val nextOwner = Any()
        mailbox.dispatchPending { _, _ ->
            assertFalse(Thread.holdsLock(mailbox))
            mailbox.clearAll()
            val next = requireNotNull(mailbox.register(nextOwner))
            assertTrue(mailbox.publish(next, "next"))
        }
        assertFalse(mailbox.isCurrent(first))
        assertTrue(mailbox.hasPending())
        mailbox.dispatchPending { owner, frame -> assertSame(nextOwner, owner); assertEquals("next", frame) }
    }

    private fun retireWhileProducerWaits(
        mailbox: IdentityFrameMailbox<Any, String>,
        registration: IdentityFrameMailbox.Registration<Any, String>,
        transition: () -> Unit,
    ) {
        val admitted = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val producer = Thread {
            try {
                assertTrue(mailbox.isCurrent(registration))
                admitted.countDown()
                assertTrue(resume.await(5, TimeUnit.SECONDS))
                assertFalse(mailbox.publish(registration, "retired"))
            } catch (error: Throwable) {
                failure.set(error)
                admitted.countDown()
            }
        }.apply { isDaemon = true; start() }
        try {
            assertTrue(admitted.await(5, TimeUnit.SECONDS))
            assertNull(failure.get())
            transition()
        } finally {
            resume.countDown()
            producer.join(5_000L)
        }
        assertFalse("Producer did not finish", producer.isAlive)
        failure.get()?.let { throw AssertionError("Producer failed", it) }
    }
}
