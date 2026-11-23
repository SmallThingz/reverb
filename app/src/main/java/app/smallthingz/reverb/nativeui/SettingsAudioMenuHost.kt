package app.smallthingz.reverb.nativeui

import android.view.View
import android.widget.FrameLayout
import smallthingz.ui.components.NativeAnchoredDropdown
import smallthingz.ui.util.closePreservingPrimaryFailure

/** Owns the one expanded audio choice and forwards wheel selections to the Settings draft. */
internal class SettingsAudioMenuHost(
    initialEnvironment: ReverbViewEnvironment,
    private val overlayHost: () -> FrameLayout?,
    private val inputAllowed: () -> Boolean,
    private val expandedChanged: (SettingsAudioChoice, Boolean) -> Unit,
) : SettingsAudioPopupHost, AutoCloseable {
    private class Session(
        val kind: SettingsAudioChoice,
        val anchor: View,
        val dropdown: NativeAnchoredDropdown,
        val label: String,
        val options: List<*>,
        val optionLabels: List<String>,
        var selected: Any?,
        var selectAt: (Int) -> Unit,
    ) {
        val layoutDirection = anchor.layoutDirection
        var expectedSelection: Any? = selected
        var expanded = false
    }

    private var environment = initialEnvironment
    private var current: Session? = null
    private var closed = false

    fun bindConfiguration(next: ReverbViewEnvironment) {
        if (closed) return
        val previous = environment
        environment = next
        if (previous.appearance !== next.appearance || previous.palette != next.palette ||
            previous.textDensity !== next.textDensity || previous.sharedFontResolver !== next.sharedFontResolver) {
            dismissAll()
        }
    }

    override fun <T> open(kind: SettingsAudioChoice, anchor: View, content: SettingsDropdownContent<T>,
        onSelected: (T) -> Unit) {
        if (closed || !inputAllowed()) return
        val host = overlayHost() ?: return
        if (!host.isAttachedToWindow || host.windowToken != anchor.windowToken) return
        if (content.options.size <= 1 || content.selected !in content.options) return
        dismissAll()
        if (closed || current != null || !inputAllowed()) return

        val options = content.options.toList()
        val labels = options.map(content.optionLabel)
        val config = environment
        val dropdown = NativeAnchoredDropdown(anchor.context, host, config.appearance, config.palette,
            config.textDensity, config.sharedFontResolver, palette = config.palette.dropdownColors())
        val session = Session(kind, anchor, dropdown, content.label, options, labels, content.selected,
            selectAt = { onSelected(options[it]) })
        current = session
        try {
            val mounted = dropdown.open(anchor, content.copy(options = options), { terminal(session) }) select@{ value ->
                if (current !== session) return@select
                if (!inputAllowed()) {
                    dismissAll()
                    return@select
                }
                session.expectedSelection = value
                session.selectAt(options.indexOf(value))
            }
            if (mounted && current === session) {
                session.expanded = true
                expandedChanged(kind, true)
            } else terminal(session)
        } catch (failure: Throwable) {
            if (current === session) closePreservingPrimaryFailure(failure, ::dismissAll)
            throw failure
        }
    }

    override fun <T> update(kind: SettingsAudioChoice, anchor: View, content: SettingsDropdownContent<T>,
        onSelected: (T) -> Unit) {
        val session = current?.takeIf { it.kind == kind } ?: return
        if (!inputAllowed() || session.anchor !== anchor || session.layoutDirection != anchor.layoutDirection ||
            session.label != content.label || session.options != content.options ||
            session.optionLabels != content.options.map(content.optionLabel) ||
            (session.selected != content.selected && session.expectedSelection != content.selected)) {
            dismissAll()
            return
        }
        // A wheel edit is echoed by the Settings draft while the gesture still owns the slot.
        // Rebind the typed callback through the unchanged option order.
        session.selected = content.selected
        val options = content.options.toList()
        session.selectAt = { onSelected(options[it]) }
    }

    fun dismissAll() {
        val session = current ?: return
        var failure = closePreservingPrimaryFailure(null, session.dropdown::close)
        failure = closePreservingPrimaryFailure(failure) { terminal(session) }
        failure?.let { throw it }
    }

    private fun terminal(session: Session) {
        if (current !== session) return
        current = null
        if (session.expanded) expandedChanged(session.kind, false)
    }

    override fun close() {
        if (closed) return
        closed = true
        dismissAll()
    }
}
