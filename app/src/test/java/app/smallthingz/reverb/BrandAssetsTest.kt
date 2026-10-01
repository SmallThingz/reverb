package app.smallthingz.reverb

import java.io.File
import javax.imageio.ImageIO
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element

class BrandAssetsTest {
    private val root = listOf(File(".."), File(".")).first { File(it, "app/src/main/icon.svg").isFile }
    private val android = "http://schemas.android.com/apk/res/android"
    private fun file(path: String) = File(root, path)
    private fun document(path: String): Element = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    }.newDocumentBuilder().parse(file(path)).documentElement
    private fun Element.nodes(tag: String): List<Element> = getElementsByTagName(tag).let { nodes ->
        List(nodes.length) { nodes.item(it) as Element }
    }
    private fun Element.a(name: String) = getAttributeNS(android, name)
    private fun Element.namedGroup(name: String) = nodes("group").single { it.a("name") == name }
    private val svg get() = document("app/src/main/icon.svg")
    private fun vector(name: String) = document("app/src/main/res/drawable/$name.xml")
    private fun animator(name: String) = document("app/src/main/res/animator/reverb_splash_$name.xml")

    @Test fun nativeVariantsUseTheSameSvgPathsAndOpticalAnchor() {
        val source = svg
        val paths = source.nodes("path").associate { it.getAttribute("id") to it.getAttribute("d") }
        assertEquals(setOf("body", "leg"), paths.keys)
        val anchor = source.nodes("g").single { it.getAttribute("id") == "mark" }
        val translation = Regex("translate\\(([-\\d.]+) ([-\\d.]+)\\)")
            .matchEntire(anchor.getAttribute("transform"))!!.groupValues
        val glyph = source.nodes("g").single { it.getAttribute("id") == "r" }
        val uses = source.nodes("use")
        assertEquals(3, uses.size)
        for (name in listOf("ic_launcher_foreground", "ic_launcher_monochrome", "reverb_splash_vector")) {
            val drawable = vector(name)
            assertEquals("512", drawable.a("viewportWidth"))
            assertEquals("512", drawable.a("viewportHeight"))
            val mark = drawable.namedGroup("mark_root")
            assertEquals(translation[1], mark.a("translateX"))
            assertEquals(translation[2], mark.a("translateY"))
            assertEquals(6, drawable.nodes("path").size)
            for (use in uses) {
                assertEquals("#r", use.getAttribute("href"))
                val layer = use.getAttribute("id")
                val group = drawable.namedGroup(if (layer == "main") "main_r" else "${layer}_echo")
                val expectedX = if (name == "reverb_splash_vector" && layer == "far") {
                    uses.single { it.getAttribute("id") == "near" }.getAttribute("x")
                } else use.getAttribute("x")
                assertEquals(expectedX, group.a("translateX"))
                for (path in group.nodes("path")) {
                    assertEquals(paths[path.a("name").substringAfter('_')], path.a("pathData"))
                    assertEquals(glyph.getAttribute("stroke-width"), path.a("strokeWidth"))
                    assertEquals(glyph.getAttribute("stroke-linecap"), path.a("strokeLineCap"))
                    assertEquals(glyph.getAttribute("stroke-linejoin"), path.a("strokeLineJoin"))
                    val alpha = if (name == "reverb_splash_vector" && layer != "main") "0" else use.getAttribute("stroke-opacity")
                    assertEquals(alpha, path.a("strokeAlpha"))
                }
            }
        }
    }

    @Test fun splashNeverMovesThePrimaryRAndFinishesAtTheSameEchoGeometry() {
        val targets = vector("reverb_splash_animated_icon").nodes("target")
        assertEquals(setOf("far_echo", "near_body", "near_leg", "far_body", "far_leg"),
            targets.map { it.a("name") }.toSet())
        assertEquals("0", vector("reverb_splash_vector").namedGroup("main_r").a("translateX"))
        val uses = svg.nodes("use").associateBy { it.getAttribute("id") }
        assertEquals(uses.getValue("far").getAttribute("x"), animator("far_translate").a("valueTo"))
        for (layer in listOf("far", "near")) {
            assertEquals(uses.getValue(layer).getAttribute("stroke-opacity"), animator("${layer}_alpha").a("valueTo"))
        }
        assertFalse(file("app/src/main/res/animator/reverb_splash_main_translate.xml").exists())
    }

    @Test fun launcherAndInAppMarksHaveNoSeparateOffsets() {
        for (qualifier in listOf("v26", "v33")) {
            for (name in listOf("ic_launcher", "ic_launcher_round")) {
                val adaptive = document("app/src/main/res/mipmap-anydpi-$qualifier/$name.xml")
                assertEquals("@drawable/ic_launcher_foreground_inset", adaptive.nodes("foreground").single().a("drawable"))
                if (qualifier == "v33") assertEquals("@drawable/ic_launcher_monochrome_inset", adaptive.nodes("monochrome").single().a("drawable"))
            }
        }
        for (name in listOf("foreground", "monochrome")) {
            val inset = vector("ic_launcher_${name}_inset")
            assertEquals("@drawable/ic_launcher_$name", inset.a("drawable"))
            for (edge in listOf("Left", "Top", "Right", "Bottom")) assertEquals("0dp", inset.a("inset$edge"))
        }
        val inApp = file("app/src/main/java/app/smallthingz/reverb/ReverbBrandMark.kt").readText()
        assertTrue(inApp.contains("painterResource(R.drawable.ic_launcher_foreground)"))
        assertFalse(inApp.contains(".offset("))
        assertFalse(inApp.contains("translationX"))
    }

    @Test fun storeIconCentersTheBrightRInsteadOfItsFaintEchoes() {
        val image = ImageIO.read(file("fastlane/metadata/android/en-US/images/icon.png"))
        assertEquals(512, image.width)
        assertEquals(512, image.height)
        var left = 512; var right = 0; var top = 512; var bottom = 0
        var count = 0; var xSum = 0.0; var ySum = 0.0
        for (y in 0 until 512) for (x in 0 until 512) {
            val color = image.getRGB(x, y)
            if ((color ushr 24) > 128 && ((color ushr 16) and 255) > 200 &&
                ((color ushr 8) and 255) > 200 && (color and 255) > 200) {
                left = minOf(left, x); right = maxOf(right, x)
                top = minOf(top, y); bottom = maxOf(bottom, y)
                count++; xSum += x + 0.5; ySum += y + 0.5
            }
        }
        assertTrue(count > 10_000)
        assertTrue("Primary R bounds are off-center", abs((left + right + 1) * 0.5 - 256) <= 2)
        assertTrue(abs((top + bottom + 1) * 0.5 - 256) <= 2)
        assertTrue("Primary R visual mass is off-center", abs(xSum / count - 256) <= 4)
        assertTrue(abs(ySum / count - 256) <= 4)
    }
}
