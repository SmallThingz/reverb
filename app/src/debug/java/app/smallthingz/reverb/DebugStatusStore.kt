package app.smallthingz.reverb

import android.content.Context

internal object DebugStatusStore {
    private const val PREFS = "debug-status"
    private const val KEY_LAST = "last"

    fun write(
        context: Context,
        value: String,
    ) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_LAST, value).apply()
    }

    fun read(context: Context): String {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LAST, "") ?: ""
    }
}
