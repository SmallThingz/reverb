package app.smallthingz.reverb.nativeui

import android.content.Context
import androidx.annotation.ColorInt
import androidx.annotation.MainThread
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density

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
) {
    /** Direct factory: returns the real View, without a feature-specific wrapper. */
    @MainThread
    fun paragraph(
        context: Context,
        text: String,
        role: ReverbTextRole,
        @ColorInt textColor: Int = palette.ink,
    ): ReverbParagraphView = ReverbParagraphView(
        context = context,
        initialStyle = ReverbParagraphStyles[role],
        initialDensity = textDensity,
        initialFontResolver = sharedFontResolver,
        initialTextColor = textColor,
    ).also { it.setText(text) }

    /**
     * Explicit host rebind after replacing this environment. No registry retains Views or roles.
     * Text/content, overflow, layout parameters, focus, accessibility and translation are untouched.
     */
    @MainThread
    fun bindParagraphStyle(
        view: ReverbParagraphView,
        role: ReverbTextRole,
        @ColorInt textColor: Int = palette.ink,
    ) {
        view.setTypography(ReverbParagraphStyles[role], textDensity, sharedFontResolver)
        view.setTextColor(textColor)
    }
}
