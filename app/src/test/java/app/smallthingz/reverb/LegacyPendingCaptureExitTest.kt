package app.smallthingz.reverb

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.ContextWrapper
import android.util.AtomicFile
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowActivityManager

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [JournalHostIoShadow::class])
class LegacyPendingCaptureExitTest {
    // RIP1 v1, count 1, armed PID 42, start elapsed 10, armed wall 150,
    // armed elapsed 60, package update 1, resume 700. No boot count or end bound.
    private val releasedQueue = ("52495031010001010000002a" +
        "000000000000000a0000000000000096000000000000003c000000000000000100000000000002bc")
        .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private val detector = RecordingIncident(occurredAtMillis = 300L, resumedAtMillis = 700L,
        acknowledgedAtMillis = 800L, pid = 42, processStartedAtMillis = 100L, captureArmedAtMillis = 150L,
        description = "Recording process ended while capture was running; exit evidence pending")

    @Test fun releasedOwnerRejectsReusedPidThenEnrichesOneOriginalCard() = fixture {
        val original = seed(detector)
        addExit(500L)
        recover()
        val owner = DataInputStream(pending.inputStream()).use { RecordingIncidentStore.readPendingSessionsPayload(it).single() }
        assertEquals(300L, owner.endedByMillis)
        assertEquals(700L, owner.resumedAtMillis)
        assertArrayEquals(original, history.readBytes())

        addExit(200L)
        recover()
        assertFalse(pending.exists())
        val result = DataInputStream(history.inputStream()).use { RecordingIncidentStore.readHistoryPayload(it).single() }
        assertEquals(200L, result.occurredAtMillis)
        assertEquals(700L, result.resumedAtMillis)
        assertEquals(800L, result.acknowledgedAtMillis)
        assertEquals(100L, result.processStartedAtMillis)
        assertEquals(150L, result.captureArmedAtMillis)
        assertEquals(ApplicationExitInfo.REASON_CRASH, result.exitReason)
        assertEquals("exit at 200", result.description)
    }

    @Test fun unsupportedHistoryLeavesReleasedOwnerAndHistoryIntact() = fixture {
        addExit(200L)
        for (rows in listOf(emptyList(), listOf(detector.copy(description = "Capture service stopped")),
                listOf(detector.copy(occurredAtMillis = 150L)), listOf(detector.copy(pid = 43)),
                listOf(detector.copy(processStartedAtMillis = -1L)), listOf(detector.copy(processStartedAtMillis = 99L)),
                listOf(detector.copy(captureArmedAtMillis = 149L)), listOf(detector.copy(exitStatus = 0)),
                listOf(detector.copy(rssKb = 0)), listOf(detector, detector.copy(occurredAtMillis = 301L)))) {
            val original = seed(*rows.toTypedArray())
            recover()
            assertArrayEquals(original, history.readBytes())
            assertArrayEquals(releasedQueue, pending.readBytes())
        }
    }

    @Test fun rewrittenZeroCannotBorrowANewerDetectorCard() = fixture {
        val original = seed(detector)
        val owners = DataInputStream(releasedQueue.inputStream()).use(RecordingIncidentStore::readPendingSessionsPayload)
        DataOutputStream(pending.outputStream()).use { RecordingIncidentStore.writePendingSessionsPayload(it, owners) }
        val rewritten = pending.readBytes()
        addExit(200L)
        recover()
        assertArrayEquals(rewritten, pending.readBytes())
        assertArrayEquals(original, history.readBytes())
    }

    @Test fun visibleMigrationAfterFailedSyncRequiresProofBeforeUsingItsBound() = fixture {
        val original = seed(detector)
        addExit(200L)
        JournalHostIoShadow.failDirectorySyncs = 1
        assertThrows(IOException::class.java) { recover() }
        val visible = pending.readBytes()
        assertEquals(3, visible[4].toInt())
        assertEquals(300L, DataInputStream(pending.inputStream()).use { RecordingIncidentStore.readPendingSessionsPayload(it).single().endedByMillis })
        assertArrayEquals(original, history.readBytes())

        JournalHostIoShadow.failDirectorySyncs = 1
        assertThrows(IOException::class.java) { recover() }
        assertArrayEquals(visible, pending.readBytes())
        assertArrayEquals(original, history.readBytes())
        recover()
        assertFalse(pending.exists())
        assertEquals("exit at 200", DataInputStream(history.inputStream()).use { RecordingIncidentStore.readHistoryPayload(it).single().description })
    }

