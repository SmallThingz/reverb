package app.smallthingz.reverb.nativeui

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.os.Build
import app.smallthingz.reverb.R

/** API28 Editor obtains its constructor cursor resource through Context.getDrawable. */
internal fun legacyNativeCursorBinding(context: Context, thicknessPx: Float, color: Int) =
    if (Build.VERSION.SDK_INT == 28) LegacyNativeCursorBinding(context, thicknessPx, color) else null

internal class LegacyNativeCursorBinding(base: Context, thicknessPx: Float, color: Int) {
    private val cursor = NativeEditableCursorDrawable(thicknessPx, color)
    private val cursorResources = CursorResources(base.resources, cursor)

    // ContextWrapper retains the original theme, services, package, configuration-context access
    // and styled-attribute resolution. Only this editor's dedicated cursor resource is intercepted.
    val context: Context = object : ContextWrapper(base) {
        override fun getResources(): Resources = cursorResources.also { it.syncConfiguration() }
    }

    fun bind(thicknessPx: Float, color: Int) {
        cursorResources.syncConfiguration()
        cursor.bind(thicknessPx, color)
    }
}

@Suppress("DEPRECATION")
private class CursorResources(
    private val original: Resources,
    private val cursor: Drawable,
) : Resources(original.assets, original.displayMetrics, original.configuration) {
    fun syncConfiguration() {
        if (super.getConfiguration() != original.configuration ||
            super.getDisplayMetrics() != original.displayMetrics) {
            super.updateConfiguration(original.configuration, original.displayMetrics)
        }
    }

    override fun getDrawable(id: Int): Drawable =
        if (id == R.drawable.reverb_native_cursor) cursor else original.getDrawable(id)

    override fun getDrawable(id: Int, theme: Theme?): Drawable =
        if (id == R.drawable.reverb_native_cursor) cursor else original.getDrawable(id, theme)

    override fun getDrawableForDensity(id: Int, density: Int): Drawable? =
        if (id == R.drawable.reverb_native_cursor) cursor else original.getDrawableForDensity(id, density)

    override fun getDrawableForDensity(id: Int, density: Int, theme: Theme?): Drawable? =
        if (id == R.drawable.reverb_native_cursor) cursor else original.getDrawableForDensity(id, density, theme)
}
