package app.smallthingz.nativeui.focus
@JvmInline value class FocusDirection private constructor(val value: Int) {
    companion object {
        val Next = FocusDirection(1); val Previous = FocusDirection(2)
        val Left = FocusDirection(3); val Right = FocusDirection(4)
        val Up = FocusDirection(5); val Down = FocusDirection(6)
        val Enter = FocusDirection(7); val Exit = FocusDirection(8)
    }
}
