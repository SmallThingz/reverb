package app.smallthingz.reverb.nativeui

import android.content.Context
import smallthingz.ui.runtime.MonotonicFrameClock
import kotlinx.coroutines.CoroutineScope

/**
 * The released audio-choice presentation and its per-choice menu state. Option resolution and
 * durable Settings draft mutation remain the existing caller's typed callbacks.
 */
internal class SettingsAudioChoicesSection(
    context: Context,
    initialEnvironment: ReverbViewEnvironment,
    initial: SettingsAudioChoicesState,
    sampleRateLabel: (Int) -> String,
    selections: SettingsAudioSelections,
    frames: MonotonicFrameClock,
    scope: CoroutineScope,
    savedMenuScrollPositions: Map<SettingsAudioChoice, Int> = emptyMap(),
    inputRoot: () -> android.view.View? = { null },
) : AutoCloseable {
    private var current = initial
    private var environment = initialEnvironment
    private val expanded = mutableSetOf<SettingsAudioChoice>()
    private var binding = false
    private var needsBinding = false
    private var closed = false
    private lateinit var choices: SettingsAudioChoicesView
    private val menus = SettingsAudioMenuHost(
        ReverbParagraphStyles[ReverbTextRole.LABEL_LARGE], initialEnvironment.textDensity,
        initialEnvironment.sharedFontResolver, frames, scope, initialEnvironment.palette,
        expandedChanged = { kind, value ->
            if (value) expanded.add(kind) else expanded.remove(kind)
            if (::choices.isInitialized) render()
        },
        savedScrollPositions = savedMenuScrollPositions,
    )
    val view get() = choices.view

    init {
        choices = SettingsAudioChoicesView(context,initialEnvironment,
            initial.copy(expanded=emptySet()),sampleRateLabel,menus,selections,inputRoot)
    }

    fun bind(next: SettingsAudioChoicesState, environment: ReverbViewEnvironment) {
        if (closed) return
        current = next
        this.environment = environment
        menus.bindConfiguration(ReverbParagraphStyles[ReverbTextRole.LABEL_LARGE], environment.textDensity,
            environment.sharedFontResolver, environment.palette)
        render()
    }

    private fun render() {
        if (closed) return
        if (binding) { needsBinding=true; return }
        do {
            needsBinding=false
            binding=true
            try { choices.bind(current.copy(expanded=expanded.toSet()),environment) }
            finally { binding=false }
        } while (needsBinding)
    }

    fun active(active: Boolean) {
        if (!active && !closed) menus.dismissAll()
    }

    override fun close() {
        if (closed) return
        closed=true
        var failure: Throwable?=null
        try { menus.close() } catch (problem: Throwable) { failure=problem }
        try { choices.close() } catch (problem: Throwable) {
            if (failure == null) failure=problem else failure.addSuppressed(problem)
        }
        failure?.let { throw it }
    }
}
