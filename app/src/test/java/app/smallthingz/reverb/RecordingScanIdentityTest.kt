package app.smallthingz.reverb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingScanIdentityTest {
    @Test
    fun fileScanIdentity_requiresPinnedDescriptorAndCurrentPath() {
        val path = "stat:1:2:3:4:5"
        val descriptor = "statfd:1:2:3:4"
        assertTrue(scannedFileRecordingIdentityRemainsCurrent(path, descriptor, descriptor, path))

        assertFalse(
            scannedFileRecordingIdentityRemainsCurrent(
                path,
                "statfd:1:9:3:4",
                "statfd:1:9:3:4",
                path,
            ),
        )
        assertFalse(
            scannedFileRecordingIdentityRemainsCurrent(
                path,
                descriptor,
                "statfd:1:2:8:9",
                path,
            ),
        )
        assertFalse(
            scannedFileRecordingIdentityRemainsCurrent(
                path,
                descriptor,
                descriptor,
                "stat:1:9:3:4:5",
            ),
        )
        assertTrue(scannedFileRecordingIdentityRemainsCurrent("", descriptor, descriptor, ""))
        assertFalse(
            scannedFileRecordingIdentityRemainsCurrent(
                "",
                descriptor,
                "statfd:9:9:9:9",
                "stat:9:9:9:9:9",
            ),
        )
        assertTrue(
            scannedFileRecordingIdentityRemainsCurrent(
                "",
                descriptor,
                descriptor,
                "stat:1:2:3:4:5",
            ),
        )
        assertFalse(scannedFileRecordingIdentityRemainsCurrent("", descriptor, "", ""))
        assertTrue(scannedFileRecordingIdentityRemainsCurrent("", "", "", ""))
    }

    @Test
    fun mediaStoreListing_requiresKnownPendingState() {
        assertEquals(false, mediaStoreListedPendingState(pendingKnown = true, pending = false))
        assertEquals(true, mediaStoreListedPendingState(pendingKnown = true, pending = true))
        assertEquals(null, mediaStoreListedPendingState(pendingKnown = false, pending = false))
        assertEquals(null, mediaStoreListedPendingState(pendingKnown = false, pending = true))
    }

    @Test
    fun listedProviderIdentity_requiresKnownSizeBeforeGrantingAuthority() {
        val documentId = "content://docs/tree/root/document/clip"
        val mediaId = "content://media/external_primary/audio/media/7"

        assertTrue(
            listedProviderRecordingIdentity(
                RecordingStorageType.DOCUMENT, documentId, false, 0L, 9L,
            ).isBlank(),
        )
        assertTrue(
            listedProviderRecordingIdentity(
                RecordingStorageType.MEDIASTORE, mediaId, false, 0L, 11L,
            ).isBlank(),
        )
        assertTrue(
            listedProviderRecordingIdentity(
                RecordingStorageType.DOCUMENT, documentId, true, 0L, 9L,
            ).isNotBlank(),
        )
        assertTrue(
            listedProviderRecordingIdentity(
                RecordingStorageType.MEDIASTORE, mediaId, true, 0L, 11L,
            ).isNotBlank(),
        )
    }

    @Test
    fun providerScanIdentity_requiresStableDescriptorHandoffForKnownRevision() {
        val before = providerRecordingIdentity(
            RecordingStorageType.MEDIASTORE,
            "content://media/1",
            100L,
            9L,
        )
        val changed = providerRecordingIdentity(
            RecordingStorageType.MEDIASTORE,
            "content://media/1",
            101L,
            10L,
        )
        assertTrue(scannedProviderRecordingIdentityRemainsCurrent(before, before, before))
        assertFalse(scannedProviderRecordingIdentityRemainsCurrent(before, changed, before))
        assertFalse(scannedProviderRecordingIdentityRemainsCurrent(before, before, changed))
        assertFalse(scannedProviderRecordingIdentityRemainsCurrent("", changed, before))
        assertTrue(scannedProviderRecordingIdentityRemainsCurrent("", before, before))
        assertFalse(scannedProviderRecordingIdentityRemainsCurrent("", before, ""))
        assertTrue(scannedProviderRecordingIdentityRemainsCurrent("", "", ""))
    }

    @Test
    fun recoveredProviderCatalogIdentity_keepsPublishedRevisionBoundToScan() {
        val published = providerRecordingIdentity(
            RecordingStorageType.MEDIASTORE,
            "content://media/1",
            100L,
            9L,
        )
        val replacement = providerRecordingIdentity(
            RecordingStorageType.MEDIASTORE,
            "content://media/1",
            101L,
            10L,
        )
        assertTrue(recoveredProviderCatalogIdentity(published, published) == published)
        assertTrue(recoveredProviderCatalogIdentity(published, replacement) == null)
        assertTrue(recoveredProviderCatalogIdentity("", published) == null)
    }

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
