package app.smallthingz.reverb.nativeui

import android.animation.ValueAnimator
import android.content.Context
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings

/** The numeric scale is public on ValueAnimator only from API 33. */
internal fun reverbAnimatorDurationScale(context: Context): Float =
    if (Build.VERSION.SDK_INT >= 33) ValueAnimator.getDurationScale()
    else LegacyAnimatorDurationScale.read(context)

/** Process lifetime matches the platform animator scale cache; no Activity or View is retained. */
private object LegacyAnimatorDurationScale {
    @Volatile private var cache: CachedAnimatorDurationScale? = null

    fun read(context: Context): Float {
        val state = cache ?: initialize(context)
        return if (ValueAnimator.areAnimatorsEnabled()) state.value else 0f
    }

    @Synchronized private fun initialize(context: Context): CachedAnimatorDurationScale {
        cache?.let { return it }
        val resolver = context.applicationContext.contentResolver
        val state = CachedAnimatorDurationScale {
            Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        }
        val handler = Handler(Looper.getMainLooper())
        val observer = object : ContentObserver(handler) {
            val retry = Runnable { refresh() }

            override fun onChange(selfChange: Boolean) = refresh()

            fun refresh() {
                handler.removeCallbacks(retry)
                state.refresh()?.let { delay -> handler.postDelayed(retry, delay) }
            }
        }
        try {
            resolver.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer,
            )
            // Close the read/register gap; subsequent frames only read memory.
            observer.refresh()
        } catch (failure: Throwable) {
            handler.removeCallbacks(observer.retry)
            try { resolver.unregisterContentObserver(observer) }
            catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
        cache = state
        return state
    }
}

internal class CachedAnimatorDurationScale(private val readSetting: () -> Float) {
    private var retryDelayMillis = 1000L
    @Volatile var value: Float = readSetting()
        private set

    /** Null means current; provider unavailability retains the value and requests a bounded retry. */
    fun refresh(): Long? = try {
        value = readSetting()
        retryDelayMillis = 1000L
        null
    } catch (_: RuntimeException) {
        retryDelayMillis.also { retryDelayMillis = (it * 2).coerceAtMost(30_000L) }
    }
}
