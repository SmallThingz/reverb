package app.smallthingz.reverb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecordingCatalogRowIsolationTest {
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
