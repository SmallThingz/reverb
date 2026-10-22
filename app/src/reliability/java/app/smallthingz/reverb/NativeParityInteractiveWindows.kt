package app.smallthingz.reverb

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Instrumentation
import android.app.UiAutomation
import android.os.Build
import android.os.Parcel
import android.view.accessibility.AccessibilityWindowInfo
import org.json.JSONObject

/** Public QA connection: never suppress the user's enabled accessibility services. */
internal fun nativeParityUiAutomation(instrumentation: Instrumentation): UiAutomation {
    check(instrumentation.targetContext.packageName == "app.smallthingz.reverb.nativeparity.reliability")
    check(instrumentation.targetContext.applicationContext.javaClass == android.app.Application::class.java)
    return checkNotNull(instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES))
}

/** Scoped setup for the two QA probes that use interactive accessibility windows. */
internal class NativeParityInteractiveWindows private constructor(
    private val automation: UiAutomation,
    private val original: AccessibilityServiceInfo,
    private val evidence: JSONObject,
) : AutoCloseable {
    private var changed = false
    private var closed = false

    /** Retention reads the exact scoped connection; no default getter can reconnect mid-flow. */
    fun windows(): List<AccessibilityWindowInfo> {
        check(!closed) { "Interactive-window scope is closed" }
        return automation.windows
    }

    fun recordHost(instrumentation: Instrumentation, host: NativeParityActivity) {
        requireNativeParityHost(instrumentation, host)
        val observation = nativeParityOnMain(instrumentation) {
            JSONObject().put("package", host.packageName)
                .put("display_id", host.fixtureRoot.display?.displayId ?: JSONObject.NULL)
                .put("has_window_focus", host.hasWindowFocus())
                .put("root_attached", host.fixtureRoot.isAttachedToWindow)
        }
        evidence.put("host", observation)
        check(observation.getInt("display_id") == 0)
        check(observation.getBoolean("has_window_focus") && observation.getBoolean("root_attached"))
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            // This is the complete prior configuration of this instrumentation connection,
            // not the user's enabled accessibility services or any persistent system setting.
            if (changed) automation.serviceInfo = original
            val restored = checkNotNull(automation.serviceInfo) { "UiAutomation service info unavailable after restore" }
            evidence.put("restored_flags", restored.flags)
                .put("restoration_requested", changed)
                .put("restored_configuration_matches", sameOtherConfiguration(restored, original))
            check(restored.flags == original.flags) { "UiAutomation flags did not restore" }
            check(sameOtherConfiguration(restored, original)) { "UiAutomation configuration did not restore" }
            evidence.put("restored", true)
        } catch (error: Throwable) {
            evidence.put("restored", false).put("restoration_error", error.stackTraceToString())
            throw error
        }
    }

    companion object {
        fun open(instrumentation: Instrumentation, result: JSONObject): NativeParityInteractiveWindows {
            check(instrumentation.targetContext.packageName == "app.smallthingz.reverb.nativeparity.reliability")
            check(instrumentation.targetContext.applicationContext.javaClass == android.app.Application::class.java)
            val evidence = JSONObject().put("expected_display_id", 0).put("restored", false)
                .put("connection_flags", UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            result.put("ui_automation_windows", evidence)
            val automation = nativeParityUiAutomation(instrumentation)
            val original = checkNotNull(automation.serviceInfo) { "UiAutomation service info unavailable before setup" }
            val requested = copyServiceInfo(original)
            requested.flags = original.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            evidence.put("original_flags", original.flags).put("requested_flags", requested.flags)
            val scope = NativeParityInteractiveWindows(automation, original, evidence)
            try {
                if (requested.flags != original.flags) {
                    // Mark before the call: a transport failure may still have applied the setup.
                    scope.changed = true
                    automation.serviceInfo = requested
                }
                val effective = checkNotNull(automation.serviceInfo) { "UiAutomation service info unavailable after setup" }
                evidence.put("effective_flags", effective.flags)
                    .put("other_configuration_preserved", sameOtherConfiguration(effective, original))
                check(effective.flags == requested.flags) { "Interactive-window test setup did not take effect" }
                check(sameOtherConfiguration(effective, original)) { "Interactive-window setup changed unrelated test-service configuration" }
                return scope
            } catch (error: Throwable) {
                evidence.put("setup_error", error.stackTraceToString())
                try { scope.close() } catch (cleanup: Throwable) {
                    if (cleanup !== error) error.addSuppressed(cleanup)
                }
                throw error
            }
        }

        private fun copyServiceInfo(source: AccessibilityServiceInfo): AccessibilityServiceInfo {
            val parcel = Parcel.obtain()
            try {
                source.writeToParcel(parcel, 0)
                parcel.setDataPosition(0)
                return AccessibilityServiceInfo.CREATOR.createFromParcel(parcel)
            } finally { parcel.recycle() }
        }

        // Compare the platform's dynamically configurable fields, not component-only equals()
        // or Parcel bytes that also contain unrelated framework metadata.
        private fun sameOtherConfiguration(a: AccessibilityServiceInfo, b: AccessibilityServiceInfo): Boolean =
            a.eventTypes == b.eventTypes && a.packageNames?.toList() == b.packageNames?.toList() &&
                a.feedbackType == b.feedbackType && a.notificationTimeout == b.notificationTimeout &&
                (Build.VERSION.SDK_INT < 29 || (a.interactiveUiTimeoutMillis == b.interactiveUiTimeoutMillis &&
                    a.nonInteractiveUiTimeoutMillis == b.nonInteractiveUiTimeoutMillis)) &&
                (Build.VERSION.SDK_INT < 34 || a.motionEventSources == b.motionEventSources)
    }
}
