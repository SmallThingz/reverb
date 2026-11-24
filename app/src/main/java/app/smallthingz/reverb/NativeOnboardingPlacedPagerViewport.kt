package app.smallthingz.reverb

internal typealias NativeOnboardingPlacedPagerViewport = smallthingz.ui.reverb.onboarding.NativeOnboardingPlacedPagerViewport

internal fun nativeOnboardingPlacedPages(width: Int, offset: Long): IntRange =
    smallthingz.ui.reverb.onboarding.nativeOnboardingPlacedPages(width, offset)

internal fun nativeOnboardingPageMembershipMask(placed: IntRange, focusSearchPages: Set<Int>, focusedPage: Int?): Int =
    smallthingz.ui.reverb.onboarding.nativeOnboardingPageMembershipMask(placed, focusSearchPages, focusedPage)
