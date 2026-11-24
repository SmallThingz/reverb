package app.smallthingz.reverb.nativeui

import android.content.Context
import android.view.View
import smallthingz.ui.reverb.ReverbScaffold
import app.smallthingz.reverb.NativeSourceFocusChildOrder

/** The released Home Scaffold itself; its direct body slot is Capture or the permission branch. */
internal open class ReverbHomeScaffoldView private constructor(
    context: Context,
    initialEnvironment: ReverbViewEnvironment,
    initialNavigation: ReverbAppTopBarActions,
    initialHasIncidents: Boolean,
    initialInsets: ReverbHomeInsets,
    private val header: ReverbAppTopBarView,
) : ReverbScaffold(context, header, initialEnvironment.palette, initialInsets), NativeSourceFocusChildOrder {
    constructor(context: Context, initialEnvironment: ReverbViewEnvironment, initialNavigation: ReverbAppTopBarActions,
                initialHasIncidents: Boolean, initialInsets: ReverbHomeInsets, nativeInputRoot: View) :
        this(context, initialEnvironment, initialNavigation, initialHasIncidents, initialInsets,
            ReverbAppTopBarView(context, initialEnvironment, nativeInputRoot))

    private var environment = initialEnvironment
    private var insets = initialInsets
    private var navigation = initialNavigation
    private var hasIncidents = initialHasIncidents
    val topBar: ReverbAppTopBarView get() = header

    init {
        header.setActionInsets(insets.contentLeft, insets.contentRight)
        header.bind(environment, initialHasIncidents, initialNavigation, statusInsetPx = insets.statusTop)
    }

    override fun sourceFocusChildAt(index: Int): View = scaffoldFocusChildAt(index)

    fun bindShell(navigation: ReverbAppTopBarActions, hasIncidents: Boolean, nextInsets: ReverbHomeInsets) {
        if (insets != nextInsets) {
            insets = nextInsets
            header.setActionInsets(insets.contentLeft, insets.contentRight)
            setScaffoldInsets(nextInsets)
        }
        this.navigation = navigation
        this.hasIncidents = hasIncidents
        header.bind(environment, hasIncidents, navigation, statusInsetPx = insets.statusTop)
    }

    open fun setEnvironment(value: ReverbViewEnvironment) {
        environment = value
        bindScaffoldPalette(value.palette)
        header.bind(value, hasIncidents, navigation, statusInsetPx = insets.statusTop)
        requestLayout()
    }
}
