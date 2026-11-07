package app.smallthingz.reverb

class OnboardingNavigationStateTest : OnboardingNavigationContract() {
    internal override fun newNavigation(): NavigationContract = referenceNavigation()
}
