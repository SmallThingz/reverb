package app.smallthingz.reverb.nativeui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import app.smallthingz.reverb.R
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal data class SettingsStorageState(
    val pathText:String,
    val hasCustomDirectory:Boolean,
    val canMove:Boolean,
    val settingsPersisting:Boolean,
    val allFilesAvailable:Boolean=false,
    val allFilesAllowed:Boolean=false,
    val audioReadAvailable:Boolean=false,
    val audioReadAllowed:Boolean=false,
)

/** Source SettingsScreen1874–1962; picker/result/move operations stay with their existing owner. */
internal class SettingsStorageSection(
    context:Context,
    initial:SettingsStorageState,
    private var environment:ReverbViewEnvironment,
    inputRoot:()->View?,
    onDefaultFolder:()->Unit,
    onChooseFolder:()->Unit,
    onMoveRecordings:()->Unit,
    onAllFilesAccess:()->Unit={},
    onAudioReadAccess:()->Unit={},
):ViewGroup(context),AutoCloseable {
    private var state=initial
    private val title=settingsSectionTitle(context,R.string.storage_settings_title,environment)
    private val surface=ReverbGroupLayout(context,20f)
    private val path=environment.paragraph(context,initial.pathText,ReverbTextRole.BODY_LARGE,environment.palette.muted)
    private val reset=SettingsStorageActionView(context,environment,ReverbIcon.Reset,
        context.getString(R.string.default_folder),inputRoot,onDefaultFolder)
    private val choose=SettingsStorageActionView(context,environment,ReverbIcon.Folder,
        context.getString(R.string.choose_folder),inputRoot,onChooseFolder)
    private val move=SettingsStorageActionView(context,environment,null,
        context.getString(R.string.move_recordings),inputRoot,onMoveRecordings)
    private val audioRead=SettingsPermissionReviewRow(context,environment,inputRoot,ReverbIcon.AudioFile,
        R.string.settings_audio_read_missing,R.string.background_review,R.string.audio_read_access_settings,onAudioReadAccess)
    private val allFiles=SettingsPermissionReviewRow(context,environment,inputRoot,ReverbIcon.Folder,
        R.string.settings_all_files_optional,R.string.settings_manage_access,R.string.all_files_access_settings,onAllFilesAccess)
    private val body=StorageBody(context)
    init {
        importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO
        body.addView(path);body.addView(reset);body.addView(choose);body.addView(move)
        surface.addRow(body,ReverbGroupLayout.LayoutParams(-1,-2))
        surface.addRow(audioRead,ReverbGroupLayout.LayoutParams(-1,-2).apply {dividerTop=true})
        surface.addRow(allFiles,ReverbGroupLayout.LayoutParams(-1,-2).apply {dividerTop=true})
        addView(title);addView(surface);bind(initial,environment)
    }
    fun bind(next:SettingsStorageState,colors:ReverbViewEnvironment) {
        state=next;environment=colors
        bindSectionTitle(title,colors)
        path.setText(next.pathText);colors.bindParagraphStyle(path,ReverbTextRole.BODY_LARGE,colors.palette.muted)
        surface.setPalette(colors.palette.field,colors.palette.border)
        reset.visibility=if(next.hasCustomDirectory)VISIBLE else GONE
        reset.bind(colors,true);choose.bind(colors,!next.settingsPersisting);move.bind(colors,next.canMove&&!next.settingsPersisting)
        val missingAllFiles=next.allFilesAvailable&&!next.allFilesAllowed
        allFiles.visibility=if(missingAllFiles)VISIBLE else GONE
        allFiles.bind(colors,!next.settingsPersisting&&missingAllFiles)
        val missingAudio=next.audioReadAvailable&&!next.audioReadAllowed
        audioRead.visibility=if(missingAudio)VISIBLE else GONE
        audioRead.bind(colors,!next.settingsPersisting&&missingAudio)
        requestLayout()
    }
    override fun onMeasure(w:Int,h:Int) {
        val width=MeasureSpec.getSize(w)
        val inner=(width-2*dp(16)).coerceAtLeast(0)
        title.measure(atMost(inner),unspecified());surface.measure(exact(inner),unspecified())
        setMeasuredDimension(resolveSize(width,w),resolveSize(2*dp(12)+title.measuredHeight+2*dp(4)+surface.measuredHeight,h))
    }
    override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int) {
        val titleX=if(layoutDirection==LAYOUT_DIRECTION_RTL)width-dp(16)-title.measuredWidth else dp(16)
        title.layout(titleX,dp(12),titleX+title.measuredWidth,dp(12)+title.measuredHeight)
        val top=2*dp(12)+title.measuredHeight+dp(4)
        surface.layout(dp(16),top,width-dp(16),top+surface.measuredHeight)
    }
    override fun close() {reset.close();choose.close();move.close();allFiles.close();audioRead.close()}
    private fun dp(value:Int)=(value*environment.textDensity.density).roundToInt()

    private inner class StorageBody(context:Context):ViewGroup(context) {
        private var rowHeight=0
        private var firstGap=0
        private var secondGap=0
        init {importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO}
        override fun onMeasure(w:Int,h:Int) {
            val width=MeasureSpec.getSize(w)
            val inner=(width-2*dp(16)).coerceAtLeast(0)
            var remaining=inner
            if(state.hasCustomDirectory) {
                reset.measure(exact(min(dp(48),remaining)),exact(dp(48)))
                remaining-=reset.measuredWidth
                secondGap=min(dp(8),remaining);remaining-=secondGap
            }else secondGap=0
            choose.measure(exact(min(dp(48),remaining)),exact(dp(48)))
            remaining-=choose.measuredWidth
            firstGap=min(dp(8),remaining);remaining-=firstGap
            // Path itself has weight1; unlike an inner Column its paragraph fills this allocation.
            path.measure(exact(remaining),unspecified())
            rowHeight=max(path.measuredHeight,max(choose.measuredHeight,
                if(state.hasCustomDirectory)reset.measuredHeight else 0))
            move.measure(atMost(inner),unspecified())
            setMeasuredDimension(width,2*dp(10)+rowHeight+move.measuredHeight)
        }
        override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int) {
            fun place(child:View,start:Int,top:Int) {
                val x=if(layoutDirection==LAYOUT_DIRECTION_RTL)width-start-child.measuredWidth else start
                child.layout(x,top,x+child.measuredWidth,top+child.measuredHeight)
            }
            fun center(child:View,start:Int)=place(child,start,dp(10)+((rowHeight-child.measuredHeight)/2f).roundToInt())
            var start=dp(16)
            center(path,start);start+=path.measuredWidth+firstGap
            if(state.hasCustomDirectory) {center(reset,start);start+=reset.measuredWidth+secondGap}
            center(choose,start)
            place(move,dp(16),dp(10)+rowHeight)
        }
    }
}

