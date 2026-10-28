package app.smallthingz.reverb

import app.smallthingz.reverb.nativeui.OnboardingPermission
import app.smallthingz.reverb.nativeui.OnboardingPermissionStatus

/** Serializes automatic first-page prompts and explicit retries without repeating denied prompts. */
internal class OnboardingPermissionRequestSequence(
    attempted: Set<OnboardingPermission> = emptySet(),
    pending: OnboardingPermission? = null,
    batteryLeftForeground: Boolean = false,
    settingsReview: Boolean = false,
    settingsLeftForeground: Boolean = false,
) {
    var attemptedMask: Int = attempted.fold(0) { bits, permission -> bits or bit(permission) } or
        (pending?.let(::bit) ?: 0)
        private set
    var pending: OnboardingPermission? = pending
        private set
    var batteryLeftForeground: Boolean = batteryLeftForeground && pending == OnboardingPermission.BATTERY
        private set
    var settingsReview: Boolean = settingsReview && pending != null && pending != OnboardingPermission.BATTERY
        private set
    var settingsLeftForeground: Boolean = settingsLeftForeground && this.settingsReview
        private set

    fun claimNext(status: OnboardingPermissionStatus, resumed: Boolean): OnboardingPermission? {
        if (!resumed || !status.interactionEnabled || pending != null) return null
        val permission = OnboardingPermission.entries.firstOrNull {
            attemptedMask and bit(it) == 0 && missing(status, it)
        } ?: return null
        claim(permission)
        return permission
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
            settingsReview = false
            settingsLeftForeground = false
        }
    }

    /** A denied runtime request with no rationale cannot show another system prompt. */
    fun beginSettingsReview(previouslyRequested: Boolean, showsRationale: Boolean): Boolean {
        if (pending == null || pending == OnboardingPermission.BATTERY ||
            !previouslyRequested || showsRationale) return false
        settingsReview = true
        return true
    }

    fun leftForeground() {
        if (pending == OnboardingPermission.BATTERY) batteryLeftForeground = true
        if (settingsReview) settingsLeftForeground = true
    }

    /** External reviews finish on return; runtime permission dialogs still require their result. */
    fun returnedToForeground() {
        if (batteryLeftForeground) completed(OnboardingPermission.BATTERY)
        if (settingsLeftForeground) pending?.let(::completed)
    }

    private fun claim(permission: OnboardingPermission) {
        attemptedMask = attemptedMask or bit(permission)
        pending = permission
        batteryLeftForeground = false
        settingsReview = false
        settingsLeftForeground = false
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
