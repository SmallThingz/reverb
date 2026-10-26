package app.smallthingz.reverb.nativeui

import app.smallthingz.reverb.closePreservingPrimaryFailure

import android.view.View
import smallthingz.ui.runtime.MonotonicFrameClock
import smallthingz.ui.text.TextStyle
import smallthingz.ui.text.font.FontFamily
import smallthingz.ui.unit.Density
import kotlinx.coroutines.CoroutineScope
import java.util.EnumMap

/**
 * Per-Settings activation owner for the five released DropdownMenu calls. Their scroll positions
 * survive close/reopen, but disappear with a conditionally removed choice or the screen owner.
 * Selection invokes the current typed callback before dismissing, as in SettingsDropdown.
 */
internal class SettingsAudioMenuHost(
    private val style: TextStyle,
    private val density: Density,
    private val resolver: FontFamily.Resolver,
    private val frames: MonotonicFrameClock,
    private val scope: CoroutineScope,
    initialPalette: ReverbPalette,
    private val expandedChanged: (SettingsAudioChoice, Boolean) -> Unit,
    savedScrollPositions: Map<SettingsAudioChoice, Int> = emptyMap(),
) : SettingsAudioPopupHost, AutoCloseable {
    private class Session(
        val anchor: View,
        val content: SettingsMenuContent,
        val input: SettingsMenuPointerBinding,
        val window: SettingsMenuWindow,
    ) : AutoCloseable {
        override fun close() = window.close()
    }
    private val sessions = EnumMap<SettingsAudioChoice, Session>(SettingsAudioChoice::class.java)
    private val scrollStates = EnumMap<SettingsAudioChoice, NativeVerticalScrollState>(SettingsAudioChoice::class.java)
    private val expanded = mutableSetOf<SettingsAudioChoice>()
    private var palette = initialPalette
    private var closed = false

    init {
        savedScrollPositions.forEach { (kind, position) ->
            scrollStates[kind] = NativeVerticalScrollState(position)
        }
    }

    fun saveScrollPositions(): Map<SettingsAudioChoice, Int> =
        scrollStates.mapValues { (_, state) -> state.save() }

    fun palette(palette: ReverbPalette) { this.palette = palette }

    override fun <T> open(
        kind: SettingsAudioChoice,
        anchor: View,
        content: SettingsDropdownContent<T>,
        onSelected: (T) -> Unit,
    ) {
        check(!closed)
        val previous = sessions[kind]
        if (previous != null && previous.anchor === anchor) {
            bindSession(kind, previous, content, onSelected)
            previous.window.expanded(true)
            publishExpanded(kind, true)
            return
        }
        if (previous != null) retire(kind, previous)
        val scroll = scrollStates.getOrPut(kind) { NativeVerticalScrollState() }
        lateinit var input: SettingsMenuPointerBinding
        val menu = SettingsMenuContent(anchor.context, density, style, resolver, palette, scroll, frames, scope) {
            event -> input.interestedParent(event)
        }
        input = SettingsMenuPointerBinding(menu, density)
        lateinit var session: Session
        val window = SettingsMenuWindow(
            anchor,menu,density,frames,scope,input,
            onDismissRequest = { dismiss(kind) },
            onTerminal = {
                if (sessions[kind] === session) retire(kind, session)
            },
        )
        session = Session(anchor, menu, input, window)
        sessions[kind] = session
        try {
            bindSession(kind, session, content, onSelected)
            window.show()
            publishExpanded(kind, true)
        } catch (failure: Throwable) {
            if (sessions[kind] === session) sessions.remove(kind)
            try { session.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    /** Called with the same resolved options used to bind the visible choice row. */
    override fun <T> update(
        kind: SettingsAudioChoice,
        anchor: View,
        content: SettingsDropdownContent<T>,
        onSelected: (T) -> Unit,
    ) {
        if (closed) return
        if (content.options.size <= 1) {
            sessions[kind]?.let { retire(kind, it) }
            scrollStates.remove(kind)
            return
        }
        sessions[kind]?.let {
            if (it.anchor !== anchor) retire(kind, it)
            else bindSession(kind,it,content,onSelected)
        }
    }

    private fun <T> bindSession(
        kind: SettingsAudioChoice,
        session: Session,
        content: SettingsDropdownContent<T>,
        select: (T) -> Unit,
    ) {
        session.content.bind(content,palette) { value ->
            select(value)
            // A callback that throws deliberately does not run the following source statement.
            if (sessions[kind] === session) dismiss(kind)
        }
        session.input.refreshRows()
    }

    /** The panel's released active=false effect closes menus, preserving each retained scroll. */
    fun dismissAll() {
        var failure: Throwable? = null
        for (kind in sessions.keys.toList()) {
            failure = closePreservingPrimaryFailure(failure) { dismiss(kind) }
        }
        failure?.let { throw it }
    }

    private fun dismiss(kind: SettingsAudioChoice) {
        val session = sessions[kind] ?: return
        var failure = closePreservingPrimaryFailure(null) { publishExpanded(kind,false) }
        failure = closePreservingPrimaryFailure(failure) { session.window.expanded(false) }
        failure?.let { throw it }
    }

    private fun retire(kind: SettingsAudioChoice, session: Session) {
        if (sessions[kind] === session) sessions.remove(kind)
        var failure = closePreservingPrimaryFailure(null) { publishExpanded(kind,false) }
        failure = closePreservingPrimaryFailure(failure) { session.close() }
        failure?.let { throw it }
    }

    private fun publishExpanded(kind: SettingsAudioChoice, value: Boolean) {
        val changed = if (value) expanded.add(kind) else expanded.remove(kind)
        if (changed) expandedChanged(kind,value)
    }

    override fun close() {
        if (closed) return
        closed = true
        val retiring = sessions.values.toList()
        sessions.clear()
        scrollStates.clear()
        expanded.clear()
        var failure: Throwable? = null
        for (session in retiring) {
            try { session.close() } catch (problem: Throwable) {
                if (failure == null) failure=problem else failure.addSuppressed(problem)
            }
        }
        failure?.let { throw it }
    }
}
