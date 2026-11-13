package app.smallthingz.reverb.nativeui

import android.content.Context
import android.view.View
import smallthingz.ui.text.font.FontFamily
import smallthingz.ui.unit.Density
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import app.smallthingz.reverb.R

internal enum class OnboardingPermission { MICROPHONE, STORAGE, RECOVERY, NOTIFICATIONS, BATTERY, ALL_FILES, OVERLAY }

internal data class OnboardingPermissionStatus(
    val microphoneAllowed: Boolean,
    val storageRequired: Boolean,
    val storageAllowed: Boolean,
    val recoveryRequired: Boolean,
    val recoveryAllowed: Boolean,
    val notificationPermissionRequired: Boolean,
    val notificationsAllowed: Boolean,
    val batteryOptimizationAllowed: Boolean,
    /** False for Finish/predictive preview, not ordinary pager movement. */
    val interactionEnabled: Boolean,
    val allFilesAvailable: Boolean = false,
    val allFilesAllowed: Boolean = false,
    val overlayAllowed: Boolean = true,
)

/** Optional all-files, battery and restart access do not gate the footer highlight. */
internal fun OnboardingPermissionStatus.isReadyForHighlightedContinue(): Boolean =
    microphoneAllowed && storageAllowed &&
        (!recoveryRequired || recoveryAllowed || allFilesAllowed) &&
        (!notificationPermissionRequired || notificationsAllowed)

private data class PermissionRowState(
    val kind: OnboardingPermission,
    val marker: String,
    val title: String,
    val body: String?,
    val allowed: Boolean,
    val canRequest: Boolean,
    val icon: ReverbIcon? = null,
)

/** Native permission rows; Activity launchers, refresh and durable Finish remain screen-owned. */
internal class OnboardingPermissionGroup(
    private val context: Context,
    private val typography: OnboardingPageTypography,
    private val density: Density,
    private val resolver: FontFamily.Resolver,
    private val interactions: OnboardingNativeInteractionsFactory,
) {
    val view = ReverbGroupLayout(context, cornerRadiusDp = 20f)
    private val rows = linkedMapOf<OnboardingPermission, PermissionRow>()
    private var rowOrder = emptyList<OnboardingPermission>()
    private var owner: ActionOwner? = null

    init {
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) { cancelPendingRowInput() }
        })
    }

    /** An old close handle cannot remove callbacks connected by a newer owner. */
    fun connect(lifecycleOwner: LifecycleOwner, onRequest: (OnboardingPermission) -> Unit): AutoCloseable {
        owner?.let(::releaseOwner)
        val connected = ActionOwner(lifecycleOwner, onRequest)
        connected.observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY) releaseOwner(connected)
        }
        owner = connected
        lifecycleOwner.lifecycle.addObserver(connected.observer)
        return AutoCloseable { releaseOwner(connected) }
    }

    fun bind(status: OnboardingPermissionStatus, palette: ReverbPalette) {
        val states = buildList {
            add(PermissionRowState(OnboardingPermission.MICROPHONE, "●",
                context.getString(R.string.onboarding_microphone_title), context.getString(R.string.onboarding_microphone_body),
                status.microphoneAllowed, status.interactionEnabled))
            if (status.storageRequired && !status.storageAllowed) add(PermissionRowState(OnboardingPermission.STORAGE, "S",
                context.getString(R.string.storage_settings_title), null,
                false, status.interactionEnabled, ReverbIcon.Folder))
            if (status.recoveryRequired && !status.recoveryAllowed && !status.allFilesAllowed)
                add(PermissionRowState(OnboardingPermission.RECOVERY, "R",
                    context.getString(R.string.settings_audio_read_missing), null,
                    false, status.interactionEnabled, ReverbIcon.AudioFile))
            if (status.allFilesAvailable && !status.allFilesAllowed) add(PermissionRowState(OnboardingPermission.ALL_FILES, "F",
                context.getString(R.string.settings_all_files_optional), null,
                false, status.interactionEnabled, ReverbIcon.Folder))
            add(PermissionRowState(OnboardingPermission.NOTIFICATIONS, "N",
                context.getString(R.string.onboarding_notifications_title), context.getString(R.string.onboarding_notifications_body),
                status.notificationsAllowed, status.interactionEnabled && status.notificationPermissionRequired))
            add(PermissionRowState(OnboardingPermission.BATTERY, "B",
                context.getString(R.string.onboarding_background_title), context.getString(R.string.onboarding_background_body),
                status.batteryOptimizationAllowed, status.interactionEnabled))
            add(PermissionRowState(OnboardingPermission.OVERLAY, "R",
                context.getString(R.string.onboarding_restart_resume_title), context.getString(R.string.onboarding_restart_resume_body),
                status.overlayAllowed, status.interactionEnabled, ReverbIcon.BackgroundWarning))
        }
        val present = states.mapTo(mutableSetOf()) { it.kind }
        rows.keys.filterNot(present::contains).forEach { kind ->
            rows.remove(kind)?.let { row -> row.cancelPendingInputEvents(); view.removeRow(row) }
        }
        states.forEach { state ->
            rows.getOrPut(state.kind) {
                PermissionRow(context, typography, density, resolver, interactions, state.marker, state.icon, ::request)
            }.bind(state, palette)
        }
        val desiredOrder = states.map { it.kind }
        if (rowOrder != desiredOrder) {
            rows.values.forEach(view::removeRow)
            desiredOrder.forEachIndexed { index, kind ->
                view.addRow(checkNotNull(rows[kind]), ReverbGroupLayout.LayoutParams(-1, -2).apply {
                    dividerTop = index > 0
                })
            }
            rowOrder = desiredOrder
        }
        view.setPalette(palette.field, palette.border)
    }

    fun cancelPendingRowInput() = rows.values.forEach(View::cancelPendingInputEvents)

    private fun request(kind: OnboardingPermission) {
        val current = owner ?: return
        val row = rows[kind] ?: return
        if (!view.isAttachedToWindow || !row.canRequestNow) return
        current.request(kind)
    }

    private fun releaseOwner(expected: ActionOwner) {
        if (owner !== expected) return
        owner = null
        expected.lifecycleOwner.lifecycle.removeObserver(expected.observer)
        cancelPendingRowInput()
    }

    private class ActionOwner(val lifecycleOwner: LifecycleOwner, val request: (OnboardingPermission) -> Unit) {
        lateinit var observer: LifecycleEventObserver
    }
}

