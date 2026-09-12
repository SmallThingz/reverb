package app.smallthingz.reverb

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity

class QuickTileActionActivity : ComponentActivity() {
    private var bound = false
    private var finished = false
    private var resumed = false
    private var actionStarted = false
    private var recorder: ReverbService? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            recorder = (binder as? ReverbService.BackgroundRecorderBinder)?.service
            if (recorder == null) {
                finishAction()
                return
            }
            runActionIfReady()
        }

        override fun onServiceDisconnected(name: ComponentName) = finishAction()
        override fun onBindingDied(name: ComponentName) = finishAction()
        override fun onNullBinding(name: ComponentName) = finishAction()
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

    private fun runActionIfReady() {
        val recorder = recorder ?: return
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
                        val requestedBuffer = requestedBufferSlot() ?: run {
                            finishAction()
                            return
                        }
                        val snapshot = RecordingTileSnapshot(
                            listening = listeningEnabled,
                            activeBuffer = activeBufferSlot,
                            oneShotEnabled = oneShotIsEnabled,
                            oneShotFull = oneShotIsFull,
                            loopingEnabled = loopingIsEnabled,
                        )
                        if (intent.action == ACTION_START) {
                            when (recordingTileUiState(requestedBuffer, snapshot)) {
                                RecordingTileUiState.ACTIVE,
                                RecordingTileUiState.AVAILABLE,
                                -> {
                                    if (listeningEnabled) recorder.selectCaptureBuffer(requestedBuffer)
                                    else recorder.enableListening(requestedBuffer)
                                }
                                RecordingTileUiState.RECORDING,
                                RecordingTileUiState.FULL,
                                RecordingTileUiState.DISABLED,
                                -> Unit
                            }
                        }
                        RecordingQuickTiles.requestRefresh(this@QuickTileActionActivity)
                        finishAction()
                    }
                },
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (requestedBufferSlot() == null || intent.action != ACTION_START) {
            finishAction()
            return
        }
        bound = bindService(Intent(this, ReverbService::class.java), connection, Context.BIND_AUTO_CREATE)
        if (!bound) {
            finishAction()
            return
        }
        window.decorView.postDelayed({ finishAction() }, ACTION_TIMEOUT_MILLIS)
    }

    override fun onDestroy() {
        unbindRecorder()
        super.onDestroy()
    }

    private fun requestedBufferSlot(): ReverbService.BufferSlot? {
        val stored = intent.getStringExtra(EXTRA_BUFFER_SLOT) ?: return null
        return runCatching { ReverbService.BufferSlot.valueOf(stored) }.getOrNull()
    }

    private fun finishAction() {
        if (finished) return
        finished = true
        unbindRecorder()
        finishAndRemoveTask()
    }

    private fun unbindRecorder() {
        if (!bound) return
        bound = false
        runCatching { unbindService(connection) }
    }

    companion object {
        const val ACTION_START = "app.smallthingz.reverb.quicktile.START"
        const val EXTRA_BUFFER_SLOT = "buffer_slot"
        private const val ACTION_TIMEOUT_MILLIS = 3_000L
    }
}
