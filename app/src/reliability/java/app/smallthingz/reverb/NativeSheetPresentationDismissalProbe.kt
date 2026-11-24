package app.smallthingz.reverb

import android.app.Instrumentation
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.view.ViewTreeObserver
import android.widget.TextView
import app.smallthingz.reverb.nativeui.ReverbPalette
import app.smallthingz.reverb.nativeui.ReverbViewEnvironment
import app.smallthingz.reverb.nativeui.createReverbActionSheet
import org.json.JSONArray
import org.json.JSONObject
import smallthingz.ui.components.NativeActionSheet
import smallthingz.ui.interaction.view.readCachedAnimatorDurationScale
import smallthingz.ui.reverb.ReverbSheetActions
import smallthingz.ui.reverb.ReverbSheetButton
import smallthingz.ui.text.font.createFontFamilyResolver
import smallthingz.ui.unit.Density

/** Actual shared presentation button, retained exit, and terminal/teardown ownership. */
internal fun verifySheetPresentationDismissal(i: Instrumentation, host: NativeParityActivity, anchor: View, cases: JSONArray) {
    fun <T> main(block: () -> T) = nativeParityOnMain(i, block)
    for (teardown in listOf(false, true)) {
        val evidence = JSONObject().put("teardown_during_exit", teardown).put("passed", false)
        cases.put(evidence)
        var sheet: NativeActionSheet? = null
        var actions: ReverbSheetActions? = null
        var cancel: ReverbSheetButton? = null
        var confirm: ReverbSheetButton? = null
        var observer: ViewTreeObserver? = null
        var listener: ViewTreeObserver.OnDrawListener? = null
        var failure: Throwable? = null
        var ownerDismissals = 0
        var confirmations = 0
        var busy = true
        var intermediate = 0
        try {
            val dialog = main {
                val env = ReverbViewEnvironment(Density(host), createFontFamilyResolver(host), ReverbPalette.fallback(false))
                createReverbActionSheet(anchor, env, "Presentation dismissal") {
                    ownerDismissals++
                    sheet?.dismiss()
                }.also { value ->
                    sheet = value
                    val content = value.contentContext
                    cancel = ReverbSheetButton(host, content, env.theme, false, "Cancel", content.requestAnimatedDismiss)
                    confirm = ReverbSheetButton(host, content, env.theme, true, "Confirm", { confirmations++ })
                    actions = ReverbSheetActions(host, content, env.theme, listOf(checkNotNull(cancel), checkNotNull(confirm)))
                    value.setContent(TextView(host).apply { text = "Only presentation Cancel waits for exit." },
                        checkNotNull(actions), listOf(checkNotNull(actions).hitLayout))
                    value.setDismissalAdmission { !busy }
                    value.show()
                }
            }
            val decor = main { checkNotNull(dialog.window).decorView }
            val deadline = SystemClock.uptimeMillis() + 8000
            while (!main { dialog.hasCurrentWindowFocus && actionSheetFixtureExpanded(dialog) }) {
                check(SystemClock.uptimeMillis() < deadline) { "Presentation sheet did not settle" }
                awaitNativeParityDraw(i, decor)
            }
            val scale = main {
                checkNotNull(cancel).accessibilitySemanticView.performClick()
                check(dialog.isShowing && ownerDismissals == 0 && dialog.contentContext.canAcceptAction())
                busy = false
                dialog.refreshDismissalAdmission()
                val initialTop = dialog.contentView.top
                listener = ViewTreeObserver.OnDrawListener {
                    if (dialog.isShowing && dialog.contentView.top != initialTop &&
                        dialog.contentView.getGlobalVisibleRect(Rect()) && ownerDismissals == 0) intermediate++
                }
                observer = decor.viewTreeObserver.also { it.addOnDrawListener(checkNotNull(listener)) }
                val currentScale = readCachedAnimatorDurationScale(host)
                evidence.put("animator_scale", currentScale).put("busy_veto_retained_action", true)
                checkNotNull(cancel).accessibilitySemanticView.performClick()
                dialog.contentContext.requestAnimatedDismiss()
                checkNotNull(cancel).accessibilitySemanticView.performClick()
                checkNotNull(confirm).accessibilitySemanticView.performClick()
                check(confirmations == 0 && !dialog.contentContext.canAcceptAction()) { "Closing sheet accepted a competing action" }
                if (teardown) {
                    val before = ownerDismissals
                    dialog.dismiss()
                    check(!dialog.isShowing && !decor.isAttachedToWindow) { "Lifecycle teardown waited for exit motion" }
                    dialog.requestAnimatedDismiss()
                    check(ownerDismissals == before) { "Retired sheet delivered dismissal" }
                    evidence.put("owner_count_at_teardown", before)
                }
                currentScale
            }
            val finish = SystemClock.uptimeMillis() + 8000
            while (!main { !dialog.isShowing }) {
                check(SystemClock.uptimeMillis() < finish) { "Presentation exit did not complete" }
                awaitNativeParityDraw(i, anchor)
            }
            repeat(2) { awaitNativeParityDraw(i, anchor) }
            main {
                evidence.put("owner_dismissals", ownerDismissals).put("confirmations", confirmations)
                    .put("intermediate_exit_draws", intermediate)
                check(confirmations == 0)
                if (teardown) check(ownerDismissals == evidence.getInt("owner_count_at_teardown"))
                else {
                    check(ownerDismissals == 1) { "Presentation dismissal was not exactly once" }
                    if (scale > 0f) check(intermediate > 0) { "No retained intermediate exit frame" }
                }
                evidence.put("passed", true)
            }
        } catch (error: Throwable) { failure = error }
        finally {
            failure = captureNativeParityCleanup(failure) { main {
                listener?.let { value -> observer?.takeIf { it.isAlive }?.removeOnDrawListener(value) }
            } }
            failure = captureNativeParityCleanup(failure) { main { sheet?.dismiss() } }
            failure = captureNativeParityCleanup(failure) { main { actions?.close() } }
            failure = captureNativeParityCleanup(failure) { main { cancel?.close() } }
            failure = captureNativeParityCleanup(failure) { main { confirm?.close() } }
            failure?.let { evidence.put("passed", false).put("failure", it.stackTraceToString()) }
        }
        failure?.let { throw it }
    }
}
