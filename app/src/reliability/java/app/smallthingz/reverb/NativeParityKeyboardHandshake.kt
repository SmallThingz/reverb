package app.smallthingz.reverb

import android.app.Instrumentation
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.util.UUID

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
    val requestId = UUID.randomUUID().toString().replace("-", "")
    val requestFile = File(directory, "keyboard-${requestId}.request.json")
    val acknowledgementFile = File(directory, "keyboard-${requestId}.ack.json")
    val challengeFile = File(directory, "keyboard-${requestId}.challenge.json")
    val challengeResponseFile = File(directory, "keyboard-${requestId}.challenge-response.json")
    check(!requestFile.exists() && !acknowledgementFile.exists() &&
        !challengeFile.exists() && !challengeResponseFile.exists())
    val first = JSONObject(guardedSnapshot().toString())
    check(first.getInt("display_id") == 0)
    check(first.getString("window_title").isNotBlank()) { "An exact own-window title is required" }
    check(first.getString("target_identity").isNotBlank() && first.getString("scene_label").isNotBlank())
    check(renderer == "compose" || renderer == "native")
    val started = SystemClock.uptimeMillis()
    val request = JSONObject()
        .put("ack_protocol", 3)
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
        .put("status", "pending")
    var primary: Throwable? = null
    try {
        writeKeyboardRequest(requestFile, request)
        onRequest(request)
        instrumentation.sendStatus(0, Bundle().apply {
            putString("stream", "NATIVE_KEYBOARD_REQUEST ${request}\n")
        })
        var challengeAnswered: String? = null
        while (!acknowledgementFile.exists()) {
            assertSameKeyboardWindow(first, guardedSnapshot(), stableTargetBounds)
            check(SystemClock.uptimeMillis() < started + 30_000L) {
                "Targeted keyboard host acknowledgement timed out; no input fallback is allowed"
            }
            if (challengeFile.exists() && challengeAnswered == null) {
                check(challengeFile.length() in 1L..65_536L)
                val challenge = JSONObject(challengeFile.readText())
                for (key in listOf("ack_protocol", "request_id", "nonce", "stage")) {
                    check(challenge.get(key) == request.get(key)) { "Freshness challenge mismatch: $key" }
                }
                val challengeNonce = challenge.getString("challenge_nonce")
                check(challengeNonce.matches(Regex("[0-9a-f]{32}")))
                check(!acknowledgementFile.exists() && !challengeResponseFile.exists())
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
        check(acknowledgementFile.length() in 1L..65_536L) { "Invalid keyboard acknowledgement size" }
        val acknowledgement = JSONObject(acknowledgementFile.readText())
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
        check(keyboardIntegral(acknowledgement.get("ack_protocol"), "ack_protocol") == 3L)
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
