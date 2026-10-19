package app.smallthingz.reverb

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SettingsMoveAvailabilityOwnerTest {
    @Test
    fun hiddenRequestWaitingForRepositoryMutexNeverStartsStorageWork() = runBlocking {
        val mutex = Mutex(locked = true)
        val waiting = CompletableDeferred<Unit>()
        val terminal = CompletableDeferred<Unit>()
        var storageRead = false
        val published = mutableListOf<Boolean>()
        val owner = SettingsMoveAvailabilityOwner(
            scope = this,
            scan = { _, mayStart ->
                waiting.complete(Unit)
                try {
                    withMoveAvailabilityAdmission(mutex, mayStart) {
                        storageRead = true
                        true
                    }
                } finally {
                    terminal.complete(Unit)
                }
            },
            publish = published::add,
        )

        owner.request("destination-a", 0)
        waiting.await()
        owner.hide()
        mutex.unlock()
        terminal.await()
        yield()

        assertFalse(storageRead)
        assertFalse(published.any { it })
        owner.close()
    }

    @Test
    fun destinationChangesKeepAdmittedTargetAndOnlyScanLatestPendingTarget() = runBlocking {
        val admitted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val latestPublished = CompletableDeferred<Unit>()
        val scans = mutableListOf<String>()
        val published = mutableListOf<Boolean>()
        val owner = SettingsMoveAvailabilityOwner(
            scope = this,
            scan = { target, mayStart ->
                if (!mayStart()) false else {
                    scans += target
                    if (target == "destination-a") {
                        admitted.complete(Unit)
                        releaseFirst.await()
                    }
                    true
                }
            },
            publish = { result ->
                published += result
                if (result) latestPublished.complete(Unit)
            },
        )

        owner.request("destination-a", 0)
        admitted.await()
        owner.request("destination-b", 0)
        owner.request("destination-c", 0)
        owner.request("destination-c", 0)
        releaseFirst.complete(Unit)
        latestPublished.await()

        assertEquals(listOf("destination-a", "destination-c"), scans)
        assertEquals(listOf(false, false, false, true), published)
        owner.request("destination-c", 1)
        assertEquals(listOf("destination-a", "destination-c", "destination-c"), scans)
        assertEquals(listOf(false, false, false, true, false, true), published)
        owner.close()
    }

    @Test
    fun closeFinishesAdmittedRecoveryButDropsPendingWorkAndPublication() = runBlocking {
        val admitted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val terminal = CompletableDeferred<Unit>()
        val scans = mutableListOf<String>()
        val published = mutableListOf<Boolean>()
        val owner = SettingsMoveAvailabilityOwner(
            scope = this,
            scan = { target, mayStart ->
                if (!mayStart()) false else {
                    scans += target
                    admitted.complete(Unit)
                    release.await()
                    terminal.complete(Unit)
                    true
                }
            },
            publish = published::add,
        )

        owner.request("destination-a", 0)
        admitted.await()
        owner.request("destination-b", 0)
        owner.close()
        owner.request("destination-c", 0)
        release.complete(Unit)
        terminal.await()
        yield()

        assertEquals(listOf("destination-a"), scans)
        assertEquals(listOf(false, false), published)
    }
}
