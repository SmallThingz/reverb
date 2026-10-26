package app.smallthingz.reverb

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.os.Build
import android.os.Parcel
import org.json.JSONArray
import org.json.JSONObject

/** Varies only this QA connection's layout-wrapper traversal, never an enabled system service. */
internal fun verifyNativeMainImportanceTraversal(
    automation: UiAutomation,
    evidence: JSONObject,
    capture: (JSONObject) -> JSONObject,
) {
    val original = checkNotNull(automation.serviceInfo)
    val captures = JSONArray()
    evidence.put("original_flags", original.flags).put("captures", captures).put("restored", false)
    var failure: Throwable? = null
    try {
        for (includeLayouts in listOf(false, true)) {
            val requested = copyMainTraversalServiceInfo(original)
            requested.flags = if (includeLayouts)
                original.flags or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            else original.flags and AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS.inv()
            val entry = JSONObject().put("include_not_important_views", includeLayouts)
                .put("requested_flags", requested.flags).put("passed", false)
            captures.put(entry)
            automation.serviceInfo = requested
            val effective = checkNotNull(automation.serviceInfo)
            entry.put("effective_flags", effective.flags)
            check(effective.flags == requested.flags && sameMainTraversalConfiguration(effective, original)) {
                "QA importance traversal did not preserve its other connection settings"
            }
            entry.put("platform", capture(entry)).put("passed", true)
        }
    } catch (error: Throwable) {
        failure = error
        throw error
    } finally {
        try {
            automation.serviceInfo = original
            val restored = checkNotNull(automation.serviceInfo)
            evidence.put("restored_flags", restored.flags)
            check(restored.flags == original.flags && sameMainTraversalConfiguration(restored, original)) {
                "QA importance traversal configuration did not restore"
            }
            evidence.put("restored", true)
        } catch (cleanup: Throwable) {
            evidence.put("restoration_error", cleanup.stackTraceToString())
            val primary = failure
            if (primary != null) primary.addSuppressed(cleanup) else throw cleanup
        }
    }
}

private fun copyMainTraversalServiceInfo(source: AccessibilityServiceInfo): AccessibilityServiceInfo {
    val parcel = Parcel.obtain()
    try {
        source.writeToParcel(parcel, 0)
        parcel.setDataPosition(0)
        return AccessibilityServiceInfo.CREATOR.createFromParcel(parcel)
    } finally { parcel.recycle() }
}

// Match the platform's configurable fields, not component-only equality or unrelated Parcel data.
private fun sameMainTraversalConfiguration(a: AccessibilityServiceInfo, b: AccessibilityServiceInfo): Boolean =
    a.eventTypes == b.eventTypes && a.packageNames.contentEquals(b.packageNames) &&
        a.feedbackType == b.feedbackType && a.notificationTimeout == b.notificationTimeout &&
        (Build.VERSION.SDK_INT < 29 || (a.interactiveUiTimeoutMillis == b.interactiveUiTimeoutMillis &&
            a.nonInteractiveUiTimeoutMillis == b.nonInteractiveUiTimeoutMillis)) &&
        (Build.VERSION.SDK_INT < 34 || a.motionEventSources == b.motionEventSources)
