package app.smallthingz.reverb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DurableStringSetBoundsTest {
    @Test
    fun boundedJournal_returnsDetachedCopy() {
        val source = linkedSetOf("v1|a", "v1|b")
        val bounded = boundedDurableStringSet(source, "journal")

        source += "v1|c"

        assertEquals(setOf("v1|a", "v1|b"), bounded)
        assertFalse("v1|c" in bounded)
    }

    @Test
    fun boundedJournal_rejectsTooManyEntries() {
        val entries = (0..MAX_DURABLE_JOURNAL_ENTRIES).mapTo(linkedSetOf()) { "v1|$it" }

        val error = assertThrows(IllegalStateException::class.java) {
            boundedDurableStringSet(entries, "journal")
        }

        assertTrue(error.message.orEmpty().contains("entries"))
    }

    @Test
    fun boundedJournal_rejectsOversizedEntry() {
        val entries = setOf("x".repeat(MAX_DURABLE_JOURNAL_ENTRY_CHARS + 1))

        val error = assertThrows(IllegalStateException::class.java) {
            boundedDurableStringSet(entries, "journal")
        }

        assertTrue(error.message.orEmpty().contains("oversized entry"))
    }

    @Test
    fun boundedJournal_rejectsOversizedTotalPayload() {
        val entrySize = MAX_DURABLE_JOURNAL_ENTRY_CHARS
        val entryCount = MAX_DURABLE_JOURNAL_TOTAL_CHARS / entrySize + 1
        val entries = (0 until entryCount).mapTo(linkedSetOf()) { index ->
            index.toString().padEnd(entrySize, 'x')
        }

        val error = assertThrows(IllegalStateException::class.java) {
            boundedDurableStringSet(entries, "journal")
        }

        assertTrue(error.message.orEmpty().contains("total chars"))
    }
}
