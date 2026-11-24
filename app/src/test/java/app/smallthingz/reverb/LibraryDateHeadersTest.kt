package app.smallthingz.reverb

import org.junit.Assert.*
import org.junit.Test
import java.text.FieldPosition
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class LibraryDateHeadersTest {
    private fun recording(id: String, instant: String) = RecordingEntity(id, "$id.wav", "audio/wav",
        java.time.Instant.parse(instant).toEpochMilli(), 1L, 1L, "PCM", RecordingStorageType.FILE, "/library")

    private fun format(zone: String) = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        timeZone = TimeZone.getTimeZone(zone)
    }

    @Test fun unchangedCatalogRegroupsAcrossLocalMidnightWhenZoneChanges() {
        val rows = listOf(recording("A", "2026-11-25T01:30:00Z"), recording("B", "2026-11-24T23:30:00Z"))
        val utc = buildLibraryListItems(rows, format("UTC"))
        val pacific = buildLibraryListItems(rows, format("America/Los_Angeles"))
        assertEquals(listOf("2026-11-25", "2026-11-24"), utc.filterIsInstance<LibraryListItem.Header>().map { it.dateLabel })
        assertEquals(listOf("2026-11-24"), pacific.filterIsInstance<LibraryListItem.Header>().map { it.dateLabel })
        pacific.filterIsInstance<LibraryListItem.Recording>().forEachIndexed { index, item ->
            assertSame(rows[index], item.recording)
        }
    }

    @Test fun repeatedDayFormatsOnceIncludingTheRepeatedHourAtDstFallback() {
        var calls = 0
        val formatter = object : SimpleDateFormat("yyyy-MM-dd", Locale.US) {
            override fun format(date: Date, result: StringBuffer, field: FieldPosition): StringBuffer {
                calls++
                return super.format(date, result, field)
            }
        }.apply { timeZone = TimeZone.getTimeZone("America/Los_Angeles") }
        val a = recording("A", "2026-11-01T09:30:00Z")
        val b = recording("B", "2026-11-01T08:30:00Z")
        val rows = List(100) { if (it % 2 == 0) a else b } + recording("C", "2026-10-31T12:00:00Z")
        val result = buildLibraryListItems(rows, formatter)
        assertEquals(2, calls)
        assertEquals(listOf("2026-11-01", "2026-10-31"), result.filterIsInstance<LibraryListItem.Header>().map { it.dateLabel })
        assertEquals(rows, result.filterIsInstance<LibraryListItem.Recording>().map { it.recording })
    }

    @Test fun sameOrdinalDayAcrossYearsIsNotReusedAndInputOrderIsPreserved() {
        val rows = listOf(recording("A", "2026-01-01T12:00:00Z"), recording("B", "2025-01-01T12:00:00Z"),
            recording("C", "2026-01-01T12:00:00Z"))
        assertEquals(listOf("2026-01-01", "2025-01-01", "2026-01-01"),
            buildLibraryListItems(rows, format("UTC")).filterIsInstance<LibraryListItem.Header>().map { it.dateLabel })
        assertTrue(buildLibraryListItems(emptyList(), format("UTC")).isEmpty())
    }
}
