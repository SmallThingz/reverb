package app.smallthingz.reverb.nativeui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import smallthingz.ui.animation.AnimatedValue
import smallthingz.ui.animation.VectorConverter
import smallthingz.ui.animation.core.AnimationVector4D
import smallthingz.ui.animation.core.tween
import smallthingz.ui.graphics.Color
import smallthingz.ui.graphics.toArgb
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

class HomeBufferContrastTest {
    @Test fun productionColorTweenReproducesOldSelectedRecordingContrastCrossing() {
        for (dark in listOf(false, true)) for (reverse in listOf(false, true)) {
            val p = ReverbPalette.fallback(dark)
            val start = colors(p, if (reverse) State.RECORDING else State.IDLE)
            val end = colors(p, if (reverse) State.IDLE else State.RECORDING)
            val motion = Motion(p, start)
            motion.target(p, end)
            var oldMinimum = 21.0
            var fixedMinimum = 21.0
            for (ms in 0..160) {
                motion.frame(ms)
                val background = composite(motion.fill, p.field)
                oldMinimum = min(oldMinimum, contrast(composite(motion.rawInk, background), background))
                fixedMinimum = min(fixedMinimum, minimumRenderedContrast(p, motion.fill, motion.ink))
            }
            assertTrue("Old dark=$dark reverse=$reverse minimum=$oldMinimum", oldMinimum < 1.2)
            assertTrue("Fixed dark=$dark reverse=$reverse minimum=$fixedMinimum", fixedMinimum > 2.0)
            println("Home selected-recording dark=$dark reverse=$reverse old=$oldMinimum fixed=$fixedMinimum")
        }
    }

    @Test fun everyStateDirectionPreservesAlphaEndpointsAndAchievableContrast() {
        for (p in palettes()) for (start in State.entries) for (end in State.entries) {
            val a = colors(p, start)
            val b = colors(p, end)
            val motion = Motion(p, a)
            assertEquals(a.ink, motion.ink)
            motion.target(p, b)
            for (ms in 0..160 step 2) { motion.frame(ms); motion.assertContrast() }
            assertEquals("$start->$end fill", b.fill, motion.fill)
            assertEquals("$start->$end ink", b.ink, motion.ink)
        }
    }

    @Test fun interruptedRetargetsPreserveTheUnfinishedEndpointFloor() {
        for (p in palettes()) for (middle in State.entries) {
            val motion = Motion(p, colors(p, State.IDLE))
            motion.target(p, colors(p, State.RECORDING))
            for (ms in 0..42 step 2) { motion.frame(ms); motion.assertContrast() }
            motion.target(p, colors(p, middle))
            for (ms in 44..78 step 2) { motion.frame(ms); motion.assertContrast() }
            motion.target(p, colors(p, State.IDLE))
            for (ms in 80..260 step 2) { motion.frame(ms); motion.assertContrast() }
            assertEquals(colors(p, State.IDLE).ink, motion.ink)
            // A new settled-to-selected transition must no longer inherit earlier dimming.
            motion.target(p, colors(p, State.RECORDING))
            for (ms in 262..424 step 2) { motion.frame(ms); motion.assertContrast() }
        }
    }

    @Test fun themeChangesUseNewParentAndOverlayEvenWhenColorTargetsAreUnchanged() {
        for (old in palettes()) for (next in palettes()) for (state in State.entries) {
            val motion = Motion(old, colors(old, state))
            motion.target(next, colors(next, state))
            for (ms in 0..160 step 2) { motion.frame(ms); motion.assertContrast() }
            assertEquals(colors(next, state).ink, motion.ink)
        }
        val old = ReverbPalette.fallback(true)
        val next = old.copy(surfaceContainerLow = 0xFF383E35.toInt(), onSurface = 0xFFC6E9B3.toInt())
        val motion = Motion(old, colors(old, State.IDLE))
        motion.target(old, colors(old, State.RECORDING))
        for (ms in 0..40 step 2) motion.frame(ms)
        motion.target(next, colors(next, State.RECORDING))
        for (ms in 42..204 step 2) { motion.frame(ms); motion.assertContrast() }
    }

    @Test fun zeroDurationScaleRestoresExactSemanticColors() {
        for (p in palettes()) for (state in State.entries) {
            val motion = Motion(p, colors(p, State.RECORDING))
            val end = colors(p, state)
            motion.target(p, end, 0f)
            motion.frame(0, 0f)
            assertEquals(end.fill, motion.fill)
            assertEquals(end.ink, motion.ink)
        }
    }

