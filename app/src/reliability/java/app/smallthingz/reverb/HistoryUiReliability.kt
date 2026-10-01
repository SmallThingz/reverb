package app.smallthingz.reverb

import android.app.Instrumentation
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Actual Compose controls with a deliberately delayed audio queue, in the throwaway package only. */
internal fun verifyLargeHistoryRangeUi(instrumentation: Instrumentation, report: StringBuilder) {
    val context = instrumentation.targetContext
    check(context.packageName.endsWith(".reliability"))
    check(File(context.noBackupFilesDir, "$BUFFER_CACHE_FOLDER_NAME/fixture-ready").isFile) {
        "Prepare synthetic UI history in a fresh QA install first"
    }
    val started = SystemClock.elapsedRealtimeNanos()
    val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    val connected = CountDownLatch(1)
    var service: ReverbService? = null
    val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as ReverbService.BackgroundRecorderBinder).service
            connected.countDown()
        }
        override fun onServiceDisconnected(name: ComponentName) = Unit
    }
    val audioRelease = CountDownLatch(1)
    var bound = false
    try {
        val rangeLabel = context.getString(R.string.export_range_title)
        val loading = context.getString(R.string.loading_audio_history)
        var selectionRequested = false
        awaitUiCondition {
            val ready = findVisibleNode(instrumentation) {
                it.contentDescription?.toString() == rangeLabel && enabledClickTarget(it) != null
            } != null
            if (!ready && !selectionRequested &&
                findVisibleNode(instrumentation) { it.contentDescription?.toString() == loading } == null) {
                findVisibleNode(instrumentation) {
                    it.text?.toString() == context.getString(R.string.buffer_loop) && enabledClickTarget(it) != null
                }?.let { clickUiNode(it); selectionRequested = true }
            }
            ready
        }
        report.append("MEASURE ui_history_ready_ms=${(SystemClock.elapsedRealtimeNanos()-started)/1_000_000.0}\n")
        saveUiCapture(instrumentation, "history-home.png")
        bound = context.bindService(Intent(context, ReverbService::class.java), connection, Context.BIND_AUTO_CREATE)
        check(bound && connected.await(10, TimeUnit.SECONDS))
        val recorder = requireNotNull(service)
        val audio = recorder.uiFixtureField("audioHandler") as Handler
        val blocked = CountDownLatch(1)
        check(audio.post { blocked.countDown(); check(audioRelease.await(15, TimeUnit.SECONDS)) })
        check(blocked.await(5, TimeUnit.SECONDS))
        clickUiNode(requireNotNull(findVisibleNode(instrumentation) { it.contentDescription?.toString() == rangeLabel }))
        val preparing = context.getString(R.string.preparing_audio_selection)
        awaitUiCondition { findVisibleNode(instrumentation) { it.contentDescription?.toString() == preparing } != null }
        val close = requireNotNull(findVisibleNode(instrumentation) {
            it.contentDescription?.toString() == context.getString(R.string.close) && enabledClickTarget(it) != null
        }) { "Preparing range has no visible Close action" }
        val bounds = Rect().also(close::getBoundsInScreen)
        check(bounds.width() > 0 && bounds.height() > 0)
        saveUiCapture(instrumentation, "range-preparing.png")
        clickUiNode(close)
        awaitUiCondition { findVisibleNode(instrumentation) { it.contentDescription?.toString() == rangeLabel } != null }
        audioRelease.countDown()
        val barrier = FutureTask { Unit }
        check(audio.post(barrier)); barrier.get(10, TimeUnit.SECONDS)
        val store = recorder.uiFixtureField("loopingAudioChunkStore") as PersistentAudioChunkStore
        awaitUiCondition {
            synchronized(store) {
                val records = PersistentAudioChunkStore::class.java.getDeclaredField("chunks")
                    .apply { isAccessible = true }.get(store) as Iterable<*>
                records.all { (it as PersistentAudioChunkStore.ChunkRecord).refCount == 0 }
            }
        }
        check(findVisibleNode(instrumentation) { it.contentDescription?.toString() == preparing } == null)
        saveUiCapture(instrumentation, "range-cancelled.png")
        report.append("PASS range_loading_is_visible_cancellable_and_releases_late_snapshot\n")
    } catch (error: Throwable) {
        val root = instrumentation.uiAutomation.rootInActiveWindow
        report.append("UI_FAILURE_ROOT ").append(root?.packageName).append('\n')
        if (root?.packageName?.toString() == context.packageName) {
            runCatching { saveUiCapture(instrumentation, "range-test-failure.png") }
            val pending = ArrayDeque<AccessibilityNodeInfo>()
            pending.add(root)
            var count = 0
            while (pending.isNotEmpty() && count++ < 160) {
                val node = pending.removeFirst()
                if (node.text != null || node.contentDescription != null || node.isClickable) {
                    report.append("UI_NODE text=").append(node.text).append(" desc=").append(node.contentDescription)
                        .append(" visible=").append(node.isVisibleToUser).append(" enabled=").append(node.isEnabled)
                        .append(" clickable=").append(node.isClickable).append('\n')
                }
                repeat(node.childCount) { node.getChild(it)?.let(pending::addLast) }
            }
        }
        throw error
    } finally {
        audioRelease.countDown()
        if (bound) context.unbindService(connection)
        instrumentation.runOnMainSync { activity.finish() }
    }
}

private fun ReverbService.uiFixtureField(name: String): Any? =
    ReverbService::class.java.getDeclaredField(name).apply { isAccessible = true }.get(this)

private fun awaitUiCondition(condition: () -> Boolean) {
    val deadline = SystemClock.uptimeMillis() + 30_000L
    while (!condition()) {
        check(SystemClock.uptimeMillis() < deadline) { "Timed out waiting for QA UI condition" }
        SystemClock.sleep(50L)
    }
}

private fun findVisibleNode(
    instrumentation: Instrumentation,
    matches: (AccessibilityNodeInfo) -> Boolean,
): AccessibilityNodeInfo? {
    // UiAutomation can retain the initial disabled Compose nodes across hydration. Each
    // assertion must inspect current nodes, not turn its own cache into app-state evidence.
    if (Build.VERSION.SDK_INT >= 34) instrumentation.uiAutomation.clearCache()
    val root = instrumentation.uiAutomation.rootInActiveWindow ?: return null
    if (root.packageName?.toString() != instrumentation.targetContext.packageName) return null
    val pending = ArrayDeque<AccessibilityNodeInfo>()
    pending.add(root)
    while (pending.isNotEmpty()) {
        val node = pending.removeFirst()
        if (node.isVisibleToUser && matches(node)) return node
        repeat(node.childCount) { index -> node.getChild(index)?.let(pending::addLast) }
    }
    return null
}

private fun enabledClickTarget(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
    var current: AccessibilityNodeInfo? = node
    while (current != null) {
        if (!current.isEnabled) return null
        if (current.isClickable) return current
        current = current.parent
    }
    return null
}

private fun clickUiNode(node: AccessibilityNodeInfo) {
    check(enabledClickTarget(node)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) {
        "QA control rejected click"
    }
}

private fun saveUiCapture(instrumentation: Instrumentation, name: String) {
    val image = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
    try {
        FileOutputStream(File(instrumentation.targetContext.filesDir, name)).use {
            check(image.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    } finally { image.recycle() }
}
