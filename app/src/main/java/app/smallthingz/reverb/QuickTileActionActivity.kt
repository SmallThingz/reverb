package app.smallthingz.reverb

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.activity.ComponentActivity

/**
 * Invisible foreground bridge used only when a QS tile needs to cold-start
 * microphone capture on Android versions that gate while-in-use permissions.
 */
class QuickTileActionActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private var bound = false
    private var resumed = false
    private var actionStarted = false
    private var finished = false
    private var recorder: ReverbService? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            recorder = (binder as? ReverbService.BackgroundRecorderBinder)?.service
            runActionIfReady()
        }

        override fun onServiceDisconnected(name: ComponentName) = finishAction()
        override fun onBindingDied(name: ComponentName) = finishAction()
        override fun onNullBinding(name: ComponentName) = finishAction()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
        if (intent.action != ACTION_START || requestedBufferSlot() == null) {
            finishAction()
            return
        }
        bound = bindService(Intent(this, ReverbService::class.java), connection, Context.BIND_AUTO_CREATE)
        if (!bound) {
            finishAction()
            return
        }
        handler.postDelayed(::finishAction, ACTION_TIMEOUT_MILLIS)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        runActionIfReady()
    }

    override fun onPause() {
        resumed = false
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        unbindRecorder()
        super.onDestroy()
    }

    private fun runActionIfReady() {
        val recorder = recorder ?: return
        val requestedBuffer = requestedBufferSlot() ?: return finishAction()
        if (!resumed || actionStarted || finished) return
        actionStarted = true
        recorder.getState(
            object : ReverbService.StateCallback {
                override fun state(
                    commandGeneration: Long,
                    listeningEnabled: Boolean,
                    activeBufferSlot: ReverbService.BufferSlot?,
                    oneShotSeconds: Float,
                    oneShotBytes: Long,
                    loopingSeconds: Float,
                    loopingBytes: Long,
                    oneShotIsEnabled: Boolean,
                    oneShotIsFull: Boolean,
                    loopingIsEnabled: Boolean,
                ) {
                    if (finished) return
                    if (!resumed) {
                        finishAction()
                        return
                    }
                    val usable = canActivateCaptureBuffer(
                        requested = requestedBuffer,
                        oneShotEnabled = oneShotIsEnabled,
                        oneShotFull = oneShotIsFull,
                        loopingEnabled = loopingIsEnabled,
                    )
                    if (!usable) {
                        RecordingQuickTiles.requestRefresh(this@QuickTileActionActivity)
                        finishAction()
                        return
                    }
                    val selection = recorder.selectCaptureBuffer(requestedBuffer)
                    if (!selection.accepted) {
                        RecordingQuickTiles.requestRefresh(this@QuickTileActionActivity)
                        finishAction()
                        return
                    }
                    val result = recorder.enableListening(requestedBuffer)
                    if (!result.accepted) {
                        RecordingQuickTiles.requestRefresh(this@QuickTileActionActivity)
                        finishAction()
                        return
                    }
                    waitForRuntimeCapture()
                }
            },
        )
    }

    private fun waitForRuntimeCapture() {
        if (finished) return
        if (recordingRuntimeCaptureActive) {
            RecordingQuickTiles.requestRefresh(this)
            finishAction()
            return
        }
        handler.postDelayed(::waitForRuntimeCapture, CAPTURE_POLL_MILLIS)
    }

    private fun requestedBufferSlot(): ReverbService.BufferSlot? {
        val stored = intent.getStringExtra(EXTRA_BUFFER_SLOT) ?: return null
        return runCatching { ReverbService.BufferSlot.valueOf(stored) }.getOrNull()
    }

    private fun finishAction() {
        if (finished) return
        finished = true
        handler.removeCallbacksAndMessages(null)
        unbindRecorder()
        finishAndRemoveTask()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    private fun unbindRecorder() {
        if (!bound) return
        bound = false
        runCatching { unbindService(connection) }
    }

    companion object {
        const val ACTION_START = "app.smallthingz.reverb.quicktile.START"
        const val EXTRA_BUFFER_SLOT = "buffer_slot"
        private const val CAPTURE_POLL_MILLIS = 50L
        private const val ACTION_TIMEOUT_MILLIS = 3_000L
    }
}
