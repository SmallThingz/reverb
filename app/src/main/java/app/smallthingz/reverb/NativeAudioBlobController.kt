package app.smallthingz.reverb

import smallthingz.ui.components.blob.AudioReactiveBlobView

/** Replays the last app-owned visualization frame when the stationary renderer transfers buffers. */
internal class NativeAudioBlobController {
    private var view: AudioReactiveBlobView? = null
    private var latestFrame = ReverbService.VisualizationFrame.EMPTY

    fun attach(view: AudioReactiveBlobView) {
        this.view = view
        view.present(latestFrame)
    }

    fun detach(view: AudioReactiveBlobView) {
        if (this.view === view) this.view = null
    }

    fun submit(frame: ReverbService.VisualizationFrame) {
        latestFrame = frame
        view?.present(frame)
    }

    fun clear() {
        latestFrame = ReverbService.VisualizationFrame.EMPTY
        view?.clearFrame()
    }

    fun currentBaseRadiusFraction(): Float? = view?.currentBaseRadiusFraction()
}

private fun AudioReactiveBlobView.present(frame: ReverbService.VisualizationFrame) =
    submitFrame(frame.activity, frame.bins, frame.silenceSuppressed)