/** Same compact icon/title/action arrangement as the background-access warning, sharing group borders. */
internal class SettingsPermissionReviewRow(
    context:Context,
    private var environment:ReverbViewEnvironment,
    inputRoot:()->View?,
    private val glyph:ReverbIcon,
    private val titleResource:Int,
    private val actionResource:Int,
    private val actionDescriptionResource:Int,
    request:()->Unit,
):ViewGroup(context),AutoCloseable {
    private val icon=OnboardingIconView(context,glyph,environment.palette.tertiary)
    private val title=environment.paragraph(context,context.getString(titleResource),
        ReverbTextRole.BODY_MEDIUM,environment.palette.onSurface)
    private val action=SettingsCalibrationActionView(context,environment,inputRoot,action=request)
    private var rowHeight=0
    private var firstGap=0
    private var secondGap=0
    private var stackedAction=false
    init {
        importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO
        icon.importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO
        action.textView.contentDescription=context.getString(actionDescriptionResource)
        addView(icon);addView(title);addView(action)
    }
    fun bind(next:ReverbViewEnvironment,enabled:Boolean,titleResource:Int=this.titleResource,
        actionResource:Int=this.actionResource,actionDescriptionResource:Int=this.actionDescriptionResource,
        glyph:ReverbIcon=this.glyph) {
        environment=next
        icon.bind(glyph,next.palette.tertiary)
        title.setText(context.getString(titleResource))
        next.bindParagraphStyle(title,ReverbTextRole.BODY_MEDIUM,next.palette.onSurface)
        action.textView.contentDescription=context.getString(actionDescriptionResource)
        action.bind(context.getString(actionResource),enabled,next)
        requestLayout()
    }
    override fun onMeasure(w:Int,h:Int) {
        val width=MeasureSpec.getSize(w)
        val available=(width-2*dp(15)).coerceAtLeast(0)
        icon.measure(exact(min(dp(22),available)),exact(dp(22)))
        firstGap=min(dp(12),(available-icon.measuredWidth).coerceAtLeast(0))
        action.measure(atMost(available),unspecified())
        secondGap=min(dp(12),(available-icon.measuredWidth-firstGap-action.measuredWidth).coerceAtLeast(0))
        val inlineTitleWidth=(available-icon.measuredWidth-firstGap-action.measuredWidth-secondGap).coerceAtLeast(0)
        title.measure(exact(inlineTitleWidth),unspecified())
        // Keep ordinary one/two-line labels compact; move the action before text becomes a sliver.
        val inlineLineCount=title.currentParagraph?.lineCount?:Int.MAX_VALUE
        stackedAction=inlineTitleWidth==0||(inlineLineCount>2&&title.measuredHeight>action.measuredHeight)
        if(stackedAction)title.measure(exact((available-icon.measuredWidth-firstGap).coerceAtLeast(0)),unspecified())
        rowHeight=max(icon.measuredHeight,title.measuredHeight)
        if(!stackedAction)rowHeight=max(rowHeight,action.measuredHeight)
        val extra=if(stackedAction)dp(4)+action.measuredHeight else 0
        setMeasuredDimension(resolveSize(width,w),resolveSize(rowHeight+extra+2*dp(6),h))
    }
    override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int) {
        var start=dp(15)
        fun place(child:View,top:Int=dp(6)+(rowHeight-child.measuredHeight)/2) {
            val x=if(layoutDirection==LAYOUT_DIRECTION_RTL)width-start-child.measuredWidth else start
            child.layout(x,top,x+child.measuredWidth,top+child.measuredHeight)
        }
        place(icon);start+=icon.measuredWidth+firstGap
        place(title);start+=title.measuredWidth+secondGap
        if(stackedAction) {
            start=width-dp(15)-action.measuredWidth
            place(action,dp(6)+rowHeight+dp(4))
        }else place(action)
    }
    override fun close()=action.close()
    private fun dp(value:Int)=(value*environment.textDensity.density).roundToInt()
}
