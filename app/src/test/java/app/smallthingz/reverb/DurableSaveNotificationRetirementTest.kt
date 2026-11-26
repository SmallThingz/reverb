package app.smallthingz.reverb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DurableSaveNotificationRetirementTest {
    @Test fun retainedCompletionCancelsOnceAcrossOtherJobUpdates() {
        val retirements = SaveNotificationRetirements()
        val rendered = linkedMapOf("complete" to 99, "removed" to 10, "active" to 25)
        val canceled = mutableListOf<String>()

        for (percent in 26..30) {
            retirements.cancelMissing(setOf("complete"), setOf("active"), rendered.keys, canceled::add)
            rendered["active"] = percent
        }

        assertEquals(listOf("complete", "removed"), canceled)
        assertEquals(mapOf("active" to 30), rendered)
    }

    @Test fun failureKeepsTheRenderedEntryAndRetriesOnlyTheUnconfirmedCancellation() {
        val retirements = SaveNotificationRetirements()
        val completed = linkedSetOf("first", "retry")
        val rendered = linkedMapOf("first" to 99, "retry" to 99)
        val attempts = mutableListOf<String>()

        assertThrows(IllegalStateException::class.java) {
            retirements.cancelMissing(completed, emptySet(), rendered.keys) { id ->
                attempts.add(id)
                if (id == "retry") error("Cancellation rejected")
            }
        }
        assertEquals(mapOf("retry" to 99), rendered)

        repeat(2) {
            retirements.cancelMissing(completed, emptySet(), rendered.keys, attempts::add)
        }
        assertEquals(listOf("first", "retry", "retry"), attempts)
        assertEquals(emptyMap<String, Int>(), rendered)
    }

    @Test fun reopenedOrAbsentJobDoesNotKeepAnOldCancellationReceipt() {
        for (between in listOf(setOf("same-id"), emptySet())) {
            val retirements = SaveNotificationRetirements()
            val rendered = mutableMapOf("same-id" to 99)
            val canceled = mutableListOf<String>()
            val completed = setOf("same-id")

            retirements.cancelMissing(completed, emptySet(), rendered.keys, canceled::add)
            retirements.cancelMissing(emptySet(), between, rendered.keys, canceled::add)
            if (between.isNotEmpty()) rendered["same-id"] = 12
            retirements.cancelMissing(completed, emptySet(), rendered.keys, canceled::add)

            assertEquals(listOf("same-id", "same-id"), canceled)
            assertEquals(emptyMap<String, Int>(), rendered)
        }
    }
}
