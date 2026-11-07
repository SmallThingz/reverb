package app.smallthingz.reverb

class NativeOnboardingNavigationStateTest : OnboardingNavigationContract() {
    internal override fun newNavigation(): NavigationContract = nativeNavigation()
}
