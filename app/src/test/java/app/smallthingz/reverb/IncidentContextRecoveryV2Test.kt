package app.smallthingz.reverb

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.ContextWrapper
import android.os.Process
import android.provider.Settings
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowActivityManager
import org.robolectric.shadows.ShadowProcess

/** Actual persistent recovery with Android exit evidence controlled only at ActivityManager. */
@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [JournalHostIoShadow::class])
class IncidentContextRecoveryV2Test {
    // Independently assembled published big-endian wire fixtures (Python struct).
    private val priorSession = hex("524953310301000000290000000000000000000000000000006400000000000000000000000000000007ffffffff")
    private val sentinel = hex("02000000000000025800000000000002bc0000000000000320000000030000000400000063000000640000000000000065000000000000006600000000000001900000000000000000000873656e74696e656c0000000000000000")
    private val deleted = hex("0100000000000000960000000000000000800000000000000000000000800000000000002980000000ffffffffffffffffffffffffffffffff00000000000000640000000000000064000764656c657465640000000000000000")
    private val expectedEnrichedHistory = hex("5249483104000201000000000000009600000000000000000000000000000000000000040000001100000029000000640000000000000065000000000000006600000000000000640000000000000064000a6c6174652d6372617368000000000000000002000000000000025800000000000002bc0000000000000320000000030000000400000063000000640000000000000065000000000000006600000000000001900000000000000000000873656e74696e656c0000000000000000")

    @Test fun observationEndAfterVisibleBarrierFailureRetainsAcknowledgementAndSignalsDurableRevision() = fixture { context, root ->
        val duration = CaptureObservationDuration(1_000L)
        try {
            assertTrue(RecordingIncidentStore.recordCaptureInputObservation(
                context, 10_000L, CaptureInputObservationReason.POLICY_SILENCED, duration))
            val opened = awaitOpenObservation(context, duration)
            RecordingIncidentStore.toggleIncidentAcknowledged(context, opened)
            val revision = RecordingIncidentStore.historyRevision.value
            val barriers = JournalHostIoShadow.directorySyncAttempts
            JournalHostIoShadow.failDirectorySyncs = 1
            duration.finish(3_250L)
            awaitHistoryWriters()

            val raw = wireRows(File(root, "recording-incidents.bin").readBytes()).single()
            assertEquals(12_250L, ByteBuffer.wrap(raw).getLong(9))
            assertEquals(opened.observationId, ByteBuffer.wrap(raw).getLong(raw.size - 8))
            assertTrue(ByteBuffer.wrap(raw).getLong(17) > 0)
            assertTrue(JournalHostIoShadow.directorySyncAttempts >= barriers + 2)
            assertTrue(RecordingIncidentStore.historyRevision.value >= revision + 2)
            val closed = RecordingIncidentStore.readIncidents(context).single()
            assertNull(closed.ongoingPolicyDuration)
            assertEquals(2_250L, recordingIncidentDowntimeMillis(closed))
        } finally {
            duration.finish(-1L)
            awaitHistoryWriters()
        }
    }

    @Test fun exactObservationEndCannotReinsertDeletedOrEvictedHistory() = fixture { context, root ->
        val deletedDuration = CaptureObservationDuration(1_000L)
        val evictedDuration = CaptureObservationDuration(2_000L)
        val file = File(root, "recording-incidents.bin")
        try {
            assertTrue(RecordingIncidentStore.recordCaptureInputObservation(
                context, 10_000L, CaptureInputObservationReason.POLICY_SILENCED, deletedDuration))
            val deleted = awaitOpenObservation(context, deletedDuration)
            RecordingIncidentStore.deleteIncidentInBackground(context, deleted)
            awaitCondition { RecordingIncidentStore.readIncidents(context).none { it.observationId == deleted.observationId } }
            assertTrue(RecordingIncidentStore.recordCaptureInputObservation(
                context, 10_001L, CaptureInputObservationReason.POLICY_SILENCED, evictedDuration))
            val evicted = awaitOpenObservation(context, evictedDuration)
            // Reproduce the history remaining after this exact row fell out of the bounded list.
            val remaining = java.io.DataInputStream(file.inputStream()).use(RecordingIncidentStore::readHistoryPayload)
                .filterNot { it.observationId == evicted.observationId }
            java.io.DataOutputStream(file.outputStream()).use { RecordingIncidentStore.writeHistoryPayload(it, remaining) }
            val expectedBytes = file.readBytes()

            deletedDuration.finish(4_000L)
            evictedDuration.finish(4_001L)
            awaitHistoryWriters()
            assertTrue(RecordingIncidentStore.readIncidents(context).isEmpty())
            assertArrayEquals(expectedBytes, file.readBytes())
        } finally {
            deletedDuration.finish(-1L)
            evictedDuration.finish(-1L)
            awaitHistoryWriters()
        }
    }

