package app.smallthingz.reverb

import android.app.ApplicationExitInfo
import java.time.ZoneOffset
import java.time.Instant
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IncidentGroupingTest {
    @Test fun groupedCardsUseLocalDaysAndCompactOccurrenceDetails() {
        val beforeMidnight = RecordingIncident(
            Instant.parse("2026-11-22T13:59:00Z").toEpochMilli(), pid = 10, description = "Microphone error",
        )
        val afterMidnight = beforeMidnight.copy(
            occurredAtMillis = Instant.parse("2026-11-22T14:01:00Z").toEpochMilli(), pid = 11,
        )
        val later = afterMidnight.copy(
            occurredAtMillis = afterMidnight.occurredAtMillis + 60_000,
            resumedAtMillis = afterMidnight.occurredAtMillis + 69_500,
            processStartedAtMillis = afterMidnight.occurredAtMillis,
        )
        val history = prepareIncidentHistory(listOf(afterMidnight, beforeMidnight, later), Locale.US,
            ZoneOffset.ofHours(10))

        assertEquals(listOf("Monday, 23 Nov 26", "Sunday, 22 Nov 26"), history.days.map { it.label })
        val today = history.days.first().groups.single()
        assertEquals("Microphone error", today.message)
        assertEquals(listOf("12:02:00 AM · 0:10", "12:01:00 AM · Unknown"), today.occurrences.map { it.timeLabel })
        assertEquals("PID 11 · Process ${formatDurationInput(60)}", today.occurrences.first().processLabel)
        assertEquals(1, history.days.last().groups.single().occurrences.size)
    }

    @Test fun identicalMessagesShareOneHeadingWithNewestOccurrenceFirst() {
        val first = RecordingIncident(1_000, pid = 12, description = "Microphone error")
        val second = RecordingIncident(2_000, pid = 31, description = "Different error")
        val third = RecordingIncident(3_000, resumedAtMillis = 4_000, pid = 81, description = first.description)
        val history = prepareIncidentHistory(listOf(second, third, first), Locale.US, ZoneOffset.UTC)
        val groups = history.days.single().groups
        assertEquals(listOf(first.description, second.description), groups.map { it.message })
        val repeat = groups[0].occurrences.map { it as IncidentPresentation }
        assertEquals(listOf(third, first), repeat.map { it.incident })
        assertTrue(repeat.first().started.endsWith("12:00:03 AM"))
        assertTrue(checkNotNull(repeat.first().ended).endsWith("12:00:04 AM"))
        assertNull(repeat.last().ended)
        assertTrue(repeat.first().cause.contains("PID 81"))
    }

    @Test fun sameSecondKeepsDistinctOccurrencesAndKnownExitReasons() {
        val crash = RecordingIncident(1_200, resumedAtMillis = 0, pid = 24,
            exitReason = ApplicationExitInfo.REASON_CRASH)
        val stop = crash.copy(occurredAtMillis = 1_300, pid = 25, resumedAtMillis = -1,
            exitReason = ApplicationExitInfo.REASON_USER_REQUESTED)
        val history = prepareIncidentHistory(listOf(crash, stop), Locale.US, ZoneOffset.UTC)
        assertEquals(1, history.rows.map { it.startLabel }.distinct().size)
        assertEquals(2, history.rows.map { it.key }.distinct().size)
        assertEquals(listOf("User requested stop", "Crash"), history.days.single().groups.map { it.message })
        assertEquals(listOf("Unknown", "Awaiting capture resume"), history.rows.map { it.durationLabel })
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
