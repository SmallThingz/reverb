package app.smallthingz.reverb

class OnboardingPredictiveNavigationStateTest : OnboardingPredictiveNavigationContract() {
    internal override fun newNavigation(): NavigationContract = referenceNavigation()
}
