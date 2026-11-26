package app.smallthingz.reverb

import android.R as AndroidR
import java.io.File
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element
import smallthingz.ui.components.NativeColorScheme
import smallthingz.ui.components.NativeDarkSurfacePreset
import smallthingz.ui.components.NativeDefaultColorScheme
import smallthingz.ui.components.NativeGroupSurfaceRecipes
import smallthingz.ui.graphics.NOISE_TILE_SIZE
import smallthingz.ui.graphics.generateNoisePixels

class BrandAssetsTest {
    private val root = listOf(File(".."), File(".")).first { File(it, "native-ui/src/main/brand/reverb.svg").isFile }
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
    private val svg get() = document("native-ui/src/main/brand/reverb.svg")
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
                assertEquals("@drawable/ic_launcher_background", adaptive.nodes("background").single().a("drawable"))
                assertEquals("@drawable/ic_launcher_foreground_inset", adaptive.nodes("foreground").single().a("drawable"))
                if (qualifier == "v33") assertEquals("@drawable/ic_launcher_monochrome_inset", adaptive.nodes("monochrome").single().a("drawable"))
            }
        }
        for (name in listOf("foreground", "monochrome")) {
            val inset = vector("ic_launcher_${name}_inset")
            assertEquals("@drawable/ic_launcher_$name", inset.a("drawable"))
            for (edge in listOf("Left", "Top", "Right", "Bottom")) assertEquals("0dp", inset.a("inset$edge"))
        }
        val inApp = file("native-ui/src/main/kotlin/smallthingz/ui/reverb/ReverbBrandMarkView.kt").readText()
        assertTrue(inApp.contains("Generated from native-ui/src/main/brand/reverb.svg"))
        assertTrue(inApp.contains("NativeLayeredMarkView(context, description, reverbBrandArt, ::readReverbBrandPalette)"))
        assertTrue(inApp.contains("init { bindDefaultReverbBrandSurface() }"))
        val appAdapter = file("app/src/main/java/app/smallthingz/reverb/nativeui/ReverbBrandMarkView.kt").readText()
        assertTrue(appAdapter.contains("typealias ReverbBrandMarkView = smallthingz.ui.reverb.ReverbBrandMarkView"))
        assertFalse(appAdapter.contains("Path("))
        assertFalse(appAdapter.contains("translationX"))
        val home = file("app/src/main/java/app/smallthingz/reverb/nativeui/ReverbAppTopBarView.kt").readText()
        assertTrue(home.contains("import smallthingz.ui.reverb.bindReverbBrandSurface"))
        assertTrue(home.contains("mark.bindReverbBrandSurface(value.palette)"))
        val boot = file("app/src/main/java/app/smallthingz/reverb/BootResumeActivity.kt").readText()
        assertTrue(boot.contains("import smallthingz.ui.reverb.bindReverbBrandSurface"))
        assertTrue(boot.contains("apply { bindReverbBrandSurface(palette) }"))
    }

    @Test fun generatedRuntimeAssetsMatchCanonicalLibrarySourceWithoutStoreRenderer() {
        val process = ProcessBuilder("python3", "scripts/generate_brand_assets.py", "--check", "--android-only")
            .directory(root).redirectErrorStream(true).start()
        val completed = process.waitFor(30, TimeUnit.SECONDS)
        if (!completed) process.destroyForcibly()
        assertTrue("Brand verification timed out", completed)
        val output = process.inputStream.bufferedReader().use { it.readText() }
        assertEquals(output, 0, process.exitValue())
    }

    @Test fun everyLauncherPaletteAliasesTheQualifiedLibraryBrandRoles() {
        val libraryPalettes = file("native-ui/src/main/res").listFiles()!!.filter {
            it.name.startsWith("values") && File(it, "reverb_brand_colors.xml").isFile
        }
        assertTrue(libraryPalettes.map { it.name }.containsAll(listOf("values", "values-v31", "values-night-v31")))
        val roles = listOf("background", "accent", "main")
        for (directory in libraryPalettes) {
            val library = document("native-ui/src/main/res/${directory.name}/reverb_brand_colors.xml")
                .nodes("color").associate { it.getAttribute("name") to it.textContent.trim() }
            assertEquals(roles.map { "reverb_brand_$it" }.toSet(), library.keys)
            assertEquals("@color/reverb_brand_surface", library["reverb_brand_background"])
            assertEquals("@color/reverb_brand_surface_accent", library["reverb_brand_accent"])
            assertEquals("@color/reverb_brand_surface_main", library["reverb_brand_main"])
            val launcher = document("app/src/main/res/${directory.name}/colors.xml")
                .nodes("color").associate { it.getAttribute("name") to it.textContent.trim() }
            for (role in roles) assertEquals("@color/reverb_brand_$role", launcher["launcher_$role"])
        }
    }

    @Test fun launcherPlateDelegatesToOneLibraryDrawableWithoutLocalGeometry() {
        val wrapper = vector("ic_launcher_background")
        assertEquals("layer-list", wrapper.tagName)
        val items = wrapper.nodes("item")
        assertEquals(1, items.size)
        assertEquals("@drawable/reverb_brand_plate", items.single().a("drawable"))
        assertEquals("The launcher wrapper must not add padding, tint or a second material",
            1, items.single().attributes.length)
        assertTrue(wrapper.nodes("path").isEmpty())
        assertTrue(wrapper.nodes("shape").isEmpty())
        assertTrue(wrapper.nodes("bitmap").isEmpty())
    }

    @Test fun libraryPlateAndInksUseSharedSurfaceRolesWithoutIndependentAppColors() {
        val recipe = NativeGroupSurfaceRecipes.Default.recordingInfoRow
        fun roles(colors: NativeColorScheme) = mapOf(
            "reverb_brand_surface" to recipe.fillColor(colors),
            "reverb_brand_surface_main" to colors.ink,
            "reverb_brand_surface_accent" to colors.primary,
        )
        val surfaces = file("native-ui/src/main/res").listFiles()!!.filter {
            it.name.startsWith("values") && File(it, "reverb_brand_surface.xml").isFile
        }
        assertTrue(surfaces.map { it.name }.containsAll(listOf("values", "values-night", "values-v31", "values-v34")))
        for (directory in surfaces) {
            val qualifier = directory.name
            val dark = "night" in qualifier.split('-')
            val sdk = Regex("-v(\\d+)").find(qualifier)?.groupValues?.get(1)?.toInt() ?: 28
            val declared = document("native-ui/src/main/res/$qualifier/reverb_brand_surface.xml")
                .nodes("color").associate { it.getAttribute("name") to it.textContent.trim() }
            // The default resolver includes AMOLED dark surfaces; resource IDs stand in for
            // dynamic colors so the role itself supplies the platform mapping without literals.
            val expected = roles(NativeDefaultColorScheme.resolve(dark, sdk) { it })
            for ((name, value) in expected) {
                val color = declared.getValue(name)
                if (color.startsWith("#")) {
                    val rgb = color.removePrefix("#")
                    assertTrue(rgb.length == 6 || rgb.length == 8)
                    val argb = rgb.toLong(16).toInt() or if (rgb.length == 6) 0xff000000.toInt() else 0
                    assertEquals("$qualifier/$name", value, argb)
                } else {
                    val roleName = AndroidR.color::class.java.fields.single { it.getInt(null) == value }.name
                    assertEquals("$qualifier/$name", "@android:color/$roleName", color)
                }
            }
        }
    }

    @Test fun launcherPlateAndGrainRemainLibraryOwnedAcrossAppSourceSets() {
        val owned = setOf("reverb_brand_surface", "reverb_brand_surface_main", "reverb_brand_surface_accent",
            "reverb_brand_plate", "reverb_brand_grain", "reverb_brand_grain_tile")
        val appSources = file("app/src")
        val shadows = appSources.walkTopDown().filter { it.isFile &&
            "/res/" in it.relativeTo(appSources).invariantSeparatorsPath }.filter { resource ->
            resource.nameWithoutExtension in owned ||
                (resource.extension == "xml" && resource.parentFile.name.startsWith("values") &&
                    document(resource.relativeTo(root).invariantSeparatorsPath).nodes("*")
                        .any { it.getAttribute("name") in owned })
        }.map { it.relativeTo(appSources).invariantSeparatorsPath }.toList()
        assertEquals("App variants must not shadow the shared plate material", emptyList<String>(), shadows)
    }

    @Test fun libraryPlateComposesItsSurfaceUnderTheSharedRepeatingGrain() {
        fun Element.references(): Set<String> = (listOf(this) + nodes("*")).flatMap { element ->
            List(element.attributes.length) { element.attributes.item(it).nodeValue }
        }.filter { it.startsWith("@") }.toSet()
        val plate = document("native-ui/src/main/res/drawable/reverb_brand_plate.xml")
        assertEquals("layer-list", plate.tagName)
        val layers = plate.nodes("item")
        assertEquals(2, layers.size)
        assertEquals(setOf("@color/reverb_brand_surface"), layers[0].references())
        assertEquals(setOf("@drawable/reverb_brand_grain_tile"), layers[1].references())
        assertTrue("The plate must not duplicate the SVG mark", plate.nodes("path").isEmpty())

        val grain = document("native-ui/src/main/res/drawable/reverb_brand_grain.xml")
        assertEquals("bitmap", grain.tagName)
        assertEquals("@drawable/reverb_brand_grain_tile", grain.a("src"))
        assertEquals("repeat", grain.a("tileMode"))
        assertEquals("false", grain.a("filter"))
        assertEquals("The native grain resource must remain raw", "", grain.a("alpha"))
        val launcherGrain = layers[1].nodes("bitmap").single()
        for (attribute in listOf("src", "tileMode", "filter")) {
            assertEquals("Launcher and native marks must sample the same raw texture: $attribute",
                grain.a(attribute), launcherGrain.a(attribute))
        }
        val alphaNodes = (listOf(plate) + plate.nodes("*")).filter { it.a("alpha").isNotBlank() }
        assertEquals("Apply foreground grain intensity once, not to both the PNG and drawable", 1, alphaNodes.size)
        assertEquals((NativeDarkSurfacePreset.Amoled.foregroundGrain * 255f).roundToInt(),
            (alphaNodes.single().a("alpha").toFloat() * 255f).roundToInt())
        assertTrue(file("native-ui/src/main/res/drawable-nodpi/reverb_brand_grain_tile.png").isFile)
        for (qualifier in listOf("values", "values-night", "values-v31", "values-v34")) {
            val surface = document("native-ui/src/main/res/$qualifier/reverb_brand_surface.xml")
            assertFalse("The shared role must not be transformed by a separate lStar rule",
                (listOf(surface) + surface.nodes("*")).any { element ->
                    (0 until element.attributes.length).any { element.attributes.item(it).localName == "lStar" }
                })
        }
    }

    @Test fun iconGrainIsTheRawPhaseZeroCropOfTheSharedNoiseDistribution() {
        val tile = ImageIO.read(file("native-ui/src/main/res/drawable-nodpi/reverb_brand_grain_tile.png"))
        // Icon-specific repetition period; general UI continues to use the full shared noise tile.
        assertEquals(64, tile.width)
        assertEquals(64, tile.height)
        assertTrue(tile.width < NOISE_TILE_SIZE)
        val shared = generateNoisePixels()
        for (y in 0 until tile.height) for (x in 0 until tile.width) {
            assertEquals("Raw grain drift or baked opacity at ($x, $y)",
                shared[y * NOISE_TILE_SIZE + x], tile.getRGB(x, y))
        }
    }

    @Test fun storeIconBalancesTheCompleteMarkWithCircularClearance() {
        val image = ImageIO.read(file("fastlane/metadata/android/en-US/images/icon.png"))
        assertEquals(512, image.width)
        assertEquals(512, image.height)
        fun luminance(color: Int): Double = .2126 * ((color ushr 16) and 255) +
            .7152 * ((color ushr 8) and 255) + .0722 * (color and 255)

        val background = luminance(image.getRGB(256, 80))
        var left = 512; var right = 0; var top = 512; var bottom = 0
        var primaryCount = 0
        var mass = 0.0; var xSum = 0.0; var ySum = 0.0
        var radiusSquared = 0.0
        for (y in 0 until 512) for (x in 0 until 512) {
            val color = image.getRGB(x, y)
            if (color ushr 24 != 255) continue
            val contrast = abs(luminance(color) - background)
            if (contrast < 1) continue
            if (((color ushr 16) and 255) > 200 && ((color ushr 8) and 255) > 200 && (color and 255) > 200) {
                primaryCount++
            }
            left = minOf(left, x); right = maxOf(right, x)
            top = minOf(top, y); bottom = maxOf(bottom, y)
            mass += contrast; xSum += (x + 0.5) * contrast; ySum += (y + 0.5) * contrast
            val dx = x + 0.5 - 256; val dy = y + 0.5 - 256
            radiusSquared = maxOf(radiusSquared, dx * dx + dy * dy)
        }
        assertTrue(primaryCount > 10_000)
        // Contrast mass leans left while the two echoes still give the full outline a rightward reach.
        assertTrue("Full-mark visual mass lost its slight left bias", xSum / mass in 243.0..251.0)
        assertTrue("Full bounds should retain both echoes", abs((left + right + 1) * 0.5 - 268.5) <= 2)
        assertTrue(abs((top + bottom + 1) * 0.5 - 256) <= 2)
        assertTrue(abs(ySum / mass - 256) <= 4)
        // AdaptiveIconDrawable shows the inner 72 of 108dp in each dimension.
        val circularMaskRadius = 512.0 / 3
        assertTrue("A stroke extends beyond the circular adaptive mask", radiusSquared < circularMaskRadius * circularMaskRadius)
    }
}
