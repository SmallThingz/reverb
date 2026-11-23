package app.smallthingz.reverb

import java.time.ZoneOffset
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IncidentGroupingTest {
    @Test fun identicalMessagesShareOneHeadingWithNewestOccurrenceFirst() {
        val first = RecordingIncident(1_000, pid = 12, description = "Microphone error")
        val second = RecordingIncident(2_000, pid = 31, description = "Different error")
        val third = RecordingIncident(3_000, resumedAtMillis = 4_000, pid = 81, description = first.description)
        val history = prepareIncidentHistory(listOf(second, third, first), Locale.US, ZoneOffset.UTC)
        val groups = history.groups.entries.toList()
        assertEquals(listOf(first.description, second.description), groups.map { it.key.description })
        val repeat = groups[0].value
        assertEquals(listOf(third, first), repeat.map { it.incident })
        assertTrue(repeat.first().started.endsWith("12:00:03 AM"))
        assertTrue(checkNotNull(repeat.first().ended).endsWith("12:00:04 AM"))
        assertNull(repeat.last().ended)
        assertTrue(repeat.first().cause.contains("PID 81"))
    }

    @Test fun batchTombstonesTheSnapshotAndRetainsNewOccurrencesOfTheSameMessage() {
        val old = RecordingIncident(1_000, pid = 27, captureArmedAtMillis = 10, processStartedAtMillis = 1,
            description = "Same message")
        val enriched = old.copy(resumedAtMillis = 1_200, description = "Enriched message")
        val concurrent = old.copy(captureArmedAtMillis = 100, processStartedAtMillis = 90)
        val otherTarget = old.copy(occurredAtMillis = 1_500, pid = 52)
        val result = checkNotNull(markRecordingIncidentsDeleted(listOf(concurrent, enriched, otherTarget),
            listOf(old, otherTarget)))
        assertEquals(listOf(false, true, true), result.map { it.deleted })
        assertEquals(concurrent, result.first())
        assertEquals(enriched.description, result[1].description)
        assertFalse(result[1].acknowledged)
        assertEquals(result, markRecordingIncidentsDeleted(result, listOf(old, otherTarget)))
    }
}
