/*
 * Generic border algorithm adapted from AndroidX foundation 1.11.2 Border.kt.
 * Copyright 2020 The Android Open Source Project; Apache License 2.0.
 */
package app.smallthingz.reverb.nativeui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import app.smallthingz.nativeui.geometry.Offset
import app.smallthingz.nativeui.geometry.Size
import app.smallthingz.reverb.NativeHomePointerRoot
import app.smallthingz.reverb.NativeViewPointerHitLayout
import app.smallthingz.reverb.NativeWindowPointerCoordinates
import kotlin.math.ceil
import kotlin.math.min

/**
 * Exact released two-lobe fill and pinned generic-path border. Fill/border are outside the child
 * clipping layer. This does not replace the shape with a rounded rectangle or center-line stroke.
 */
internal abstract class ReverbHomeControlSurface(context: Context) : ViewGroup(context) {
    // This existing clipped content is also the retained-pointer phase owner. Android's ordinary
    // rectangular child dispatch cannot reach a 40dp tab's source-expanded 48dp touch target.
    protected val inputCoordinates: NativeWindowPointerCoordinates = NativeWindowPointerCoordinates { content }
    private val inputLayout: NativeViewPointerHitLayout = object : NativeViewPointerHitLayout {
        override val placed get() = content.isAttachedToWindow && content.visibility == VISIBLE
        override val size get() = Size(width.toFloat(), height.toFloat())
        override val minimumTouchTarget get() = Size.Zero
        override val nodes = emptyList<app.smallthingz.reverb.NativeViewPhasePointerNode>()
        override val childrenInHitOrder get() = childHitLayouts()
        override fun screenToLocal(screen: Offset) = inputCoordinates.coordinates(content).local(screen)
        override fun withinLayerBounds(local: Offset) = contains(local.x, local.y)
    }
    protected val content: ControlContent = ControlContent(context, inputCoordinates, inputLayout)
    protected abstract fun childHitLayouts(): List<NativeViewPointerHitLayout>
    private val fill = Paint(FLAGS)
    private val borderPaint = Paint(FLAGS)
    private val rasterPaint = Paint(FLAGS)
    private val shape = Path()
    private val mask = Path()
    private val bounds = RectF()
    private val sourceRect = Rect()
    private val destinationRect = RectF()
    private var borderColorFilter: PorterDuffColorFilter? = null
    private val hitPoint = Path()
    private val hitIntersection = Path()
    private var bitmap: Bitmap? = null
    private var palette: ReverbPalette? = null
    private var density = resources.displayMetrics.density
    private var fullBorder = false
    private var borderDirty = true
    private var borderTint = 0

    init {
        setWillNotDraw(false)
        clipChildren = false
        clipToPadding = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        if (Build.VERSION.SDK_INT >= 29) isForceDarkAllowed = false
        addView(content)
    }

    protected fun setSurfaceEnvironment(environment: ReverbViewEnvironment) {
        val previous = palette
        palette = environment.palette
        fill.color = environment.palette.field
        if (previous?.border != environment.palette.border || density != environment.textDensity.density) {
            borderDirty = true
        }
        density = environment.textDensity.density
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        reverbCaptureControlUnionPath(w.toFloat(), h.toFloat(), shape)
        shape.computeBounds(bounds, false)
        content.setShape(shape)
        borderDirty = true
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawPath(shape, fill)
    }

    override fun draw(canvas: Canvas) {
        super.draw(canvas)
        val color = palette?.border ?: return
        if (width <= 0 || height <= 0) return
        if (borderDirty) updateBorder(color)
        if (fullBorder) {
            borderPaint.colorFilter = null
            borderPaint.color = color
            canvas.drawPath(shape, borderPaint)
        } else bitmap?.let { image ->
            borderPaint.color = android.graphics.Color.BLACK
            borderPaint.colorFilter = borderColorFilter
            val checkpoint = canvas.save()
            canvas.translate(bounds.left, bounds.top)
            canvas.drawBitmap(image, sourceRect, destinationRect, borderPaint)
            canvas.restoreToCount(checkpoint)
        }
    }

