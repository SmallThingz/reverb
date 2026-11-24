package app.smallthingz.reverb

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Test

class UnexpectedErrorIncidentDescriptionTest {
    @Test
    fun largePaddedMessageKeepsOnlyTheSameVisiblePrefix() {
        val error = IOException("  " + "x".repeat(1_000_000) + "  ")
        val prefix = "Failure: java.io.IOException: "
        assertEquals(prefix + "x".repeat(384 - prefix.length),
            unexpectedErrorIncidentDescription("Failure", error))
    }

    @Test
    fun fullSourceBudgetDoesNotReadDiscardedExceptionDetails() {
        var reads = 0
        val error = object : IOException() {
            override val message: String get() { reads++; return "unused" }
            override val cause: Throwable? get() { reads++; return null }
        }
        assertEquals("source", unexpectedErrorIncidentDescription("  source too long  ", error, 6))
        assertEquals(0, reads)
    }

    @Test
    fun fullMessageBudgetDoesNotWalkDiscardedCauses() {
        var causeReads = 0
        val error = object : IOException("x".repeat(1024)) {
            override val cause: Throwable? get() { causeReads++; return IOException("unused") }
        }
        assertEquals(384, unexpectedErrorIncidentDescription("Failure", error).length)
        assertEquals(0, causeReads)
    }

    @Test
    fun largeRootMessageAndWhitespaceKeepTheExistingPrefixAtEveryLimit() {
        val root = IOException(" \t" + "y".repeat(10_000) + "\n ")
        val error = IllegalStateException("  outer  ", root).apply { stackTrace = emptyArray() }
        val expected = "Failure: java.lang.IllegalStateException: outer; cause java.io.IOException: " + "y".repeat(1024)
        for (limit in 1..1024) {
            assertEquals("limit=$limit", expected.take(limit),
                unexpectedErrorIncidentDescription(" \tFailure\n", error, limit))
        }
    }
}
