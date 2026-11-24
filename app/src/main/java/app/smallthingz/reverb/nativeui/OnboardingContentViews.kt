package app.smallthingz.reverb.nativeui

import android.view.View

internal typealias OnboardingPageTypography = smallthingz.ui.reverb.onboarding.OnboardingPageTypography
internal typealias OnboardingIconView = smallthingz.ui.reverb.ReverbGlyphView
internal typealias OnboardingBufferRow = smallthingz.ui.reverb.onboarding.OnboardingBufferRow

internal fun exact(size: Int): Int = View.MeasureSpec.makeMeasureSpec(size.coerceAtLeast(0), View.MeasureSpec.EXACTLY)
internal fun atMost(size: Int): Int = View.MeasureSpec.makeMeasureSpec(size.coerceAtLeast(0), View.MeasureSpec.AT_MOST)
internal fun unspecified(): Int = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
