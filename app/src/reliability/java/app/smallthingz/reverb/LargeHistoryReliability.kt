package app.smallthingz.reverb

import android.content.Context
import android.os.SystemClock
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.zip.CRC32

/** Sparse synthetic audio only. Never points at either of the installed app's real stores. */
internal fun measureLargeHistory(context: Context, report: StringBuilder) {
    check(context.packageName.endsWith(".reliability"))
    val root = File(context.filesDir, "performance-012-large")
    val count = 16_384
    val payloadBytes = 1_048_576
    ensureSparseHistoryFixture(root, count, payloadBytes)
    repeat(3) { iteration ->
        val beforeIo = processReadBytes()
        val start = SystemClock.elapsedRealtimeNanos()
        val cpu = android.os.Debug.threadCpuTimeNanos()
        val store = PersistentAudioChunkStore(root)
        store.configure(RetentionMode.SIZE, Long.MAX_VALUE, 48_000, 1, PcmSampleFormat.PCM_16,
            deferRetentionCleanup = true)
        val snapshot = store.peekSnapshot()!!
        val loaded = SystemClock.elapsedRealtimeNanos()
        val cpuElapsed = android.os.Debug.threadCpuTimeNanos() - cpu
        check(snapshot.chunkCount == count && snapshot.filledBytes == count.toLong() * payloadBytes)
        val rangeStart = SystemClock.elapsedRealtimeNanos()
        val lease = requireNotNull(store.acquireRange(0.0, store.durationSeconds()))
        val rangeReady = SystemClock.elapsedRealtimeNanos()
        lease.close()
        val rangeClosed = SystemClock.elapsedRealtimeNanos()
        report.append("MEASURE history iteration=$iteration chunks=$count logical_bytes=${snapshot.filledBytes}")
            .append(" load_ms=${(loaded-start)/1_000_000.0} cpu_ms=${cpuElapsed/1_000_000.0}")
            .append(" range_ms=${(rangeReady-rangeStart)/1_000_000.0} release_ms=${(rangeClosed-rangeReady)/1_000_000.0}")
            .append(" read_bytes=${processReadBytes()-beforeIo}\n")
        store.close()
    }
    measureRetiredStartup(context, report)
}

/** Run only on a freshly installed throwaway QA package; never clears an existing store. */
internal fun prepareLargeHistoryUi(context: Context) {
    check(context.packageName.endsWith(".reliability"))
    val root = File(context.noBackupFilesDir, BUFFER_CACHE_FOLDER_NAME)
    check(!root.exists()) { "UI fixture requires a fresh QA install; existing history was left untouched" }
    ensureSparseHistoryFixture(root, 16_384, 1_048_576)
    val configuration = RetentionConfiguration(RetentionMode.SIZE, 0L, 0L, 180_000L, 17_179_869_184L)
    check(writeRetentionRecoveryConfiguration(context, configuration))
    val preferences = getRecorderPreferences(context)
    check(restoreRetentionConfigurationToPreferences(preferences, configuration))
    check(preferences.edit()
        .putBoolean(PrefKey.ONBOARDING_SHOWN, true)
        .putBoolean(PrefKey.AUDIO_MEMORY_ENABLED, false)
        .putInt(PrefKey.CAPTURE_BUFFER_SLOT, ReverbService.BufferSlot.LOOPING.storageCode.toInt())
        .putInt(PrefKey.CHANNEL_MODE, ChannelMode.MONO.storageCode.toInt())
        .putInt(PrefKey.PCM_SAMPLE_FORMAT, PcmSampleFormat.PCM_16.storageCode.toInt())
        .putInt(PrefKey.SAMPLE_RATE, 48_000)
        .commit())
}

private fun ensureSparseHistoryFixture(root: File, count: Int, payloadBytes: Int) {
    val chunks = File(root, "chunks")
    if (!File(root, "fixture-ready").isFile) {
        check(!root.exists()) { "Incomplete fixture retained for inspection" }
        check(chunks.mkdirs())
        val crc = CRC32().apply { update(ByteArray(payloadBytes)) }.value.toInt()
        repeat(count) { id ->
            RandomAccessFile(File(chunks, id.toString()), "rw").use { file ->
                file.write(fixtureChunkHeader(id, payloadBytes, crc))
                file.setLength(128L + payloadBytes)
            }
        }
        File(root, "fixture-ready").writeText("$count:$payloadBytes")
    }
    check(File(root, "fixture-ready").readText() == "$count:$payloadBytes")
}

private fun measureRetiredStartup(context: Context, report: StringBuilder) {
    repeat(3) { iteration ->
        val retiredRoot = File(context.filesDir, "performance-012-retired-${UUID.randomUUID()}")
        val retiredChunks = File(retiredRoot, "chunks").apply { check(mkdirs()) }
        val retiredMarkers = File(retiredRoot, "retired").apply { check(mkdir()) }
        val retiredCount = 512
        val crc = CRC32().apply { update(ByteArray(128)) }.value.toInt()
        repeat(retiredCount) { id ->
            File(retiredChunks, id.toString()).writeBytes(fixtureChunkHeader(id, 128, crc) + ByteArray(128))
            File(retiredMarkers, id.toString()).writeText("v2|$id|${fixtureCreatedAt(id)}|48000|1|2")
        }
        val start = SystemClock.elapsedRealtimeNanos()
        var loaded = start
        var maintenanceSteps = 0
        PersistentAudioChunkStore(retiredRoot).use { store ->
            store.configure(RetentionMode.SIZE, Long.MAX_VALUE, 48_000, 1, PcmSampleFormat.PCM_16,
                deferRetentionCleanup = true)
            check(!store.hasData()) { "Retired audio resurrected" }
            loaded = SystemClock.elapsedRealtimeNanos()
            while (store.retentionMaintenanceNeeded()) {
                check(++maintenanceSteps <= retiredCount)
                check(store.performRetentionMaintenanceStep().progressed)
            }
        }
        report.append("MEASURE retired iteration=$iteration chunks=$retiredCount load_ms=${(loaded-start)/1_000_000.0}")
            .append(" maintenance_ms=${(SystemClock.elapsedRealtimeNanos()-loaded)/1_000_000.0} maintenance_steps=$maintenanceSteps\n")
        check(retiredRoot.deleteRecursively()) // Only this newly created synthetic fixture.
    }
}

private fun fixtureCreatedAt(id: Int): Long = 1_790_000_000_000L + id * 11_000L

private fun fixtureChunkHeader(id: Int, payloadBytes: Int, payloadCrc: Int): ByteArray {
    val bytes = ByteArray(128)
    val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    b.putInt(0, 0x52564348); b.putInt(4, 2); b.putInt(8, id); b.putInt(12, 128)
    b.putLong(16, fixtureCreatedAt(id)); b.putInt(24, 48_000); b.putInt(28, 1); b.putInt(32, 2)
    b.putInt(40, CRC32().apply { update(bytes, 0, 40) }.value.toInt())
    b.putLong(48, 1L); b.putInt(56, 2); b.putInt(60, payloadCrc)
    b.putLong(64, payloadBytes.toLong()); b.putLong(72, payloadBytes.toLong()/2)
    b.putInt(80, CRC32().apply { update(bytes, 48, 32) }.value.toInt())
    return bytes
}

private fun processReadBytes(): Long = runCatching {
    File("/proc/self/io").readLines().first { it.startsWith("rchar:") }.substringAfter(':').trim().toLong()
}.getOrDefault(0L)
