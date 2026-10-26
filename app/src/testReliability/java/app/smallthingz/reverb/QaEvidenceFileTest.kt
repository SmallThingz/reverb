package app.smallthingz.reverb

import java.io.File
import java.io.IOException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class QaEvidenceFileTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun interruptedReplacementKeepsInitialIncompleteReceipt() {
        val target = File(temporary.root, "result.json")
        val initial = "{\"complete\":false,\"passed\":false}"
        writeQaEvidenceAtomically(target) { it.write(initial.toByteArray()) }
        val expected = IOException("injected partial write failure")
        try {
            writeQaEvidenceAtomically(target) {
                it.write("{\"complete\":".toByteArray())
                throw expected
            }
            fail("The failed write must propagate")
        } catch (error: IOException) { assertSame(expected, error) }
        assertEquals(initial, target.readText())
        assertEquals("{\"complete\":", File(temporary.root, "result.json.pending").readText())
    }

    @Test fun completedFailureReceiptSurvivesAnUncommittedReplacement() {
        val target = File(temporary.root, "result.json")
        val failed = "{\"complete\":true,\"passed\":false,\"terminal_error\":\"mismatch\"}"
        writeQaEvidenceAtomically(target) { it.write(failed.toByteArray()) }
        try {
            writeQaEvidenceAtomically(target) {
                it.write("replacement".toByteArray())
                throw IOException("not committed")
            }
            fail("The failed write must propagate")
        } catch (_: IOException) { }
        assertEquals(failed, target.readText())
    }

    @Test fun successfulReplacementPublishesOnceAndConsumesPendingFile() {
        val target = File(temporary.root, "result.json")
        writeQaEvidenceAtomically(target) { it.write("incomplete".toByteArray()) }
        var writes = 0
        val finished = "{\"complete\":true,\"dynamic_frame_phase_qualified\":false}"
        writeQaEvidenceAtomically(target) { writes++; it.write(finished.toByteArray()) }
        assertEquals(1, writes)
        assertEquals(finished, target.readText())
        assertFalse(File(temporary.root, "result.json.pending").exists())
    }

    @Test fun syncFailureCannotReplaceCanonicalReceipt() {
        val target = File(temporary.root, "result.json")
        writeQaEvidenceAtomically(target) { it.write("initial".toByteArray()) }
        try {
            writeQaEvidenceAtomically(target) {
                it.write("uncommitted".toByteArray())
                it.close() // The helper's required sync now fails on a closed descriptor.
            }
            fail("Sync failure must propagate")
        } catch (_: IOException) { }
        assertEquals("initial", target.readText())
        assertTrue(File(temporary.root, "result.json.pending").exists())
    }

    @Test fun failedAtomicMovePreservesExistingDestinationAndPendingEvidence() {
        val target = temporary.newFolder("result.json")
        val sentinel = File(target, "keep").apply { writeText("existing destination") }
        try {
            writeQaEvidenceAtomically(target) { it.write("pending evidence".toByteArray()) }
            fail("A nonempty directory must not be replaced by the receipt")
        } catch (_: IOException) { }
        assertTrue(target.isDirectory)
        assertEquals("existing destination", sentinel.readText())
        assertEquals("pending evidence", File(temporary.root, "result.json.pending").readText())
    }
}
