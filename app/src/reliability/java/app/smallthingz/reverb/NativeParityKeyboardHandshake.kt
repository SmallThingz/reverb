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
    check(context.packageName == "app.smallthingz.reverb.nativeparity.reliability")
    check(context.applicationContext.javaClass == android.app.Application::class.java)
    check(directory.parentFile?.canonicalFile == context.filesDir.canonicalFile)
    check(directory.name.matches(Regex("native-(about-parity|retention-keyboard|input-parity)-[0-9]+")))
    if (directory.name.startsWith("native-input-parity-")) {
        check(stage == "input-keyboard-mode" && keyCodeName == "KEYCODE_TAB")
    }
    check(directory.isDirectory)
    val requestId = UUID.randomUUID().toString().replace("-", "")
    val requestFile = File(directory, "keyboard-${requestId}.request.json")
    val acknowledgementFile = File(directory, "keyboard-${requestId}.ack.json")
    check(!requestFile.exists() && !acknowledgementFile.exists())
    val first = JSONObject(guardedSnapshot().toString())
    check(first.getInt("display_id") == 0)
    check(first.getString("window_title").isNotBlank()) { "An exact own-window title is required" }
    check(first.getString("target_identity").isNotBlank() && first.getString("scene_label").isNotBlank())
    check(renderer == "compose" || renderer == "native")
    val started = SystemClock.uptimeMillis()
    val request = JSONObject()
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
        .put("status", "pending")
    var primary: Throwable? = null
    try {
        writeKeyboardRequest(requestFile, request)
        onRequest(request)
        instrumentation.sendStatus(0, Bundle().apply {
            putString("stream", "NATIVE_KEYBOARD_REQUEST ${request}\n")
        })
        while (!acknowledgementFile.exists()) {
            assertSameKeyboardWindow(first, guardedSnapshot(), stableTargetBounds)
            check(SystemClock.uptimeMillis() < started + 30_000L) {
                "Targeted keyboard host acknowledgement timed out; no input fallback is allowed"
            }
            Thread.sleep(50)
        }
        assertSameKeyboardWindow(first, guardedSnapshot(), stableTargetBounds)
        check(SystemClock.uptimeMillis() < started + 30_000L) { "Keyboard acknowledgement arrived after expiry" }
        check(acknowledgementFile.length() in 1L..65_536L) { "Invalid keyboard acknowledgement size" }
        val acknowledgement = JSONObject(acknowledgementFile.readText())
        request.put("host_acknowledgement", acknowledgement)
        for (key in listOf("nonce", "request_id", "renderer", "stage",
                "package", "uid", "pid", "display_id", "key_code", "window_title", "decor_identity", "target_identity", "scene_label")) {
            check(acknowledgement.get(key) == request.get(key)) { "Keyboard acknowledgement mismatch: $key" }
        }
        check(keyboardIntegral(acknowledgement.get("command_exit"), "command_exit") == 0L &&
            acknowledgement.get("post_admitted").let { it is Boolean && it }) {
            "Targeted keyboard command or its post-admission failed"
        }
        check(keyboardIntegral(acknowledgement.get("acknowledged_uptime_ms"), "acknowledged_uptime_ms") in
            started..(started + 29_999L)) {
            "Keyboard acknowledgement is outside this request lifetime"
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
