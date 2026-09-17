package app.smallthingz.reverb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingScanIdentityTest {
    @Test
    fun scanIdentity_requiresStableKnownRevisionAcrossValidation() {
        val file = "stat:1:2:3:4:5"
        assertTrue(scannedRecordingIdentityRemainsCurrent(RecordingStorageType.FILE, file, file))
        assertFalse(scannedRecordingIdentityRemainsCurrent(RecordingStorageType.FILE, file, "stat:1:2:4:5:5"))
        assertFalse(scannedRecordingIdentityRemainsCurrent(RecordingStorageType.FILE, file, ""))

        val media = providerRecordingIdentity(RecordingStorageType.MEDIASTORE, "content://media/1", 100L, 9L)
        val changed = providerRecordingIdentity(RecordingStorageType.MEDIASTORE, "content://media/1", 101L, 10L)
        assertTrue(scannedRecordingIdentityRemainsCurrent(RecordingStorageType.MEDIASTORE, media, media))
        assertFalse(scannedRecordingIdentityRemainsCurrent(RecordingStorageType.MEDIASTORE, media, changed))
        assertFalse(scannedRecordingIdentityRemainsCurrent(RecordingStorageType.MEDIASTORE, media, ""))
    }

    @Test
    fun identitylessScan_staysNonAuthoritativeInsteadOfFailingDiscovery() {
        assertTrue(scannedRecordingIdentityRemainsCurrent(RecordingStorageType.FILE, "", ""))
        assertTrue(scannedRecordingIdentityRemainsCurrent(RecordingStorageType.DOCUMENT, "", "provider:2:item:1:1"))
    }
}
