package app.smallthingz.reverb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingMoveAvailabilityScanTest {
    @Test
    fun firstAuthoritativeCandidateStopsBeforeTheRemainingProviderRows() {
        val first = recording("first").copy(missingSinceMillis = 5L)
        val rows = listOf(first) + List(999) { recording("tail-$it") }
        val observations = mutableListOf<String>()
        val identities = mutableListOf<String>()
        val updates = mutableListOf<RecordingEntity>()

        assertTrue(scanForMovableRecording(
            recordings = rows,
            targetDirectoryId = "target",
            pendingDeletionIds = emptySet(),
            nowMillis = 100L,
            mutationIsActive = { false },
            assetState = {
                observations += it.id
                check(it.id == "first") { "the remaining provider rows must not be probed" }
                RecordingAssetState.PRESENT
            },
            destructiveIdentityMatches = { identities += it.id; true },
            collectUpdate = { updates += it },
        ))

        assertEquals(listOf("first"), observations)
        assertEquals(listOf("first"), identities)
        assertEquals(listOf(first.copy(lastSeenAtMillis = 100L, missingSinceMillis = null)), updates)
    }

    @Test
    fun skippedAndUncertainRowsDoNotGrantAvailabilityAndPriorUpdatesAreRetained() {
        val rows = listOf(
            recording("pending"), recording("active"), recording("target", "target"),
            recording("missing"), recording("unavailable"),
            recording("changed").copy(missingSinceMillis = 5L),
            recording("verified"), recording("tail"),
        )
        val observations = mutableListOf<String>()
        val identities = mutableListOf<String>()
        val updates = mutableListOf<RecordingEntity>()

        assertTrue(scanForMovableRecording(
            recordings = rows,
            targetDirectoryId = "target",
            pendingDeletionIds = setOf("pending"),
            nowMillis = 100L,
            mutationIsActive = { it == "active" },
            assetState = {
                observations += it.id
                when (it.id) {
                    "missing" -> RecordingAssetState.MISSING
                    "unavailable" -> RecordingAssetState.UNAVAILABLE
                    else -> RecordingAssetState.PRESENT
                }
            },
            destructiveIdentityMatches = { identities += it.id; it.id == "verified" },
            collectUpdate = { updates += it },
        ))

        assertEquals(listOf("missing", "unavailable", "changed", "verified"), observations)
        assertEquals(listOf("changed", "verified"), identities)
        assertEquals(listOf(
            rows[3].copy(missingSinceMillis = 100L),
            rows[5].copy(lastSeenAtMillis = 100L, missingSinceMillis = null),
        ), updates)
    }

    @Test
    fun noAuthoritativeCandidateReturnsFalseWithoutTreatingUnavailableAsMissing() {
        val unavailable = recording("unavailable")
        val missing = recording("missing").copy(missingSinceMillis = 5L)
        val changed = recording("changed")
        val updates = mutableListOf<RecordingEntity>()
        var identityChecks = 0
        val available = scanForMovableRecording(
            recordings = listOf(unavailable, missing, changed),
            targetDirectoryId = "target",
            pendingDeletionIds = emptySet(),
            nowMillis = 100L,
            mutationIsActive = { false },
            assetState = {
                when (it.id) {
                    "unavailable" -> RecordingAssetState.UNAVAILABLE
                    "missing" -> RecordingAssetState.MISSING
                    else -> RecordingAssetState.PRESENT
                }
            },
            destructiveIdentityMatches = { identityChecks++; false },
            collectUpdate = { updates += it },
        )

        assertFalse(available)
        assertEquals(1, identityChecks)
        assertTrue(updates.isEmpty())
    }

    @Test
    fun emptyOrEntirelyIneligibleCatalogDoesNotTouchStorage() {
        for (rows in listOf(emptyList(), listOf(recording("pending"), recording("active"), recording("target", "target")))) {
            assertFalse(scanForMovableRecording(
                recordings = rows,
                targetDirectoryId = "target",
                pendingDeletionIds = setOf("pending"),
                nowMillis = 100L,
                mutationIsActive = { it == "active" },
                assetState = { error("ineligible rows must not touch storage") },
                destructiveIdentityMatches = { error("ineligible rows must not resolve identity") },
                collectUpdate = { error("ineligible rows must not change catalog state") },
            ))
        }
    }

    @Test(expected = IllegalStateException::class)
    fun failedAuthoritativeIdentityReadIsNotPublishedAsAvailable() {
        scanForMovableRecording(
            recordings = listOf(recording("first")),
            targetDirectoryId = "target",
            pendingDeletionIds = emptySet(),
            nowMillis = 100L,
            mutationIsActive = { false },
            assetState = { RecordingAssetState.PRESENT },
            destructiveIdentityMatches = { throw IllegalStateException("provider observation failed") },
            collectUpdate = { error("no update can be published after the failed observation") },
        )
    }

    private fun recording(id: String, directoryId: String = "source") = RecordingEntity(
        id = id,
        displayName = "$id.wav",
        mimeType = "audio/wav",
        startedAtMillis = 1L,
        durationMillis = 1L,
        sizeBytes = 1L,
        codecSummary = "PCM",
        storageType = RecordingStorageType.DOCUMENT,
        directoryId = directoryId,
        fileIdentity = "verified-identity",
        createdAtMillis = 1L,
    )
}
