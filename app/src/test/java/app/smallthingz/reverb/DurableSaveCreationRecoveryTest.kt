package app.smallthingz.reverb

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class DurableSaveCreationRecoveryTest {
    private val session = "11111111-1111-4111-8111-111111111111"
    private val token = "22222222-2222-4222-8222-222222222222"

    @Test
    fun interruptedCreationCanProbeAbsenceForInlineCompactAndLegacyStagingNames() {
        val names = StagingOutputKind.entries.flatMap { kind ->
            listOf("saved.wav", "a".repeat(240) + ".wav").map { finalName ->
                stagingOutputName(finalName, token, session, kind)
            }
        } + listOf("export", "export-v2", "copy").map { kind ->
            "reverb-partial-${kind}__${session}__${token}__c2F2ZWQud2F2.wav"
        }
        for (name in names) {
            var probes = 0
            assertTrue(name, durableSaveCreationAttemptIsAbsent(name) { probes++; true })
            assertEquals(name, 1, probes)
            // Admitting an absence query must never make staging discoverable as saved audio.
            assertFalse(name, isDirectChildRecordingName(name))
            assertFalse(name, isDiscoverableFileRecordingName(name))
        }
    }

    @Test
    fun presentAndUnavailableCreationAttemptsNeverBecomeAbsent() {
        val name = stagingOutputName("saved.wav", token, session)
        assertFalse(durableSaveCreationAttemptIsAbsent(name) { false })
        val unavailable = IOException("provider membership unavailable")
        val failure = assertThrows(IOException::class.java) {
            durableSaveCreationAttemptIsAbsent(name) { throw unavailable }
        }
        assertSame(unavailable, failure)
    }

    @Test
    fun malformedCreationNamesAreRejectedBeforeAnyStorageProbe() {
        val name = stagingOutputName("saved.wav", token, session)
        val invalid = listOf("", " ", ".", "..", "saved.wav", "/$name", "../$name",
            "$name/child", "$name\\child", "$name\u0000", "$name\uD800", "$name\uDC00")
        for (candidate in invalid) {
            var probes = 0
            assertThrows(candidate, IOException::class.java) {
                durableSaveCreationAttemptIsAbsent(candidate) { probes++; true }
            }
            assertEquals(candidate, 0, probes)
        }
    }
}
