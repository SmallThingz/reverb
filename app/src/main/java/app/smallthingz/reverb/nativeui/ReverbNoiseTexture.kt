package app.smallthingz.reverb.nativeui

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import java.lang.ref.WeakReference

/** A shared immutable bitmap; the two shaders differ only in their released phase offsets. */
internal object ReverbNoiseTexture {
    private val tile = NoiseTileCache {
        Bitmap.createBitmap(generateNoisePixels(), NOISE_TILE_SIZE, NOISE_TILE_SIZE,
            Bitmap.Config.ARGB_8888).apply { density = Bitmap.DENSITY_NONE }
    }
    private val main = Handler(Looper.getMainLooper())
    private val pending = mutableListOf<WeakReference<ReverbNoiseDrawable>>()
    private val shaders = mutableMapOf<Int, BitmapShader>()

    fun prepare() {
        tile.start(execute = { work ->
            Thread({
                try {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                } catch (error: SecurityException) {
                    Log.w("ReverbNoise", "Unable to lower grain worker priority", error)
                }
                work()
            }, "reverb-noise").apply { isDaemon = true }.start()
        }, onComplete = {
            tile.failureOrNull()?.let { Log.e("ReverbNoise", "Unable to generate background grain", it) }
            main.post {
                val notify = synchronized(this) { pending.toList().also { pending.clear() } }
                notify.forEach { it.get()?.invalidateSelf() }
            }
        })
    }

    fun observe(drawable: ReverbNoiseDrawable) {
        synchronized(this) {
            if (tile.valueOrNull() == null && tile.failureOrNull() == null) {
                pending.removeAll { it.get() == null }
                pending.add(WeakReference(drawable))
            }
        }
        prepare()
    }

    fun shader(seed: Int): BitmapShader? = synchronized(this) {
        val bitmap = tile.valueOrNull() ?: return null
        fun createShader() =
            BitmapShader(bitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT).apply {
                if (seed != ReverbNoiseDrawable.SEED_BACKGROUND) {
                    val x = Math.floorMod(seed * 37, NOISE_TILE_SIZE).toFloat()
                    val y = Math.floorMod(seed * 61, NOISE_TILE_SIZE).toFloat()
                    setLocalMatrix(Matrix().apply { setTranslate(x, y) })
                }
            }
        // Only the two app phases occupy the process cache; arbitrary callers own their shader.
        if (seed == ReverbNoiseDrawable.SEED_BACKGROUND || seed == ReverbNoiseDrawable.SEED_TOP_BAR)
            shaders.getOrPut(seed, ::createShader) else createShader()
    }
}