private class PermissionRow(
    context: Context,
    typography: OnboardingPageTypography,
    density: Density,
    resolver: FontFamily.Resolver,
    interactions: OnboardingNativeInteractionsFactory,
    private val markerText: String,
    icon: ReverbIcon?,
    request: (OnboardingPermission) -> Unit,
) : OnboardingCardView(context, typography, density, resolver,
    OnboardingMarker(context, typography.titleMedium, density, resolver, icon = icon, marker = markerText),
    OnboardingIconView(context, ReverbIcon.Unchecked, 0), 24, 24, interactions) {
    private var state: PermissionRowState? = null
    private val allowLabel = context.getString(R.string.allow)
    override val accessibilityClickLabel: CharSequence get() =
        when (state?.kind) {
            OnboardingPermission.ALL_FILES -> context.getString(R.string.all_files_access_settings)
            OnboardingPermission.OVERLAY -> context.getString(R.string.settings_restart_access)
            else -> allowLabel
        }
    val canRequestNow: Boolean get() = state?.let { it.canRequest && !it.allowed } == true

    init { onActivate = { state?.takeIf { canRequestNow }?.let { request(it.kind) } } }

    fun bind(value: PermissionRowState, palette: ReverbPalette) {
        state = value
        (trailing as OnboardingIconView).bind(
            if (value.kind == OnboardingPermission.ALL_FILES || value.kind == OnboardingPermission.OVERLAY && !value.allowed) ReverbIcon.Settings
            else if (value.allowed) ReverbIcon.Checked else ReverbIcon.Unchecked,
            if (value.allowed) palette.primary else palette.onSurfaceVariant,
        )
        bindCard(value.title, value.body, canRequestNow, palette)
        trailing.contentDescription = when {
            value.kind == OnboardingPermission.ALL_FILES -> context.getString(R.string.all_files_access_settings)
            value.kind == OnboardingPermission.OVERLAY && !value.allowed -> context.getString(R.string.settings_restart_access)
            else -> context.getString(if (value.allowed) R.string.onboarding_allowed else R.string.allow)
        }
        trailing.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        trailing.isScreenReaderFocusable = false
    }
}
