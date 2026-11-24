package app.smallthingz.reverb.nativeui

import android.view.View
import smallthingz.ui.text.view.NativeSourceAccessibilityNode

internal typealias OnboardingFooterState = smallthingz.ui.reverb.onboarding.OnboardingFooterState
internal typealias OnboardingFooterView = smallthingz.ui.reverb.onboarding.OnboardingFooterView

internal fun OnboardingFooterView.bindTextAccessibility(
    window: NativeWindowTextAccessibilityOwner,
    sourceNode: ((View) -> NativeSourceAccessibilityNode)? = null,
    exposed: (View) -> Boolean,
): AutoCloseable = window.bindFooter(this, sourceNode, exposed)