    private class Motion(var palette: ReverbPalette, initial: Colors) {
        private val fillMotion = colorMotion(initial.fill)
        private val inkMotion = colorMotion(initial.ink)
        private val policy = HomeBufferInk()
        private var endpointFloor = min(4.5, bound(palette, initial.fill, initial.ink))
        private var targetFloor = endpointFloor
        val fill get() = fillMotion.value.toArgb()
        val rawInk get() = inkMotion.value.toArgb()
        var ink = initial.ink
            private set
        init {
            policy.target(palette.field, palette.ink, initial.fill, initial.ink)
            ink = policy.foreground(fill, rawInk, false)
        }
        fun target(next: ReverbPalette, colors: Colors, scale: Float = 1f) {
            palette = next
            targetFloor = min(4.5, bound(next, colors.fill, colors.ink))
            endpointFloor = min(endpointFloor, targetFloor)
            policy.target(next.field, next.ink, colors.fill, colors.ink)
            fillMotion.target(Color(colors.fill), scale)
            inkMotion.target(Color(colors.ink), scale)
            apply()
        }
        fun frame(ms: Int, scale: Float = 1f) {
            fillMotion.frame(ms * 1_000_000L, scale)
            inkMotion.frame(ms * 1_000_000L, scale)
            apply()
        }
        private fun apply() {
            val running = fillMotion.running || inkMotion.running
            ink = policy.foreground(fill, rawInk, running)
            if (!running) endpointFloor = targetFloor
        }
        fun assertContrast() {
            assertEquals("Intentional opacity", rawInk ushr 24, ink ushr 24)
            val black = rawInk and -0x1000000
            val white = black or 0xFFFFFF
            val achievable = min(endpointFloor, max(bound(palette, fill, black), bound(palette, fill, white)))
            val actual = minimumRenderedContrast(palette, fill, ink)
            assertTrue("actual=$actual achievable=$achievable fill=${fill.toUInt().toString(16)}", actual + 1e-10 >= achievable)
        }
    }

    private enum class State { IDLE, RECORDING, FULL, DISABLED, DIMMED, DIMMED_DISABLED }
    private data class Colors(val fill: Int, val ink: Int)

    companion object {
        private fun colors(p: ReverbPalette, state: State): Colors = when (state) {
            State.IDLE -> Colors(reverbColorWithAlpha(p.primaryContainer, .74f), p.onPrimaryContainer)
            State.RECORDING -> Colors(p.primary, p.onPrimary)
            State.FULL -> Colors(p.tertiary, p.onTertiary)
            State.DISABLED -> Colors(reverbColorWithAlpha(p.primaryContainer, .74f), reverbColorWithAlpha(p.onPrimaryContainer, .62f))
            State.DIMMED -> Colors(0, reverbColorWithAlpha(p.muted, .52f))
            State.DIMMED_DISABLED -> Colors(0, reverbColorWithAlpha(p.muted, .30f))
        }
        private fun palettes() = listOf(ReverbPalette.fallback(false), ReverbPalette.fallback(true),
            ReverbPalette.fallback(false).copy(primary = 0xFF005233.toInt(), onPrimary = -1,
                primaryContainer = 0xFF9FD5B3.toInt(), onPrimaryContainer = 0xFF003822.toInt(),
                tertiary = 0xFF544600.toInt(), onTertiary = -1),
            ReverbPalette.fallback(true).copy(primary = 0xFF9FD5B3.toInt(), onPrimary = 0xFF003822.toInt(),
                primaryContainer = 0xFF005233.toInt(), onPrimaryContainer = 0xFFC6F8D7.toInt(),
                tertiary = 0xFFE8CC72.toInt(), onTertiary = 0xFF332B00.toInt()))
        private fun colorMotion(argb: Int): AnimatedValue<Color, AnimationVector4D> {
            val color = Color(argb)
            return AnimatedValue(color, (Color.VectorConverter)(color.colorSpace), tween<Color>(160),
                { AnimationVector4D(0f, 0f, 0f, 0f) })
        }
        private fun minimumRenderedContrast(p: ReverbPalette, fill: Int, ink: Int): Double {
            val background = composite(fill, p.field)
            val foreground = composite(ink, background)
            var result = 21.0
            // Sample independently varying state/ripple opacities, including coincident maxima.
            for (state in intArrayOf(0, 8, 16, 20, 26)) for (press in intArrayOf(0, 8, 16, 26)) {
                val a = composite(p.ink, composite(p.ink, foreground, state), press)
                val b = composite(p.ink, composite(p.ink, background, state), press)
                result = min(result, contrast(a, b))
            }
            return result
        }
        private fun bound(p: ReverbPalette, fill: Int, ink: Int): Double {
            val background = composite(fill, p.field)
            val foreground = composite(ink, background)
            fun interval(color: Int): Pair<Double, Double> {
                val overlaid = composite(p.ink, composite(p.ink, color, 26), 26)
                var low = 0
                var high = 0
                for (shift in intArrayOf(0, 8, 16)) {
                    val a = color ushr shift and 255
                    val b = overlaid ushr shift and 255
                    low = low or (min(a, b) shl shift)
                    high = high or (max(a, b) shl shift)
                }
                return luminance(low) to luminance(high)
            }
            val (a, b) = interval(foreground)
            val (c, d) = interval(background)
            return if (a >= d) (a + .05) / (d + .05) else if (c >= b) (c + .05) / (b + .05) else 1.0
        }
        private fun composite(front: Int, back: Int, alpha: Int = front ushr 24): Int {
            var result = -0x1000000
            for (shift in intArrayOf(0, 8, 16)) {
                val channel = ((front ushr shift and 255) * alpha + (back ushr shift and 255) * (255 - alpha) + 127) / 255
                result = result or (channel shl shift)
            }
            return result
        }
        private fun contrast(a: Int, b: Int): Double {
            val x = luminance(a)
            val y = luminance(b)
            return (max(x, y) + .05) / (min(x, y) + .05)
        }
        private fun luminance(color: Int): Double {
            fun channel(shift: Int): Double {
                val value = (color ushr shift and 255) / 255.0
                return if (value <= .04045) value / 12.92 else ((value + .055) / 1.055).pow(2.4)
            }
            return .2126 * channel(16) + .7152 * channel(8) + .0722 * channel(0)
        }
    }
}
