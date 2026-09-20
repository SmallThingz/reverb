package app.smallthingz.reverb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingCatalogRowIsolationTest {
    @Test
    fun catalogProjectionBoundsDynamicValuesBeforeCursorMaterialization() {
        val projection = recordingCatalogProjection()
        val aliases = projection.map { expression -> expression.substringAfterLast(" AS ") }.toSet()
        val expectedAliases = setOf(
            RecordingDatabase.COLUMN_ID,
            RecordingDatabase.COLUMN_DISPLAY_NAME,
            RecordingDatabase.COLUMN_MIME_TYPE,
            RecordingDatabase.COLUMN_STARTED_AT_MILLIS,
            RecordingDatabase.COLUMN_DURATION_MILLIS,
            RecordingDatabase.COLUMN_SIZE_BYTES,
            RecordingDatabase.COLUMN_CODEC_SUMMARY,
            RecordingDatabase.COLUMN_STORAGE_TYPE,
            RecordingDatabase.COLUMN_STORAGE_TYPE_CODE,
            RecordingDatabase.COLUMN_DIRECTORY_ID,
            RecordingDatabase.COLUMN_FILE_IDENTITY,
            RecordingDatabase.COLUMN_WAVEFORM_DATA,
            RecordingDatabase.COLUMN_WAVEFORM_REVISION,
            RecordingDatabase.COLUMN_CREATED_AT_MILLIS,
            RecordingDatabase.COLUMN_LAST_SEEN_AT_MILLIS,
            RecordingDatabase.COLUMN_MISSING_SINCE_MILLIS,
        )

        assertEquals(expectedAliases, aliases)
        projection.forEach { expression ->
            assertTrue("projection must type-gate raw catalog values: " + expression, "typeof(" in expression)
        }
        assertTrue(
            projection.single { it.endsWith(" AS " + RecordingDatabase.COLUMN_ID) }
                .contains(
                    "substr(" + RecordingDatabase.COLUMN_ID + ", 1, " +
                        (MAX_RECORDING_CATALOG_TEXT_CHARS + 1) + ")",
                ),
        )
        assertTrue(
            projection.single { it.endsWith(" AS " + RecordingDatabase.COLUMN_WAVEFORM_DATA) }
                .contains(
                    "substr(" + RecordingDatabase.COLUMN_WAVEFORM_DATA + ", 1, " +
                        (MAX_RECORDING_CATALOG_WAVEFORM_CHARS + 1) + ")",
                ),
        )
        assertTrue(
            projection.single { it.endsWith(" AS " + RecordingDatabase.COLUMN_STORAGE_TYPE_CODE) }
                .contains(Long.MAX_VALUE.toString()),
        )
        assertTrue(
            "typeof(" + RecordingDatabase.COLUMN_STARTED_AT_MILLIS + ")" in recordingCatalogOrderBy(),
        )
        assertTrue(
            "typeof(" + RecordingDatabase.COLUMN_CREATED_AT_MILLIS + ")" in recordingCatalogOrderBy(),
        )
    }

    @Test
    fun boundedTextColumnRejectsOversizedProjectedText() {
        val exact = "x".repeat(MAX_RECORDING_CATALOG_TEXT_CHARS)
        val oversized = exact + "x"

        assertEquals(
            exact,
            recordingCatalogTextColumn(android.database.Cursor.FIELD_TYPE_STRING) { exact },
        )
        assertNull(
            recordingCatalogTextColumn(android.database.Cursor.FIELD_TYPE_STRING) { oversized },
        )
        assertNull(
            recordingCatalogTextColumn(
                android.database.Cursor.FIELD_TYPE_STRING,
                MAX_RECORDING_CATALOG_WAVEFORM_CHARS,
            ) {
                "x".repeat(MAX_RECORDING_CATALOG_WAVEFORM_CHARS + 1)
            },
        )
    }

    @Test
    fun typedColumnsRejectWrongSqliteTypesBeforeReading() {
        var integerReads = 0
        val rejectedInteger = recordingCatalogIntegerColumn(android.database.Cursor.FIELD_TYPE_STRING) {
            integerReads++
            throw NumberFormatException("must not parse malformed integer text")
        }
        assertNull(rejectedInteger)
        assertEquals(0, integerReads)
        assertEquals(
            42L,
            recordingCatalogIntegerColumn(android.database.Cursor.FIELD_TYPE_INTEGER) { 42L },
        )

        var textReads = 0
        val rejectedText = recordingCatalogTextColumn(android.database.Cursor.FIELD_TYPE_BLOB) {
            textReads++
            error("must not stringify malformed blob")
        }
        assertNull(rejectedText)
        assertEquals(0, textReads)
        assertEquals(
            "recording.wav",
            recordingCatalogTextColumn(android.database.Cursor.FIELD_TYPE_STRING) { "recording.wav" },
        )
    }
}
