package app.smallthingz.reverb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecordingCatalogStorageTypeTest {
    @Test
    fun catalogStorageType_acceptsFreshAndMatchingLegacyRows() {
        assertEquals(
            RecordingStorageType.FILE,
            resolveRecordingCatalogStorageType(RecordingStorageType.FILE.storageCode.toInt(), ""),
        )
        assertEquals(
            RecordingStorageType.DOCUMENT,
            resolveRecordingCatalogStorageType(
                RecordingStorageType.DOCUMENT.storageCode.toInt(),
                "DOCUMENT",
            ),
        )
        assertEquals(
            RecordingStorageType.MEDIASTORE,
            resolveRecordingCatalogStorageType(
                RecordingStorageType.MEDIASTORE.storageCode.toInt(),
                RecordingStorageType.MEDIASTORE.storageCode.toInt().toString(),
            ),
        )
    }

    @Test
    fun catalogStorageType_recoversValidLegacyWhenCodeIsInvalid() {
        assertEquals(
            RecordingStorageType.DOCUMENT,
            resolveRecordingCatalogStorageType(127, "DOCUMENT"),
        )
    }

    @Test
    fun catalogStorageType_rejectsMalformedOrConflictingAuthority() {
        assertNull(resolveRecordingCatalogStorageType(127, ""))
        assertNull(resolveRecordingCatalogStorageType(RecordingStorageType.FILE.storageCode.toInt(), "garbage"))
        assertNull(
            resolveRecordingCatalogStorageType(
                RecordingStorageType.FILE.storageCode.toInt(),
                "DOCUMENT",
            ),
        )
    }
}
