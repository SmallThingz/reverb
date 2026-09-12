package app.smallthingz.reverb

import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalResources

private val DarkField = Color(0xFF101927)
private val DarkRaised = Color(0xFF1C2939)
private val DarkInk = Color(0xFFEDF3F3)
private val DarkMuted = Color(0xFFA0B1C0)
private val LightField = Color(0xFFF0F5F5)
private val LightRaised = Color(0xFFE5EEEE)
private val LightInk = Color(0xFF182D34)
private val LightMuted = Color(0xFF526872)

internal data class AppChrome(
    val field: Color,
    val raised: Color,
    val ink: Color,
    val muted: Color,
    val border: Color,
)

private object AppNoiseTile {
    @Volatile
    private var cachedBrush: Brush? = null

    fun brush(resources: android.content.res.Resources): Brush {
        cachedBrush?.let { return it }
        return synchronized(this) {
            cachedBrush ?: run {
                val image = checkNotNull(
                    BitmapFactory.decodeResource(resources, R.drawable.settings_noise_tile),
                ).asImageBitmap()
                val shader = ImageShader(
                    image = image,
                    tileModeX = TileMode.Repeated,
                    tileModeY = TileMode.Repeated,
                )
                object : ShaderBrush() {
                    override fun createShader(size: Size): Shader = shader
                }.also { cachedBrush = it }
            }
        }
    }
}

@Composable
internal fun appChrome(): AppChrome {
    val colors = MaterialTheme.colorScheme
    val dark = colors.surface.luminance() < 0.5f
    return AppChrome(
        field = (if (dark) DarkField else LightField).copy(alpha = if (dark) 0.82f else 0.88f),
        raised = (if (dark) DarkRaised else LightRaised).copy(alpha = if (dark) 0.90f else 0.94f),
        ink = if (dark) DarkInk else LightInk,
        muted = if (dark) DarkMuted else LightMuted,
        border = colors.outlineVariant.copy(alpha = if (dark) 0.34f else 0.42f),
    )
}

@Composable
internal fun rememberAppNoiseBrush(): Brush {
    val resources = LocalResources.current
    return remember(resources) { AppNoiseTile.brush(resources) }
}

internal fun Modifier.appNoise(brush: Brush): Modifier = drawBehind {
    drawRect(brush = brush)
}

@Composable
internal fun ReverbNoiseBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val brush = rememberAppNoiseBrush()
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .appNoise(brush),
        content = content,
    )
}
