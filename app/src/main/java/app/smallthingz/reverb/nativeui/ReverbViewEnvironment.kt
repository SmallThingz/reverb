package app.smallthingz.reverb.nativeui

import smallthingz.ui.text.view.ParagraphView
import android.content.Context
import androidx.annotation.ColorInt
import androidx.annotation.MainThread
import smallthingz.ui.components.NativeSettingsVisualEnvironment
import smallthingz.ui.components.NativeUiAppearance
import smallthingz.ui.text.font.FontFamily
import smallthingz.ui.text.font.createFontFamilyResolver
import smallthingz.ui.unit.Density

/** The appearance used by each Reverb window. */
internal val reverbAppearance = NativeUiAppearance.Default

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
    override val textDensity: Density,
    override val sharedFontResolver: FontFamily.Resolver,
    override val palette: ReverbPalette,
    private val settingsTextContrast: Boolean = false,
    override val appearance: NativeUiAppearance = reverbAppearance,
) : NativeSettingsVisualEnvironment {
    val audioBlobStyle = appearance.audioBlob.resolve(palette, appearance.typography)

    constructor(context: Context, darkTheme: Boolean, appearance: NativeUiAppearance = reverbAppearance) :
        this(Density(context), createFontFamilyResolver(context), appearance.resolveColorScheme(context, darkTheme),
            appearance = appearance)

    /** Same immutable typography/palette, scoped once per Settings environment identity. */
    fun forSettings():ReverbViewEnvironment = if(settingsTextContrast)this else
        ReverbViewEnvironment(textDensity, sharedFontResolver, palette, true, appearance)

    fun withPalette(value: ReverbPalette): ReverbViewEnvironment =
        ReverbViewEnvironment(textDensity, sharedFontResolver, value, settingsTextContrast, appearance)

    /** Direct factory: returns the real View, without a feature-specific wrapper. */
    @MainThread
    override fun paragraph(
        context: Context,
        text: String,
        role: ReverbTextRole,
        @ColorInt textColor: Int,
    ): ParagraphView = ParagraphView(
        context = context,
        initialStyle = appearance.typography[role],
        initialDensity = textDensity,
        initialFontResolver = sharedFontResolver,
        initialTextColor = textColor,
    ).also { it.setText(text); bindTextContrast(it,textColor) }

    /**
     * Explicit host rebind after replacing this environment. No registry retains Views or roles.
     * Text/content, overflow, layout parameters, focus, accessibility and translation are untouched.
     */
    @MainThread
    override fun bindParagraphStyle(
        view: ParagraphView,
        role: ReverbTextRole,
        @ColorInt textColor: Int,
    ) {
        view.setTypography(appearance.typography[role], textDensity, sharedFontResolver)
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
