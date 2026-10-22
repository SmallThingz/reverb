package app.smallthingz.reverb

import android.app.Application
import android.app.Instrumentation
import android.graphics.Typeface
import android.view.Choreographer
import android.view.Gravity
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import androidx.emoji2.text.EmojiCompat
import androidx.emoji2.text.MetadataRepo
import app.smallthingz.nativeui.text.Paragraph
import app.smallthingz.nativeui.text.ParagraphIntrinsics
import app.smallthingz.nativeui.text.TextStyle
import app.smallthingz.nativeui.text.font.createFontFamilyResolver
import app.smallthingz.nativeui.unit.Density
import app.smallthingz.nativeui.unit.sp
import app.smallthingz.reverb.nativeui.ReverbParagraphView
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject

/**
 * Isolated QA only. Uses EmojiCompat's documented public test reset and empty MetadataRepo APIs,
 * then restores its previous singleton. No reflection, provider, downloaded font or application data.
 * Empty metadata exercises readiness delivery, not emoji glyph rasterization.
 *
 * Retires the former Compose AndroidFont/Snapshot bridge tests: neither mechanism is in production.
 */
@Suppress("RestrictedApi")
internal fun verifyNativeFontNotifications(instrumentation: Instrumentation, report: StringBuilder) {
    val context = instrumentation.targetContext
    check(context.packageName == "app.smallthingz.reverb.nativeparity.reliability")
    check(context.applicationContext.javaClass == Application::class.java)
    val directory = File(context.filesDir, "native-font-notifications-" + System.currentTimeMillis())
    check(directory.mkdir())
    val cases = JSONArray()
    val result = JSONObject().put("cases", cases)
        .put("test", "Native EmojiCompat readiness callback lifecycle")
        .put("scope", "Empty metadata readiness and attached View invalidation; glyph rasterization is separate")
        .put("retired_cases", JSONArray(listOf("Compose downloadable AndroidFont", "Snapshot write filtering")))
    var previous: EmojiCompat? = null
    var activity: NativeParityActivity? = null
    var terminalFailure: Throwable? = null
    try {
        instrumentation.runOnMainSync {
            previous = if (EmojiCompat.isConfigured()) EmojiCompat.get() else null
        }
        val host = launchNativeParityActivity(instrumentation)
        activity = host
        awaitNativeParityDraw(instrumentation, host.fixtureRoot)
        requireNativeParityHost(instrumentation, host)
        val density = Density(host)
        val fonts = createFontFamilyResolver(host)
        val style = TextStyle(fontSize = 20.sp)

        fun startLoading(): ControlledEmojiLoader {
            val loader = ControlledEmojiLoader()
            instrumentation.runOnMainSync {
                host.fixtureRoot.removeAllViews()
                EmojiCompat.reset(object : EmojiCompat.Config(loader) {})
            }
            check(loader.started.await(5, TimeUnit.SECONDS)) { "Emoji metadata loader did not start" }
            return loader
        }
        fun createView(): ReverbParagraphView = ReverbParagraphView(
            host, style, density, fonts, 0xff111111.toInt(),
        ).apply { setText("Audio ready 😀") }
        fun attach(view: ReverbParagraphView) {
            check(host.hasWindowFocus())
            host.fixtureRoot.removeAllViews()
            host.fixtureRoot.setBackgroundColor(0xffeeeeee.toInt())
            host.fixtureRoot.addView(view, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }

        val attachedLoader = startLoading()
        lateinit var attached: ReverbParagraphView
        instrumentation.runOnMainSync { attached = createView(); attach(attached) }
        awaitNativeParityDraw(instrumentation, attached)
        awaitFontFrames(instrumentation, 4)
        lateinit var initial: Paragraph
        val automaticLayout = CountDownLatch(1)
        val automaticDraw = CountDownLatch(1)
        lateinit var layoutListener: ViewTreeObserver.OnGlobalLayoutListener
        lateinit var drawListener: ViewTreeObserver.OnDrawListener
        instrumentation.runOnMainSync {
            initial = checkNotNull(attached.currentParagraph)
            check(!attached.isLayoutRequested)
            layoutListener = ViewTreeObserver.OnGlobalLayoutListener {
                if (attached.currentParagraph != null && attached.currentParagraph !== initial)
                    automaticLayout.countDown()
            }
            drawListener = ViewTreeObserver.OnDrawListener {
                if (automaticLayout.count == 0L && attached.currentParagraph !== initial)
                    automaticDraw.countDown()
            }
            attached.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
            attached.viewTreeObserver.addOnDrawListener(drawListener)
        }
        try {
            attachedLoader.complete()
            check(automaticLayout.await(5, TimeUnit.SECONDS)) { "Emoji readiness did not relayout attached text" }
            check(automaticDraw.await(5, TimeUnit.SECONDS)) { "Emoji readiness did not redraw attached text" }
        } finally {
            instrumentation.runOnMainSync {
                if (attached.viewTreeObserver.isAlive) {
                    attached.viewTreeObserver.removeOnGlobalLayoutListener(layoutListener)
                    attached.viewTreeObserver.removeOnDrawListener(drawListener)
                }
            }
        }
        requireNativeParityHost(instrumentation, host)
        cases.put(JSONObject().put("name", "attached-readiness").put("automatic_layout", true)
            .put("automatic_draw", true).put("passed", true))

        val detachedLoader = startLoading()
        lateinit var detached: ReverbParagraphView
        lateinit var detachedParagraph: Paragraph
        var detachedRequestedLayout = false
        instrumentation.runOnMainSync { detached = createView(); attach(detached) }
        awaitNativeParityDraw(instrumentation, detached)
        awaitFontFrames(instrumentation, 3)
        instrumentation.runOnMainSync {
            host.fixtureRoot.removeView(detached)
            detachedParagraph = checkNotNull(detached.currentParagraph)
            detachedRequestedLayout = detached.isLayoutRequested
        }
        detachedLoader.complete()
        awaitFontFrames(instrumentation, 4)
        instrumentation.runOnMainSync {
            check(detached.currentParagraph === detachedParagraph)
            check(detached.isLayoutRequested == detachedRequestedLayout)
            attach(detached)
        }
        awaitNativeParityDraw(instrumentation, detached)
        instrumentation.runOnMainSync { check(detached.currentParagraph !== detachedParagraph) }
        cases.put(JSONObject().put("name", "detached-readiness-and-reattach").put("passed", true))

        val precompleted = startLoading()
        precompleted.complete()
        awaitFontFrames(instrumentation, 2)
        instrumentation.runOnMainSync {
            val view = createView()
            attach(view)
            check(EmojiCompat.get().loadState == EmojiCompat.LOAD_STATE_SUCCEEDED)
        }
        awaitNativeParityDraw(instrumentation, host.fixtureRoot)
        cases.put(JSONObject().put("name", "completion-before-registration").put("passed", true))

        val canceledLoader = startLoading()
        val callbacks = AtomicInteger()
        instrumentation.runOnMainSync {
            val intrinsics = ParagraphIntrinsics("Audio", style, density = density, fontFamilyResolver = fonts)
            val lease = intrinsics.observeFontChanges { callbacks.incrementAndGet() }
            canceledLoader.complete()
            // Init delivery is posted. Closing before its dispatch must suppress that queued callback.
            lease.close()
        }
        awaitFontFrames(instrumentation, 4)
        check(callbacks.get() == 0) { "Closed font readiness subscription delivered a callback" }
        cases.put(JSONObject().put("name", "closed-queued-callback").put("passed", true))

        val failedLoader = startLoading()
        lateinit var failed: ReverbParagraphView
        lateinit var failedParagraph: Paragraph
        instrumentation.runOnMainSync { failed = createView(); attach(failed) }
        awaitNativeParityDraw(instrumentation, failed)
        awaitFontFrames(instrumentation, 3)
        instrumentation.runOnMainSync { failedParagraph = checkNotNull(failed.currentParagraph) }
        failedLoader.fail()
        awaitFontFrames(instrumentation, 4)
        instrumentation.runOnMainSync {
            check(EmojiCompat.get().loadState == EmojiCompat.LOAD_STATE_FAILED)
            check(failed.currentParagraph === failedParagraph)
            check(!failed.isLayoutRequested)
        }
        cases.put(JSONObject().put("name", "failed-load-retains-fallback").put("passed", true))
        report.append("PASS native_font_notifications native EmojiCompat lifecycle; empty metadata, glyph parity separate\n")
    } catch (error: Throwable) {
        terminalFailure = error
        result.put("terminal_error", android.util.Log.getStackTraceString(error))
        throw error
    } finally {
        var cleanupFailure: Throwable? = null
        try {
            instrumentation.runOnMainSync {
                activity?.fixtureRoot?.removeAllViews()
                activity?.finish()
                EmojiCompat.reset(previous)
            }
        } catch (error: Throwable) {
            cleanupFailure = error
            terminalFailure?.addSuppressed(error)
            result.put("cleanup_error", android.util.Log.getStackTraceString(error))
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

private class ControlledEmojiLoader : EmojiCompat.MetadataRepoLoader {
    val started = CountDownLatch(1)
    private val callback = AtomicReference<EmojiCompat.MetadataRepoLoaderCallback>()
    override fun load(value: EmojiCompat.MetadataRepoLoaderCallback) {
        check(callback.compareAndSet(null, value))
        started.countDown()
    }
    @Suppress("RestrictedApi")
    fun complete() = checkNotNull(callback.getAndSet(null)).onLoaded(MetadataRepo.create(Typeface.DEFAULT))
    fun fail() = checkNotNull(callback.getAndSet(null)).onFailed(IllegalStateException("Controlled QA failure"))
}

/** Schedules only frame callbacks; does not request layout or invalidate the tested paragraph. */
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
