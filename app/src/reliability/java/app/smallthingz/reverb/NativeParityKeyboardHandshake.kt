package app.smallthingz.reverb

import android.app.Instrumentation
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.util.UUID

internal const val NATIVE_KEYBOARD_ACK_PROTOCOL = 4

/**
 * QA-only rendezvous with the existing host runner. This code never injects an input event.
 * The runner admits only its finite stage/key table and records completion after fresh
 * physical-display-0 UID/PID/window/scene admission. Callers independently validate the result.
 * This helper owns no input, focus, settings, capture, or backend operation.
 */
internal fun awaitNativeParityKeyboardCommand(
    instrumentation: Instrumentation,
    directory: File,
    sessionNonce: String?,
    renderer: String,
    stage: String,
    keyCodeName: String,
    stableTargetBounds: Boolean = true,
    guardedSnapshot: () -> JSONObject,
    onRequest: (JSONObject) -> Unit,
    validateObservedAfter: (JSONObject) -> Unit,
) {
    val nonce = checkNotNull(sessionNonce) {
        "Keyboard qualification requires the targeted host keyboard_session_nonce handshake"
    }
    check(nonce.matches(Regex("[0-9a-f]{32}"))) { "Invalid keyboard session nonce" }
    val context = instrumentation.targetContext
    check(context.packageName in setOf("app.smallthingz.reverb.nativeparity.reliability",
        "app.smallthingz.reverb.uitheme.reliability"))
    check(context.applicationContext.javaClass == android.app.Application::class.java)
    check(directory.parentFile?.canonicalFile == context.filesDir.canonicalFile)
    check(directory.name.matches(Regex("native-(about-parity|retention-keyboard|input-parity|rename-editor-current)-[0-9]+")))
    if (directory.name.startsWith("native-input-parity-")) {
        check(stage == "input-keyboard-mode" && keyCodeName == "KEYCODE_TAB")
    }
    if (context.packageName == "app.smallthingz.reverb.uitheme.reliability") {
        check(directory.name.startsWith("native-retention-keyboard-") ||
            directory.name.startsWith("native-rename-editor-current-"))
    }
    check(directory.isDirectory)
    // Use the canonical private directory, and pin its non-symlink identity for this request.
    check(OsConstants.S_ISDIR(Os.lstat(directory.path).st_mode))
    val protocolDirectory = File(context.filesDir.canonicalFile, directory.name)
    check(directory.canonicalFile == protocolDirectory)
    val directoryIdentity = Os.lstat(protocolDirectory.path)
    check(OsConstants.S_ISDIR(directoryIdentity.st_mode))
    val requestId = UUID.randomUUID().toString().replace("-", "")
    val prefix = "keyboard-v4-$nonce-$requestId"
    val requestFile = File(protocolDirectory, "$prefix.request.json")
    val acknowledgementFile = File(protocolDirectory, "$prefix.ack.json")
    val challengeFile = File(protocolDirectory, "$prefix.challenge.json")
    val challengeResponseFile = File(protocolDirectory, "$prefix.challenge-response.json")
    val acknowledgementReady = File(protocolDirectory, "${acknowledgementFile.name}.ready")
    val challengeReady = File(protocolDirectory, "${challengeFile.name}.ready")
    check(listOf(requestFile, acknowledgementFile, challengeFile, challengeResponseFile,
        acknowledgementReady, challengeReady).all { keyboardLstatOrMissing(it) == null })
    fun readHostPayload(file: File, ready: File): JSONObject? = readPublishedKeyboardPayload(
        file, ready, protocolDirectory, directoryIdentity, prefix)
    val first = JSONObject(guardedSnapshot().toString())
    check(first.getInt("display_id") == 0)
    check(first.getString("window_title").isNotBlank()) { "An exact own-window title is required" }
    check(first.getString("target_identity").isNotBlank() && first.getString("scene_label").isNotBlank())
    check(renderer == "compose" || renderer == "native")
    val started = SystemClock.uptimeMillis()
    val request = JSONObject()
        .put("ack_protocol", NATIVE_KEYBOARD_ACK_PROTOCOL)
        .put("nonce", nonce).put("request_id", requestId)
        .put("renderer", renderer).put("stage", stage)
        .put("package", context.packageName).put("uid", Process.myUid()).put("pid", Process.myPid())
        .put("display_id", 0).put("key_code", keyCodeName)
        .put("window_title", first.getString("window_title"))
        .put("decor_identity", first.getInt("decor_identity"))
        .put("scene", first).put("target_bounds", first.getJSONArray("target_bounds"))
        .put("target_identity", first.getString("target_identity"))
        .put("scene_label", first.getString("scene_label"))
        .put("observed_target", first.getJSONObject("observed_target"))
        .put("evidence_directory", directory.name).put("uptime_ms", started)
        .put("expires_uptime_ms", started + 30_000L)
        .put("request_file", "files/${directory.name}/${requestFile.name}")
        .put("ack_file", "files/${directory.name}/${acknowledgementFile.name}")
        .put("challenge_file", "files/${directory.name}/${challengeFile.name}")
        .put("challenge_response_file", "files/${directory.name}/${challengeResponseFile.name}")
        .put("challenge_ready_file", "files/${directory.name}/${challengeReady.name}")
        .put("ack_ready_file", "files/${directory.name}/${acknowledgementReady.name}")
        .put("status", "pending")
    var primary: Throwable? = null
    try {
        writeKeyboardRequest(requestFile, request)
        onRequest(request)
        instrumentation.sendStatus(0, Bundle().apply {
            putString("stream", "NATIVE_KEYBOARD_REQUEST ${request}\n")
        })
        var challengeAnswered: String? = null
        var receivedAcknowledgement: JSONObject? = null
        while (receivedAcknowledgement == null) {
            assertSameKeyboardWindow(first, guardedSnapshot(), stableTargetBounds)
            check(SystemClock.uptimeMillis() < started + 30_000L) {
                "Targeted keyboard host acknowledgement timed out; no input fallback is allowed"
            }
            // A payload name may be visible while the host is still writing it. Only the
            // create-once ready marker authorizes reading, including for acknowledgements.
            receivedAcknowledgement = readHostPayload(acknowledgementFile, acknowledgementReady)
            if (receivedAcknowledgement != null) break
            val challenge = if (challengeAnswered == null) readHostPayload(challengeFile, challengeReady) else null
            if (challenge != null) {
                for (key in listOf("ack_protocol", "request_id", "nonce", "stage")) {
                    check(challenge.get(key) == request.get(key)) { "Freshness challenge mismatch: $key" }
                }
                val challengeNonce = challenge.getString("challenge_nonce")
                check(challengeNonce.matches(Regex("[0-9a-f]{32}")))
                check(readHostPayload(acknowledgementFile, acknowledgementReady) == null &&
                    keyboardLstatOrMissing(challengeResponseFile) == null)
                assertSameKeyboardWindow(first, guardedSnapshot(), stableTargetBounds)
                val remaining = started + 30_000L - SystemClock.uptimeMillis()
                check(remaining > 0L) { "Freshness challenge arrived after request expiry" }
                val response = JSONObject(challenge.toString()).put("remaining_ms", remaining)
                writeKeyboardRequest(challengeResponseFile, response)
                challengeAnswered = challengeNonce
                request.put("freshness_response", response)
            }
            Thread.sleep(50)
        }
        check(challengeAnswered != null) { "Host acknowledged without a completed freshness challenge" }
        assertSameKeyboardWindow(first, guardedSnapshot(), stableTargetBounds)
        check(SystemClock.uptimeMillis() < started + 30_000L) { "Keyboard acknowledgement arrived after expiry" }
        val acknowledgement = checkNotNull(receivedAcknowledgement)
        request.put("host_acknowledgement", acknowledgement)
        check(acknowledgement.getString("challenge_nonce") == challengeAnswered)
        for (key in listOf("nonce", "request_id", "renderer", "stage",
                "package", "uid", "pid", "display_id", "key_code", "window_title", "decor_identity", "target_identity", "scene_label")) {
            check(acknowledgement.get(key) == request.get(key)) { "Keyboard acknowledgement mismatch: $key" }
        }
        check(keyboardIntegral(acknowledgement.get("command_exit"), "command_exit") == 0L &&
            acknowledgement.get("post_admitted").let { it is Boolean && it }) {
            "Targeted keyboard command or its post-admission failed"
        }
        check(keyboardIntegral(acknowledgement.get("ack_protocol"), "ack_protocol") == NATIVE_KEYBOARD_ACK_PROTOCOL.toLong())
        // Only instrumentation owns Android uptime. Host acknowledges after guarded input returns;
        // receiving its nonce-bound completion before expiry establishes this causal boundary.
        val acknowledgementReceived = SystemClock.uptimeMillis()
        request.put("ack_received_uptime_ms", acknowledgementReceived)
        check(acknowledgementReceived in started..(started + 29_999L)) {
            "Keyboard acknowledgement was received outside this request lifetime"
        }
        val now = guardedSnapshot()
        assertSameKeyboardWindow(first, now, stableTargetBounds)
        request.put("observed_after", now)
        validateObservedAfter(now)
        request.put("status", "completed")
    } catch (error: Throwable) {
        primary = error
        request.put("status", "failed").put("terminal_error", android.util.Log.getStackTraceString(error))
        throw error
    } finally {
        request.put("closed_uptime_ms", SystemClock.uptimeMillis())
        try { writeKeyboardRequest(requestFile, request) }
        catch (cleanup: Throwable) {
            val earlier = primary
            if (earlier == null) throw cleanup else if (earlier !== cleanup) earlier.addSuppressed(cleanup)
        }
    }
}

