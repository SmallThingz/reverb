package app.smallthingz.reverb

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder

/** Explicit sampling owns a short bind even when the ordinary Settings bind found no stopped service. */
internal class SilenceSampleOwner(
    context: Context,
    private val expectedInput: SilenceCalibrationInput? = null,
) : SilenceSampler {
    private val context = context.applicationContext
    override val request = SilenceSampleRequest()
    private var bound = false
    private var released = false
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            if (released || !request.running()) return
            val service = (binder as? ReverbService.BackgroundRecorderBinder)?.service
            if (service == null) request.fail(context.getString(R.string.silence_sample_failed))
            // The audio queue owns initialization and requested/operational configuration.
            else service.sampleSilence(request, expectedInput)
        }
        override fun onServiceDisconnected(name: ComponentName) { request.fail(context.getString(R.string.silence_sample_failed)) }
        override fun onNullBinding(name: ComponentName) { request.fail(context.getString(R.string.silence_sample_failed)); release() }
        override fun onBindingDied(name: ComponentName) { request.fail(context.getString(R.string.silence_sample_failed)); release() }
    }

    override fun start() {
        check(!bound && !released)
        try {
            bound = context.bindService(Intent(context, ReverbService::class.java), connection, Context.BIND_AUTO_CREATE)
            if (!bound) request.fail(context.getString(R.string.silence_sample_failed))
        } catch (error: RuntimeException) { request.fail(error.message ?: context.getString(R.string.silence_sample_failed)) }
    }

    fun release() {
        if (released) return
        released = true
        if (bound) {
            bound = false
            try { context.unbindService(connection) }
            catch (error: RuntimeException) {
                AppFeedbackCenter.post(context.getString(R.string.silence_sample_failed), FeedbackTone.ERROR)
                android.util.Log.w("SilenceSample", "Unable to release sampling binding", error)
            }
        }
    }

    override fun close() { request.cancel(); release() }
}
