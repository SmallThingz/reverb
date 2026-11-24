package app.smallthingz.reverb

internal typealias NativePagerBeyondBoundsFocus = smallthingz.ui.reverb.onboarding.NativePagerBeyondBoundsFocus

internal fun nativePagerBeyondBoundsIndices(
    firstPlaced: Int, lastPlaced: Int, forward: Boolean, enabled: Boolean, pageCount: Int = 3,
): List<Int> = smallthingz.ui.reverb.onboarding.nativePagerBeyondBoundsIndices(firstPlaced, lastPlaced, forward, enabled, pageCount)
