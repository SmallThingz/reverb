package app.smallthingz.reverb

internal interface NavigationContract {
    val predictiveBackTarget: OnboardingBackTarget?
    fun begin(): Any
    fun attachCancellation(owner: Any, cancel: () -> Unit)
    fun complete(owner: Any)
    fun beginPredictiveBack(sourcePage: Int): OnboardingBackTarget
    fun ownsPredictiveBack(target: OnboardingBackTarget): Boolean
    fun endPredictiveBack(target: OnboardingBackTarget)
    fun mayNavigate(): Boolean
    fun mayFinish(onLastPage: Boolean, scrolling: Boolean, predictiveBack: Boolean): Boolean
}

internal fun referenceNavigation(): NavigationContract {
    val state = OnboardingNavigationState()
    return object : NavigationContract {
        override val predictiveBackTarget get() = state.predictiveBackTarget
        override fun begin() = state.begin()
        override fun attachCancellation(owner: Any, cancel: () -> Unit) = state.attachCancellation(owner, cancel)
        override fun complete(owner: Any) = state.complete(owner)
        override fun beginPredictiveBack(sourcePage: Int) = state.beginPredictiveBack(sourcePage)
        override fun ownsPredictiveBack(target: OnboardingBackTarget) = state.ownsPredictiveBack(target)
        override fun endPredictiveBack(target: OnboardingBackTarget) = state.endPredictiveBack(target)
        override fun mayNavigate() = state.mayNavigate()
        override fun mayFinish(onLastPage: Boolean, scrolling: Boolean, predictiveBack: Boolean) =
            state.mayFinish(onLastPage, scrolling, predictiveBack)
    }
}

internal fun nativeNavigation(): NavigationContract {
    val state = NativeOnboardingNavigationState()
    return object : NavigationContract {
        override val predictiveBackTarget get() = state.predictiveBackTarget
        override fun begin() = state.begin()
        override fun attachCancellation(owner: Any, cancel: () -> Unit) = state.attachCancellation(owner, cancel)
        override fun complete(owner: Any) = state.complete(owner)
        override fun beginPredictiveBack(sourcePage: Int) = state.beginPredictiveBack(sourcePage)
        override fun ownsPredictiveBack(target: OnboardingBackTarget) = state.ownsPredictiveBack(target)
        override fun endPredictiveBack(target: OnboardingBackTarget) = state.endPredictiveBack(target)
        override fun mayNavigate() = state.mayNavigate()
        override fun mayFinish(onLastPage: Boolean, scrolling: Boolean, predictiveBack: Boolean) =
            state.mayFinish(onLastPage, scrolling, predictiveBack)
    }
}
