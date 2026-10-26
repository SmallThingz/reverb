package app.smallthingz.reverb.nativeui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.smallthingz.reverb.NativeQuickSettingsTilesOwner
import app.smallthingz.reverb.QuickSettingsTileDeleteTarget
import app.smallthingz.reverb.R
import kotlin.math.max
import kotlin.math.roundToInt

/** Actual tile-delete sheet content, retaining one target identity and accepted owner mutation. */
internal class QuickTileDeleteContent(
    context: Context,
    private val owner: NativeQuickSettingsTilesOwner,
    val target: QuickSettingsTileDeleteTarget,
    private var environment: ReverbViewEnvironment,
    inputRoot: () -> View?,
    private val sheetLifecycle: Lifecycle,
    private val sheetView: () -> View,
    private val hasWindowFocus: () -> Boolean,
) : AutoCloseable {
    // Text() inherits MaterialTheme's bodyLarge; the source does not specify bodyMedium here.
    val body = ReverbParagraphView(context, reverbDefaultTextStyle(), environment.textDensity,
        environment.sharedFontResolver, environment.palette.onSurface).also {
        it.setText(context.getString(R.string.tiles_ui_delete_confirmation))
    }
    private val cancel = SettingsCalibrationActionView(context, environment, inputRoot) {
        owner.dismissDelete()
    }
    private val confirm = SettingsCalibrationActionView(context, environment, inputRoot) {
        val view = sheetView()
        owner.confirmDelete(target, sheetLifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
            view.isAttachedToWindow && view.isShown && hasWindowFocus())
    }
    val actions = Actions(context)
    private var closed = false
    private val lifecycleObserver = LifecycleEventObserver { _, _ -> bind(environment) }
    init {
        actions.addView(cancel)
        actions.addView(confirm)
        sheetLifecycle.addObserver(lifecycleObserver)
        bind(environment)
    }
    fun bind(next: ReverbViewEnvironment) {
        if (closed) return
        environment = next
        body.setTypography(reverbDefaultTextStyle(), next.textDensity, next.sharedFontResolver)
        body.setTextColor(next.palette.onSurface)
        cancel.bind(body.context.getString(R.string.cancel), !owner.editorState.deletingBusy, next)
        confirm.bind(body.context.getString(R.string.delete_recording),
            owner.canConfirmDelete(target, hasWindowFocus() &&
                sheetLifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)), next)
        actions.requestLayout()
    }
    override fun close() {
        if (closed) return
        closed = true
        sheetLifecycle.removeObserver(lifecycleObserver)
        cancel.close()
        confirm.close()
    }
    internal inner class Actions(context: Context) : ViewGroup(context) {
        init {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            clipChildren = false
            clipToPadding = false
        }
        override fun onWindowFocusChanged(hasFocus: Boolean) {
            super.onWindowFocusChanged(hasFocus)
            bind(environment)
        }
        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            bind(environment)
        }
        override fun onMeasure(w: Int, h: Int) {
            val width = MeasureSpec.getSize(w)
            cancel.measure(atMost(width), unspecified())
            confirm.measure(atMost((width - cancel.measuredWidth).coerceAtLeast(0)), unspecified())
            setMeasuredDimension(resolveSize(width, w),
                resolveSize(max(cancel.measuredHeight, confirm.measuredHeight), h))
        }
        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            var start = width - cancel.measuredWidth - confirm.measuredWidth
            for (index in 0..1) {
                val child = if (index == 0) cancel else confirm
                val x = if (layoutDirection == LAYOUT_DIRECTION_RTL) width - start - child.measuredWidth else start
                val y = ((height - child.measuredHeight) / 2f).roundToInt()
                child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
                start += child.measuredWidth
            }
        }
    }
}
