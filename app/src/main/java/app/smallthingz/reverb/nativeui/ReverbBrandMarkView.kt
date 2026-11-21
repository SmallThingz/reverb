package app.smallthingz.reverb.nativeui

import android.content.Context
import android.graphics.Path
import app.smallthingz.reverb.R
import smallthingz.ui.components.NativeLayeredMarkArt
import smallthingz.ui.components.NativeLayeredMarkPalette
import smallthingz.ui.components.NativeLayeredMarkStroke
import smallthingz.ui.components.NativeLayeredMarkView
import smallthingz.ui.components.nativeLayeredMarkBounds

/** Reverb's released launcher foreground and configuration-specific resource colors. */
internal class ReverbBrandMarkView(context: Context, description: CharSequence? = null) :
    NativeLayeredMarkView(context, description, reverbBrandArt, ::readReverbBrandPalette)

private const val BRAND_SIZE_DP = 108f

private val reverbBrandArt = NativeLayeredMarkArt(
    sizeDp = BRAND_SIZE_DP,
    viewport = 512f,
    strokeWidth = 26f,
    originX = 44.5f,
    paths = listOf(
        Path().apply {
            moveTo(127.686f, 358f)
            lineTo(156.357f, 154f)
            lineTo(222.357f, 154f)
            cubicTo(272.357f, 154f, 301.859f, 186f, 295.676f, 230f)
            cubicTo(289.492f, 274f, 250.995f, 306f, 200.995f, 306f)
            lineTo(134.995f, 306f)
        },
        Path().apply {
            moveTo(196.995f, 306f)
            lineTo(281.686f, 358f)
        },
    ),
    layers = listOf(
        NativeLayeredMarkStroke(56f, 0.16f, accent = true),
        NativeLayeredMarkStroke(28f, 0.34f, accent = true),
        NativeLayeredMarkStroke(0f, 1f, accent = false),
    ),
)

internal fun readReverbBrandPalette(context: Context): NativeLayeredMarkPalette = NativeLayeredMarkPalette(
    context.resources.getColor(R.color.launcher_background, context.theme),
    context.resources.getColor(R.color.launcher_accent, context.theme),
    context.resources.getColor(R.color.launcher_main, context.theme),
)

internal fun reverbBrandFillBounds(density: Float, width: Int, height: Int) = nativeLayeredMarkBounds(BRAND_SIZE_DP, density, width, height)
