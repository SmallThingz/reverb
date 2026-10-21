package app.smallthingz.reverb.nativeui

import android.content.Context
import android.view.View
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import app.smallthingz.reverb.R

internal enum class OnboardingPermission { MICROPHONE, STORAGE, RECOVERY, NOTIFICATIONS, BATTERY }

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
)

/** Battery exemption does not gate the released footer highlight. */
internal fun OnboardingPermissionStatus.isReadyForHighlightedContinue(): Boolean =
    microphoneAllowed && storageAllowed &&
        (!recoveryRequired || recoveryAllowed) &&
        (!notificationPermissionRequired || notificationsAllowed)

private data class PermissionRowState(
    val kind: OnboardingPermission,
    val marker: String,
    val title: String,
    val body: String?,
    val allowed: Boolean,
    val canRequest: Boolean,
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
                context.getString(R.string.onboarding_microphone_title), null,
                status.microphoneAllowed, status.interactionEnabled))
            if (status.storageRequired) add(PermissionRowState(OnboardingPermission.STORAGE, "S",
                context.getString(R.string.storage_settings_title), context.getString(R.string.onboarding_storage_body),
                status.storageAllowed, status.interactionEnabled))
            if (status.recoveryRequired) add(PermissionRowState(OnboardingPermission.RECOVERY, "R",
                context.getString(R.string.recording_recovery_title), context.getString(R.string.recording_recovery_body),
                status.recoveryAllowed, status.interactionEnabled))
            add(PermissionRowState(OnboardingPermission.NOTIFICATIONS, "N",
                context.getString(R.string.onboarding_notifications_title), context.getString(R.string.onboarding_notifications_body),
                status.notificationsAllowed, status.interactionEnabled && status.notificationPermissionRequired))
            add(PermissionRowState(OnboardingPermission.BATTERY, "B",
                context.getString(R.string.onboarding_background_title), context.getString(R.string.onboarding_background_body),
                status.batteryOptimizationAllowed, status.interactionEnabled))
        }
        val present = states.mapTo(mutableSetOf()) { it.kind }
        rows.keys.filterNot(present::contains).forEach { kind ->
            rows.remove(kind)?.let { row -> row.cancelPendingInputEvents(); view.removeRow(row) }
        }
        states.forEach { state ->
            rows.getOrPut(state.kind) {
                PermissionRow(context, typography, density, resolver, interactions, state.marker, ::request)
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
    request: (OnboardingPermission) -> Unit,
) : OnboardingCardView(context, typography, density, resolver,
    OnboardingMarker(context, typography.titleMedium, density, resolver, marker = markerText),
    OnboardingIconView(context, ReverbIcon.Unchecked, 0), 24, 24, interactions) {
    private var state: PermissionRowState? = null
    private val allowLabel = context.getString(R.string.allow)
    override val accessibilityClickLabel: CharSequence get() = allowLabel
    val canRequestNow: Boolean get() = state?.let { it.canRequest && !it.allowed } == true

    init { onActivate = { state?.takeIf { canRequestNow }?.let { request(it.kind) } } }

    fun bind(value: PermissionRowState, palette: ReverbPalette) {
        state = value
        (trailing as OnboardingIconView).bind(
            if (value.allowed) ReverbIcon.Checked else ReverbIcon.Unchecked,
            if (value.allowed) palette.primary else palette.onSurfaceVariant,
        )
        bindCard(value.title, value.body, canRequestNow, palette)
        trailing.contentDescription = context.getString(
            if (value.allowed) R.string.onboarding_allowed else R.string.allow)
        trailing.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        trailing.isScreenReaderFocusable = false
    }
}
