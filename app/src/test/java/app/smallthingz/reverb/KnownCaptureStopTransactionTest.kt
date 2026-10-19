package app.smallthingz.reverb

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KnownCaptureStopTransactionTest {
    @Test
    fun startupStopBeforeApplicationRecovery_preservesPriorProcessEvidence() {
        val old = Marker(pid = 11, processStart = 100L, arm = 200L)
        val store = Store(old)

        assertEquals(KnownCaptureStopResult.DURABLE, store.stop())
        assertNull(store.marker)
        assertEquals(setOf(old), store.pending)
        assertEquals(setOf(old), store.incidents)
        assertEquals(1, store.recoveries)
        assertEquals(0, store.disarms)
        // Delayed Application recovery and a repeated Stop cannot recreate/disarm the old session.
        store.recoverPriorSession()
        assertEquals(KnownCaptureStopResult.DURABLE, store.stop())
        assertEquals(1, store.incidents.size)
        assertEquals(1, store.pending.size)
        assertEquals(0, store.disarms)
    }

    @Test
    fun failedPriorRecovery_preservesEveryPublishedBoundaryAndRetryDoesNotDuplicate() {
        for (boundary in 1..3) {
            val old = Marker(pid = 11, processStart = 100L, arm = 200L)
            val store = Store(old).apply { failedRecoveryBoundary = boundary }

            assertEquals(KnownCaptureStopResult.FAILED_UNCERTAIN, store.stop())
            assertEquals(old, store.marker)
            assertEquals(if (boundary >= 2) setOf(old) else emptySet<Marker>(), store.pending)
            assertEquals(if (boundary >= 3) setOf(old) else emptySet<Marker>(), store.incidents)
            assertEquals(0, store.disarms)
            assertEquals(0, store.removals)

            store.failedRecoveryBoundary = 0
            assertEquals(KnownCaptureStopResult.DURABLE, store.stop())
            assertNull(store.marker)
            assertEquals(setOf(old), store.pending)
            assertEquals(setOf(old), store.incidents)
            assertEquals(KnownCaptureStopResult.DURABLE, store.stop())
            assertEquals(1, store.pending.size)
            assertEquals(1, store.incidents.size)
        }
    }

    @Test
    fun unavailableMarker_isPreservedInsteadOfBeingDeletedAsAKnownStop() {
        val current = currentMarker()
        val store = Store(current).apply { failedRead = 1 }

        assertEquals(KnownCaptureStopResult.FAILED_UNCERTAIN, store.stop())
        assertEquals(current, store.marker)
        assertEquals(0, store.disarms)
        assertEquals(0, store.removals)
        assertEquals(0, store.recoveries)
        store.failedRead = 0
        assertEquals(KnownCaptureStopResult.DURABLE, store.stop())
        assertEquals(current.copy(armed = false), store.marker)
    }

    @Test
    fun unavailablePostRecoveryRead_keepsPendingAndHistoryWithoutGrantingDeleteAuthority() {
        val old = Marker(pid = 11, processStart = 100L, arm = 200L)
        val store = Store(old).apply { failedRead = 2 }

        assertEquals(KnownCaptureStopResult.FAILED_UNCERTAIN, store.stop())
        assertEquals(setOf(old), store.pending)
        assertEquals(setOf(old), store.incidents)
        assertEquals(0, store.disarms)
        assertEquals(0, store.removals)
        store.failedRead = 0
        assertEquals(KnownCaptureStopResult.DURABLE, store.stop())
        assertEquals(1, store.pending.size)
        assertEquals(1, store.incidents.size)
    }

    @Test
    fun recoveryThatLeavesOldOwner_cannotAuthorizeDisarmEvenWhenPidWasReused() {
        val old = currentMarker().copy(processStart = 100L)
        val store = Store(old).apply { leavePriorMarker = true }

        assertEquals(KnownCaptureStopResult.FAILED_UNCERTAIN, store.stop())
        assertEquals(old, store.marker)
        assertEquals(1, store.recoveries)
        assertEquals(0, store.disarms)
        assertEquals(0, store.removals)
    }

    @Test
    fun currentSessionStop_isFastIdempotentAndDoesNotScanPriorSessionJournals() {
        val current = currentMarker()
        val store = Store(current)

        assertEquals(KnownCaptureStopResult.DURABLE, store.stop())
        assertEquals(current.copy(armed = false), store.marker)
        assertEquals(KnownCaptureStopResult.DURABLE, store.stop())
        assertEquals(1, store.disarms)
        assertEquals(0, store.removals)
        assertEquals(0, store.recoveries)
        assertTrue(store.pending.isEmpty())
        assertTrue(store.incidents.isEmpty())
    }

    @Test
    fun pendingServiceStopEvidence_mustFinishBeforeCurrentMarkerCanBeDisarmed() {
        val current = currentMarker()
        val store = Store(current).apply {
            pendingLocalIncident = current
            failLocalRetry = true
        }

        assertEquals(KnownCaptureStopResult.FAILED_UNCERTAIN, store.stop())
        assertEquals(current, store.marker)
        assertEquals(0, store.reads)
        assertEquals(0, store.disarms)
        assertEquals(0, store.removals)
        store.failLocalRetry = false
        assertEquals(KnownCaptureStopResult.DURABLE, store.stop())
        assertEquals(setOf(current), store.incidents)
        assertFalse(requireNotNull(store.marker).armed)
        assertEquals(0, store.recoveries)
    }

    @Test
    fun failedCurrentStop_preservesExistingDurableDeleteAndExactRearmFallbacks() {
        val current = currentMarker()
        val deleteFallback = Store(current).apply { failDisarm = true }
        assertEquals(KnownCaptureStopResult.DURABLE, deleteFallback.stop())
        assertNull(deleteFallback.marker)

        val rearmFallback = Store(current).apply { failDisarm = true; failRemove = true }
        assertEquals(KnownCaptureStopResult.FAILED_REARMED, rearmFallback.stop())
        assertEquals(current, rearmFallback.marker)
        assertEquals(1, rearmFallback.restores)

        val uncertain = Store(current).apply {
            failDisarm = true
            failRemove = true
            failRestore = true
        }
        assertEquals(KnownCaptureStopResult.FAILED_UNCERTAIN, uncertain.stop())
        assertEquals(current, uncertain.marker)
    }

    @Test
    fun visibleDisarmedMarker_requiresFreshDurabilityConfirmation() {
        val disarmed = currentMarker().copy(armed = false)
        val store = Store(disarmed).apply { failDisarmedConfirmation = true }

        assertEquals(KnownCaptureStopResult.FAILED_UNCERTAIN, store.stop())
        assertEquals(disarmed, store.marker)
        assertEquals(1, store.confirmations)
        assertEquals(0, store.disarms)
        assertEquals(0, store.removals)
        store.failDisarmedConfirmation = false
        assertEquals(KnownCaptureStopResult.DURABLE, store.stop())
        assertEquals(2, store.confirmations)
        assertTrue(store.incidents.isEmpty())
    }

    @Test
    fun publishedDisarmFailure_retainsExactSessionThroughHistoryAndBarrierRetries() {
        val current = currentMarker()
        val store = Store(current).apply {
            publishDisarmBeforeFailure = true
            failDisarm = true
            failRemove = true
            failRestore = true
        }

        assertEquals(KnownCaptureStopResult.FAILED_UNCERTAIN, store.stop())
        assertEquals(current.copy(armed = false), store.marker)
        assertEquals(current, store.pendingLocalIncident)
        assertEquals(listOf(current), store.retainedUncertainEvidence)

        val readsBeforeRetry = store.reads
        store.failLocalRetry = true
        assertEquals(KnownCaptureStopResult.FAILED_UNCERTAIN, store.stop())
        assertEquals(readsBeforeRetry, store.reads)
        assertEquals(current, store.pendingLocalIncident)
        assertTrue(store.incidents.isEmpty())

        store.failLocalRetry = false
        store.failDisarmedConfirmation = true
        assertEquals(KnownCaptureStopResult.FAILED_UNCERTAIN, store.stop())
        assertEquals(setOf(current), store.incidents)
        assertNull(store.pendingLocalIncident)
        assertEquals(current.copy(armed = false), store.marker)
        store.failDisarmedConfirmation = false
        assertEquals(KnownCaptureStopResult.DURABLE, store.stop())
        assertEquals(KnownCaptureStopResult.DURABLE, store.stop())
        assertEquals(setOf(current), store.incidents)
        assertEquals(listOf(current), store.retainedUncertainEvidence)
    }

    @Test
    fun failedUnlinkBarrier_retainsExactSessionUntilMissingStateIsDurable() {
        val current = currentMarker()
        val store = Store(current).apply {
            publishDisarmBeforeFailure = true
            removeBeforeFailure = true
            failDisarm = true
            failRemove = true
            failRestore = true
        }

        assertEquals(KnownCaptureStopResult.FAILED_UNCERTAIN, store.stop())
        assertNull(store.marker)
        assertEquals(current, store.pendingLocalIncident)
        store.failLocalRetry = true
        assertEquals(KnownCaptureStopResult.FAILED_UNCERTAIN, store.stop())
        assertEquals(current, store.pendingLocalIncident)
        store.failLocalRetry = false
        assertEquals(KnownCaptureStopResult.FAILED_UNCERTAIN, store.stop())
        assertEquals(setOf(current), store.incidents)
        store.failRemove = false
        assertEquals(KnownCaptureStopResult.DURABLE, store.stop())
        assertEquals(KnownCaptureStopResult.DURABLE, store.stop())
        assertEquals(setOf(current), store.incidents)
        assertEquals(listOf(current), store.retainedUncertainEvidence)
    }

    private data class Marker(
        val pid: Int,
        val processStart: Long,
        val arm: Long,
        val armed: Boolean = true,
    )

    private class Store(var marker: Marker?) {
        val pending = linkedSetOf<Marker>()
        val incidents = linkedSetOf<Marker>()
        var failedRecoveryBoundary = 0
        var failedRead = 0
        var leavePriorMarker = false
        var pendingLocalIncident: Marker? = null
        var failLocalRetry = false
        var failDisarm = false
        var failRemove = false
        var failRestore = false
        var publishDisarmBeforeFailure = false
        var removeBeforeFailure = false
        var failDisarmedConfirmation = false
        val retainedUncertainEvidence = mutableListOf<Marker>()
        var confirmations = 0
        var reads = 0
        var recoveries = 0
        var disarms = 0
        var removals = 0
        var restores = 0

        fun recoverPriorSession() {
            recoveries++
            val old = marker?.takeIf { it.armed && !belongsToCurrentProcess(it) } ?: return
            if (leavePriorMarker) return
            if (failedRecoveryBoundary == 1) throw IOException("pending journal unavailable")
            pending += old
            if (failedRecoveryBoundary == 2) throw IOException("history publication failed")
            incidents += old
            if (failedRecoveryBoundary == 3) throw IOException("marker retirement failed")
            marker = null
        }

        fun stop(): KnownCaptureStopResult = completeKnownCaptureStop(
            retryPendingEvidence = {
                if (failLocalRetry) throw IOException("Service-stop retry is unavailable")
                pendingLocalIncident?.let { incidents += it }
                pendingLocalIncident = null
            },
            readMarker = {
                reads++
                if (reads == failedRead) throw IOException("marker read unavailable")
                marker
            },
            isArmed = { it.armed },
            belongsToCurrentProcess = ::belongsToCurrentProcess,
            recoverPriorSession = ::recoverPriorSession,
            confirmDisarmed = {
                confirmations++
                if (failDisarmedConfirmation) throw IOException("directory confirmation failed")
                check(marker == it)
            },
            retainUncertainEvidence = {
                retainedUncertainEvidence += it
                pendingLocalIncident = it
            },
            disarm = {
                disarms++
                if (publishDisarmBeforeFailure) marker = it.copy(armed = false)
                if (failDisarm) throw IOException("disarm failed")
                marker = it.copy(armed = false)
            },
            remove = {
                removals++
                if (removeBeforeFailure) marker = null
                if (failRemove) throw IOException("remove failed")
                marker = null
            },
            restore = {
                restores++
                if (failRestore) throw IOException("restore failed")
                marker = it
            },
        )
    }

    companion object {
        private fun currentMarker() = Marker(pid = 22, processStart = 300L, arm = 400L)
        private fun belongsToCurrentProcess(marker: Marker): Boolean =
            marker.pid == 22 && marker.processStart == 300L
    }
}
