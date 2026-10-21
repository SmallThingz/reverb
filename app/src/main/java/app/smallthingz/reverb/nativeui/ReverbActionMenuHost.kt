package app.smallthingz.reverb.nativeui

import android.view.View
import androidx.compose.runtime.MonotonicFrameClock
import kotlinx.coroutines.CoroutineScope
import java.util.IdentityHashMap

/**
 * Concrete owner for the released leading-icon DropdownMenus. It reuses the Settings popup's
 * source window/placement/transition/scroll/input implementation; no platform PopupMenu is used.
 * An identity is one source menuCreated lifetime, not a recording ID or callback identity.
 */
internal class ReverbActionMenuHost(
    private var environment: ReverbViewEnvironment,
    private val frames: MonotonicFrameClock,
    private val scope: CoroutineScope,
) : AutoCloseable {
    private class Entry(val scroll: NativeVerticalScrollState = NativeVerticalScrollState()) {
        var session: Session? = null
    }
    private class Session(
        val anchor: View,
        val content: SettingsMenuContent,
        val input: SettingsMenuPointerBinding,
        val window: SettingsMenuWindow,
    ) : AutoCloseable {
        var onDismissRequest: () -> Unit = {}
        override fun close() = window.close()
    }
    private val entries = IdentityHashMap<Any, Entry>()
    private var closed = false

    fun bindPalette(palette: ReverbPalette) {
        environment = ReverbViewEnvironment(environment.textDensity, environment.sharedFontResolver, palette)
    }

    fun show(identity: Any, anchor: View, actions: List<ReverbMenuAction>, background: Int,
             onDismissRequest: () -> Unit = {}) {
        if (closed) return
        val entry = entries.getOrPut(identity) { Entry() }
        val prior = entry.session
        if (prior != null && prior.anchor === anchor) {
            prior.onDismissRequest = onDismissRequest
            bind(identity, entry, prior, actions, background)
            prior.window.expanded(true)
            return
        }
        if (prior != null) retire(entry, prior)
        lateinit var input: SettingsMenuPointerBinding
        val content = SettingsMenuContent(anchor.context, environment.textDensity,
            ReverbParagraphStyles[ReverbTextRole.LABEL_LARGE], environment.sharedFontResolver,
            environment.palette, entry.scroll, frames, scope) { event -> input.interestedParent(event) }
        input = SettingsMenuPointerBinding(content, environment.textDensity)
        lateinit var session: Session
        val window = SettingsMenuWindow(anchor, content, environment.textDensity, frames, scope, input,
            onDismissRequest = {
                if (entries[identity] === entry && entry.session === session) {
                    session.onDismissRequest()
                    if (entries[identity] === entry && entry.session === session) session.window.expanded(false)
                }
            },
            onTerminal = { if (entry.session === session) retire(entry, session) })
        session = Session(anchor, content, input, window)
        session.onDismissRequest = onDismissRequest
        entry.session = session
        try {
            bind(identity, entry, session, actions, background)
            window.show()
        } catch (failure: Throwable) {
            if (entry.session === session) entry.session = null
            try { session.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    fun update(identity: Any, anchor: View, actions: List<ReverbMenuAction>, background: Int,
               onDismissRequest: () -> Unit = {}) {
        if (closed) return
        val entry = entries[identity] ?: return
        val session = entry.session ?: return
        session.onDismissRequest = onDismissRequest
        if (session.anchor !== anchor) retire(entry, session)
        else bind(identity, entry, session, actions, background)
    }

    private fun bind(identity: Any, entry: Entry, session: Session,
                     actions: List<ReverbMenuAction>, background: Int) {
        session.content.bindActions(actions.map { action ->
            action.copy(onClick = {
                if (!closed && entries[identity] === entry && entry.session === session) {
                    // Both source Incidents and Library assign expanded=false BEFORE the action.
                    session.window.expanded(false)
                    action.onClick()
                }
            })
        }, environment.palette, background)
        session.input.refreshRows()
    }

    /** Source expanded=false keeps ScrollState/menuCreated until its row is actually disposed. */
    fun hide(identity: Any) { entries[identity]?.session?.window?.expanded(false) }

    fun release(identity: Any) {
        val entry = entries.remove(identity) ?: return
        entry.session?.let { retire(entry, it) }
    }

    private fun retire(entry: Entry, session: Session) {
        if (entry.session === session) entry.session = null
        session.close()
    }

    override fun close() {
        if (closed) return
        closed = true
        val sessions = entries.values.mapNotNull { it.session }
        entries.clear()
        var failure: Throwable? = null
        for (session in sessions) {
            try { session.close() } catch (problem: Throwable) {
                if (failure == null) failure = problem else failure.addSuppressed(problem)
            }
        }
        failure?.let { throw it }
    }
}
