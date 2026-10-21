package app.smallthingz.reverb

import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalResources

internal data class AppChrome(
    val field: Color,
    val raised: Color,
    val ink: Color,
    val muted: Color,
    val border: Color,
)

internal const val APP_NOISE_SEED_BACKGROUND = 0
internal const val APP_NOISE_SEED_TOP_BAR = 0x5A17

private object AppNoiseTile {
    private val cachedBrushes = mutableMapOf<Int, Brush>()

    fun brush(resources: android.content.res.Resources, seed: Int): Brush = synchronized(this) {
        cachedBrushes[seed] ?: run {
            val image = checkNotNull(
                BitmapFactory.decodeResource(resources, R.drawable.settings_noise_tile),
            ).asImageBitmap()
            val shader = ImageShader(
                image = image,
                tileModeX = TileMode.Repeated,
                tileModeY = TileMode.Repeated,
            )
            if (seed != APP_NOISE_SEED_BACKGROUND) {
                val x = Math.floorMod(seed * 37, image.width).toFloat()
                val y = Math.floorMod(seed * 61, image.height).toFloat()
                shader.setLocalMatrix(Matrix().apply { setTranslate(x, y) })
            }
            object : ShaderBrush() {
                override fun createShader(size: Size): Shader = shader
            }.also { cachedBrushes[seed] = it }
        }
    }
}

@Composable
internal fun appChrome(): AppChrome {
    val colors = MaterialTheme.colorScheme
    return AppChrome(
        field = colors.surfaceContainerLow,
        raised = colors.surfaceContainerHigh,
        ink = colors.onSurface,
        muted = colors.onSurfaceVariant,
        border = colors.onSurface.copy(alpha = 0.14f),
    )
}

@Composable
internal fun rememberAppNoiseBrush(seed: Int = APP_NOISE_SEED_BACKGROUND): Brush {
    val resources = LocalResources.current
    return remember(resources, seed) { AppNoiseTile.brush(resources, seed) }
}

internal fun Modifier.appNoise(brush: Brush): Modifier = drawBehind {
    drawRect(brush = brush)
}
