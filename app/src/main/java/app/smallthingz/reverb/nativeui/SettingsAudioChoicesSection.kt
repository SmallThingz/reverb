package app.smallthingz.reverb.nativeui

import android.content.Context
import android.view.View
import android.widget.FrameLayout

/**
 * The audio-choice presentation and its anchored dropdown. Option resolution and
 * durable Settings draft mutation remain the existing caller's typed callbacks.
 */
internal class SettingsAudioChoicesSection(
    context: Context,
    initialEnvironment: ReverbViewEnvironment,
    initial: SettingsAudioChoicesState,
    sampleRateLabel: (Int) -> String,
    selections: SettingsAudioSelections,
    inputRoot: () -> View?,
    inputAllowed: () -> Boolean,
) : AutoCloseable {
    private var current = initial
    private var environment = initialEnvironment
    private val expanded = mutableSetOf<SettingsAudioChoice>()
    private var binding = false
    private var needsBinding = false
    private var closed = false
    private lateinit var choices: SettingsAudioChoicesView
    private val menus = SettingsAudioMenuHost(
        initialEnvironment,
        overlayHost = { inputRoot()?.rootView?.findViewById<View>(android.R.id.content) as? FrameLayout },
        inputAllowed = inputAllowed,
        expandedChanged = { kind, value ->
            if (value) expanded.add(kind) else expanded.remove(kind)
            if (::choices.isInitialized) render()
        },
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
        menus.bindConfiguration(environment)
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

    fun dismissDropdown() = menus.dismissAll()

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
