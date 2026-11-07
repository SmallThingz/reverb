package app.smallthingz.reverb

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import app.smallthingz.reverb.nativeui.NativeEditableCursorDrawable

/** Exercises the platform clone contract with real Android Paint/Drawable/Canvas objects. */
internal fun verifyNativeEditableCursorClone(context: Context) {
    check(context.packageName.endsWith(".reliability"))
    val original = NativeEditableCursorDrawable(2f, Color.RED)
    original.bind(4f, Color.BLUE)
    original.alpha = 128
    val filter = PorterDuffColorFilter(Color.GREEN, PorterDuff.Mode.SRC_IN)
    original.colorFilter = filter
    check(original.alpha == 128 && original.colorFilter === filter)
    original.changingConfigurations = 16
    val snapshot = checkNotNull(original.constantState) { "Editor cursor must support ConstantState cloning" }
    val clone = snapshot.newDrawable() as NativeEditableCursorDrawable
    val resourceClone = snapshot.newDrawable(context.resources) as NativeEditableCursorDrawable
    check(clone !== original && clone !== resourceClone)
    check(clone.intrinsicWidth == 4 && clone.changingConfigurations == 16)
    check(clone.alpha == 128 && clone.colorFilter === filter)
    check(resourceClone.alpha == 128 && resourceClone.colorFilter === filter)
    check(clone.bounds.isEmpty)
    fun pixels(cursor: NativeEditableCursorDrawable): IntArray {
        val bitmap = Bitmap.createBitmap(12, 12, Bitmap.Config.ARGB_8888)
        return try {
            cursor.setBounds(2, 1, 6, 11)
            cursor.draw(Canvas(bitmap))
            IntArray(144).also { bitmap.getPixels(it, 0, 12, 0, 0, 12, 12) }
        } finally { bitmap.recycle() }
    }
    val expected = pixels(original)
    check(expected.any { Color.alpha(it) > 0 })
    check(expected.contentEquals(pixels(clone))) { "Clone lost bound color, alpha, filter or width" }
    check(expected.contentEquals(pixels(resourceClone)))
    original.bind(1f, Color.YELLOW)
    original.colorFilter = null
    check(original.colorFilter == null)
    check(clone.alpha == 128 && clone.colorFilter === filter)
    check(expected.contentEquals(pixels(clone))) { "Original mutation changed clone paint" }
    clone.bind(7f, Color.MAGENTA)
    clone.colorFilter = null
    check(expected.contentEquals(pixels(resourceClone))) { "Sibling clones share mutable paint" }
    check(expected.contentEquals(pixels(snapshot.newDrawable() as NativeEditableCursorDrawable))) {
        "ConstantState snapshot changed after original mutation"
    }
    original.beginTextDraw()
    try {
        val deferredClone = checkNotNull(original.constantState).newDrawable() as NativeEditableCursorDrawable
        check(pixels(deferredClone).any { Color.alpha(it) > 0 }) { "Clone inherited deferred drawing state" }
    } finally { original.endTextDraw() }
}
