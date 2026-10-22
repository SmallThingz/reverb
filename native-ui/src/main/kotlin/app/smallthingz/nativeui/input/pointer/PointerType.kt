package app.smallthingz.nativeui.input.pointer
@JvmInline value class PointerType private constructor(val value: Int) {
    companion object {
        val Unknown = PointerType(0); val Touch = PointerType(1); val Mouse = PointerType(2)
        val Stylus = PointerType(3); val Eraser = PointerType(4)
    }
}
