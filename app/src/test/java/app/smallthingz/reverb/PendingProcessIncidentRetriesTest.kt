package app.smallthingz.reverb

import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class PendingProcessIncidentRetriesTest {
    private val userRoot = File("/owned/user/no_backup")
    private val fixtureRoot = File("/owned/user/no_backup/fixture")

    private fun retry(root: File, time: Long, pid: Int = 42, started: Long = 10L) =
        PendingProcessIncidentRetry(
            root, time, "outage-$time", pid, started,
            RecordingIncident(time, pid = pid, description = "outage-$time"),
        )

    @Test
    fun sameProcessRetriesRemainSeparateAndNeverReplayIntoAnotherRoot() {
        val queue = PendingProcessIncidentRetries()
        val user = retry(userRoot, 100L)
        val fixture = retry(fixtureRoot, 200L)
        queue.enqueue(user)
        queue.enqueue(fixture)
        val applied = mutableListOf<PendingProcessIncidentRetry>()
        queue.retryOwned(fixtureRoot, applied::add)
        assertEquals(listOf(fixture), applied)
        assertEquals(listOf(user.incident), queue.incidents(userRoot))
        assertEquals(emptyList<RecordingIncident>(), queue.incidents(fixtureRoot))
        queue.retryOwned(File("/unrelated")) { error("foreign callback") }
        queue.retryOwned(userRoot, applied::add)
        assertEquals(listOf(fixture, user), applied)
        queue.retryOwned(userRoot) { error("already retired") }
    }

    @Test
    fun failedRetryPreservesItsExactEvidenceAndDoesNotBlockAnotherRoot() {
        val queue = PendingProcessIncidentRetries()
        val user = retry(userRoot, 100L)
        val fixture = retry(fixtureRoot, 200L)
        queue.enqueue(user)
        queue.enqueue(fixture)
        val failed = IOException("original history unavailable")
        assertSame(failed, assertThrows(IOException::class.java) {
            queue.retryOwned(userRoot) { assertSame(user, it); throw failed }
        })
        queue.retryOwned(fixtureRoot) { assertSame(fixture, it) }
        assertEquals(listOf(user.incident), queue.incidents(userRoot))
        queue.retryOwned(userRoot) { assertSame(user, it) }
        assertEquals(emptyList<RecordingIncident>(), queue.incidents(userRoot))
    }

    @Test
    fun dedupRetainsFirstOutageOnlyWithinExactRootAndProcessLifetime() {
        val queue = PendingProcessIncidentRetries()
        val first = retry(userRoot, 100L)
        val newProcess = retry(userRoot, 300L, started = 11L)
        val newPid = retry(userRoot, 400L, pid = 43)
        val fixture = retry(fixtureRoot, 500L)
        listOf(first, retry(userRoot, 200L), newProcess, newPid, fixture).forEach(queue::enqueue)
        val applied = mutableListOf<PendingProcessIncidentRetry>()
        queue.retryOwned(userRoot, applied::add)
        assertEquals(listOf(first, newProcess, newPid), applied)
        assertEquals(listOf(fixture.incident), queue.incidents(fixtureRoot))
    }

    @Test
    fun failureAfterEarlierSuccessRetiresOnlyCompletedOwnersAndRetainsLaterOnes() {
        val queue = PendingProcessIncidentRetries()
        val first = retry(userRoot, 100L)
        val second = retry(userRoot, 200L, pid = 43)
        val later = retry(userRoot, 300L, pid = 44)
        listOf(first, second, later).forEach(queue::enqueue)
        val seen = mutableListOf<PendingProcessIncidentRetry>()
        assertThrows(IOException::class.java) {
            queue.retryOwned(userRoot) {
                seen += it
                if (it === second) throw IOException("barrier failed")
            }
        }
        assertEquals(listOf(first, second), seen)
        assertEquals(listOf(second.incident, later.incident), queue.incidents(userRoot))
        seen.clear()
        queue.retryOwned(userRoot, seen::add)
        assertEquals(listOf(second, later), seen)
    }
}
