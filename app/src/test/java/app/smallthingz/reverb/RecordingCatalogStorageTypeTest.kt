package app.smallthingz.reverb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    @Test
    fun catalogCoreFields_rejectUnsafeLocationAndNegativeGeometry() {
        fun valid(
            id: String? = "/recordings/a.wav",
            displayName: String? = "a.wav",
            directoryId: String? = "/recordings",
            startedAtMillis: Long = 0L,
            durationMillis: Long = 0L,
            sizeBytes: Long = 0L,
            createdAtMillis: Long = 0L,
            lastSeenAtMillis: Long = 0L,
            missingSinceMillis: Long? = null,
        ) = recordingCatalogCoreFieldsAreValid(
            id, displayName, directoryId, startedAtMillis, durationMillis, sizeBytes,
            createdAtMillis, lastSeenAtMillis, missingSinceMillis,
        )

        assertTrue(valid())
        assertFalse(valid(id = ""))
        assertFalse(valid(displayName = ""))
        assertFalse(valid(directoryId = ""))
        assertFalse(valid(startedAtMillis = -1L))
        assertFalse(valid(durationMillis = -1L))
        assertFalse(valid(sizeBytes = -1L))
        assertFalse(valid(createdAtMillis = -1L))
        assertFalse(valid(lastSeenAtMillis = -1L))
        assertFalse(valid(missingSinceMillis = -1L))
    }

}
