package app.smallthingz.reverb.nativeui

import smallthingz.ui.text.view.ParagraphView
import android.content.Context
import androidx.annotation.ColorInt
import androidx.annotation.MainThread
import smallthingz.ui.text.font.FontFamily
import smallthingz.ui.unit.Density

/**
 * Immutable bindings for one native host window and one configuration/theme generation.
 *
 * Keep the supplied immutable Density implementation; do not copy just density/fontScale.
 * The resolver is shared by this window's labels; its internal font cache may resolve asynchronously.
 * This object owns no View, Context, listener, lifecycle, mutable UI state, or render tree.
 *
 * On configuration changes, the host constructs a replacement with a new Density and resolver
 * from its current window configuration, plus a newly resolved palette. It explicitly rebinds
 * existing labels. For a palette-only change it can retain the existing density/resolver.
 */
internal class ReverbViewEnvironment(
    val textDensity: Density,
    val sharedFontResolver: FontFamily.Resolver,
    val palette: ReverbPalette,
    private val settingsTextContrast: Boolean = false,
) {
    /** Same immutable typography/palette, scoped once per Settings environment identity. */
    fun forSettings():ReverbViewEnvironment = if(settingsTextContrast)this else
        ReverbViewEnvironment(textDensity,sharedFontResolver,palette,true)

    /** Direct factory: returns the real View, without a feature-specific wrapper. */
    @MainThread
    fun paragraph(
        context: Context,
        text: String,
        role: ReverbTextRole,
        @ColorInt textColor: Int = palette.ink,
    ): ParagraphView = ParagraphView(
        context = context,
        initialStyle = ReverbParagraphStyles[role],
        initialDensity = textDensity,
        initialFontResolver = sharedFontResolver,
        initialTextColor = textColor,
    ).also { it.setText(text); bindTextContrast(it,textColor) }

    /**
     * Explicit host rebind after replacing this environment. No registry retains Views or roles.
     * Text/content, overflow, layout parameters, focus, accessibility and translation are untouched.
     */
    @MainThread
    fun bindParagraphStyle(
        view: ParagraphView,
        role: ReverbTextRole,
        @ColorInt textColor: Int = palette.ink,
    ) {
        view.setTypography(ReverbParagraphStyles[role], textDensity, sharedFontResolver)
        bindTextContrast(view,textColor)
        view.animateTextColor(textColor)
    }

    /** Only owned neutral labels opt in. Error/accent/custom colors retain their renderer. */
    private fun bindTextContrast(view:ParagraphView,color:Int) {
        if(!settingsTextContrast||(color!=palette.onSurface&&color!=palette.onSurfaceVariant)) {
            view.paintedTextSurface=null
        } else if(view.paintedTextSurface==null) {
            view.paintedTextSurface=java.util.function.LongSupplier { settingsTextSurface(view) }
        }
    }
}
