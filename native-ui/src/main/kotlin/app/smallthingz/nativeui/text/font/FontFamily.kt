package app.smallthingz.nativeui.text.font

import android.content.Context
import android.content.res.Configuration
import android.graphics.Typeface
import android.os.Build

data class FontWeight(val weight: Int) : Comparable<FontWeight> {
    init { require(weight in 1..1000) }
    override fun compareTo(other: FontWeight): Int = weight.compareTo(other.weight)
    companion object {
        val Thin = FontWeight(100)
        val ExtraLight = FontWeight(200)
        val Light = FontWeight(300)
        val Normal = FontWeight(400)
        val Medium = FontWeight(500)
        val SemiBold = FontWeight(600)
        val Bold = FontWeight(700)
        val ExtraBold = FontWeight(800)
        val Black = FontWeight(900)
    }
}

enum class FontStyle { Normal, Italic }
enum class FontSynthesis { None, All, Weight, Style }

/** A named Android system family; no bundled or downloadable font runtime. */
class FontFamily private constructor(internal val familyName: String?) {
    interface Resolver {
        fun resolve(
            fontFamily: FontFamily? = null,
            fontWeight: FontWeight = FontWeight.Normal,
            fontStyle: FontStyle = FontStyle.Normal,
            fontSynthesis: FontSynthesis = FontSynthesis.All,
        ): ResolvedTypeface
    }

    companion object {
        val Default = FontFamily(null)
        val SansSerif = FontFamily("sans-serif")
        val Serif = FontFamily("serif")
        val Monospace = FontFamily("monospace")
        val Cursive = FontFamily("cursive")
    }
}

class ResolvedTypeface(val value: Typeface)

/**
 * The owning window replaces this resolver with its configuration generation.
 * Resolved system Typefaces are stable and need no snapshot observation or readiness polling.
 */
fun createFontFamilyResolver(context: Context): FontFamily.Resolver {
    val configuration = Configuration(context.resources.configuration)
    val adjustment = if (Build.VERSION.SDK_INT >= 31) configuration.fontWeightAdjustment else 0
    return PlatformFontResolver(adjustment)
}

private class PlatformFontResolver(private val adjustment: Int) : FontFamily.Resolver {
    private data class Key(val family: FontFamily, val weight: Int, val italic: Boolean)
    private val cache = object : LinkedHashMap<Key, ResolvedTypeface>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, ResolvedTypeface>?): Boolean = size > 32
    }

    override fun resolve(fontFamily: FontFamily?, fontWeight: FontWeight, fontStyle: FontStyle,
                         fontSynthesis: FontSynthesis): ResolvedTypeface = synchronized(cache) {
        val family = fontFamily ?: FontFamily.Default
        val weight = if (adjustment == 0 || adjustment == Int.MAX_VALUE) fontWeight.weight
            else (fontWeight.weight + adjustment).coerceIn(1, 1000)
        val key = Key(family, weight, fontStyle == FontStyle.Italic)
        cache[key] ?: ResolvedTypeface(
            if (family === FontFamily.Default && weight == 400 && !key.italic) Typeface.DEFAULT
            else Typeface.create(Typeface.create(family.familyName, Typeface.NORMAL), weight, key.italic)
        ).also { cache[key] = it }
    }
}
