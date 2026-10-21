package app.smallthingz.reverb

import android.os.Process
import android.os.SystemClock
import android.view.View
import android.view.inspector.WindowInspector
import org.json.JSONArray
import org.json.JSONObject

/** Read-only readiness for one explicit own UI transition; never a substitute for an input guard. */
internal class NativeLibraryWindowTransition(
    private val host: NativeParityActivity,
    private val origin: View,
    private val evidence: JSONObject,
) {
    private val main = host.window.decorView
    private val initial = visibleRoots()
    private var popup: View? = null
    private var reads = 0

    init {
        check(initial.all { it === main || it === origin }) { "Unrelated own window before UI transition" }
        check(initial.filter(View::hasWindowFocus).single() === origin) { "UI transition origin lost focus" }
        evidence.put("origin_identity", System.identityHashCode(origin))
            .put("main_identity", System.identityHashCode(main)).put("accepted", false)
    }

    /** Called on main, only while the existing post-action readiness deadline is running. */
    fun focusedRoot(): View? {
        val roots = visibleRoots()
        val observation = JSONObject().put("uptime_ms", SystemClock.uptimeMillis())
            .put("windows", JSONArray(roots.map { root ->
                JSONObject().put("identity", System.identityHashCode(root)).put("class", root.javaClass.name)
                    .put("package", root.context.packageName).put("uid", root.context.applicationInfo.uid)
                    .put("display_id", root.display?.displayId).put("focused", root.hasWindowFocus())
            }))
        if (!evidence.has("first_observation")) evidence.put("first_observation", observation)
        evidence.put("last_observation", observation).put("reads", ++reads)
        val added = roots.filter { it !in initial }
        check(added.size <= 1) { "Ambiguous new windows during owned UI transition" }
        popup?.let { pinned ->
            check(added.singleOrNull() === pinned) { "Owned popup was replaced or disappeared before readiness" }
        }
        if (popup == null && added.isNotEmpty()) {
            popup = added.single()
            evidence.put("popup_identity", System.identityHashCode(popup))
        }
        val focused = roots.filter(View::hasWindowFocus)
        check(focused.size <= 1) { "Ambiguous focus during owned UI transition" }
        if (focused.isEmpty()) {
            evidence.put("zero_focus_reads", evidence.optInt("zero_focus_reads") + 1)
            return null
        }
        return focused.single()
    }

    fun accepted(root: View) {
        evidence.put("accepted", true).put("accepted_identity", System.identityHashCode(root))
            .put("accepted_uptime_ms", SystemClock.uptimeMillis())
    }

    private fun visibleRoots(): List<View> {
        check(!host.isFinishing && host.fixtureRoot.isAttachedToWindow && host.fixtureRoot.isShown &&
            host.fixtureRoot.display?.displayId == 0 && host.applicationInfo.uid == Process.myUid())
        return WindowInspector.getGlobalWindowViews().filter { it.isAttachedToWindow && it.isShown }.also { roots ->
            check(roots.isNotEmpty() && roots.all {
                it.context.packageName == host.packageName && it.context.applicationInfo.uid == Process.myUid() &&
                    it.display?.displayId == 0
            }) { "Foreign, missing, or wrong-display windows during owned UI transition" }
        }
    }
}
