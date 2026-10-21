package app.smallthingz.reverb

import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.Gravity
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.ObserverHandle
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateObserver
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.Paragraph
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.AndroidFont
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontLoadingStrategy
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import app.smallthingz.reverb.nativeui.ReverbParagraphView
import app.smallthingz.reverb.nativeui.ReverbFontSnapshotNotifications
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONArray
import org.json.JSONObject

/**
 * Run alone in a fresh instrumentation process, before any ComposeView exists.
 * The controlled font uses built-in Typeface data: no provider, download, permissions, or app data.
 * The fixture never calls Snapshot apply/initialization APIs and never starts GlobalSnapshotManager.
 */
@OptIn(ExperimentalTextApi::class)
internal fun verifyNativeFontNotifications(instrumentation: Instrumentation, report: StringBuilder) {
    val context = instrumentation.targetContext
    check(context.packageName == "app.smallthingz.reverb.nativeparity.reliability")
    check(context.applicationContext.javaClass == Application::class.java)
    val directory = File(context.filesDir, "native-font-notifications-" + System.currentTimeMillis())
    check(directory.mkdir())
    val cases = JSONArray()
    val result = JSONObject().put("cases", cases)
        .put("test", "Native-only asynchronous font delivery without Compose bootstrap")
        .put("snapshot_apply_calls_from_fixture", 0)
        .put("limits", "Built-in controlled async fonts; detached observable invalidation only, not private callback counters")
    var activity: NativeParityActivity? = null
    var terminalFailure: Throwable? = null
    val loaders = mutableListOf<ControlledFont>()
    val bitmaps = mutableListOf<Bitmap>()
    try {
        requireSnapshotManagerNotStarted()
        result.put("compose_snapshot_manager_started_before", false)
        val host = launchNativeParityActivity(instrumentation)
        activity = host
        awaitNativeParityDraw(instrumentation, host.fixtureRoot)
        requireNativeParityHost(instrumentation, host)
        requireSnapshotManagerNotStarted()
        val density = Density(context.resources.displayMetrics.density, context.resources.configuration.fontScale)
        val text = "iiii WWWW 0123456789"
        val targetTypeface = Typeface.MONOSPACE
        lateinit var resolver: FontFamily.Resolver
        instrumentation.runOnMainSync { resolver = createFontFamilyResolver(host) }

        fun createView(font: ControlledFont): ReverbParagraphView = ReverbParagraphView(
            host, TextStyle(fontFamily = FontFamily(font), fontSize = 20.sp), density,
            resolver, 0xff111111.toInt(),
        ).apply { setText(text) }

        fun attach(view: ReverbParagraphView) {
            check(host.hasWindowFocus())
            host.fixtureRoot.removeAllViews()
            host.fixtureRoot.setBackgroundColor(0xffeeeeee.toInt())
            host.fixtureRoot.addView(view, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER,
            ))
        }

        // Once the initial draw and all queued frame callbacks have finished, only font completion
        // may invalidate this paragraph. Capture helpers are not invoked until automatic draw proves it.
        val attachedFont = ControlledFont().also(loaders::add)
        lateinit var attached: ReverbParagraphView
        lateinit var attachedState: State<Any>
        instrumentation.runOnMainSync {
            attachedState = resolver.resolve(FontFamily(attachedFont))
            attached = createView(attachedFont)
            attach(attached)
        }
        check(attachedFont.started.await(5, TimeUnit.SECONDS)) { "Controlled font loader did not start" }
        awaitNativeParityDraw(instrumentation, attached)
        awaitFontFrames(instrumentation, 4)
        requireNativeParityHost(instrumentation, host)
        lateinit var initialParagraph: Paragraph
        var initialWidth = 0
        instrumentation.runOnMainSync {
            initialParagraph = checkNotNull(attached.currentParagraph)
            initialWidth = attached.maxIntrinsicTextWidth()
            check(attachedState.value !== targetTypeface)
            check(!attached.isLayoutRequested)
        }
        bitmaps += captureNativeParityContent(instrumentation, host) { attached }
        saveNativeParityBitmap(directory, "attached-before.png", bitmaps.last())
        awaitFontFrames(instrumentation, 3)
        val automaticLayout = CountDownLatch(1)
        val automaticDraw = CountDownLatch(1)
        lateinit var layoutListener: ViewTreeObserver.OnGlobalLayoutListener
        lateinit var drawListener: ViewTreeObserver.OnDrawListener
        instrumentation.runOnMainSync {
            layoutListener = ViewTreeObserver.OnGlobalLayoutListener {
                if (attached.currentParagraph != null && attached.currentParagraph !== initialParagraph) {
                    automaticLayout.countDown()
                }
            }
            drawListener = ViewTreeObserver.OnDrawListener {
                if (attached.currentParagraph != null && attached.currentParagraph !== initialParagraph &&
                    automaticLayout.count == 0L) automaticDraw.countDown()
            }
            attached.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
            attached.viewTreeObserver.addOnDrawListener(drawListener)
        }
        try {
            check(attachedFont.result.complete(targetTypeface))
            check(automaticLayout.await(5, TimeUnit.SECONDS)) { "Font completion did not autonomously relayout the native View" }
            check(automaticDraw.await(5, TimeUnit.SECONDS)) { "Font completion did not autonomously redraw the native View" }
        } finally {
            instrumentation.runOnMainSync {
                if (attached.viewTreeObserver.isAlive) {
                    attached.viewTreeObserver.removeOnGlobalLayoutListener(layoutListener)
                    attached.viewTreeObserver.removeOnDrawListener(drawListener)
                }
            }
        }
        requireNativeParityHost(instrumentation, host)
        var resolvedWidth = 0
        instrumentation.runOnMainSync {
            check(attachedState.value === targetTypeface)
            resolvedWidth = attached.maxIntrinsicTextWidth()
            check(resolvedWidth != initialWidth) { "Controlled typeface did not change observable text metrics" }
        }
        bitmaps += captureNativeParityContent(instrumentation, host) { attached }
        saveNativeParityBitmap(directory, "attached-after.png", bitmaps.last())
        cases.put(JSONObject().put("name", "attached-font-completion")
            .put("automatic_layout", true).put("automatic_draw", true)
            .put("fallback_intrinsic_width", initialWidth).put("resolved_intrinsic_width", resolvedWidth)
            .put("passed", true))

        // Completion while detached must not clear/rebuild the detached paragraph or request layout.
        // Reattachment must see the latest state even though no observer was active at completion.
        val detachedFont = ControlledFont().also(loaders::add)
        lateinit var detached: ReverbParagraphView
        lateinit var detachedState: State<Any>
        instrumentation.runOnMainSync {
            detachedState = resolver.resolve(FontFamily(detachedFont))
            detached = createView(detachedFont)
            attach(detached)
        }
        check(detachedFont.started.await(5, TimeUnit.SECONDS))
        awaitNativeParityDraw(instrumentation, detached)
        awaitFontFrames(instrumentation, 4)
        lateinit var detachedParagraph: Paragraph
        var detachedLayoutFlag = false
        instrumentation.runOnMainSync {
            host.fixtureRoot.removeView(detached)
            check(!detached.isAttachedToWindow)
            detachedParagraph = checkNotNull(detached.currentParagraph)
            detachedLayoutFlag = detached.isLayoutRequested
        }
        check(detachedFont.result.complete(targetTypeface))
        awaitResolvedTypeface(instrumentation, detachedState, targetTypeface)
        awaitFontFrames(instrumentation, 4)
        requireNativeParityHost(instrumentation, host)
        instrumentation.runOnMainSync {
            check(detached.currentParagraph === detachedParagraph) { "Detached font completion invalidated paragraph" }
            check(detached.isLayoutRequested == detachedLayoutFlag) { "Detached font completion requested layout" }
            attach(detached)
        }
        awaitNativeParityDraw(instrumentation, detached)
        instrumentation.runOnMainSync {
            check(detached.currentParagraph !== detachedParagraph)
            check(detached.maxIntrinsicTextWidth() == resolvedWidth) { "Reattached paragraph did not use completed font" }
        }
        cases.put(JSONObject().put("name", "detached-completion-and-reattach")
            .put("no_observable_detached_invalidation", true).put("reattached_latest_font", true).put("passed", true))

        // A completion before paragraph/read registration must not be lost.
        val precompletedFont = ControlledFont().also(loaders::add)
        lateinit var precompletedState: State<Any>
        instrumentation.runOnMainSync {
            host.fixtureRoot.removeAllViews()
            precompletedState = resolver.resolve(FontFamily(precompletedFont))
        }
        check(precompletedFont.started.await(5, TimeUnit.SECONDS))
        check(precompletedFont.result.complete(targetTypeface))
        awaitResolvedTypeface(instrumentation, precompletedState, targetTypeface)
        lateinit var precompleted: ReverbParagraphView
        instrumentation.runOnMainSync { precompleted = createView(precompletedFont); attach(precompleted) }
        awaitNativeParityDraw(instrumentation, precompleted)
        instrumentation.runOnMainSync {
            check(precompleted.maxIntrinsicTextWidth() == resolvedWidth) { "Pre-registration font completion was lost" }
        }
        cases.put(JSONObject().put("name", "completion-before-paragraph-registration").put("passed", true))
        instrumentation.runOnMainSync { host.fixtureRoot.removeAllViews() }
        awaitFontFrames(instrumentation, 4)
        verifyFontNotificationBridge(instrumentation, cases)
        requireSnapshotManagerNotStarted()
        result.put("compose_snapshot_manager_started_after", false)
        report.append("PASS native_font_notifications actual-font lifecycle and scoped notification filtering/handoff; no Compose bootstrap\n")
    } catch (error: Throwable) {
        terminalFailure = error
        result.put("terminal_error", android.util.Log.getStackTraceString(error))
        throw error
    } finally {
        var cleanupFailure: Throwable? = null
        fun cleanup(block: () -> Unit) {
            try { block() } catch (error: Throwable) {
                if (cleanupFailure == null) cleanupFailure = error else cleanupFailure!!.addSuppressed(error)
            }
        }
        cleanup { instrumentation.runOnMainSync { activity?.finish() } }
        cleanup { loaders.forEach { it.result.cancel() } }
        cleanup { bitmaps.forEach(Bitmap::recycle) }
        cleanupFailure?.let {
            terminalFailure?.addSuppressed(it)
            result.put("cleanup_error", android.util.Log.getStackTraceString(it))
        }
        result.put("passed", terminalFailure == null && cleanupFailure == null && cases.length() == 5)
        report.append("PARITY_EVIDENCE ").append(directory.name).append("/result.json\n")
        try { File(directory, "result.json").writeText(result.toString(2)) }
        catch (error: Throwable) {
            val primary = terminalFailure ?: cleanupFailure
            if (primary == null) throw error else primary.addSuppressed(error)
        }
        if (terminalFailure == null) cleanupFailure?.let { throw it }
    }
}

