package app.smallthingz.reverb

import android.content.Context
import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.DataInputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/** Explicit two-launch QA case. The prepare process must die; it cannot produce a normal test PASS. */
internal fun prepareCaptureStopProcessDeath(
    context: Context, run: String, expectedApkSha: String, sourceManifestSha: String,
    terminate: (readyRelativePath: String, readySha256: String) -> Nothing,
): Nothing {
    val apkSha = guardCaptureStopDeath(context, run, expectedApkSha, sourceManifestSha)
    val readyFile = File(context.noBackupFilesDir, "capture-stop-death-$run.ready.json")
    check(!readyFile.exists()) { "An existing process-death receipt must be preserved" }
    val oldPid = Process.myPid()
    val oldBirth = Process.getStartElapsedRealtime()
    val oldStartTicks = captureStopProcessStartTicks(oldPid)
    withFramePipeline(context, "stop_death_${run.replace('-', '_')}") { fixture ->
        val input = PipelineInput(holdFirst = false, automaticReads = 4, holdReadOrdinal = 4) { ordinal, _ ->
            ByteBuffer.allocate(1024).order(ByteOrder.LITTLE_ENDIAN).apply {
                repeat(512) { putShort(((ordinal - 1) * 512 + it + 1).toShort()) }
            }.array()
        }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val insideBarrier = AtomicBoolean()
        fixture.configure(input, ReverbService.BufferSlot.LOOPING)
        fixture.onAudio {
            val field = PersistentAudioChunkStore::class.java.getDeclaredField("directorySync").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST") val original = field.get(fixture.loop) as (File) -> Unit
            val claimed = AtomicBoolean()
            field.set(fixture.loop, { directory: File ->
                if (claimed.compareAndSet(false, true)) {
                    val stack = Thread.currentThread().stackTrace.map { it.methodName }
                    check(stack.containsAll(listOf("createActiveChunkLocked", "appendCapturedAt", "consumeQueuedCapture")))
                    insideBarrier.set(true)
                    entered.countDown()
                    try { check(release.await(15, TimeUnit.SECONDS)) { "Death fixture barrier expired without termination" } }
                    finally { insideBarrier.set(false) }
                }
                original(directory)
            })
        }
        try {
            fixture.start()
            check(entered.await(5, TimeUnit.SECONDS) && input.heldReadEntered.await(5, TimeUnit.SECONDS))
            val owner = checkNotNull(fixture.io.currentInput())
            check(input.reads.get() == 4 && owner.processedFrames == 0L)
            check(fixture.service.disableListening().accepted)
            val prefs = getRecorderPreferences(fixture.admission.context)
            check(prefs.all[PrefKey.AUDIO_MEMORY_ENABLED.name] == false)
            val token = RecordingIncidentStore.beginKnownCaptureStop(fixture.admission.context)
            check(token.armed) { "Accepted Stop retired its marker before queued PCM could be sealed" }
            val marker = checkNotNull(token.marker)
            check(marker.pid == oldPid && marker.processStartElapsedRealtimeMillis == oldBirth)
            val markerFile = File(fixture.root, "recording-session.bin")
            val markerBytes = readCaptureStopRegular(markerFile, 1024)
            check(decodeCaptureStopMarker(markerBytes) == marker)
            val markerSha = captureStopSha(markerBytes)
            captureStopWriteNew(File(fixture.root, "death-preseal-marker.bin"), markerBytes)
            val rootStat = Os.lstat(fixture.root.absolutePath)
            check(OsConstants.S_ISDIR(rootStat.st_mode))
            val ready = JSONObject().put("version", 1).put("state", "READY_TO_KILL").put("run", run)
                .put("package", context.packageName).put("apk_sha256", apkSha)
                .put("externally_bound_source_manifest_sha256", sourceManifestSha)
                .put("death_action", "SELF_KILL_QA").put("pid", oldPid).put("process_birth_elapsed", oldBirth)
                .put("proc_start_ticks", oldStartTicks).put("root", fixture.root.relativeTo(context.noBackupFilesDir).path)
                .put("root_device", rootStat.st_dev).put("root_inode", rootStat.st_ino)
                .put("preference_name", "reverb_frame_fixture_${fixture.root.name}")
                .put("marker_sha256", markerSha).put("capture_armed_at_millis", marker.armedAtMillis)
                .put("marker_armed", true).put("durable_listening", false)
                .put("durable_destination", prefs.getInt(PrefKey.CAPTURE_BUFFER_SLOT, -1))
                .put("completed_native_reads", 3).put("held_native_read_ordinal", 4)
                .put("writer_processed_frames", owner.processedFrames).put("volatile_pcm_recovery_claim", false)
                .put("native_factory_calls", fixture.admission.factoryCalls.get())
            check(ready.getInt("native_factory_calls") == 0)
            captureStopWriteNew(readyFile, ready.toString(2).toByteArray(Charsets.UTF_8))
            // Receipt durability precedes death. Recheck the live boundary so a watchdog/failure
            // cannot manufacture a READY death after normal teardown has already progressed.
            check(insideBarrier.get() && input.reads.get() == 4 && owner.processedFrames == 0L)
            val status = owner.storage.ring.status()
            check(status.reading && status.occupiedPackets == 4 && status.terminal == CapturePcmRing.Terminal.STOP)
            check(RecordingIncidentStore.beginKnownCaptureStop(fixture.admission.context).armed)
            check(captureStopSha(readCaptureStopRegular(markerFile, 1024)) == markerSha)
            terminate(readyFile.name, captureStopSha(readCaptureStopRegular(readyFile, 16_384)))
        } finally {
            // Runs only on a failed preparation/termination. A successful process death never
            // executes ordinary Stop/seal cleanup and never returns PASS in this process.
            release.countDown()
            input.unblockAll()
        }
    }
    error("Process-death preparation returned without an isolated process termination")
}

