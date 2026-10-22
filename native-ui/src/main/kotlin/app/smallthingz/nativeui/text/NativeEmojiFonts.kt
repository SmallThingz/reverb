package app.smallthingz.nativeui.text

import androidx.emoji2.text.EmojiCompat

/** Uses the existing platform EmojiCompat initializer without any UI-runtime state observer. */
internal object NativeEmojiFonts {
    val ready: Boolean
        get() = EmojiCompat.isConfigured() && EmojiCompat.get().loadState == EmojiCompat.LOAD_STATE_SUCCEEDED

    fun process(text: String): CharSequence =
        if (ready) EmojiCompat.get().process(text) ?: text else text

    fun observe(onLoaded: () -> Unit): AutoCloseable {
        if (!EmojiCompat.isConfigured() || ready) return AutoCloseable {}
        val owner = EmojiCompat.get()
        var active = true
        val callback = object : EmojiCompat.InitCallback() {
            override fun onInitialized() { if (active) onLoaded() }
        }
        owner.registerInitCallback(callback)
        return AutoCloseable {
            active = false
            owner.unregisterInitCallback(callback)
        }
    }
}
