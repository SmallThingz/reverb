package app.smallthingz.reverb.nativeui

/**
 * Source-derived plain-label substitution state, with no composition or app state.
 * Original text remains the accessibility/content-capture/request text while translation is shown.
 */
internal class ReverbTextSubstitutionState(
    initialOriginal: String,
    private val invalidateTranslation: () -> Unit,
) {
    var original: String = initialOriginal
        private set
    var substitution: String? = null
        private set
    var isShowingSubstitution: Boolean = false
        private set

    val displayed: String
        get() = if (isShowingSubstitution) substitution ?: original else original

    /** Corresponds to TextStringSimpleNode.updateText: changing the source retires its translation. */
    fun replaceOriginal(value: String): Boolean {
        if (original == value) return false
        original = value
        substitution = null
        isShowingSubstitution = false
        return true
    }

    /** Existing substitution visibility survives an updated response, as in the released node. */
    fun setSubstitution(value: String) {
        substitution = value
        // Released SetTextSubstitution always invalidates semantics/measurement/draw, even for
        // an identical response. Do not infer "same pixels" from a successful response alone.
        invalidateTranslation()
    }

    fun showSubstitution(show: Boolean): Boolean {
        if (substitution == null) return false
        isShowingSubstitution = show
        invalidateTranslation()
        return true
    }

    fun clearSubstitution() {
        substitution = null
        isShowingSubstitution = false
        invalidateTranslation()
    }
}