    @Test fun alreadyEndedObservationSurvivesUncertainFirstPublicationAsOneRow() = fixture { context, root ->
        assertTrue(RecordingIncidentStore.readIncidents(context).isEmpty())
        val duration = CaptureObservationDuration(2_000L)
        synchronized(RecordingIncidentStore) {
            JournalHostIoShadow.failDirectorySyncs = 1
            assertTrue(RecordingIncidentStore.recordCaptureInputObservation(
                context, 10_000L, CaptureInputObservationReason.POLICY_SILENCED, duration))
            duration.finish(3_000L)
        }
        awaitHistoryWriters()

        val closed = RecordingIncidentStore.readIncidents(context).single()
        assertNull(closed.ongoingPolicyDuration)
        assertEquals(1_000L, recordingIncidentDowntimeMillis(closed))
        val raw = wireRows(File(root, "recording-incidents.bin").readBytes()).single()
        assertEquals(11_000L, ByteBuffer.wrap(raw).getLong(9))
        assertEquals(closed.observationId, ByteBuffer.wrap(raw).getLong(raw.size - 8))
    }

    @Test fun orphanedV4OngoingRecordShowsUnknownWithoutRewritingHistory() = fixture { context, root ->
        val file = File(root, "recording-incidents.bin")
        val orphan = RecordingIncident(10_000L, resumedAtMillis = 0L,
            kind = RecordingIncidentKind.CAPTURE_INPUT_OBSERVATION, pid = 41,
            processStartedAtMillis = 7L, observationId = 1L,
            description = CaptureInputObservationReason.POLICY_SILENCED.description)
        java.io.DataOutputStream(file.outputStream()).use { RecordingIncidentStore.writeHistoryPayload(it, listOf(orphan)) }
        val original = file.readBytes()
        val loaded = RecordingIncidentStore.readIncidents(context).single()
        assertEquals(-1L, loaded.resumedAtMillis)
        assertNull(loaded.ongoingPolicyDuration)
        assertEquals(recordingIncidentPresentationKey(orphan), recordingIncidentPresentationKey(loaded))
        assertTrue(formatIncidentSummary(loaded).endsWith("duration unknown"))
        assertArrayEquals(original, file.readBytes())
    }

    @Test fun priorArmedProcessPublishesPendingAndProvisionalHistoryBeforeRetiringMarker() = fixture { context, root ->
        seed(root)
        val before = System.currentTimeMillis()
        RecordingIncidentStore.recoverPriorSessionIfNeeded(context)
        val after = System.currentTimeMillis()
        assertFalse(File(root, "recording-session.bin").exists())
        val pending = File(root, "recording-incident-pending.bin").readBytes()
        assertArrayEquals(hex("52495031030001"), pending.copyOfRange(0, 7))
        assertEquals(64, pending.size)
        assertArrayEquals(priorSession.copyOfRange(5, 46), pending.copyOfRange(7, 48))
        assertEquals(0L, ByteBuffer.wrap(pending).getLong(48))
        assertTrue(ByteBuffer.wrap(pending).getLong(56) > 150L)
        val history = File(root, "recording-incidents.bin").readBytes()
        val rows = wireRows(history)
        assertEquals(2, rows.size)
        assertArrayEquals(sentinel, rows.single { wirePid(it) == 99 })
        val provisional = rows.single { wirePid(it) == 41 }
        assertEquals(1, provisional[0].toInt())
        assertTrue(ByteBuffer.wrap(provisional).getLong(1) in before..after)
        assertEquals(0L, ByteBuffer.wrap(provisional).getLong(9))
        assertEquals(0, ByteBuffer.wrap(provisional).getInt(25))
        assertEquals(100L, ByteBuffer.wrap(provisional).getLong(57))
        assertEquals(100L, ByteBuffer.wrap(provisional).getLong(65))
        assertEquals("Recording process ended while capture was running; exit evidence pending", wireDescription(provisional))
        repeat(2) { RecordingIncidentStore.recoverPriorSessionIfNeeded(context) }
        assertArrayEquals(pending, File(root, "recording-incident-pending.bin").readBytes())
        assertArrayEquals(history, File(root, "recording-incidents.bin").readBytes())
        assertEquals(setOf("recording-incidents.bin", "recording-incident-pending.bin"), root.list()!!.toSet())
    }