/** Uses the unchanged, real old-process marker. No copied PID or synthetic process identity is accepted. */
internal fun recoverCaptureStopProcessDeath(
    context: Context, run: String, expectedApkSha: String, sourceManifestSha: String,
    expectedReadySha: String, report: StringBuilder,
) {
    val apkSha = guardCaptureStopDeath(context, run, expectedApkSha, sourceManifestSha)
    check(expectedReadySha.matches(Regex("[0-9a-f]{64}")))
    val readyFile = File(context.noBackupFilesDir, "capture-stop-death-$run.ready.json")
    val readyBytes = readCaptureStopRegular(readyFile, 16_384)
    check(captureStopSha(readyBytes) == expectedReadySha) { "Prepared receipt changed" }
    val ready = JSONObject(readyBytes.toString(Charsets.UTF_8))
    check(ready.getInt("version") == 1 && ready.getString("state") == "READY_TO_KILL")
    check(ready.getString("run") == run && ready.getString("package") == context.packageName)
    check(ready.getString("apk_sha256") == apkSha &&
        ready.getString("externally_bound_source_manifest_sha256") == sourceManifestSha)
    check(ready.getString("death_action") == "SELF_KILL_QA" && !ready.getBoolean("volatile_pcm_recovery_claim"))
    val oldPid = ready.getInt("pid")
    val oldBirth = ready.getLong("process_birth_elapsed")
    check(oldPid > 0 && (oldPid != Process.myPid() || oldBirth != Process.getStartElapsedRealtime())) {
        "Recovery must run in another process lifetime"
    }
    val deathProof = captureStopOldLifetimeGone(oldPid, ready.getLong("proc_start_ticks"))
    val relativeRoot = ready.getString("root")
    val parts = relativeRoot.split('/')
    check(parts.size == 2 && parts[0] == "capture-pipeline" &&
        parts[1].startsWith("stop_death_${run.replace('-', '_')}-") && parts[1].matches(Regex("[A-Za-z0-9_-]+")))
    val parent = File(context.noBackupFilesDir, parts[0])
    check(OsConstants.S_ISDIR(Os.lstat(parent.absolutePath).st_mode))
    val root = File(parent, parts[1])
    val stat = Os.lstat(root.absolutePath)
    check(OsConstants.S_ISDIR(stat.st_mode) && stat.st_dev == ready.getLong("root_device") && stat.st_ino == ready.getLong("root_inode"))
    val preferenceName = "reverb_frame_fixture_${root.name}"
    check(ready.getString("preference_name") == preferenceName)
    val isolated = FrameFixtureContext(context.applicationContext, root, preferenceName, null)
    val prefs = getRecorderPreferences(isolated)
    check(prefs.all[PrefKey.AUDIO_MEMORY_ENABLED.name] == false && !ready.getBoolean("durable_listening"))
    check(prefs.getInt(PrefKey.CAPTURE_BUFFER_SLOT, -1) == ready.getInt("durable_destination"))
    val markerBytes = readCaptureStopRegular(File(root, "recording-session.bin"), 1024)
    check(captureStopSha(markerBytes) == ready.getString("marker_sha256")) { "Original armed marker changed before recovery" }
    check(readCaptureStopRegular(File(root, "death-preseal-marker.bin"), 1024).contentEquals(markerBytes))
    val marker = decodeCaptureStopMarker(markerBytes)
    check(marker.armed && marker.pid == oldPid && marker.processStartElapsedRealtimeMillis == oldBirth &&
        marker.armedAtMillis == ready.getLong("capture_armed_at_millis"))
    val resultFile = File(context.noBackupFilesDir, "capture-stop-death-$run.recovered.json")
    check(!resultFile.exists()) { "Preserve the prior process-death recovery result" }
    // ReverbApplication startup recovers its base QA root, not this nested fixture root.
    // This case qualifies real QA process death plus explicit actual Store recovery of the
    // isolated marker; it does not claim automatic Application discovery of that marker.
    RecordingIncidentStore.recoverPriorSessionIfNeeded(isolated)
    val incidents = RecordingIncidentStore.readIncidents(isolated)
    val recovered = incidents.single { it.pid == oldPid && it.captureArmedAtMillis == marker.armedAtMillis }
    check(recovered.kind == RecordingIncidentKind.UNEXPECTED_SHUTDOWN && !recovered.deleted)
    check(recovered.processStartedAtMillis == recordingSessionProcessStartedAtMillis(marker))
    check(prefs.all[PrefKey.AUDIO_MEMORY_ENABLED.name] == false)
    val result = JSONObject().put("version", 1).put("state", "RECOVERED").put("run", run)
        .put("apk_sha256", apkSha).put("ready_sha256", expectedReadySha)
        .put("externally_bound_source_manifest_sha256", sourceManifestSha)
        .put("old_pid", oldPid).put("old_process_birth_elapsed", oldBirth).put("old_lifetime_gone_proof", deathProof)
        .put("recovery_pid", Process.myPid()).put("recovery_process_birth_elapsed", Process.getStartElapsedRealtime())
        .put("root", relativeRoot).put("marker_sha256", ready.getString("marker_sha256"))
        .put("durable_listening", false).put("matching_visible_incidents", 1)
        .put("capture_armed_at_millis", marker.armedAtMillis).put("incident_kind", recovered.kind.name)
        .put("exit_reason", recovered.exitReason).put("exit_status", recovered.exitStatus)
        .put("description", recovered.description ?: JSONObject.NULL).put("actual_process_boundary", true)
        .put("copied_process_identity", false).put("volatile_pcm_recovery_claim", false)
    captureStopWriteNew(resultFile, result.toString(2).toByteArray(Charsets.UTF_8))
    report.append("RECOVERED capture Stop run=$run oldPid=$oldPid proof=$deathProof receipt=${resultFile.name}\n")
}

