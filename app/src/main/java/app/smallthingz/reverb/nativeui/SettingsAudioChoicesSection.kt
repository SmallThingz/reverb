package app.smallthingz.reverb.nativeui

import android.content.Context
import smallthingz.ui.runtime.MonotonicFrameClock
import smallthingz.ui.text.TextStyle
import smallthingz.ui.text.font.FontFamily
import smallthingz.ui.unit.Density
import kotlinx.coroutines.CoroutineScope

/**
 * The released audio-choice presentation and its per-choice menu state. Option resolution and
 * durable Settings draft mutation remain the existing caller's typed callbacks.
 */
internal class SettingsAudioChoicesSection(
    context: Context,
    bodySmall: TextStyle,
    bodyLarge: TextStyle,
    labelLarge: TextStyle,
    density: Density,
    resolver: FontFamily.Resolver,
    initial: SettingsAudioChoicesState,
    initialPalette: ReverbPalette,
    sampleRateLabel: (Int) -> String,
    selections: SettingsAudioSelections,
    frames: MonotonicFrameClock,
    scope: CoroutineScope,
    savedMenuScrollPositions: Map<SettingsAudioChoice, Int> = emptyMap(),
    inputRoot: () -> android.view.View? = { null },
) : AutoCloseable {
    private var current = initial
    private var palette = initialPalette
    private val expanded = mutableSetOf<SettingsAudioChoice>()
    private var binding = false
    private var needsBinding = false
    private var closed = false
    private lateinit var choices: SettingsAudioChoicesView
    private val menus = SettingsAudioMenuHost(
        labelLarge,density,resolver,frames,scope,initialPalette,
        expandedChanged = { kind, value ->
            if (value) expanded.add(kind) else expanded.remove(kind)
            if (::choices.isInitialized) render()
        },
        savedScrollPositions = savedMenuScrollPositions,
    )
    val view get() = choices.view

    init {
        choices = SettingsAudioChoicesView(context,bodySmall,bodyLarge,density,resolver,
            initial.copy(expanded=emptySet()),initialPalette,sampleRateLabel,menus,selections,inputRoot)
    }

    fun bind(next: SettingsAudioChoicesState, palette: ReverbPalette) {
        if (closed) return
        current = next
        this.palette = palette
        menus.palette(palette)
        render()
    }

    private fun render() {
        if (closed) return
        if (binding) { needsBinding=true; return }
        do {
            needsBinding=false
            binding=true
            try { choices.bind(current.copy(expanded=expanded.toSet()),palette) }
            finally { binding=false }
        } while (needsBinding)
    }

    fun active(active: Boolean) {
        if (!active && !closed) menus.dismissAll()
    }

    fun saveMenuScrollPositions() = menus.saveScrollPositions()

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
