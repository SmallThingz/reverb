package app.smallthingz.reverb.nativeui

import android.os.Looper
import android.view.View
import android.view.translation.TranslationRequestValue
import android.view.translation.ViewTranslationCallback
import android.view.translation.ViewTranslationRequest
import android.view.translation.ViewTranslationResponse
import androidx.annotation.RequiresApi
import java.util.function.Consumer

/**
 * Construct under SDK >=31; the owner delegates its two public translation overrides.
 * The owner must keep distinct original/display paragraph caches to preserve the released text semantics.
 * This adapter never calls setText with translated text, enables services, or changes permissions.
 */
@RequiresApi(31)
internal class ReverbTextTranslationApi31(
    private val view: View,
    private val state: ReverbTextSubstitutionState,
) : ViewTranslationCallback {
    init {
        checkMainThread()
        view.setViewTranslationCallback(this)
    }

    fun createRequest(requestsCollector: Consumer<ViewTranslationRequest>) {
        checkMainThread()
        requestsCollector.accept(
            ViewTranslationRequest.Builder(view.autofillId)
                .setValue(ViewTranslationRequest.ID_TEXT, TranslationRequestValue.forText(state.original))
                .build(),
        )
    }

    /** Call after super.onViewTranslationResponse(response), which retains the framework response. */
    fun receiveResponse(response: ViewTranslationResponse) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            view.post { receiveResponse(response) }
            return
        }
        // Android's getValue throws when a key is absent. A response without text is a no-op.
        if (ViewTranslationRequest.ID_TEXT !in response.keys) return
        val translated = response.getValue(ViewTranslationRequest.ID_TEXT).text ?: return
        state.setSubstitution(translated.toString())
    }

    override fun onShowTranslation(view: View): Boolean {
        check(view === this.view)
        checkMainThread()
        if (state.substitution != null && !state.isShowingSubstitution) state.showSubstitution(true)
        return true
    }

    override fun onHideTranslation(view: View): Boolean {
        check(view === this.view)
        checkMainThread()
        if (state.isShowingSubstitution) state.showSubstitution(false)
        return true
    }

    override fun onClearTranslation(view: View): Boolean {
        check(view === this.view)
        checkMainThread()
        if (state.substitution != null) state.clearSubstitution()
        return true
    }

    private fun checkMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper())
    }
}
