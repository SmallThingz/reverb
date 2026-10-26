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
    var failure: Throwable? = null
    try {
        val rangeLabel = context.getString(R.string.export_range_title)
        val loading = context.getString(R.string.loading_audio_history)
        var selectionRequested = false
        awaitUiCondition {
            val ready = hasVisibleNode(instrumentation) {
                it.contentDescription?.toString() == rangeLabel && hasEnabledClickTarget(it)
            }
            if (!ready && !selectionRequested &&
                !hasVisibleNode(instrumentation) { it.contentDescription?.toString() == loading }) {
                findVisibleNode(instrumentation) {
                    it.text?.toString() == context.getString(R.string.buffer_loop) && hasEnabledClickTarget(it)
                }?.let { node ->
                    try { clickUiNode(node); selectionRequested = true }
                    finally { @Suppress("DEPRECATION") node.recycle() }
                }
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
        val range = requireNotNull(findVisibleNode(instrumentation) { it.contentDescription?.toString() == rangeLabel })
        try { clickUiNode(range) } finally { @Suppress("DEPRECATION") range.recycle() }
        val preparing = context.getString(R.string.preparing_audio_selection)
        awaitUiCondition { hasVisibleNode(instrumentation) { it.contentDescription?.toString() == preparing } }
        val close = requireNotNull(findVisibleNode(instrumentation) {
            it.contentDescription?.toString() == context.getString(R.string.close) && hasEnabledClickTarget(it)
        }) { "Preparing range has no visible Close action" }
        try {
            val bounds = Rect().also(close::getBoundsInScreen)
            check(bounds.width() > 0 && bounds.height() > 0)
            saveUiCapture(instrumentation, "range-preparing.png")
            clickUiNode(close)
        } finally { @Suppress("DEPRECATION") close.recycle() }
        awaitUiCondition { hasVisibleNode(instrumentation) { it.contentDescription?.toString() == rangeLabel } }
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
        check(!hasVisibleNode(instrumentation) { it.contentDescription?.toString() == preparing })
        saveUiCapture(instrumentation, "range-cancelled.png")
        report.append("PASS range_loading_is_visible_cancellable_and_releases_late_snapshot\n")
    } catch (error: Throwable) {
        failure = error
        try {
            val pending = ArrayDeque<AccessibilityNodeInfo>()
            var root = instrumentation.uiAutomation.rootInActiveWindow
            try {
                report.append("UI_FAILURE_ROOT ").append(root?.packageName).append('\n')
                if (root?.packageName?.toString() == context.packageName) {
                    runCatching { saveUiCapture(instrumentation, "range-test-failure.png") }
                    pending.add(checkNotNull(root))
                    root = null // The queue now owns this snapshot.
                    var count = 0
                    while (pending.isNotEmpty() && count++ < 160) {
                        val node = pending.removeFirst()
                        try {
                            if (node.text != null || node.contentDescription != null || node.isClickable) {
                                report.append("UI_NODE text=").append(node.text).append(" desc=").append(node.contentDescription)
                                    .append(" visible=").append(node.isVisibleToUser).append(" enabled=").append(node.isEnabled)
                                    .append(" clickable=").append(node.isClickable).append('\n')
                            }
                            repeat(node.childCount) { node.getChild(it)?.let(pending::addLast) }
                        } finally { @Suppress("DEPRECATION") node.recycle() }
                    }
                }
            } finally {
                @Suppress("DEPRECATION") root?.recycle()
                while (pending.isNotEmpty()) {
                    @Suppress("DEPRECATION") pending.removeFirst().recycle()
                }
            }
        } catch (diagnosticFailure: Throwable) {
            if (diagnosticFailure !== error) error.addSuppressed(diagnosticFailure)
        }
    } finally {
        failure = captureNativeParityCleanup(failure) { audioRelease.countDown() }
        failure = captureNativeParityCleanup(failure) { if (bound) context.unbindService(connection) }
        failure = captureNativeParityCleanup(failure) { instrumentation.runOnMainSync { activity.finish() } }
    }
    failure?.let { throw it }
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

/** Returns an owned snapshot; the caller must recycle it. */
private fun findVisibleNode(
    instrumentation: Instrumentation,
    matches: (AccessibilityNodeInfo) -> Boolean,
): AccessibilityNodeInfo? {
    // UiAutomation can retain the initial disabled Compose nodes across hydration. Each
    // assertion must inspect current nodes, not turn its own cache into app-state evidence.
    if (Build.VERSION.SDK_INT >= 34) instrumentation.uiAutomation.clearCache()
    val pending = ArrayDeque<AccessibilityNodeInfo>()
    var root: AccessibilityNodeInfo? = instrumentation.uiAutomation.rootInActiveWindow ?: return null
    try {
        if (root?.packageName?.toString() != instrumentation.targetContext.packageName) return null
        pending.add(checkNotNull(root))
        root = null // The queue now owns this snapshot.
        while (pending.isNotEmpty()) {
            val node = pending.removeFirst()
            var retained = false
            try {
                if (node.isVisibleToUser && matches(node)) {
                    retained = true
                    return node
                }
                repeat(node.childCount) { index -> node.getChild(index)?.let(pending::addLast) }
            } finally { if (!retained) { @Suppress("DEPRECATION") node.recycle() } }
        }
        return null
    } finally {
        @Suppress("DEPRECATION") root?.recycle()
        while (pending.isNotEmpty()) {
            @Suppress("DEPRECATION") pending.removeFirst().recycle()
        }
    }
}

private fun hasVisibleNode(instrumentation: Instrumentation, matches: (AccessibilityNodeInfo) -> Boolean): Boolean {
    val node = findVisibleNode(instrumentation, matches) ?: return false
    try { return true } finally { @Suppress("DEPRECATION") node.recycle() }
}

/** Borrows node; an ancestor result is owned by the caller and must be recycled. */
private fun enabledClickTarget(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
    var current: AccessibilityNodeInfo? = node
    try {
        while (current != null) {
            if (!current.isEnabled) return null
            if (current.isClickable) {
                val target = current
                current = null // Transfer any owned ancestor snapshot to the caller.
                return target
            }
            val previous = current
            current = previous.parent
            if (previous !== node) { @Suppress("DEPRECATION") previous.recycle() }
        }
        return null
    } finally {
        if (current !== node) { @Suppress("DEPRECATION") current?.recycle() }
    }
}

private fun hasEnabledClickTarget(node: AccessibilityNodeInfo): Boolean {
    val target = enabledClickTarget(node) ?: return false
    try { return true }
    finally { if (target !== node) { @Suppress("DEPRECATION") target.recycle() } }
}

private fun clickUiNode(node: AccessibilityNodeInfo) {
    val target = enabledClickTarget(node)
    try {
        check(target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) { "QA control rejected click" }
    } finally { if (target !== node) { @Suppress("DEPRECATION") target?.recycle() } }
}

private fun saveUiCapture(instrumentation: Instrumentation, name: String) {
    val image = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
    try {
        FileOutputStream(File(instrumentation.targetContext.filesDir, name)).use {
            check(image.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    } finally { image.recycle() }
}