    @Test fun delayedExitEnrichesOneExactSessionAndRetiresPendingOnlyAfterHistoryPublication() = fixture { context, root ->
        seed(root)
        RecordingIncidentStore.recoverPriorSessionIfNeeded(context)
        addLateCrash(context)
        RecordingIncidentStore.recoverPriorSessionIfNeeded(context)
        assertArrayEquals(expectedEnrichedHistory, File(root, "recording-incidents.bin").readBytes())
        assertFalse(File(root, "recording-incident-pending.bin").exists())
        assertFalse(File(root, "recording-session.bin").exists())
        val visible = RecordingIncidentStore.readIncidents(context)
        assertEquals(2, visible.size)
        val selected = visible.single { it.pid == 41 }
        assertEquals(100L, selected.captureArmedAtMillis)
        assertEquals(100L, selected.processStartedAtMillis)
        assertEquals(ApplicationExitInfo.REASON_CRASH, selected.exitReason)
        assertEquals("late-crash", selected.description)
        repeat(2) { RecordingIncidentStore.recoverPriorSessionIfNeeded(context) }
        assertArrayEquals(expectedEnrichedHistory, File(root, "recording-incidents.bin").readBytes())
    }

    @Test fun durableDeletedSessionStaysHiddenWhenRealPendingRecoveryFindsLateExitEvidence() = fixture { context, root ->
        File(root, "recording-session.bin").writeBytes(priorSession)
        File(root, "recording-incidents.bin").writeBytes(history(deleted, sentinel))
        RecordingIncidentStore.recoverPriorSessionIfNeeded(context)
        val provisional = wireRows(File(root, "recording-incidents.bin").readBytes()).single { wirePid(it) == 41 }
        assertEquals(Long.MIN_VALUE, ByteBuffer.wrap(provisional).getLong(17))
        addLateCrash(context)
        RecordingIncidentStore.recoverPriorSessionIfNeeded(context)
        val rows = wireRows(File(root, "recording-incidents.bin").readBytes())
        assertEquals(2, rows.size)
        assertArrayEquals(sentinel, rows.single { wirePid(it) == 99 })
        val tombstone = rows.single { wirePid(it) == 41 }
        assertEquals(Long.MIN_VALUE, ByteBuffer.wrap(tombstone).getLong(17))
        assertEquals(100L, ByteBuffer.wrap(tombstone).getLong(57))
        assertEquals(100L, ByteBuffer.wrap(tombstone).getLong(65))
        assertEquals(ApplicationExitInfo.REASON_CRASH, ByteBuffer.wrap(tombstone).getInt(25))
        assertFalse(File(root, "recording-incident-pending.bin").exists())
        assertEquals(listOf(99), RecordingIncidentStore.readIncidents(context).map { it.pid })
    }

    @Test fun actualFullPendingQueueRejectsRecoveryWithoutDisarmingOrDroppingAnyExistingOwner() = fixture { context, root ->
        seed(root)
        val pending = fullPendingQueue()
        File(root, "recording-incident-pending.bin").writeBytes(pending)
        val originalHistory = File(root, "recording-incidents.bin").readBytes()
        assertThrows(IOException::class.java) { RecordingIncidentStore.recoverPriorSessionIfNeeded(context) }
        assertArrayEquals(priorSession, File(root, "recording-session.bin").readBytes())
        assertArrayEquals(pending, File(root, "recording-incident-pending.bin").readBytes())
        assertArrayEquals(originalHistory, File(root, "recording-incidents.bin").readBytes())
        assertEquals(setOf("recording-session.bin", "recording-incidents.bin", "recording-incident-pending.bin"), root.list()!!.toSet())
    }