private fun keyboardLstatOrMissing(file: File): StructStat? = try {
    Os.lstat(file.path)
} catch (error: ErrnoException) {
    if (error.errno == OsConstants.ENOENT) null else throw error
}

private fun sameKeyboardFile(before: StructStat, after: StructStat): Boolean =
    before.st_dev == after.st_dev && before.st_ino == after.st_ino &&
        before.st_mode == after.st_mode && before.st_size == after.st_size

/** Bounded no-follow read, checked against the opened descriptor and final pathname. */
private fun readKeyboardRegularFile(file: File, size: LongRange): Pair<ByteArray, StructStat> {
    check(file.canonicalFile == file.absoluteFile) { "Noncanonical keyboard protocol path" }
    val before = Os.lstat(file.path)
    check(OsConstants.S_ISREG(before.st_mode) && before.st_size in size) {
        "Invalid keyboard protocol file type/size: ${file.name}"
    }
    val descriptor = Os.open(file.path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or
        OsConstants.O_CLOEXEC or OsConstants.O_NONBLOCK, 0)
    var primary: Throwable? = null
    val bytes = try {
        check(sameKeyboardFile(before, Os.fstat(descriptor))) { "Keyboard protocol file replaced before read" }
        val data = ByteArray(before.st_size.toInt())
        var offset = 0
        while (offset < data.size) {
            val count = Os.read(descriptor, data, offset, data.size - offset)
            check(count > 0) { "Keyboard protocol payload truncated" }
            offset += count
        }
        check(Os.read(descriptor, ByteArray(1), 0, 1) == 0 && sameKeyboardFile(before, Os.fstat(descriptor))) {
            "Keyboard protocol file changed during read"
        }
        data
    } catch (error: Throwable) {
        primary = error
        throw error
    } finally {
        try { Os.close(descriptor) } catch (cleanup: Throwable) {
            val earlier = primary
            if (earlier == null) throw cleanup else if (earlier !== cleanup) earlier.addSuppressed(cleanup)
        }
    }
    check(sameKeyboardFile(before, Os.lstat(file.path))) { "Keyboard protocol pathname replaced during read" }
    return bytes to before
}

