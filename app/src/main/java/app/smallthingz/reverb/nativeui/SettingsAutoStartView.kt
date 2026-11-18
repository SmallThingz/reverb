package app.smallthingz.reverb.nativeui

import smallthingz.ui.components.NativeGroupLayout
import android.content.Context
import android.view.View
import android.view.ViewGroup
import app.smallthingz.reverb.R
import kotlin.math.roundToInt

internal class SettingsAutoStartView(
    context: Context,
    private var environment: ReverbViewEnvironment,
    inputRoot: () -> View?,
    changed: (Boolean) -> Unit,
) : ViewGroup(context), AutoCloseable {
    private val group = NativeGroupLayout(context, 20f, onPaintedSurfaceChanged = ::invalidateSettingsTextContrast)
    private val toggle = SettingsToggleRowView(context, context.getString(R.string.auto_start_title),
        false, false, environment, inputRoot, dimDisabledText = true, changed = changed,
        summary = context.getString(R.string.auto_start_summary))
    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        group.addRow(toggle, NativeGroupLayout.LayoutParams(-1, -2))
        addView(group)
    }
    fun bind(checked: Boolean, enabled: Boolean, permissionPending: Boolean, unavailable: Boolean,
        permissionMissing: Boolean, next: ReverbViewEnvironment) {
        environment = next
        group.setPalette(next.palette.field, next.palette.border)
        toggle.bind(checked, enabled, next, context.getString(when {
            unavailable -> R.string.auto_start_unavailable
            permissionPending -> R.string.auto_start_permission_pending
            permissionMissing -> R.string.auto_start_permission_missing
            else -> R.string.auto_start_summary
        }))
        requestLayout()
    }
    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w)
        group.measure(MeasureSpec.makeMeasureSpec((width - 2 * dp(16)).coerceAtLeast(0), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        setMeasuredDimension(resolveSize(width, w), resolveSize(group.measuredHeight + dp(24), h))
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        group.layout(dp(16), dp(12), width - dp(16), dp(12) + group.measuredHeight)
    }
    override fun close() = toggle.close()
    private fun dp(value: Int) = (value * environment.textDensity.density).roundToInt()
}
