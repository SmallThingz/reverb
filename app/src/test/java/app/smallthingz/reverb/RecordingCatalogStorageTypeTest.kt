package app.smallthingz.reverb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingCatalogStorageTypeTest {
    @Test
    fun catalogInitialCapacity_isBoundedBeforeRowValidation() {
        assertEquals(0, recordingCatalogInitialCapacity(-1))
        assertEquals(3, recordingCatalogInitialCapacity(3))
        assertEquals(
            MAX_RECORDING_CATALOG_INITIAL_CAPACITY,
            recordingCatalogInitialCapacity(Int.MAX_VALUE),
        )
    }

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

    @Test
    fun catalogLocation_requiresStorageSpecificAuthorityShape() {
        val managedFileDirectories = setOf("/recordings", "/legacy-recordings")
        assertTrue(
            recordingCatalogLocationIsValid(
                RecordingStorageType.FILE,
                "/recordings/a.wav",
                "/recordings",
                managedFileDirectories,
            ),
        )
        assertFalse(
            recordingCatalogLocationIsValid(
                RecordingStorageType.FILE,
                "relative/a.wav",
                "/recordings",
                managedFileDirectories,
            ),
        )
        assertFalse(
            recordingCatalogLocationIsValid(
                RecordingStorageType.FILE,
                "/other/a.wav",
                "/recordings",
                managedFileDirectories,
            ),
        )
        assertFalse(
            recordingCatalogLocationIsValid(
                RecordingStorageType.FILE,
                "/outside/a.wav",
                "/outside",
                managedFileDirectories,
            ),
        )
        assertTrue(
            recordingCatalogLocationIsValid(
                RecordingStorageType.FILE,
                "/legacy-recordings/a.wav",
                "/legacy-recordings",
                managedFileDirectories,
            ),
        )
        assertTrue(
            recordingCatalogLocationIsValid(
                RecordingStorageType.DOCUMENT,
                "content://docs/tree/root/document/1",
                "content://docs/tree/root",
            ),
        )
        assertFalse(
            recordingCatalogLocationIsValid(
                RecordingStorageType.DOCUMENT,
                "file:///recordings/a.wav",
                "content://docs/tree/root",
            ),
        )
        assertFalse(
            recordingCatalogLocationIsValid(
                RecordingStorageType.DOCUMENT,
                "content://docs/tree/other/document/1",
                "content://docs/tree/root",
            ),
        )
        assertFalse(
            recordingCatalogLocationIsValid(
                RecordingStorageType.DOCUMENT,
                "content://other/tree/root/document/1",
                "content://docs/tree/root",
            ),
        )
        assertTrue(
            recordingCatalogLocationIsValid(
                RecordingStorageType.MEDIASTORE,
                "content://media/external/audio/media/1",
                MEDIA_STORE_DIRECTORY_ID,
            ),
        )
        assertFalse(
            recordingCatalogLocationIsValid(
                RecordingStorageType.MEDIASTORE,
                "content://media/external/audio/media/1",
                "content://other/tree/root",
            ),
        )
    }

    @Test
    fun storageObjectId_requiresAbsoluteFilesOrContentUris() {
        assertTrue(recordingStorageIdIsValid(RecordingStorageType.FILE, "/recordings/a.wav"))
        assertFalse(recordingStorageIdIsValid(RecordingStorageType.FILE, "relative/a.wav"))
        assertTrue(
            recordingStorageIdIsValid(
                RecordingStorageType.DOCUMENT,
                "content://docs/tree/root/document/1",
            ),
        )
        assertTrue(
            recordingStorageIdIsValid(
                RecordingStorageType.DOCUMENT,
                "content://docs/tree/%E2%82%AC/document/%E2%82%AC",
            ),
        )
        assertTrue(
            recordingStorageIdIsValid(
                RecordingStorageType.DOCUMENT,
                "content://docs/tree/primary%3AMusic%2FReverb/document/primary%3AMusic%2FReverb%2Fclip.wav",
            ),
        )
        listOf(
            "content://docs//tree/root/document/1",
            "content://docs/tree//root/document/1",
            "content://docs/tree/root//document/1",
            "content://docs/tree/root/document//1",
            "content://docs/tree/root/document/1/",
            "content://do%63s/tree/root/document/1",
            "CONTENT://docs/tree/root/document/1",
            "content://docs/tree/%72oot/document/1",
            "content://docs/tree/root/document/%31",
            "content://docs/tree/root:alias/document/1",
            "content://docs/tree/root%3aalias/document/1",
            "content://docs/tree/%21/document/1",
            "content://docs/tree/%FF/document/1",
        ).forEach { id ->
            assertFalse(recordingStorageIdIsValid(RecordingStorageType.DOCUMENT, id))
        }
        assertFalse(
            recordingStorageIdIsValid(
                RecordingStorageType.DOCUMENT,
                "content://docs/document/1",
            ),
        )
        assertFalse(
            recordingStorageIdIsValid(
                RecordingStorageType.DOCUMENT,
                "content://docs/tree/root",
            ),
        )
        assertTrue(documentTreeIdIsValid("content://docs/tree/root"))
        assertTrue(documentTreeIdIsValid("content://docs/tree/%E2%82%AC"))
        listOf(
            "content://docs//tree/root",
            "content://docs/tree//root",
            "content://docs/tree/root/",
            "content://do%63s/tree/root",
            "CONTENT://docs/tree/root",
            "content://docs/tree/%72oot",
            "content://docs/tree/root:alias",
            "content://docs/tree/root%3aalias",
            "content://docs/tree/%21",
            "content://docs/tree/%FF",
        ).forEach { id ->
            assertFalse(documentTreeIdIsValid(id))
        }
        assertFalse(documentTreeIdIsValid("content://docs/tree/root/document/1"))
        assertFalse(documentTreeIdIsValid("content://docs/document/1"))
        assertFalse(documentTreeIdIsValid("not a uri"))
        assertTrue(
            documentRecordingBelongsToTree(
                "content://docs/tree/primary%3AMusic%2FReverb/document/primary%3AMusic%2FReverb%2Fclip.wav",
                "content://docs/tree/primary%3AMusic%2FReverb",
            ),
        )
        assertTrue(
            recordingStorageIdIsValid(
                RecordingStorageType.MEDIASTORE,
                "content://media/external/audio/media/1",
            ),
        )
        listOf(
            "content://media/external/audio/media/1?includePending=1",
            "content://media/external/audio/media/1#fragment",
            "content://media/external/audio/media/1/",
            "content://media//external/audio/media/1",
            "content://media/external/audio/media/%31",
            "content://media/external/audio/media/0001",
            "content://media/external/audio/media/9223372036854775808",
            "content://media/external%5Fprimary/audio/media/1",
            "content://me%64ia/external/audio/media/1",
            "CONTENT://media/external/audio/media/1",
        ).forEach { id ->
            assertFalse(recordingStorageIdIsValid(RecordingStorageType.MEDIASTORE, id))
        }
        assertFalse(
            recordingStorageIdIsValid(
                RecordingStorageType.DOCUMENT,
                "file:///recordings/a.wav",
            ),
        )
        assertFalse(recordingStorageIdIsValid(RecordingStorageType.MEDIASTORE, "relative"))
        assertFalse(
            recordingStorageIdIsValid(
                RecordingStorageType.MEDIASTORE,
                "content://other.provider/external/audio/media/1",
            ),
        )
        assertFalse(
            recordingStorageIdIsValid(
                RecordingStorageType.MEDIASTORE,
                "content://media/external/images/media/1",
            ),
        )
        assertFalse(
            recordingStorageIdIsValid(
                RecordingStorageType.MEDIASTORE,
                "content://media/external/audio/media/not-a-row",
            ),
        )
        assertTrue(
            recordingStorageIdIsValid(
                RecordingStorageType.MEDIASTORE,
                "content://media/external_primary/audio/media/42",
            ),
        )
    }

}
