package app.smallthingz.reverb

internal typealias NativePagerBringIntoViewSpec = smallthingz.ui.reverb.onboarding.NativePagerBringIntoViewSpec
internal typealias NativePagerContentInViewOwner = smallthingz.ui.reverb.onboarding.NativePagerContentInViewOwner

internal fun nativePagerBringIntoViewDistance(
    proposed: Float,
    offset: Float,
    size: Float,
    container: Float,
    pageWidth: Int,
    firstOffset: Int,
    rtl: Boolean,
    lastForward: Boolean,
): Float = smallthingz.ui.reverb.onboarding.nativePagerBringIntoViewDistance(
    proposed, offset, size, container, pageWidth, firstOffset, rtl, lastForward,
)
