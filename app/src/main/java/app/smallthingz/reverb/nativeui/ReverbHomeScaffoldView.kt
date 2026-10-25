package app.smallthingz.reverb.nativeui

import android.content.Context
import android.os.Build
import android.view.View
import android.view.ViewGroup
import app.smallthingz.reverb.NativeSourceFocusChildOrder

/** The released Home Scaffold itself; its direct body slot is Capture or the permission branch. */
internal open class ReverbHomeScaffoldView(
    context: Context,
    initialEnvironment: ReverbViewEnvironment,
    initialNavigation: ReverbAppTopBarActions,
    initialHasIncidents: Boolean,
    initialInsets: ReverbHomeInsets,
    nativeInputRoot: View,
) : ViewGroup(context), NativeSourceFocusChildOrder {
    private var environment = initialEnvironment
    private var insets = initialInsets
    private var navigation = initialNavigation
    private var hasIncidents = initialHasIncidents
    private val noise = ReverbNoiseDrawable(resources, environment.palette.surface)
    private val header = ReverbAppTopBarView(context, environment, nativeInputRoot)
    val topBar: ReverbAppTopBarView get() = header
    private var body: View? = null

    init {
        clipChildren = false
        clipToPadding = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        background = noise
        if (Build.VERSION.SDK_INT >= 29) isForceDarkAllowed = false
        addView(header)
        header.bind(environment, initialHasIncidents, initialNavigation,
            statusInsetPx = insets.statusTop)
    }

    /** No wrapper is added; the body remains the same direct child at index 1. */
    fun setBody(value: View?) {
        if (body === value) return
        body?.let(::removeView)
        body = value
        if (value != null) addView(value)
    }

    // Material3 Scaffold places its body before the top bar for one-dimensional focus search.
    override fun sourceFocusChildAt(index: Int): View =
        getChildAt(if (body != null && childCount > 1 && index < 2) 1 - index else index)

    fun bindShell(navigation: ReverbAppTopBarActions, hasIncidents: Boolean, nextInsets: ReverbHomeInsets) {
        if (insets != nextInsets) { insets = nextInsets; requestLayout() }
        this.navigation = navigation
        this.hasIncidents = hasIncidents
        header.bind(environment, hasIncidents, navigation, statusInsetPx = insets.statusTop)
    }

    open fun setEnvironment(value: ReverbViewEnvironment) {
        environment = value
        noise.setBackgroundColor(value.palette.surface)
        header.bind(value, hasIncidents, navigation, statusInsetPx = insets.statusTop)
        requestLayout()
    }

    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w)
        val height = MeasureSpec.getSize(h)
        setMeasuredDimension(width, height)
        header.measure(exact(width), MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST))
        val contentWidth = (width - insets.contentLeft - insets.contentRight).coerceAtLeast(0)
        val contentHeight = (height - header.measuredHeight - insets.contentBottom).coerceAtLeast(0)
        body?.measure(exact(contentWidth), exact(contentHeight))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        header.layout(0, 0, width, header.measuredHeight)
        body?.let {
            it.layout(insets.contentLeft, header.measuredHeight,
                insets.contentLeft + it.measuredWidth, header.measuredHeight + it.measuredHeight)
        }
    }

    private fun exact(size: Int) = MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY)
}