/** Synthetic font-read state exercises the production bridge, never the app's settings/state. */
private fun verifyFontNotificationBridge(instrumentation: Instrumentation, cases: JSONArray) {
    val handler = Handler(Looper.getMainLooper())
    val observer = SnapshotStateObserver { command -> handler.post { command() } }
    val scope = Any()
    val raceScope = Any()
    val tracked = mutableStateOf(0)
    val unrelated = mutableStateOf(0)
    val raced = mutableStateOf(false)
    val callbacks = AtomicInteger()
    val delivered = CountDownLatch(1)
    val raceDelivered = CountDownLatch(1)
    val unrelatedApplied = AtomicBoolean(false)
    var applyObserver: ObserverHandle? = null
    var primaryFailure: Throwable? = null
    try {
        instrumentation.runOnMainSync {
            observer.start()
            ReverbFontSnapshotNotifications.observeReads(scope) {
                observer.observeReads(scope, { _: Any -> callbacks.incrementAndGet(); delivered.countDown() }) {
                    tracked.value
                }
            }
        }
        awaitFontFrames(instrumentation, 5)
        instrumentation.runOnMainSync {
            check(callbacks.get() == 0) { "Read registration unexpectedly invalidated its owner" }
            applyObserver = Snapshot.registerApplyObserver { changed, _ ->
                if (changed.any { it === unrelated }) unrelatedApplied.set(true)
            }
            unrelated.value = 1
        }
        awaitFontFrames(instrumentation, 5)
        check(!unrelatedApplied.get() && callbacks.get() == 0) {
            "An unrelated write caused snapshot apply or owner invalidation without a Compose host"
        }
        instrumentation.runOnMainSync { tracked.value = 1 }
        check(delivered.await(5, TimeUnit.SECONDS)) { "Tracked font-read state did not deliver" }
        awaitFontFrames(instrumentation, 3)
        check(callbacks.get() == 1)
        val unrelatedDeliveredWithTracked = unrelatedApplied.get()
        instrumentation.runOnMainSync {
            ReverbFontSnapshotNotifications.clear(scope)
            observer.clear(scope)
            tracked.value = 2
        }
        awaitFontFrames(instrumentation, 5)
        check(callbacks.get() == 1) { "Cleared font-read scope still received invalidation" }
        cases.put(JSONObject().put("name", "scoped-write-filter-and-detach")
            .put("unrelated_write_alone_scheduled_apply", false)
            .put("tracked_write_delivered", true).put("cleared_scope_received_callback", false)
            .put("pending_unrelated_write_delivered_with_tracked", unrelatedDeliveredWithTracked).put("passed", true))

        instrumentation.runOnMainSync {
            ReverbFontSnapshotNotifications.observeReads(raceScope) {
                observer.observeReads(raceScope, { _: Any -> raceDelivered.countDown() }) { raced.value }
                // Completion after the font-state read but before bridge interest registration.
                // This is a helper seam, not an injected private ParagraphIntrinsics hook.
                raced.value = true
            }
        }
        check(raceDelivered.await(5, TimeUnit.SECONDS)) { "Read-to-registration font state completion was lost" }
        cases.put(JSONObject().put("name", "bridge-read-to-registration-handoff")
            .put("scope", "Synthetic font-read state; actual Paragraph race remains source-reviewed").put("passed", true))
    } catch (error: Throwable) {
        primaryFailure = error
        throw error
    } finally {
        try {
            instrumentation.runOnMainSync {
                try {
                    ReverbFontSnapshotNotifications.clear(scope)
                    ReverbFontSnapshotNotifications.clear(raceScope)
                    observer.stop()
                    observer.clear()
                } finally { applyObserver?.dispose() }
            }
        } catch (error: Throwable) {
            if (primaryFailure == null) throw error else primaryFailure.addSuppressed(error)
        }
    }
}

