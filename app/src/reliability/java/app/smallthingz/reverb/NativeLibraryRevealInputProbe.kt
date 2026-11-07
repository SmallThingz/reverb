package app.smallthingz.reverb

import android.app.Application
import android.app.Instrumentation
import android.graphics.Matrix
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.smallthingz.reverb.nativeui.*
import smallthingz.ui.geometry.Offset
import smallthingz.ui.input.pointer.PointerType

/** Actual production observer node and RecyclerView. Synthetic rows; no recording/service access. */
internal fun verifyNativeLibraryRevealInputCancellation(instrumentation: Instrumentation, report: StringBuilder) {
    val context = instrumentation.targetContext
    check(context.packageName == "app.smallthingz.reverb.uitheme.reliability")
    check(context.applicationContext.javaClass == Application::class.java)
    val preferences = getRecorderPreferences(context).all.toMap()
    val host = launchCurrentUiSmokeActivity(instrumentation)
    val runtime = nativeParityOnMain(instrumentation) { NativeAboutWindowRuntime(host).apply { setStarted(true) } }
    var recycler: ReverbLibraryRecyclerView? = null
    var owner: NativeLibraryScrollOwner? = null
    var failure: Throwable? = null
    try {
        nativeParityOnMain(instrumentation) {
            val environment = nativeParityEnvironment(host,
                androidx.compose.ui.unit.Density(host.resources.displayMetrics.density, host.resources.configuration.fontScale),
                ReverbPalette.resolve(host, false))
            val view = ReverbLibraryRecyclerView(host).also { recycler = it }
            view.layoutManager = LinearLayoutManager(host)
            view.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                override fun getItemCount() = 200
                override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
                    object : RecyclerView.ViewHolder(TextView(parent.context).apply {
                        layoutParams = RecyclerView.LayoutParams(-1, 120)
                    }) {}
                override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                    (holder.itemView as TextView).text = "Synthetic row $position"
                }
            }
            host.fixtureRoot.addView(view, FrameLayout.LayoutParams(-1, -1))
            val nested = object : NativeVerticalNestedHost {
                override fun preScroll(availableY: Float, source: NativeVerticalScrollSource) = 0f
                override fun postScroll(consumedY: Float, availableY: Float, source: NativeVerticalScrollSource) = 0f
                override suspend fun preFling(availableY: Float) = 0f
                override suspend fun postFling(consumedY: Float, availableY: Float) = 0f
            }
            owner = NativeLibraryScrollOwner(view, environment, runtime.frames, runtime.scope, nested, { false })
        }
        repeat(3) { awaitNativeParityDraw(instrumentation, host.fixtureRoot); instrumentation.waitForIdleSync() }
        nativeParityOnMain(instrumentation) {
            val view = checkNotNull(recycler)
            val scroll = checkNotNull(owner)
            check(view.isAttachedToWindow && view.height > 0 && view.canScrollVertically(1))
            var pending = NativePendingListPosition(null)
            val deferredReveal = NativeRecordingRevealRequest()
            var cancellations = 0
            view.sourceOnUserScroll = {
                cancellations++
                pending.cancelKeyedRequest()
                deferredReveal.cancel()
            }
            fun arm() { pending.request(NativeListSavedPosition(80, 0), "recording-target") }
            fun retained() { check(pending.requestedKey == "recording-target") }
            fun cleared() { check(!pending.hasKeyedRequest) }
            fun pointer(action: Int, pressed: Boolean, previouslyPressed: Boolean,
                        pass: NativePointerPass = NativePointerPass.INITIAL) {
                val now = SystemClock.uptimeMillis()
                val motion = MotionEvent.obtain(now, now, action, 30f, 30f, 0)
                try {
                    scroll.observerNode.bindMotion(motion, NativePointerCoordinates(Matrix(), Matrix(), Offset.Zero), 1)
                    scroll.observerNode.dispatch(NativeDragPointerEvent(listOf(NativeDragPointerChange(
                        0L, now, Offset(30f, 30f), Offset(30f, 30f), pressed, previouslyPressed,
                        PointerType.Touch, Offset(30f, 30f))), Offset.Zero), pass)
                } finally { scroll.observerNode.bindMotion(null, null, 0); motion.recycle() }
            }
            fun wheel(source: Int = InputDevice.SOURCE_MOUSE, axis: Int = MotionEvent.AXIS_VSCROLL,
                      value: Float = -3f, action: Int = MotionEvent.ACTION_SCROLL) {
                val properties = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE }
                val coords = MotionEvent.PointerCoords().apply { x = 30f; y = 30f; setAxisValue(axis, value) }
                val now = SystemClock.uptimeMillis()
                val event = MotionEvent.obtain(now, now, action, 1, arrayOf(properties), arrayOf(coords),
                    0, 0, 1f, 1f, 0, 0, source, 0)
                try { view.onGenericMotionEvent(event) } finally { event.recycle() }
            }

            arm()
            pointer(MotionEvent.ACTION_HOVER_MOVE, false, false); retained()
            pointer(MotionEvent.ACTION_MOVE, true, true); retained()
            pointer(MotionEvent.ACTION_UP, false, true); retained()
            pointer(MotionEvent.ACTION_DOWN, true, false, NativePointerPass.MAIN); retained()
            check(cancellations == 0)
            pointer(MotionEvent.ACTION_DOWN, true, false)
            cleared()
            check(cancellations == 1) { "DOWN must revoke reveal before drag slop or scroll consumption" }
            check(pending.pendingForPlacement(200) { 80 } == null) { "Cancelled reveal reached placement" }
            val savedAfterDown = NativeLibraryScrollRestoration(null).save(
                pending.save(NativeListSavedPosition(9, 17)), null, pending.requestedKey)
            val recreated = NativeLibraryScrollRestoration(savedAfterDown)
            check(recreated.consumeReveal() == null) { "Cancelled reveal survived saved state" }
            check(recreated.consumeList() == NativeListSavedPosition(9, 17))
            pointer(MotionEvent.ACTION_UP, false, true)

            arm(); view.isEnabled = false
            pointer(MotionEvent.ACTION_DOWN, true, false); retained()
            wheel(); retained()
            view.isEnabled = true
            arm(); view.suppressLayout(true)
            wheel(); retained()
            view.suppressLayout(false)
            arm(); wheel(value = 0f); retained()
            wheel(axis = MotionEvent.AXIS_HSCROLL); retained()
            wheel(action = MotionEvent.ACTION_HOVER_MOVE); retained()
            val layout = view.layoutManager
            view.layoutManager = null
            wheel(); retained()
            view.layoutManager = layout

            // Model only the deferred scan completion boundary; no storage scan is performed.
            // The input entry points below are the actual observer node and RecyclerView methods.
            for ((name, input) in listOf<Pair<String, () -> Unit>>(
                "DOWN" to { pointer(MotionEvent.ACTION_DOWN, true, false) },
                "mouse wheel" to { wheel() },
            )) {
                pending = NativePendingListPosition(null)
                val delayedToken = deferredReveal.begin("deferred-$name")
                check(deferredReveal.pendingRecordingId == null && !pending.hasKeyedRequest)
                input()
                // Exercise the real Android Bundle wire; this is not a full Activity recreation.
                val numericBeforeScan = NativeListSavedPosition(9, 17)
                val savedWindow = NativeMainScreenSavedState(libraryPosition = NativeLibrarySavedScrollPosition(
                    listPosition = numericBeforeScan,
                    cancelledPlaybackRevealToken = deferredReveal.cancelledRequestToken))
                val restoredPosition = checkNotNull(NativeMainScreenSavedState.fromBundle(savedWindow.toBundle())?.libraryPosition)
                check(restoredPosition.listPosition == numericBeforeScan)
                check(restoredPosition.pendingRecordingRevealId == null)
                check(restoredPosition.cancelledPlaybackRevealToken == "deferred-$name")
                val recreatedReveal = NativeRecordingRevealRequest(restoredPosition.pendingRecordingRevealId,
                    restoredPosition.cancelledPlaybackRevealToken)
                val redelivered = recreatedReveal.begin("deferred-$name")
                check(!recreatedReveal.complete(redelivered, "suppressed-after-bundle"))
                check(recreatedReveal.pendingRecordingId == null && recreatedReveal.cancelledRequestToken == null)
                val distinct = recreatedReveal.begin("new-after-bundle-$name")
                check(recreatedReveal.complete(distinct, "accepted-after-bundle"))
                val acceptedWindow = NativeMainScreenSavedState(libraryPosition = NativeLibrarySavedScrollPosition(
                    listPosition = numericBeforeScan, pendingRecordingRevealId = recreatedReveal.pendingRecordingId,
                    cancelledPlaybackRevealToken = recreatedReveal.cancelledRequestToken))
                val acceptedPosition = checkNotNull(NativeMainScreenSavedState.fromBundle(acceptedWindow.toBundle())?.libraryPosition)
                check(acceptedPosition.pendingRecordingRevealId == "accepted-after-bundle")
                check(acceptedPosition.cancelledPlaybackRevealToken == null && acceptedPosition.listPosition == numericBeforeScan)
                val acceptedLate = deferredReveal.complete(delayedToken, "late-recording")
                if (acceptedLate) pending.request(NativeListSavedPosition(80, 0), "late-recording")
                check(!acceptedLate && deferredReveal.pendingRecordingId == null && !pending.hasKeyedRequest) {
                    "$name failed to invalidate modeled deferred scan completion before an ID existed"
                }
                val freshToken = deferredReveal.begin("fresh-$name")
                check(deferredReveal.complete(freshToken, "fresh-recording"))
                pending.request(NativeListSavedPosition(81, 0), checkNotNull(deferredReveal.pendingRecordingId))
                check(pending.requestedKey == "fresh-recording")
                check(!deferredReveal.complete(delayedToken, "late-recording"))
                check(deferredReveal.pendingRecordingId == "fresh-recording")
                if (name == "DOWN") pointer(MotionEvent.ACTION_UP, false, true)
            }

            // Numeric saved-position restoration is independent of cancellation of keyed reveals.
            val numeric = NativeListSavedPosition(9, 17)
            pending = NativePendingListPosition(numeric)
            pointer(MotionEvent.ACTION_DOWN, true, false)
            check(pending.pendingForPlacement(200) == numeric)
            pointer(MotionEvent.ACTION_UP, false, true)
            wheel()
            check(pending.pendingForPlacement(200) == numeric)
        }
        repeat(3) { awaitNativeParityDraw(instrumentation, host.fixtureRoot); instrumentation.waitForIdleSync() }
        // Keep the normal platform wheel path intact and prove cancellation precedes its scroll callback.
        for ((source, axis) in listOf(InputDevice.SOURCE_MOUSE to MotionEvent.AXIS_VSCROLL,
                InputDevice.SOURCE_ROTARY_ENCODER to MotionEvent.AXIS_SCROLL)) {
            var before = 0
            nativeParityOnMain(instrumentation) {
                val view = checkNotNull(recycler)
                val pending = NativePendingListPosition(null).apply {
                    request(NativeListSavedPosition(80, 0), "recording-target")
                }
                view.sourceOnUserScroll = { pending.cancelKeyedRequest() }
                val listener = object : RecyclerView.OnScrollListener() {
                    override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                        if (dy != 0) check(!pending.hasKeyedRequest) { "Reveal was cancelled after platform scrolling" }
                    }
                }
                view.addOnScrollListener(listener)
                before = view.computeVerticalScrollOffset()
                val properties = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE }
                val coords = MotionEvent.PointerCoords().apply { x = 30f; y = 30f; setAxisValue(axis, -3f) }
                val now = SystemClock.uptimeMillis()
                val event = MotionEvent.obtain(now, now, MotionEvent.ACTION_SCROLL, 1, arrayOf(properties),
                    arrayOf(coords), 0, 0, 1f, 1f, 0, 0, source, 0)
                try {
                    view.onGenericMotionEvent(event)
                    check(!pending.hasKeyedRequest) { "Supported wheel input did not revoke reveal" }
                } finally { event.recycle(); view.removeOnScrollListener(listener) }
            }
            repeat(3) { awaitNativeParityDraw(instrumentation, host.fixtureRoot); instrumentation.waitForIdleSync() }
            nativeParityOnMain(instrumentation) {
                check(checkNotNull(recycler).computeVerticalScrollOffset() > before) { "Normal wheel scrolling was swallowed" }
            }
        }
    } catch (error: Throwable) { failure = error }
    finally {
        failure = captureNativeParityCleanup(failure) { nativeParityOnMain(instrumentation) { owner?.close() } }
        failure = captureNativeParityCleanup(failure) { nativeParityOnMain(instrumentation) {
            recycler?.let { it.sourceOnUserScroll = null; host.fixtureRoot.removeView(it); it.adapter = null }
        } }
        failure = captureNativeParityCleanup(failure) { nativeParityOnMain(instrumentation) { runtime.close(); host.finish() } }
        failure = captureNativeParityCleanup(failure) { check(getRecorderPreferences(context).all == preferences) }
    }
    failure?.let { throw it }
    report.appendLine("PASS native_library_reveal_input: actual observer DOWN and RV wheel cancellation; numeric restoration preserved")
    report.appendLine("PASS native_library_reveal_deferred_completion: actual DOWN/wheel invalidate modeled deferred scan completion; no storage scan claim")
    report.appendLine("PASS native_library_reveal_bundle: Android Bundle roundtrip preserves cancellation token and numeric position; same token suppressed, distinct token accepted")
}
