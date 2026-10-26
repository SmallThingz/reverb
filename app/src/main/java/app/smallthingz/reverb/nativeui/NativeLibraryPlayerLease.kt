package app.smallthingz.reverb.nativeui

import android.content.Context
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import androidx.activity.OnBackPressedDispatcher
import smallthingz.ui.runtime.MonotonicFrameClock
import androidx.lifecycle.LifecycleOwner
import app.smallthingz.reverb.InlinePlaybackRowOwner
import app.smallthingz.reverb.NativeInlinePlayerCallbacks
import app.smallthingz.reverb.NativeInlinePlayerInput
import app.smallthingz.reverb.NativeInlinePlayerPresentation
import app.smallthingz.reverb.NativeLibraryController
import app.smallthingz.reverb.NativeRecordingInlinePlayerController
import app.smallthingz.reverb.NativeViewPhasePointerNode
import app.smallthingz.reverb.RecordingEntity
import kotlinx.coroutines.CoroutineScope

/**
 * Concrete native row lease. Only the row and the controller own resources; this does not create a
 * second media backend or mutation owner. Back registration and phase routing belong to the window.
 */
internal class NativeLibraryPlayerLease(
    context: Context,
    private val recordingId: String,
    initialEnvironment: ReverbViewEnvironment,
    private val library: NativeLibraryController,
    private val lifecycleOwner: LifecycleOwner,
    private val backDispatcher: OnBackPressedDispatcher,
    private val scope: CoroutineScope,
    private val frames: MonotonicFrameClock,
    private val durationScale: () -> Float,
    inputRoot: View,
    private val changedNodes: () -> Unit,
) : LibraryInlineExpansionLease {
    private var environment = initialEnvironment
    private val container = FrameLayout(context).apply {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        clipChildren = false
        clipToPadding = false
    }
    override val view: View get() = container
    private val fine = ReverbSpringFineSeekView(context, SpringFineSeekEnvironment(
        initialEnvironment.palette, initialEnvironment.textDensity,
        ViewConfiguration.get(context).scaledTouchSlop.toFloat(), frames, scope, inputRoot, { true }))
    private val player = ReverbLibraryPlayerView(context, initialEnvironment, fine, inputRoot, frames, scope, durationScale)
    private val waveformNodes = listOf(player.waveformInput)
    private val fineSeekNodes = listOf(fine.fineSeekNode)
    private val puckNodes = listOf(fine.puckClickNode)
    private var controller: NativeRecordingInlinePlayerController? = null
    private var presentation: NativeInlinePlayerPresentation? = null
    private var closed = false
    private var attached = false
    private var input: NativeInlinePlayerInput? = null
    private var back: NativeLibraryPredictiveBack? = null
    private var backOwner: InlinePlaybackRowOwner? = null
    private var backKeyInitialized = false
    private val motion = NativeInlineWaveformMotion(frames, scope, durationScale, player::setWaveformFrame)

    /** The window updates the expansion-keyed predictive registration from these source values. */
    val playbackOwner: InlinePlaybackRowOwner? get() = input?.playbackOwner
    val screenActive: Boolean get() = input?.screenActive == true
    val currentPresentation: NativeInlinePlayerPresentation? get() = presentation

    init {
        container.addView(player, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
    }

    override fun bind(recording: RecordingEntity, owner: InlinePlaybackRowOwner?, expanded: Boolean,
                      screenActive: Boolean, trimRequested: Boolean) {
        if (closed) return
        require(recording.id == recordingId)
        val callbacks = NativeInlinePlayerCallbacks(
            onTrimRequestConsumed = { library.trimRequestConsumed(recordingId) },
            onTrimSaved = { library.refresh(showSpinner = false) },
            onTrimStateUncertain = { library.refresh(showSpinner = false) },
            onWaveformCached = library::waveformCached,
            onCollapse = library::collapse,
            // Source callback captures this bound revision; accepted work never redirects its target.
            onPlaybackFailed = { library.playbackFailed(recording) },
            onBusyChange = { library.localBusy(recordingId, it) })
        val next = NativeInlinePlayerInput(recording, trimRequested,
            screenActive && expanded && attached, owner,
            library::isCurrentPlaybackOwner, callbacks)
        input = next
        controller = bindAttachedLibraryPlayer(controller, attached,
            create = {
                NativeRecordingInlinePlayerController(container.context, container,
                    lifecycleOwner, scope, next, ::publish)
            },
            bindExisting = { it.bind(next) })
        if (!backKeyInitialized || backOwner !== owner) {
            backKeyInitialized = true
            backOwner = owner
            back?.close()
            back = NativeLibraryPredictiveBack(backDispatcher, lifecycleOwner, scope, frames, ::backFrame)
        }
        back?.bind(next.screenActive && owner != null) { finishBack(owner) }
        changedNodes()
    }

    private fun publish(next: NativeInlinePlayerPresentation) {
        if (closed) return
        val previous = presentation
        presentation = next
        player.bind(environment, next.state, next.actions, next.fineSeek)
        player.setWaveform(next.waveform)
        motion.bind(next.waveform, next.waveformLoading, next.waveformMorphStarted)
        if (previous == null || previous.fineSeek.enabled != next.fineSeek.enabled ||
            previous.state.trimMode != next.state.trimMode ||
            (previous.state.trimError == null) != (next.state.trimError == null)) changedNodes()
    }

    override fun setEnvironment(environment: ReverbViewEnvironment) {
        this.environment = environment
        presentation?.let { player.bind(environment, it.state, it.actions, it.fineSeek) }
    }
    override fun setPresentationAttached(attached: Boolean) {
        this.attached = attached
        // The list immediately follows with bind(screenActive && attached). Do not invent another
        // visibility signal or retire the accepted trim receiver on a transient native detach.
    }
    fun backFrame(active: Boolean, progress: Float, direction: Float) {
        controller?.backFrame(active, progress, direction)
    }
    fun finishBack(ownerAtRegistration: InlinePlaybackRowOwner?) = controller?.finishBack(ownerAtRegistration)

    /** Exact leaves: source fine parent disappears when disabled; its disabled puck remains. */
    fun pointerNodes(view: View): List<NativeViewPhasePointerNode> = when {
        view === player.waveformInput.view -> waveformNodes
        view === fine -> if (fine.fineSeekInputEnabled) fineSeekNodes else emptyList()
        view === fine.puckClickView -> puckNodes
        else -> emptyList()
    }
    override fun closePresentation() {
        if (closed) return
        closed = true
        back?.close()
        back = null
        controller?.close()
        controller = null
        motion.close()
        player.release()
        presentation = null
        input = null
        changedNodes()
    }
}
