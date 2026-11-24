package app.smallthingz.reverb.nativeui

internal typealias OnboardingPermission = smallthingz.ui.reverb.onboarding.OnboardingPermission
internal typealias OnboardingPermissionStatus = smallthingz.ui.reverb.onboarding.OnboardingPermissionStatus

internal fun OnboardingPermissionStatus.isReadyForHighlightedContinue(): Boolean =
    smallthingz.ui.reverb.onboarding.highlightedContinue(this)
