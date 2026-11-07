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
    private val allFilesStatus=environment.paragraph(context,"",ReverbTextRole.BODY_LARGE,environment.palette.onSurface)
    private val allFilesBody=environment.paragraph(context,context.getString(R.string.all_files_access_body),
        ReverbTextRole.BODY_MEDIUM,environment.palette.muted)
    private val allFilesAction=SettingsStorageActionView(context,environment,null,
        context.getString(R.string.all_files_access_settings),inputRoot,onAllFilesAccess)
    private val audioReadStatus=environment.paragraph(context,"",ReverbTextRole.BODY_LARGE,environment.palette.onSurface)
    private val audioReadBody=environment.paragraph(context,context.getString(R.string.audio_read_access_body),
        ReverbTextRole.BODY_MEDIUM,environment.palette.muted)
    private val audioReadAction=SettingsStorageActionView(context,environment,null,
        context.getString(R.string.audio_read_access_settings),inputRoot,onAudioReadAccess)
    private val body=StorageBody(context)
    init {
        importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO
        body.addView(path);body.addView(reset);body.addView(choose);body.addView(move)
        body.addView(allFilesStatus);body.addView(allFilesBody);body.addView(allFilesAction)
        body.addView(audioReadStatus);body.addView(audioReadBody);body.addView(audioReadAction)
        surface.addRow(body,ReverbGroupLayout.LayoutParams(-1,-2))
        addView(title);addView(surface);bind(initial,environment)
    }
    fun bind(next:SettingsStorageState,colors:ReverbViewEnvironment) {
        state=next;environment=colors
        bindSectionTitle(title,colors)
        path.setText(next.pathText);colors.bindParagraphStyle(path,ReverbTextRole.BODY_LARGE,colors.palette.muted)
        surface.setPalette(colors.palette.field,colors.palette.border)
        reset.visibility=if(next.hasCustomDirectory)VISIBLE else GONE
        reset.bind(colors,true);choose.bind(colors,true);move.bind(colors,next.canMove&&!next.settingsPersisting)
        val accessVisibility=if(next.allFilesAvailable)VISIBLE else GONE
        allFilesStatus.visibility=accessVisibility;allFilesBody.visibility=accessVisibility;allFilesAction.visibility=accessVisibility
        allFilesStatus.setText(context.getString(if(next.allFilesAllowed)R.string.all_files_access_allowed else R.string.all_files_access_not_allowed))
        colors.bindParagraphStyle(allFilesStatus,ReverbTextRole.BODY_LARGE,colors.palette.onSurface)
        colors.bindParagraphStyle(allFilesBody,ReverbTextRole.BODY_MEDIUM,colors.palette.muted)
        allFilesAction.bind(colors,!next.settingsPersisting)
        val audioVisibility=if(next.audioReadAvailable)VISIBLE else GONE
        audioReadStatus.visibility=audioVisibility;audioReadBody.visibility=audioVisibility;audioReadAction.visibility=audioVisibility
        audioReadStatus.setText(context.getString(if(next.audioReadAllowed)R.string.audio_read_access_allowed else R.string.audio_read_access_not_allowed))
        colors.bindParagraphStyle(audioReadStatus,ReverbTextRole.BODY_LARGE,colors.palette.onSurface)
        colors.bindParagraphStyle(audioReadBody,ReverbTextRole.BODY_MEDIUM,colors.palette.muted)
        audioReadAction.bind(colors,!next.settingsPersisting)
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
    override fun close() {reset.close();choose.close();move.close();allFilesAction.close();audioReadAction.close()}
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
            val accessHeight=if(state.allFilesAvailable) {
                allFilesStatus.measure(exact(inner),unspecified())
                allFilesBody.measure(exact(inner),unspecified())
                allFilesAction.measure(atMost(inner),unspecified())
                dp(12)+allFilesStatus.measuredHeight+dp(4)+allFilesBody.measuredHeight+dp(4)+allFilesAction.measuredHeight
            } else 0
            val audioHeight=if(state.audioReadAvailable) {
                audioReadStatus.measure(exact(inner),unspecified())
                audioReadBody.measure(exact(inner),unspecified())
                audioReadAction.measure(atMost(inner),unspecified())
                dp(12)+audioReadStatus.measuredHeight+dp(4)+audioReadBody.measuredHeight+dp(4)+audioReadAction.measuredHeight
            } else 0
            setMeasuredDimension(width,2*dp(10)+rowHeight+move.measuredHeight+accessHeight+audioHeight)
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
            var top=dp(10)+rowHeight+move.measuredHeight
            if(state.allFilesAvailable) {
                top+=dp(12)
                place(allFilesStatus,dp(16),top);top+=allFilesStatus.measuredHeight+dp(4)
                place(allFilesBody,dp(16),top);top+=allFilesBody.measuredHeight+dp(4)
                place(allFilesAction,dp(16),top);top+=allFilesAction.measuredHeight
            }
            if(state.audioReadAvailable) {
                top+=dp(12)
                place(audioReadStatus,dp(16),top);top+=audioReadStatus.measuredHeight+dp(4)
                place(audioReadBody,dp(16),top);top+=audioReadBody.measuredHeight+dp(4)
                place(audioReadAction,dp(16),top)
            }
        }
    }
}
