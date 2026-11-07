package app.smallthingz.reverb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class AudioBlobMaterialTest {
    private fun contrast(color: Int) = 1.05 / (audioBlobMaterialLuminance(color) + .05)

    @Test fun activeMaterialKeepsTheFullDynamicPaletteInsteadOfCappingItsBrightness() {
        for (red in 0..255 step 51) for (green in 0..255 step 51) for (blue in 0..255 step 51) {
            val source = -0x1000000 or (red shl 16) or (green shl 8) or blue
            val material = audioBlobMaterialColor(source)
            assertEquals(source, material)
            assertEquals(source, audioBlobMaterialColor(source and 0x00ffffff))
        }
    }

    @Test fun alreadyReadableColorsAreUnchangedAndNeutralSupportHasMargin() {
        assertEquals(0xff204858.toInt(), audioBlobMaterialColor(0xff204858.toInt()))
        assertEquals(-1, AUDIO_BLOB_READOUT_INK)
        assertTrue(contrast(AUDIO_BLOB_READOUT_SUPPORT) > 5.0)
        val bright = audioBlobMaterialColor(0xff9fd1db.toInt())
        assertEquals(0xff9fd1db.toInt(), bright)
        assertTrue((bright and 255) > (bright ushr 8 and 255))
        assertTrue((bright ushr 8 and 255) > (bright ushr 16 and 255))
    }

    @Test fun selectedSupportKeepsContrastAfterOneCodeRasterQuantization() {
        val backgrounds = listOf(0xff000000.toInt(), 0xffffffff.toInt(),
            0xff748185.toInt(), 0xffbf6179.toInt())
        for (darkInk in listOf(false, true)) for (transitioning in listOf(false, true)) {
            val selected = audioBlobReadoutSupport(darkInk, transitioning)
            for (background in backgrounds) for (redError in -1..1) for (greenError in -1..1) for (blueError in -1..1) {
                fun channel(shift: Int, error: Int): Int {
                    val material = background ushr shift and 255
                    val support = selected ushr shift and 255
                    val blended = if (darkInk) maxOf(material, support) else minOf(material, support)
                    return (blended + error).coerceIn(0, 255)
                }
                val rendered = -0x1000000 or (channel(16, redError) shl 16) or
                    (channel(8, greenError) shl 8) or channel(0, blueError)
                // The native probe includes opaque ink samples within one code of their tint.
                val ink = if (darkInk) 0xff010101.toInt() else 0xfffefefe.toInt()
                val backgroundLight = audioBlobMaterialLuminance(rendered)
                val inkLight = audioBlobMaterialLuminance(ink)
                val ratio = (maxOf(backgroundLight, inkLight) + .05) / (minOf(backgroundLight, inkLight) + .05)
                assertTrue("dark=$darkInk transition=$transitioning raster contrast=$ratio", ratio >= 4.5)
            }
        }
        // Actual paired native raster: black timer pixels over one-code-low #747474 failed.
        assertTrue((audioBlobMaterialLuminance(0xff747474.toInt()) + .05) / .05 < 4.5)
    }

    @Test fun crossoverSupportProtectsBothInkPolaritiesAndTracksActualCollapse() {
        val supportLight = audioBlobMaterialLuminance(AUDIO_BLOB_TRANSITION_SUPPORT)
        assertTrue(1.05 / (supportLight + .05) >= 4.5)
        assertTrue((supportLight + .05) / .05 >= 4.5)
        val primary = audioBlobMaterialColor(0xff204858.toInt())
        val tertiary = audioBlobMaterialColor(0xff384461.toInt())
        for (fps in listOf(60, 120, 240)) {
            var life = 1f
            var presence = 1f
            var changes = 0
            var previous = false
            repeat(fps * 3) {
                val dt = 1f / fps
                life += (.38f - life) * (1f - .85f.pow(dt * 30f))
                presence = advanceBlobPresence(presence, 0f, dt)
                val darkInk = audioBlobUsesDarkInk(primary, tertiary, 0xffdce5e5.toInt(), life, presence)
                if (darkInk != previous) changes++
                previous = darkInk
            }
            assertEquals("one material-driven polarity switch at $fps Hz", 1, changes)
            assertTrue(previous)
            assertTrue(!audioBlobUsesDarkInk(primary, tertiary, 0xff263030.toInt(), life, presence))
        }
    }

    @Test fun quietDrainSwitchesInkBeforeTheUndrainedBodyWould() {
        val color = 0xff35606c.toInt()
        val paused = 0xffdce5e5.toInt()
        for (fallback in listOf(false, true)) {
            assertTrue(audioBlobUsesDarkInk(color, color, paused, .75f, .8f, 0f, fallback))
            assertTrue(!audioBlobUsesDarkInk(color, color, paused, .75f, .8f, .8f, fallback))
        }
    }

    @Test fun alreadyReadableTealDoesNotReceiveANeutralChannelLift() {
        val teal = 0xff508f9f.toInt()
        assertTrue((audioBlobMaterialLuminance(teal) + .05) / .05 > 4.5)
        assertTrue((teal ushr 16 and 255) < (AUDIO_BLOB_TRANSITION_SUPPORT ushr 16 and 255))
        assertTrue(!audioBlobInkNeedsSupport(0xff35606c.toInt(), 0xff35606c.toInt(), teal,
            .38f, 0f, 0f, false, true, .5f, true, -1))
    }

    @Test fun supportBoundsCoverGradientInteriorsAndUncoveredBackground() {
        val red = 0xffff0000.toInt()
        val green = 0xff009b00.toInt()
        assertTrue((audioBlobMaterialLuminance(red) + .05) / .05 > 4.5)
        assertTrue((audioBlobMaterialLuminance(green) + .05) / .05 > 4.5)
        // The interpolated interior can be darker than both endpoint luminances.
        assertTrue(audioBlobInkNeedsSupport(red, green, 0, 1f, 1f, 0f, false, true, .5f, true, -1))
        val bounded = 0xff6c6c6c.toInt()
        assertTrue(!audioBlobInkNeedsSupport(bounded, bounded, 0, 1f, 1f, 0f, false, false, .5f, true, -1))
        assertTrue(audioBlobInkNeedsSupport(bounded, bounded, 0, 1f, 1f, 0f, false, false, .5f, false, -1))
    }

    @Test fun uncoveredBodyAndBackgroundNeedACombinedDarkInkBound() {
        val material = 0xffff0000.toInt()
        val background = 0xff009b00.toInt()
        val edgeBlend = 0xff804e00.toInt()
        assertTrue((audioBlobMaterialLuminance(material) + .05) / .05 > 4.5)
        assertTrue((audioBlobMaterialLuminance(background) + .05) / .05 > 4.5)
        assertTrue((audioBlobMaterialLuminance(edgeBlend) + .05) / .05 < 4.5)
        assertTrue(!audioBlobInkNeedsSupport(material, material, 0, 1f, 1f, 0f,
            false, true, .5f, true, background))
        assertTrue(audioBlobInkNeedsSupport(material, material, 0, 1f, 1f, 0f,
            false, true, .5f, false, background))
    }

    @Test fun measuredReadoutGradientCanAvoidAnUnnecessaryNeutralField() {
        val primary = 0xff1b636c.toInt()
        val tertiary = 0xff36526c.toInt()
        val paused = 0xffdae4e6.toInt()
        val life = .86004263f
        // Leave raster headroom: the old .7506477 fixture was only nominally above 4.5.
        val presence = .73f
        assertTrue(audioBlobInkNeedsSupport(primary, tertiary, paused, life, presence,
            0f, false, true, .5f, true, -1))
        assertTrue(!audioBlobInkNeedsSupport(primary, tertiary, paused, life, presence,
            0f, false, true, .5f, true, -1, .2f, .6f))
        assertTrue(audioBlobInkNeedsSupport(primary, tertiary, paused, life, presence,
            0f, false, false, .5f, true, -1, .2f, .6f))
    }

    @Test fun fullyActiveBrightAndDarkPalettesChooseReadableInkWithoutAField() {
        for (color in intArrayOf(0xff9fd1db.toInt(), 0xffb7bfdc.toInt(), 0xff204858.toInt())) {
            val expectedDark = color != 0xff204858.toInt()
            for (fallback in listOf(false, true)) for (step in 0..10) {
                val silence = step / 10f
                val darkInk = audioBlobUsesDarkInk(color, color, 0, 1f, 1f, silence, fallback)
                assertEquals("Uncapped palette must own ink polarity", expectedDark, darkInk)
                assertTrue(!audioBlobInkNeedsSupport(color, color, 0, 1f, 1f, silence,
                    fallback, darkInk, 1f, true, -1))
            }
        }
    }

    @Test fun quietFallbackStartupBoundsTheBrighterDrainedRimForLightInk() {
        // Retained device failure: a muted ACTIVE palette was brighter than this startup body.
        // The old core-only light-ink bound passed while the secondary's rim pixels failed.
        val primary = 0xff9ccfd7.toInt()
        val tertiary = 0xffc9e2ff.toInt()
        val paused = 0xff1e2729.toInt()
        for (darkInk in listOf(false, true)) {
            assertTrue(audioBlobInkNeedsSupport(primary, tertiary, paused,
                .7196844816f, .523239255f, .3407594264f, true, darkInk, 1f, false, 0xff0e1416.toInt(),
                .3857625425f, .6798745394f))
        }
        val observedBackground = audioBlobMaterialLuminance(0xff677a84.toInt())
        val observedInk = audioBlobMaterialLuminance(0xfffefefe.toInt())
        assertEquals(4.436153871106733, (observedInk + .05) / (observedBackground + .05), .000000001)
        assertEquals(primary, audioBlobMaterialColor(primary))
    }

    @Test fun aNominalCrossoverWithoutRasterHeadroomStillNeedsLocalSupport() {
        val nominal = AUDIO_BLOB_TRANSITION_SUPPORT
        val light = audioBlobMaterialLuminance(nominal)
        assertTrue((light + .05) / .05 >= 4.5 && 1.05 / (light + .05) >= 4.5)
        for (fallback in listOf(false, true)) for (darkInk in listOf(false, true)) {
            assertTrue(audioBlobInkNeedsSupport(nominal, nominal, nominal, 1f, 1f, 0f,
                fallback, darkInk, 1f, true, nominal))
        }
    }
}