    @Test fun unavailableHistoryAfterPendingPublicationKeepsArmedEvidenceUntilActualRetrySucceeds() = fixture { context, root ->
        seed(root)
        val historyFile = File(root, "recording-incidents.bin")
        val originalHistory = historyFile.readBytes()
        val blocker = File(historyFile.path + ".new").apply { check(mkdir()) }
        assertThrows(IOException::class.java) { RecordingIncidentStore.recoverPriorSessionIfNeeded(context) }
        assertArrayEquals(priorSession, File(root, "recording-session.bin").readBytes())
        assertArrayEquals(originalHistory, historyFile.readBytes())
        assertTrue(blocker.isDirectory)
        val pendingFile = File(root, "recording-incident-pending.bin")
        val pending = pendingFile.readBytes()
        assertArrayEquals(hex("52495031030001"), pending.copyOfRange(0, 7))
        assertEquals(64, pending.size)
        assertArrayEquals(priorSession.copyOfRange(5, 46), pending.copyOfRange(7, 48))
        check(blocker.delete())
        RecordingIncidentStore.recoverPriorSessionIfNeeded(context)
        assertFalse(File(root, "recording-session.bin").exists())
        assertArrayEquals(pending, pendingFile.readBytes())
        val rows = wireRows(historyFile.readBytes())
        assertEquals(2, rows.size)
        assertArrayEquals(sentinel, rows.single { wirePid(it) == 99 })
        assertEquals(1, rows.count { wirePid(it) == 41 })
    }

    @Test @Config(sdk = [29]) fun preAndroidElevenRecoveryPublishesGenericIncidentWithoutInventingPendingEvidence() = fixture { context, root ->
        seed(root)
        RecordingIncidentStore.recoverPriorSessionIfNeeded(context)
        assertFalse(File(root, "recording-session.bin").exists())
        assertFalse(File(root, "recording-incident-pending.bin").exists())
        val rows = wireRows(File(root, "recording-incidents.bin").readBytes())
        assertArrayEquals(sentinel, rows.single { wirePid(it) == 99 })
        val generic = rows.single { wirePid(it) == 41 }
        assertEquals(100L, ByteBuffer.wrap(generic).getLong(57))
        assertEquals(100L, ByteBuffer.wrap(generic).getLong(65))
        assertEquals(0, ByteBuffer.wrap(generic).getInt(25))
        assertEquals("Recording process ended while capture was running", wireDescription(generic))
        val completed = File(root, "recording-incidents.bin").readBytes()
        RecordingIncidentStore.recoverPriorSessionIfNeeded(context)
        assertArrayEquals(completed, File(root, "recording-incidents.bin").readBytes())
    }

    @Test fun observedBootBoundaryPublishesOneSyntheticIncidentAndRetiresPriorArmedOwner() = fixture { context, root ->
        seed(root)
        val priorBoot = priorSession.copyOf().also { ByteBuffer.wrap(it).putInt(42, 9) }
        File(root, "recording-session.bin").writeBytes(priorBoot)
        assertTrue(Settings.Global.putInt(context.contentResolver, Settings.Global.BOOT_COUNT, 10))
        RecordingIncidentStore.recoverPriorSessionIfNeeded(context)
        assertFalse(File(root, "recording-session.bin").exists())
        assertFalse(File(root, "recording-incident-pending.bin").exists())
        val rows = wireRows(File(root, "recording-incidents.bin").readBytes())
        assertArrayEquals(sentinel, rows.single { wirePid(it) == 99 })
        val reboot = rows.single { wirePid(it) == 41 }
        assertEquals(100L, ByteBuffer.wrap(reboot).getLong(57))
        assertEquals(100L, ByteBuffer.wrap(reboot).getLong(65))
        assertEquals("Device restarted while capture was running", wireDescription(reboot))
        val completed = File(root, "recording-incidents.bin").readBytes()
        RecordingIncidentStore.recoverPriorSessionIfNeeded(context)
        assertArrayEquals(completed, File(root, "recording-incidents.bin").readBytes())
    }

    private fun seed(root: File) {
        File(root, "recording-session.bin").writeBytes(priorSession)
        File(root, "recording-incidents.bin").writeBytes(history(sentinel))
    }

