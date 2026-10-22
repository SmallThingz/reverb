package app.smallthingz.reverb

import app.smallthingz.reverb.nativeui.OnboardingPermission
import app.smallthingz.reverb.nativeui.OnboardingPermissionStatus

/** One foreground prompt at a time; denial advances the automatic sequence, never repeats it. */
internal class OnboardingPermissionRequestSequence(
    attempted: Set<OnboardingPermission> = emptySet(),
    pending: OnboardingPermission? = null,
    batteryLeftForeground: Boolean = false,
) {
    var attemptedMask: Int = attempted.fold(0) { bits, permission -> bits or bit(permission) } or
        (pending?.let(::bit) ?: 0)
        private set
    var pending: OnboardingPermission? = pending
        private set
    var batteryLeftForeground: Boolean = batteryLeftForeground && pending == OnboardingPermission.BATTERY
        private set

    fun claimNext(
        status: OnboardingPermissionStatus,
        pageVisible: Boolean,
        resumed: Boolean,
    ): OnboardingPermission? {
        if (!pageVisible || !resumed || !status.interactionEnabled || pending != null) return null
        for (permission in OnboardingPermission.entries) {
            if (attemptedMask and bit(permission) == 0 && missing(status, permission)) {
                claim(permission)
                return permission
            }
        }
        return null
    }

    /** Explicit row taps may retry a denial, but cannot overlap another system request. */
    fun claimManual(permission: OnboardingPermission, status: OnboardingPermissionStatus, resumed: Boolean): Boolean {
        if (!resumed || !status.interactionEnabled || pending != null || !missing(status, permission)) return false
        claim(permission)
        return true
    }

    fun completed(permission: OnboardingPermission) {
        if (pending == permission) {
            pending = null
            batteryLeftForeground = false
        }
    }

    fun leftForeground() {
        if (pending == OnboardingPermission.BATTERY) batteryLeftForeground = true
    }

    /** Battery review is an external Activity, unlike the four result-contract permissions. */
    fun returnedToForeground() {
        if (batteryLeftForeground) completed(OnboardingPermission.BATTERY)
    }

    private fun claim(permission: OnboardingPermission) {
        attemptedMask = attemptedMask or bit(permission)
        pending = permission
        batteryLeftForeground = false
    }

    private fun missing(status: OnboardingPermissionStatus, permission: OnboardingPermission): Boolean = when (permission) {
        OnboardingPermission.MICROPHONE -> !status.microphoneAllowed
        OnboardingPermission.STORAGE -> status.storageRequired && !status.storageAllowed
        OnboardingPermission.RECOVERY -> status.recoveryRequired && !status.recoveryAllowed
        OnboardingPermission.NOTIFICATIONS -> status.notificationPermissionRequired && !status.notificationsAllowed
        OnboardingPermission.BATTERY -> !status.batteryOptimizationAllowed
    }

    companion object {
        private fun bit(permission: OnboardingPermission): Int = 1 shl permission.ordinal
        fun attemptedFromMask(mask: Int): Set<OnboardingPermission> =
            OnboardingPermission.entries.filterTo(mutableSetOf()) { mask and bit(it) != 0 }
    }
}
