package app.smallthingz.reverb

/**
 * One Activity Result launcher has one outstanding request. Retiring an activation keeps that
 * request occupied until its callback returns, so an old result cannot become a newer selection.
 * Only already-validated, permission-retained canonical tree URIs enter pendingTreeUri.
 */
internal class SettingsDirectoryPickerState private constructor(
    private var panelActive: Boolean,
    private var awaitingResult: Boolean,
    private var retired: Boolean,
    private var pendingTreeUri: String?,
) {
    constructor(panelActive: Boolean) : this(panelActive, false, false, null)

    val acceptsResult: Boolean get() = panelActive && awaitingResult && !retired

    fun setPanelActive(active: Boolean) {
        panelActive = active
        if (!active) retire()
    }

    fun beginRequest(): Boolean {
        if (!panelActive || awaitingResult || pendingTreeUri != null) return false
        awaitingResult = true
        retired = false
        return true
    }

    /** A newer explicit directory edit or a closed panel supersedes this selection. */
    fun retire() {
        pendingTreeUri = null
        retired = awaitingResult
    }

    fun receiveResult(canonicalTreeUri: String?) {
        if (!awaitingResult) return
        val accepted = acceptsResult
        awaitingResult = false
        retired = false
        pendingTreeUri = canonicalTreeUri.takeIf { accepted }
    }

    fun takePendingResult(hydrated: Boolean): String? {
        if (!panelActive || !hydrated) return null
        val result = pendingTreeUri
        pendingTreeUri = null
        return result
    }

    // Save alongside rememberLauncherForActivityResult's saveable registry key. This is UI
    // transaction state, not a durable setting; recreation still requires a fresh baseline read.
    fun save(): List<Any> = listOf(panelActive, awaitingResult, retired, pendingTreeUri.orEmpty())

    companion object {
        fun restore(saved: List<Any>): SettingsDirectoryPickerState =
            SettingsDirectoryPickerState(
                panelActive = saved[0] as Boolean,
                awaitingResult = saved[1] as Boolean,
                retired = saved[2] as Boolean,
                pendingTreeUri = (saved[3] as String).takeIf { it.isNotEmpty() },
            )
    }
}