private fun readPublishedKeyboardPayload(file: File, ready: File, directory: File,
    identity: StructStat, prefix: String): JSONObject? {
    fun sameDirectory() {
        val current = Os.lstat(directory.path)
        check(directory.canonicalFile == directory && OsConstants.S_ISDIR(current.st_mode) &&
            current.st_dev == identity.st_dev && current.st_ino == identity.st_ino) {
            "Keyboard protocol directory changed"
        }
    }
    sameDirectory()
    check(file.parentFile == directory && ready.parentFile == directory &&
        file.name in listOf("$prefix.challenge.json", "$prefix.ack.json") && ready.name == "${file.name}.ready")
    // Absence alone means pending. Dangling symlinks and all nonregular marker types fail.
    if (keyboardLstatOrMissing(ready) == null) return null
    val marker = readKeyboardRegularFile(ready, 0L..0L).second
    val payload = readKeyboardRegularFile(file, 1L..65_536L).first
    check(sameKeyboardFile(marker, Os.lstat(ready.path))) { "Keyboard ready marker replaced during read" }
    sameDirectory()
    return JSONObject(payload.toString(Charsets.UTF_8))
}

private fun assertSameKeyboardWindow(first: JSONObject, current: JSONObject, stableBounds: Boolean) {
    val keys = listOf("display_id", "window_title", "decor_identity", "target_identity", "scene_label") +
        if (stableBounds) listOf("target_bounds") else emptyList()
    for (key in keys) {
        check(current.get(key).toString() == first.get(key).toString()) {
            "Keyboard request scene changed: $key"
        }
    }
}

private fun writeKeyboardRequest(file: File, value: JSONObject) {
    val atomic = AtomicFile(file)
    val stream = atomic.startWrite()
    try {
        stream.write(value.toString(2).toByteArray(Charsets.UTF_8))
        atomic.finishWrite(stream)
    } catch (error: Throwable) {
        try { atomic.failWrite(stream) } catch (cleanup: Throwable) {
            if (cleanup !== error) error.addSuppressed(cleanup)
        }
        throw error
    }
}

private fun keyboardIntegral(value: Any, field: String): Long = when (value) {
    is Int -> value.toLong()
    is Long -> value
    else -> error("Keyboard acknowledgement $field is not an integral JSON number")
}
