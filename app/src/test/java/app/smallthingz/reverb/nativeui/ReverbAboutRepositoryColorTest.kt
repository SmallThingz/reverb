package app.smallthingz.reverb.nativeui

import androidx.compose.material3.contentColorFor
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import org.junit.Assert.assertEquals
import org.junit.Test

class ReverbAboutRepositoryColorTest {
    @Test
    fun distinctContainerRoleUsesReleasedMaterialContentColor() {
        for (dark in listOf(false, true)) {
            val palette = ReverbPalette.fallback(dark)
            val scheme = if (dark) darkColorScheme() else lightColorScheme()
            assertEquals(scheme.contentColorFor(scheme.surfaceContainerHighest).toArgb(),
                reverbAboutRepositoryTitleColor(palette, TextStyle.Default))
        }
    }

    @Test
    fun equalSurfaceVariantAndHighestUseEarlierMaterialRole() {
        for (dark in listOf(false, true)) {
            val base = ReverbPalette.fallback(dark)
            val palette = base.copy(surfaceContainerHighest = base.surfaceVariant)
            val scheme = (if (dark) darkColorScheme() else lightColorScheme())
                .copy(surfaceContainerHighest = Color(base.surfaceVariant))
            assertEquals(base.onSurfaceVariant, scheme.contentColorFor(scheme.surfaceContainerHighest).toArgb())
            assertEquals(scheme.contentColorFor(scheme.surfaceContainerHighest).toArgb(),
                reverbAboutRepositoryTitleColor(palette, TextStyle.Default))
        }
    }

    @Test
    fun earlierContainerMatchKeepsMaterialLookupOrder() {
        val base = ReverbPalette.fallback(true)
        val palette = base.copy(surfaceContainerHighest = base.primaryContainer,
            surfaceVariant = base.primaryContainer)
        val scheme = darkColorScheme().copy(surfaceContainerHighest = Color(base.primaryContainer),
            surfaceVariant = Color(base.primaryContainer))
        assertEquals(scheme.contentColorFor(scheme.surfaceContainerHighest).toArgb(),
            reverbAboutRepositoryContentColor(palette))
        assertEquals(base.onPrimaryContainer, reverbAboutRepositoryContentColor(palette))
    }

    @Test
    fun specifiedTitleStyleOverridesInheritedSurfaceColorOnlyForText() {
        val base = ReverbPalette.fallback(true)
        val palette = base.copy(surfaceContainerHighest = base.surfaceVariant)
        val explicit = Color(0x80426A91)
        assertEquals(explicit.toArgb(),
            reverbAboutRepositoryTitleColor(palette, TextStyle(color = explicit)))
        assertEquals(base.onSurfaceVariant, reverbAboutRepositoryContentColor(palette))
    }
}
