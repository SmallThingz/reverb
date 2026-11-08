package app.smallthingz.reverb

import android.content.SharedPreferences
import java.lang.reflect.Proxy
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

    @Test fun presentNullJournalFailsInsteadOfBecomingAuthoritativeEmpty() {
        val prefs = journalPreferences(present = true, value = null)
        assertThrows(IllegalStateException::class.java) {
            prefs.requireDurableStringSet(PrefKey.PENDING_OUTPUT_CLEANUP)
        }
    }

    @Test fun absentAndExplicitEmptyJournalsRemainEmpty() {
        assertEquals(emptySet<String>(), journalPreferences(false, null)
            .requireDurableStringSet(PrefKey.PENDING_OUTPUT_CLEANUP))
        assertEquals(emptySet<String>(), journalPreferences(true, emptySet<String>())
            .requireDurableStringSet(PrefKey.PENDING_OUTPUT_CLEANUP))
    }

    @Test fun presentWrongTypeAndOversizedJournalsStillFailClosed() {
        for (value in listOf("wrong-type", setOf("x".repeat(MAX_DURABLE_JOURNAL_ENTRY_CHARS + 1)))) {
            assertThrows(IllegalStateException::class.java) {
                journalPreferences(true, value).requireDurableStringSet(PrefKey.PENDING_OUTPUT_CLEANUP)
            }
        }
    }

    @Test fun operationalExportAuthorityDistinguishesAbsentFromNamedNull() {
        assertEquals(null, configuredExportTreePreferenceId(journalPreferences(false, null)))
        assertThrows(IllegalStateException::class.java) {
            configuredExportTreePreferenceId(journalPreferences(true, null))
        }
        assertEquals("content://docs/tree/root", configuredExportTreePreferenceId(
            journalPreferences(true, "content://docs/tree/root")))
    }

    @Test fun operationalExportAuthorityRejectsWrongTypeEmptyAndMalformedValues() {
        for (value in listOf(17, "", "content://docs/tree/root/document/child", "not a URI")) {
            assertThrows(IllegalStateException::class.java) {
                configuredExportTreePreferenceId(journalPreferences(true, value))
            }
        }
    }

    private fun journalPreferences(present: Boolean, value: Any?): SharedPreferences =
        Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "contains" -> present
                "getString" -> {
                    check(present) { "Absent authority must not invoke its getter" }
                    (value as String?) ?: args!![1] as String?
                }
                "getStringSet" -> {
                    check(present) { "Absent journal must not invoke its getter" }
                    // Android's nullable stored value returns the supplied default.
                    @Suppress("UNCHECKED_CAST")
                    ((value as Set<String>?) ?: args!![1] as Set<String>?)
                }
                else -> error("Unexpected preference operation ${method.name}")
            }
        } as SharedPreferences

}
