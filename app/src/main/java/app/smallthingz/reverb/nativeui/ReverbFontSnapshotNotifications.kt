package app.smallthingz.reverb.nativeui

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.snapshots.ObserverHandle
import androidx.compose.runtime.snapshots.Snapshot
import java.util.IdentityHashMap

/**
 * Delivers the pinned paragraph engine's font/EmojiCompat state changes without a Compose host.
 *
 * SnapshotStateObserver watches applied snapshots; it does not bootstrap global apply delivery.
 * AndroidX UI 1.11.2 normally supplies that delivery through GlobalSnapshotManager. Native text
 * instead tracks only the state objects read by ParagraphIntrinsics.hasStaleResolvedFonts.
 * One shared global-write observer exists while attached paragraphs have such reads. A matching
 * write schedules one main-thread apply notification; unrelated writes never schedule work here.
 * This is notification plumbing for the font engine, not an application state or render scheduler.
 * Snapshot's public apply/initialization APIs advance the global snapshot, so a font-triggered
 * flush can also deliver unrelated writes already pending there. It does not isolate those writes
 * or replace a mixed host's existing snapshot manager; redundant empty flushes are harmless.
 *
 * API provenance: runtime 1.11.2 Snapshot.kt registerGlobalWriteObserver/sendApplyNotifications,
 * and ui 1.11.2 GlobalSnapshotManager.android.kt. No upstream implementation is copied.
 */
internal object ReverbFontSnapshotNotifications {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()

    // Scope ownership and observer registration are main-thread-only. Font writes may be on any
    // thread, so the aggregate identity counts and the pending flag are protected by lock.
    private val scopeReads = IdentityHashMap<Any, IdentityHashMap<Any, Boolean>>()
    private val stateReaders = IdentityHashMap<Any, Int>()
    private var globalWriteObserver: ObserverHandle? = null
    private var reconcilingObserver = false
    private var drainPending = false

    private val drain = Runnable {
        val deliver = synchronized(lock) {
            drainPending = false
            stateReaders.isNotEmpty()
        }
        if (deliver) Snapshot.sendApplyNotifications()
    }

    /** The block must only observe the paragraph's resolved-font/EmojiCompat readiness. */
    fun observeReads(scope: Any, block: () -> Unit) {
        checkMainThread()
        val reads = IdentityHashMap<Any, Boolean>()
        Snapshot.observe(readObserver = { reads[it] = true }, block = block)
        replaceReads(scope, reads)
    }

    /** Remove references and pending interest when the paragraph changes or its View detaches. */
    fun clear(scope: Any) {
        checkMainThread()
        replaceReads(scope, null)
    }

    private fun replaceReads(scope: Any, reads: IdentityHashMap<Any, Boolean>?) {
        val old = scopeReads[scope]
        if (old != null && reads != null && old.size == reads.size &&
            old.keys.all { reads.containsKey(it) }
        ) return
        if (old == null && reads.isNullOrEmpty()) return

        if (reads.isNullOrEmpty()) scopeReads.remove(scope) else scopeReads[scope] = reads
        var addedState = false
        synchronized(lock) {
            old?.keys?.forEach { state ->
                val remaining = checkNotNull(stateReaders[state]) - 1
                if (remaining == 0) stateReaders.remove(state) else stateReaders[state] = remaining
            }
            reads?.keys?.forEach { state ->
                val previousReaders = stateReaders[state] ?: 0
                stateReaders[state] = previousReaders + 1
                if (previousReaders == 0) addedState = true
            }
        }
        reconcileObserver()
        if (addedState) {
            // Font states can be created by the preceding ParagraphIntrinsics construction.
            // Mark those objects initialized before relying on later global writes. Advancing
            // also catches a completion between reading a new state and registering its interest.
            Snapshot.notifyObjectsInitialized()
            scheduleDrain()
        }
        val cancel = synchronized(lock) {
            if (stateReaders.isEmpty()) {
                drainPending = false
                true
            } else false
        }
        if (cancel) mainHandler.removeCallbacks(drain)
    }

    private fun reconcileObserver() {
        // Register/dispose advances the snapshot and can synchronously notify other observers.
        // Never call Snapshot while holding lock, and tolerate a callback clearing its scope.
        if (reconcilingObserver) return
        reconcilingObserver = true
        try {
            while (true) {
                val needed = synchronized(lock) { stateReaders.isNotEmpty() }
                val existing = globalWriteObserver
                when {
                    needed && existing == null -> {
                        globalWriteObserver = Snapshot.registerGlobalWriteObserver { state ->
                            synchronized(lock) {
                                if (stateReaders.containsKey(state)) scheduleDrainLocked()
                            }
                        }
                    }
                    !needed && existing != null -> {
                        globalWriteObserver = null
                        existing.dispose()
                    }
                    else -> return
                }
            }
        } finally {
            reconcilingObserver = false
        }
    }

    private fun scheduleDrain() {
        synchronized(lock) {
            if (stateReaders.isNotEmpty()) scheduleDrainLocked()
        }
    }

    private fun scheduleDrainLocked() {
        if (drainPending) return
        drainPending = true
        mainHandler.post(drain)
    }

    private fun checkMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "Paragraph font observation belongs to the View's main thread"
        }
    }
}
