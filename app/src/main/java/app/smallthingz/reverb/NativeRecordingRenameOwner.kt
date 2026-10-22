package app.smallthingz.reverb

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

internal data class NativeRecordingRenameState(val name:String,val error:String?,val isRenaming:Boolean)
internal data class NativeRecordingRenameCallbacks(
    val onDismiss:()->Unit,
    val onRenamed:(RecordingEntity)->Unit,
    val onStateUncertain:()->Unit,
)

/**
 * Mechanical RenameRecordingDialog operation/draft owner. The source editable remains enabled
 * while a rename is accepted; only Cancel/confirm and the sheet's dismiss callback are gated.
 * Accepted work reuses runCommittedRecordingRename and RenameResultReceiver without new checks.
 */
internal class NativeRecordingRenameOwner(
    context:Context,
    initialRecording:RecordingEntity,
    initialActive:Boolean,
    private val lifecycleOwner:LifecycleOwner,
    parentScope:CoroutineScope,
    initialCallbacks:NativeRecordingRenameCallbacks,
    private val publish:(NativeRecordingRenameState)->Unit,
):AutoCloseable {
    private val appContext=context.applicationContext
    private val resources=context.resources
    private val scope=CoroutineScope(parentScope.coroutineContext+Job(parentScope.coroutineContext[Job]))
    private val main=Handler(Looper.getMainLooper())
    private var recording=initialRecording
    private var active=initialActive
    private var callbacks=initialCallbacks
    private val activeReceiver=AtomicReference<RenameResultReceiver?>(null)
    private var closed=false
    private var posted=false
    private val delivery=Runnable{posted=false;if(!closed)publish(state)}
    var state=NativeRecordingRenameState(baseName(initialRecording.displayName),null,false)
        private set

    fun bind(recording:RecordingEntity,active:Boolean,callbacks:NativeRecordingRenameCallbacks) {
        if(closed)return
        require(this.recording.id==recording.id){"A new recording ID requires a new source rename lifetime"}
        if(this.recording.displayName!=recording.displayName)state=state.copy(name=baseName(recording.displayName))
        this.recording=recording;this.active=active;this.callbacks=callbacks
        changed()
    }
    fun editName(value:String) {
        if(closed)return
        state=state.copy(name=value,error=null)
        changed()
    }
    fun requestDismiss() {if(!closed&&!state.isRenaming)callbacks.onDismiss()}
    fun submit() {
        if(closed||state.isRenaming)return
        val trimmed=state.name.trim()
        if(trimmed.isBlank()) {
            state=state.copy(error=resources.getString(R.string.rename_recording_invalid));changed();return
        }
        if(hasIllegalRecordingNameCharacters(trimmed)) {
            state=state.copy(error=resources.getString(R.string.rename_recording_illegal_chars));changed();return
        }
        state=state.copy(isRenaming=true);changed()
        // Source callbacks/recording are captured by this invocation. Only active visibility is live.
        val target=recording
        val acceptedCallbacks=callbacks
        // Accepted work may outlive this sheet; its fallback and bookkeeping must not retain it.
        val renameContext=appContext
        val receiverSlot=activeReceiver
        val accepted=RenameResultReceiver(
            onRenamed={renamed->state=state.copy(isRenaming=false);changed();acceptedCallbacks.onRenamed(renamed)},
            onRejected={state=state.copy(isRenaming=false,error=resources.getString(R.string.rename_recording_failed));changed()},
            onUncertain={state=state.copy(isRenaming=false);changed();acceptedCallbacks.onStateUncertain()},
            onHiddenTerminal={state=state.copy(isRenaming=false);changed();acceptedCallbacks.onDismiss()},
            isUiVisible={active&&lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)},
            onDetachedSuccess={renamed->AppFeedbackCenter.post(
                "${renameContext.getString(R.string.rename_recording)}: ${renamed.displayName}",FeedbackTone.SUCCESS)},
            onDetachedFailure={AppFeedbackCenter.post(renameContext.getString(R.string.rename_recording_failed),FeedbackTone.ERROR)},
            onTerminal={completed->receiverSlot.compareAndSet(completed,null)},
        )
        receiverSlot.set(accepted)
        scope.launch(start=CoroutineStart.UNDISPATCHED) {
            runCommittedRecordingRename(
                rename={RecordingRepository.rename(renameContext,target,trimmed)},
                onTerminal=accepted::terminal,
            )
        }
    }
    private fun changed(){if(!closed&&!posted){posted=true;main.post(delivery)}}
    override fun close() {
        if(closed)return
        closed=true
        activeReceiver.getAndSet(null)?.detachUi()
        // Already entered NonCancellable work retains its captured target and terminal feedback.
        scope.cancel()
        main.removeCallbacks(delivery);posted=false
    }
    private fun baseName(displayName:String):String {
        val base=displayName.substringBeforeLast('.',displayName)
        return if(base.isEmpty())displayName else base
    }
}