    private fun updateBorder(color: Int) {
        borderDirty = false
        val strokeWidth = min(ceil(density), ceil(min(width, height) / 2f))
        fullBorder = strokeWidth * 2 > min(width, height)
        if (fullBorder) return
        val cacheWidth = ceil(bounds.width()).toInt()
        val cacheHeight = ceil(bounds.height()).toInt()
        if (cacheWidth <= 0 || cacheHeight <= 0) return
        var image = bitmap
        if (image == null || image.width < cacheWidth || image.height < cacheHeight) {
            // Old display lists may retain the old Bitmap; do not recycle it before their retirement.
            image = Bitmap.createBitmap(cacheWidth, cacheHeight, Bitmap.Config.ALPHA_8)
            bitmap = image
        }
        image.eraseColor(android.graphics.Color.TRANSPARENT)
        val cache = Canvas(image)
        sourceRect.set(0, 0, cacheWidth, cacheHeight)
        destinationRect.set(0f, 0f, cacheWidth.toFloat(), cacheHeight.toFloat())
        mask.rewind()
        mask.addRect(bounds, Path.Direction.CCW)
        mask.op(shape, Path.Op.DIFFERENCE)
        val saved = cache.save()
        cache.clipRect(0, 0, cacheWidth, cacheHeight)
        cache.translate(-bounds.left, -bounds.top)
        rasterPaint.color = color
        rasterPaint.style = Paint.Style.STROKE
        rasterPaint.strokeWidth = strokeWidth * 2
        rasterPaint.strokeCap = Paint.Cap.BUTT
        rasterPaint.strokeJoin = Paint.Join.MITER
        rasterPaint.strokeMiter = 4f
        rasterPaint.xfermode = null
        cache.drawPath(shape, rasterPaint)
        // DrawScope scale uses the cache's logical size, not the retained larger allocation.
        cache.scale((cacheWidth + 1f) / cacheWidth, (cacheHeight + 1f) / cacheHeight,
            cacheWidth / 2f, cacheHeight / 2f)
        rasterPaint.style = Paint.Style.FILL
        rasterPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        cache.drawPath(mask, rasterPaint)
        rasterPaint.xfermode = null
        cache.restoreToCount(saved)
        borderTint = color or -0x1000000
        borderColorFilter = PorterDuffColorFilter(borderTint, PorterDuff.Mode.SRC_IN)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN && !contains(event.x, event.y)) return false
        return super.dispatchTouchEvent(event)
    }

    private fun contains(x: Float, y: Float): Boolean {
        // Source ShapeContainingUtil generic path admission is a tiny intersecting square.
        hitPoint.rewind()
        hitPoint.addRect(x - .005f, y - .005f, x + .005f, y + .005f, Path.Direction.CCW)
        hitIntersection.op(shape, hitPoint, Path.Op.INTERSECT)
        return !hitIntersection.isEmpty
    }

    override fun onTouchEvent(event: MotionEvent) = true // Surface inert-area shield.

    protected class ControlContent(
        context: Context,
        coordinates: NativeWindowPointerCoordinates,
        hitLayout: NativeViewPointerHitLayout,
    ) : NativeHomePointerRoot(context, coordinates, hitLayout) {
        private val shape = Path()
        private var manualClip = false
        private val sourceOutline = Outline()
        init {
            clipChildren = false
            clipToPadding = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) { outline.set(sourceOutline) }
            }
        }
        fun setShape(value: Path) {
            shape.set(value)
            sourceOutline.setEmpty()
            if (Build.VERSION.SDK_INT >= 30) sourceOutline.setPath(shape)
            else if (Build.VERSION.SDK_INT > 28 || shape.isConvex) {
                @Suppress("DEPRECATION")
                sourceOutline.setConvexPath(shape)
            }
            manualClip = (Build.VERSION.SDK_INT <= 28 && !shape.isConvex) || !sourceOutline.canClip()
            clipToOutline = !manualClip
            invalidateOutline()
            invalidate()
        }
        override fun dispatchDraw(canvas: Canvas) {
            if (!manualClip) { super.dispatchDraw(canvas); return }
            val checkpoint = canvas.save()
            canvas.clipPath(shape)
            super.dispatchDraw(canvas)
            canvas.restoreToCount(checkpoint)
        }
        // Outer source-specific cluster measures and positions the actual row children.
        override fun onMeasure(w: Int, h: Int) {
            setMeasuredDimension(MeasureSpec.getSize(w), MeasureSpec.getSize(h))
        }
        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) = Unit
    }

    private companion object {
        const val FLAGS = Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG or Paint.FILTER_BITMAP_FLAG
    }
}
