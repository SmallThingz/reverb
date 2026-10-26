package app.smallthingz.reverb

import android.app.Instrumentation
import android.graphics.BitmapFactory
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** Real MediaPlayer against only this run's existing identity-bound synthetic fixture factory. */
internal fun verifyNativeLibraryPlayback(instrumentation: Instrumentation, report: StringBuilder) {
    val context = instrumentation.targetContext
    val directory = File(context.filesDir, "native-library-playback-" + System.currentTimeMillis())
    check(directory.mkdir())
    val cases = JSONArray()
    val comparisons = JSONArray()
    val result = JSONObject().put("baseline", "3e16f8ddb78dca15df4b436d34d330801ce83ffd")
        .put("scope", "One Light/LTR pair: prepared expanded player, initial autoplay, explicit Play/progress/Pause, paused waveform seek, actual menu Trim and Cancel")
        .put("limits", "Visible progress plus native presentation; no audible-output measurement, exact scheduler timing, first-display-frame claim, native detached-bind order claim, Save or capture. Settled seek/Trim/Cancel pixels compared exactly.")
        .put("cases", cases).put("comparisons", comparisons).put("player_preparation", JSONArray()).put("passed", false)
    fun persist() = writeQaEvidenceAtomically(File(directory, "result.json")) { output ->
        output.write(result.toString(2).toByteArray(Charsets.UTF_8))
    }
    persist()
    val fixtures = LibraryOwnedFixtures(context, directory)
    var host: NativeParityActivity? = null
    var board: LibraryParityBoard? = null
    var primary: Throwable? = null
    var completed = 0
    var matches = 0
    try {
        fixtures.requireEmptyBaseline()
        val records = fixtures.create()
        check(records.first().durationMillis == 3_000L) { "Unexpected existing fixture order/duration" }
        val activity = launchNativeParityActivity(instrumentation)
        host = activity
        awaitNativeParityDraw(instrumentation, activity.fixtureRoot)
        val current = nativeParityOnMain(instrumentation) { LibraryParityBoard(activity, false, records) }
        board = current
        current.awaitInitial(instrumentation)
        current.expandFirstPaused(instrumentation, result.getJSONArray("player_preparation"))
        for (compose in listOf(true, false)) {
            val renderer = if (compose) "compose" else "native"
            val checkpoints = JSONArray()
            val entry = JSONObject().put("renderer", renderer).put("checkpoints", checkpoints).put("passed", false)
            cases.put(entry); persist()
            current.verifyPlayback(instrumentation, compose, directory) { name, state ->
                checkpoints.put(JSONObject().put("name", name).put("state", state))
                persist()
            }
            fixtures.requireUnchanged()
            entry.put("owned_audio_unchanged", true).put("passed", true)
            completed++
            persist()
            report.append("PASS native_library_playback ").append(renderer).append('\n')
        }
        for (scene in listOf("seek-paused", "trim", "trim-cancelled")) {
            val source = checkNotNull(BitmapFactory.decodeFile(File(directory, "compose-$scene.png").path))
            try {
                val native = checkNotNull(BitmapFactory.decodeFile(File(directory, "native-$scene.png").path))
                try {
                    val difference = compareNativeParityPixels(source, native)
                    val passed = difference.getLong("different_pixels") == 0L
                    comparisons.put(JSONObject().put("scene", scene).put("difference", difference).put("passed", passed))
                    if (passed) matches++
                    persist()
                } finally { native.recycle() }
            } finally { source.recycle() }
        }
        check(completed == 2 && matches == 3) { "Playback behavior/pixel comparison incomplete: $completed routes, $matches/3 matching scenes" }
    } catch (error: Throwable) {
        primary = error
        result.put("terminal_error", android.util.Log.getStackTraceString(error))
        throw error
    } finally {
        var cleanup: Throwable? = null
        fun release(block: () -> Unit) {
            try { block() } catch (error: Throwable) {
                if (cleanup == null) cleanup = error else if (cleanup !== error) cleanup!!.addSuppressed(error)
            }
        }
        release { nativeParityOnMain(instrumentation) { board?.close(); board = null } }
        release { nativeParityOnMain(instrumentation) { host?.finish() } }
        release { fixtures.close() }
        cleanup?.let {
            primary?.addSuppressed(it)
            result.put("cleanup_error", android.util.Log.getStackTraceString(it))
        }
        result.put("completed_routes", completed).put("matching_scenes", matches)
            .put("passed", primary == null && cleanup == null && completed == 2 && matches == 3)
        try {
            persist()
            report.append("PARITY_EVIDENCE ").append(directory.name).append("/result.json\n")
        } catch (error: Throwable) {
            report.append("PARITY_EVIDENCE_WRITE_FAILED ").append(directory.name).append("/result.json\n")
            val previous = primary ?: cleanup
            if (previous == null) throw error
            if (previous !== error) previous.addSuppressed(error)
        }
        if (primary == null) cleanup?.let { throw it }
    }
}