private fun requireSnapshotManagerNotStarted() {
    val started = Class.forName("androidx.compose.ui.platform.GlobalSnapshotManager")
        .getDeclaredField("started").apply { isAccessible = true }.get(null) as AtomicBoolean
    check(!started.get()) { "Run native_font_notifications alone in a fresh process before any ComposeView" }
}

/** Schedules only frame callbacks, never invalidate/requestLayout or snapshot apply notifications. */
private fun awaitFontFrames(instrumentation: Instrumentation, count: Int) {
    val complete = CountDownLatch(1)
    lateinit var callback: Choreographer.FrameCallback
    instrumentation.runOnMainSync {
        var remaining = count
        callback = Choreographer.FrameCallback {
            if (--remaining == 0) complete.countDown()
            else Choreographer.getInstance().postFrameCallback(callback)
        }
        Choreographer.getInstance().postFrameCallback(callback)
    }
    try { check(complete.await(5, TimeUnit.SECONDS)) { "Native frame barrier timed out" } }
    finally { instrumentation.runOnMainSync { Choreographer.getInstance().removeFrameCallback(callback) } }
}

private fun awaitResolvedTypeface(instrumentation: Instrumentation, state: State<Any>, expected: Typeface) {
    var resolved = false
    repeat(30) {
        instrumentation.runOnMainSync { resolved = state.value === expected }
        if (resolved) return
        awaitFontFrames(instrumentation, 2)
    }
    error("Controlled font loader completed but resolver did not publish its Typeface")
}

@OptIn(ExperimentalTextApi::class)
private class ControlledFont : AndroidFont(
    FontLoadingStrategy.Async, ControlledFontLoader, FontVariation.Settings(),
) {
    override val weight = FontWeight.Normal
    override val style = FontStyle.Normal
    val started = CountDownLatch(1)
    val result = CompletableDeferred<Typeface?>()
}

@OptIn(ExperimentalTextApi::class)
private object ControlledFontLoader : AndroidFont.TypefaceLoader {
    override fun loadBlocking(context: Context, font: AndroidFont): Typeface? =
        error("Controlled asynchronous font was loaded synchronously")

    override suspend fun awaitLoad(context: Context, font: AndroidFont): Typeface? {
        val controlled = font as ControlledFont
        controlled.started.countDown()
        return controlled.result.await()
    }
}
