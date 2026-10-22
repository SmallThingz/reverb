package app.smallthingz.reverb.nativeui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import app.smallthingz.nativeui.unit.sp
import app.smallthingz.reverb.R
import app.smallthingz.reverb.RetentionMode
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** RetentionValue2258–2352; editor remains the actual native raw-text/IME owner supplied by host. */
internal class SettingsRetentionValueView(
    context:Context,
    private val glyph:ReverbIcon,
    label:String,
    private val editor:View,
    private var environment:ReverbViewEnvironment,
):ViewGroup(context), app.smallthingz.reverb.NativeAndroidInteropHost {
    override val nativeInteropRequests = app.smallthingz.reverb.NativeAndroidInteropRequests()
    override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        nativeInteropRequests.request(disallowIntercept)
        super.requestDisallowInterceptTouchEvent(disallowIntercept)
    }

    private val icon=OnboardingIconView(context,glyph,environment.palette.muted)
    private val heading=environment.paragraph(context,label,ReverbTextRole.BODY_LARGE,environment.palette.muted)
    private val unit=environment.paragraph(context,context.getString(R.string.retention_mib_unit),ReverbTextRole.BODY_LARGE,environment.palette.muted)
    private val estimate=environment.paragraph(context,"",ReverbTextRole.BODY_LARGE,environment.palette.muted)
    private var time=true
    private var headerHeight=0
    private var headerGap=0
    private var valueHeight=0
    private var valueGap=0
    private var editorWidth=0
    init {
        importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO
        icon.importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(icon);addView(heading);addView(editor);addView(unit);addView(estimate)
        bindTypography()
    }
    fun bind(mode:RetentionMode,computedSizeMb:Double,computedTimeSeconds:Long,next:ReverbViewEnvironment) {
        environment=next;time=mode==RetentionMode.TIME
        unit.visibility=if(time)GONE else VISIBLE
        estimate.setText(if(time)context.getString(R.string.retention_size_estimate,
            computedSizeMb.coerceAtLeast(0.0).roundToLong().toString())
            else context.getString(R.string.retention_time_estimate,formatRetentionMinutesEstimate(computedTimeSeconds)))
        bindTypography();requestLayout()
    }
    private fun bindTypography() {
        val base=reverbDefaultTextStyle()
        heading.setTypography(base.copy(fontSize=14.sp,lineHeight=20.sp),environment.textDensity,environment.sharedFontResolver)
        unit.setTypography(base.copy(fontSize=14.sp,lineHeight=20.sp),environment.textDensity,environment.sharedFontResolver)
        estimate.setTypography(base.copy(fontSize=13.sp,lineHeight=20.sp),environment.textDensity,environment.sharedFontResolver)
        heading.setTextColor(environment.palette.muted);unit.setTextColor(environment.palette.muted)
        estimate.setTextColor(environment.palette.muted);icon.bind(glyph,environment.palette.muted)
    }
    override fun onMeasure(w:Int,h:Int) {
        val width=MeasureSpec.getSize(w)
        icon.measure(exact(min(dp(17),width)),exact(dp(17)))
        headerGap=min(dp(8),(width-icon.measuredWidth).coerceAtLeast(0))
        heading.measure(atMost((width-icon.measuredWidth-headerGap).coerceAtLeast(0)),unspecified())
        headerHeight=max(icon.measuredHeight,heading.measuredHeight)
        if(!time)unit.measure(atMost(width),unspecified())
        valueGap=if(time)0 else min(dp(7),(width-unit.measuredWidth).coerceAtLeast(0))
        editorWidth=(width-(if(time)0 else unit.measuredWidth)-valueGap).coerceAtLeast(0)
        editor.measure(exact(editorWidth),unspecified())
        valueHeight=max(editor.measuredHeight,if(time)0 else unit.measuredHeight+dp(6))
        estimate.measure(atMost(width),unspecified())
        setMeasuredDimension(resolveSize(width,w),resolveSize(headerHeight+dp(10)+valueHeight+dp(3)+estimate.measuredHeight,h))
    }
    override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int) {
        place(icon,0,((headerHeight-icon.measuredHeight)/2f).roundToInt())
        place(heading,icon.measuredWidth+headerGap,((headerHeight-heading.measuredHeight)/2f).roundToInt())
        val valueTop=headerHeight+dp(10)
        place(editor,0,valueTop+valueHeight-editor.measuredHeight)
        if(!time)place(unit,editorWidth+valueGap,valueTop+valueHeight-unit.measuredHeight-dp(6))
        place(estimate,0,valueTop+valueHeight+dp(3))
    }
    private fun place(view:View,start:Int,y:Int) {
        val x=if(layoutDirection==LAYOUT_DIRECTION_RTL)width-start-view.measuredWidth else start
        view.layout(x,y,x+view.measuredWidth,y+view.measuredHeight)
    }
    private fun dp(v:Int)=(v*environment.textDensity.density).roundToInt()
}

// Exact released private formatter; no locale-dependent parse/normalization is introduced.
private fun formatRetentionMinutesEstimate(seconds:Long):String {
    if(seconds<=0L)return "0"
    return if(seconds%60L==0L)(seconds/60L).toString()
        else String.format(Locale.US,"%.1f",seconds/60.0)
}
