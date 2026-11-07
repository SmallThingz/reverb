package app.smallthingz.reverb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingPlaybackRequestTest {
    private fun request(token: String = "tap-a") = RecordingPlaybackRequest(token,
        "content://provider/tree/root/document/audio", RecordingStorageType.DOCUMENT, "revision-a")

    private fun recording() = RecordingEntity(
        id = request().recordingId, displayName = "Saved recording.wav", mimeType = "audio/wav",
        startedAtMillis = 1, durationMillis = 1000, sizeBytes = 2048, codecSummary = "PCM",
        storageType = RecordingStorageType.DOCUMENT, directoryId = "content://provider/tree/root",
        fileIdentity = "revision-a", createdAtMillis = 1,
    )

    @Test fun reusedLocationNeverSelectsReplacementBytes() {
        assertTrue(request().matches(recording()))
        assertFalse(request().matches(recording().copy(fileIdentity = "revision-b")))
        assertFalse(request().matches(recording().copy(fileIdentity = "")))
        assertFalse(request().copy(fileIdentity = "").matches(recording().copy(fileIdentity = "")))
    }

    @Test fun storageAndLocationArePartOfNotificationAuthority() {
        assertFalse(request().matches(recording().copy(id = "content://provider/tree/root/document/other")))
        assertFalse(request().matches(recording().copy(storageType = RecordingStorageType.MEDIASTORE)))
        assertTrue(request().matches(recording().copy(displayName = "New catalog label.wav")))
    }

    @Test fun verificationNeedsForegroundAndCanDeliverOnlyOnce() {
        val route = RecordingPlaybackRoute()
        val tap = request()
        assertTrue(route.submit(tap))
        assertNull(route.takeIfReady(true))
        route.verified(tap.token, route.proofGeneration)
        assertNull(route.takeIfReady(false))
        assertSame(tap, route.takeIfReady(true))
        assertNull(route.takeIfReady(true))
    }

    @Test fun newerTapRejectsOldScanAndDuplicateSubmissionPreservesNewProof() {
        val route = RecordingPlaybackRoute()
        val old = request()
        val latest = request("tap-b")
        route.submit(old)
        route.submit(latest)
        route.verified(old.token, route.proofGeneration)
        assertNull(route.takeIfReady(true))
        route.verified(latest.token, route.proofGeneration)
        assertFalse(route.submit(latest))
        assertSame(latest, route.takeIfReady(true))
    }

    @Test fun pauseRequiresFreshStorageProofAndDismissalRetiresRequest() {
        val route = RecordingPlaybackRoute()
        val tap = request()
        route.submit(tap)
        val oldProof = route.proofGeneration
        route.verified(tap.token, oldProof)
        route.invalidateProof()
        assertNull(route.takeIfReady(true))
        route.verified(tap.token, oldProof)
        assertNull(route.takeIfReady(true))
        route.verified(tap.token, route.proofGeneration)
        route.clear()
        route.verified(tap.token, route.proofGeneration)
        assertNull(route.takeIfReady(true))
    }

    @Test fun notificationOpensLibraryAboveOtherPanelsAndCancelsDrag() {
        val navigation = NativeMainNavigationState(NativeMainSavedNavigation(
            showSettings = true, settingsBufferTargetCode = 1, showAboutDialog = true, showIncidents = true))
        navigation.beginVerticalReveal(0f, 100f)
        navigation.openSavedRecordingLibrary()
        assertTrue(navigation.showLibrary)
        assertFalse(navigation.showSettings)
        assertFalse(navigation.showAboutDialog)
        assertFalse(navigation.showIncidents)
        assertNull(navigation.dragTarget)
        assertEquals(-1, navigation.settingsBufferTargetCode)
        // An outgoing Incidents animation still owns visibility until its terminal callback.
        assertFalse(navigation.libraryActive)
        navigation.bindIncidentsOverlayVisibility(false)
        assertTrue(navigation.libraryActive)
    }
}