    @Test fun queueProofCachesOnlyTheConfirmedPhysicalRevisionAfterExactEof() = fixture {
        seed(detector)
        val read = RecordingIncidentStore::class.java.getDeclaredMethod("readPendingSessions", AtomicFile::class.java)
            .apply { isAccessible = true }
        fun readQueue() {
            try { read.invoke(RecordingIncidentStore, AtomicFile(pending)) }
            catch (error: InvocationTargetException) { throw error.targetException }
        }
        readQueue() // v1 to v3 migration
        readQueue() // confirm the actual v3 object
        val attempts = JournalHostIoShadow.directorySyncAttempts
        JournalHostIoShadow.failDirectorySyncs = 1
        readQueue()
        assertEquals(attempts, JournalHostIoShadow.directorySyncAttempts)

        val valid = pending.readBytes()
        val replacement = File(root, "replacement")
        replacement.writeBytes(valid + byteArrayOf(1))
        Files.move(replacement.toPath(), pending.toPath(), StandardCopyOption.REPLACE_EXISTING)
        assertThrows(IOException::class.java) { readQueue() }
        assertEquals(attempts, JournalHostIoShadow.directorySyncAttempts)
        pending.writeBytes(valid)
        assertThrows(IOException::class.java) { readQueue() }
        assertEquals(attempts + 1, JournalHostIoShadow.directorySyncAttempts)
        readQueue()
        assertEquals(attempts + 2, JournalHostIoShadow.directorySyncAttempts)
    }

    private inner class Fixture(base: Context, val root: File) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir(): File = root
        val pending = File(root, "recording-incident-pending.bin")
        val history = File(root, "recording-incidents.bin")

        fun recover() = RecordingIncidentStore.recoverPriorSessionIfNeeded(this)

        fun seed(vararg cards: RecordingIncident): ByteArray {
            pending.writeBytes(releasedQueue)
            // Released history v2 layout, including absent Android field sentinels.
            DataOutputStream(history.outputStream()).use { out ->
                out.writeInt(0x52494831)
                out.writeByte(2)
                out.writeShort(cards.size)
                cards.forEach { card ->
                    out.writeByte(card.kind.storageCode.toInt())
                    out.writeLong(card.occurredAtMillis)
                    out.writeLong(card.resumedAtMillis)
                    out.writeLong(card.acknowledgedAtMillis)
                    out.writeInt(card.exitReason)
                    out.writeInt(card.exitStatus)
                    out.writeInt(card.pid)
                    out.writeInt(card.importance)
                    out.writeLong(card.pssKb)
                    out.writeLong(card.rssKb)
                    out.writeLong(card.processStartedAtMillis)
                    out.writeLong(card.captureArmedAtMillis)
                    out.writeUTF(card.description.orEmpty())
                }
            }
            return history.readBytes()
        }

        fun addExit(time: Long) {
            val manager = getSystemService(ActivityManager::class.java)
            Shadow.extract<ShadowActivityManager>(manager).addApplicationExitInfo(
                ShadowActivityManager.ApplicationExitInfoBuilder.newBuilder().setPid(42).setProcessName(packageName)
                    .setReason(ApplicationExitInfo.REASON_CRASH).setStatus(7).setTimestamp(time)
                    .setDescription("exit at $time").build())
        }
    }

    private fun fixture(run: Fixture.() -> Unit) {
        check(ServiceStateOsShadow.fixtureRoot == null)
        JournalHostIoShadow.reset()
        val root = Files.createTempDirectory("legacy-capture-exit-").toFile()
        ServiceStateOsShadow.fixtureRoot = root.toPath()
        try {
            Fixture(RuntimeEnvironment.getApplication(), root).run()
        } finally {
            val leak = JournalHostIoShadow.finishFixture()
            ServiceStateOsShadow.fixtureRoot = null
            check(root.deleteRecursively())
            leak?.let { throw it }
        }
    }
}
