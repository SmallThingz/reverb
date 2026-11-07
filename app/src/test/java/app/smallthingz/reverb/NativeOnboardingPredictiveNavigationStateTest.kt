package app.smallthingz.reverb

class NativeOnboardingPredictiveNavigationStateTest : OnboardingPredictiveNavigationContract() {
    internal override fun newNavigation(): NavigationContract = nativeNavigation()
}