private fun guardCaptureStopDeath(context: Context, run: String, expectedApkSha: String, sourceSha: String): String {
    check(context.packageName == "app.smallthingz.reverb.reliability")
    check(UUID.fromString(run).toString() == run)
    check(expectedApkSha.matches(Regex("[0-9a-f]{64}")) && sourceSha.matches(Regex("[0-9a-f]{64}")))
    check(OsConstants.S_ISDIR(Os.lstat(context.noBackupFilesDir.absolutePath).st_mode))
    val apk = File(context.applicationInfo.sourceDir)
    val digest = MessageDigest.getInstance("SHA-256")
    apk.inputStream().use { input ->
        val bytes = ByteArray(65_536)
        while (true) { val count = input.read(bytes); if (count < 0) break; digest.update(bytes, 0, count) }
    }
    val actual = digest.digest().joinToString("") { "%02x".format(it) }
    check(actual == expectedApkSha) { "Installed QA artifact does not match this process-death admission" }
    return actual
}

private fun captureStopOldLifetimeGone(pid: Int, startTicks: Long): String {
    try { Os.kill(pid, 0) }
    catch (error: ErrnoException) {
        if (error.errno == OsConstants.ESRCH) return "kernel_ESRCH"
        throw error // Permission/inspection failure is not evidence of death.
    }
    check(captureStopProcessStartTicks(pid) != startTicks) { "Prepared process lifetime is still alive" }
    return "pid_reused_with_different_proc_start_ticks"
}

private fun captureStopProcessStartTicks(pid: Int): Long {
    val file = File("/proc/$pid/stat")
    val text = file.readText()
    check(text.length in 1..8192 && text.substringBefore(' ').toInt() == pid)
    val closing = text.lastIndexOf(')')
    check(closing > 0)
    val fields = text.substring(closing + 1).trim().split(Regex("\\s+"))
    check(fields.size > 19)
    return fields[19].toLong().also { check(it > 0L) } // Field 22; the suffix begins at field 3.
}

private fun decodeCaptureStopMarker(bytes: ByteArray): ActiveRecordingSessionMarker =
    DataInputStream(bytes.inputStream()).use { input ->
        RecordingIncidentStore.readSessionPayload(input).also { check(input.available() == 0) }
    }

private fun readCaptureStopRegular(file: File, limit: Int): ByteArray {
    val stat = Os.lstat(file.absolutePath)
    check(OsConstants.S_ISREG(stat.st_mode) && stat.st_size in 1..limit.toLong())
    return file.readBytes().also { bytes ->
        val after = Os.lstat(file.absolutePath)
        check(bytes.size.toLong() == stat.st_size && after.st_dev == stat.st_dev && after.st_ino == stat.st_ino &&
            after.st_size == stat.st_size && after.st_mtime == stat.st_mtime && after.st_ctime == stat.st_ctime)
    }
}

private fun captureStopSha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it) }

private fun captureStopWriteNew(file: File, bytes: ByteArray) {
    check(bytes.size in 1..16_384 && file.createNewFile()) { "Evidence already exists or is oversized: ${file.name}" }
    FileOutputStream(file).use { output -> output.write(bytes); output.fd.sync() }
    forceRecordingDirectoryDurable(checkNotNull(file.parentFile))
}
