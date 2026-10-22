package app.smallthingz.nativeui.text.style

enum class TextOverflow { Clip, Ellipsis, Visible, StartEllipsis, MiddleEllipsis }
enum class ResolvedTextDirection { Ltr, Rtl }

data class LineHeightStyle(
    val alignment: Alignment,
    val trim: Trim,
    val mode: Mode = Mode.Fixed,
) {
    enum class Alignment(val topRatio: Float) { Top(0f), Center(0.5f), Proportional(-1f), Bottom(1f) }
    enum class Trim { None, FirstLineTop, LastLineBottom, Both }
    enum class Mode { Fixed, Minimum, Tight }
    companion object { val Default = LineHeightStyle(Alignment.Proportional, Trim.Both) }
}

@JvmInline
value class TextDecoration private constructor(private val mask: Int) {
    operator fun contains(other: TextDecoration): Boolean = mask and other.mask == other.mask
    operator fun plus(other: TextDecoration) = TextDecoration(mask or other.mask)
    companion object {
        val None = TextDecoration(0)
        val Underline = TextDecoration(1)
        val LineThrough = TextDecoration(2)
    }
}