    private fun awaitOpenObservation(context: Context, duration: CaptureObservationDuration): RecordingIncident {
        var seen: RecordingIncident? = null
        awaitCondition {
            seen = RecordingIncidentStore.readIncidents(context).firstOrNull { it.ongoingPolicyDuration === duration }
            seen != null
        }
        return checkNotNull(seen)
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Observation did not settle in isolated history" }
            Thread.sleep(2)
        }
    }

    private fun awaitHistoryWriters() {
        val field = RecordingIncidentStore::class.java.getDeclaredField("historyMutationScope").apply { isAccessible = true }
        val scope = field.get(RecordingIncidentStore) as CoroutineScope
        runBlocking { withTimeout(8_000) {
            checkNotNull(scope.coroutineContext[Job]).children.toList().forEach { it.join() }
        } }
    }

    private fun addLateCrash(context: Context) {
        val manager = context.getSystemService(ActivityManager::class.java)
        val shadow = Shadow.extract<ShadowActivityManager>(manager)
        val exit = ShadowActivityManager.ApplicationExitInfoBuilder.newBuilder()
            .setPid(41).setProcessName(context.packageName).setReason(ApplicationExitInfo.REASON_CRASH)
            .setTimestamp(150L).setStatus(17).setImportance(100).setPss(101L).setRss(102L)
            .setDescription("late-crash").build()
        shadow.addApplicationExitInfo(exit)
    }

    private fun fullPendingQueue(): ByteArray {
        val owner = hex("01000000290000000000000000000000000000006400000000000000000000000000000007ffffffff000000000000000000000000000003e8")
        return ByteArray(7 + 128 * owner.size).also { bytes ->
            hex("52495031030080").copyInto(bytes)
            repeat(128) { index ->
                val offset = 7 + index * owner.size
                owner.copyInto(bytes, offset)
                ByteBuffer.wrap(bytes).putInt(offset + 1, 10000 + index)
            }
        }
    }

    private fun history(vararg records: ByteArray): ByteArray =
        hex("5249483104") + byteArrayOf(0, records.size.toByte()) + records.fold(byteArrayOf()) { bytes, record -> bytes + record }

    // Fixed published v4 field positions, with assertions; no production decoder is used.
    private fun wireRows(bytes: ByteArray): List<ByteArray> {
        assertArrayEquals(hex("5249483104"), bytes.copyOfRange(0, 5))
        val count = ByteBuffer.wrap(bytes).getShort(5).toInt() and 0xffff
        val rows = mutableListOf<ByteArray>()
        var offset = 7
        repeat(count) {
            val descriptionBytes = ByteBuffer.wrap(bytes).getShort(offset + 73).toInt() and 0xffff
            val end = offset + 83 + descriptionBytes
            rows += bytes.copyOfRange(offset, end)
            offset = end
        }
        assertEquals(bytes.size, offset)
        return rows
    }
    private fun wirePid(record: ByteArray): Int = ByteBuffer.wrap(record).getInt(33)
    private fun wireDescription(record: ByteArray): String =
        record.copyOfRange(75, record.size - 8).toString(Charsets.US_ASCII)
    private fun hex(value: String): ByteArray = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private class IsolatedContext(base: Context, private val root: File) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir(): File = root
    }

    private fun fixture(test: (Context, File) -> Unit) {
        val root = Files.createTempDirectory("incident-recovery-v2-").toFile()
        val previousPid = Process.myPid()
        val previousRoot = ServiceStateOsShadow.fixtureRoot
        var ownsFixture = false
        var failure: Throwable? = null
        fun record(error: Throwable) {
            val first = failure
            if (first == null) failure = error else if (first !== error) first.addSuppressed(error)
        }
        fun cleanup(action: () -> Unit) { try { action() } catch (error: Throwable) { record(error) } }
        try {
            check(previousRoot == null)
            JournalHostIoShadow.reset()
            ownsFixture = true
            ShadowProcess.setPid(4242)
            ServiceStateOsShadow.fixtureRoot = root.toPath()
            test(IsolatedContext(RuntimeEnvironment.getApplication(), root), root)
        } catch (error: Throwable) { failure = error }
        finally {
            if (ownsFixture) cleanup { JournalHostIoShadow.finishFixture()?.let(::record) }
            cleanup { ServiceStateOsShadow.fixtureRoot = previousRoot }
            cleanup { ShadowProcess.setPid(previousPid) }
            if (failure == null) cleanup { check(root.deleteRecursively()) }
        }
        failure?.let { throw it }
    }
}
