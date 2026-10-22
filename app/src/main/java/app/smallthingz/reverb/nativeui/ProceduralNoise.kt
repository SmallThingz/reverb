package app.smallthingz.reverb.nativeui

/** Version 1 grain: fixed xorshift32 stream, unchanged 1024-pixel period and released ARGB palette. */
internal const val NOISE_TILE_SIZE = 1024
private const val NOISE_SEED = 0x52455642 // "REVB"; never use a time-, theme- or device-dependent seed.

internal fun generateNoisePixels(): IntArray {
    var state = NOISE_SEED
    return IntArray(NOISE_TILE_SIZE * NOISE_TILE_SIZE) {
        state = state xor (state shl 13)
        state = state xor (state ushr 17)
        state = state xor (state shl 5)
        val sample = (state ushr 1) % 26
        val alpha = 5 + sample % 13
        (alpha shl 24) or (if (sample < 13) 0 else 0x00ffffff)
    }
}

/** One process-owned generation. Scheduling/factory failure is terminal, never a per-frame retry. */
internal class NoiseTileCache<T>(private val generate: () -> T) {
    private var started = false
    @Volatile private var terminal: Result<T>? = null

    fun valueOrNull(): T? = terminal?.getOrNull()
    fun failureOrNull(): Throwable? = terminal?.exceptionOrNull()

    fun start(execute: (() -> Unit) -> Unit, onComplete: () -> Unit) {
        synchronized(this) {
            if (started) return
            started = true
        }
        try {
            execute work@{
                if (terminal != null) return@work
                val result = runCatching(generate)
                if (settle(result)) onComplete()
                // A failure must not leave waiters hanging, but fatal VM/thread errors still propagate.
                result.exceptionOrNull()?.let { if (it is VirtualMachineError || it is ThreadDeath) throw it }
            }
        } catch (error: Throwable) {
            if (settle(Result.failure(error))) onComplete() else throw error
            if (error is VirtualMachineError || error is ThreadDeath) throw error
        }
    }

    private fun settle(result: Result<T>): Boolean = synchronized(this) {
        if (terminal != null) false else {
            terminal = result
            true
        }
    }
}
