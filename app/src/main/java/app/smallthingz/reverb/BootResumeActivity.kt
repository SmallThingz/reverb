package app.smallthingz.reverb

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.UserManager
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import app.smallthingz.reverb.nativeui.ReverbPalette
import app.smallthingz.reverb.nativeui.ReverbBrandMarkView
import java.io.Closeable
import java.util.UUID
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import smallthingz.ui.components.NativeText
import smallthingz.ui.components.NativeTextStyle
import smallthingz.ui.controls.ControlPalette

/** An ordinary opaque Activity, admitted only after a real drawn, focused foreground window. */
class BootResumeActivity : ComponentActivity() {
    private lateinit var root: BootResumeContentView
    private var launch: BootResumeLaunch? = null
    private var admission = BootResumeActivityAdmission()
    private val serviceUiOwner = CaptureServiceUiOwner()
    private val foregroundOwner = Any()
    private var checkJob: Job? = null
    private var binding: Closeable? = null
    private var foreground: Closeable? = null
    private var confirmation: Closeable? = null
    private var drawn = false
    private var closed = false
    private var startedAfterNanos = 0L
    private val timeout = Runnable { closeAttempt() }
    private val successTimeout = Runnable { closeAttempt() }
    private val drawListener = object : ViewTreeObserver.OnDrawListener {
        override fun onDraw() {
            // A pre-draw callback is too early. This runs after this traversal has drawn.
            root.post {
                if (closed) return@post
                root.viewTreeObserver.removeOnDrawListener(this)
                drawn = true
                maybeBind()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val dark = getConfiguredThemeMode(this).nativeIsDark(
            resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES)
        setTheme(if (dark) R.style.Theme_Reverb_Dark else R.style.Theme_Reverb_Light)
        super.onCreate(savedInstanceState)
        retirePendingBootLaunch(pendingLaunch, startedMains.refreshVisibility())
        val candidate = pendingLaunch.get()
        if (savedInstanceState != null || candidate == null || candidate.id != intent.getStringExtra(EXTRA_LAUNCH) ||
            !candidate.current(startedMains.visibilityRevision) ||
            SystemClock.elapsedRealtime() - candidate.createdAtMillis !in 0L until TIMEOUT_MILLIS ||
            !pendingLaunch.compareAndSet(candidate, null)) {
            finish()
            return
        }
        launch = candidate
        admission = BootResumeActivityAdmission(candidate.createdAtMillis, TIMEOUT_MILLIS)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        val palette = ReverbPalette.resolve(this, dark)
        window.setBackgroundDrawable(ColorDrawable(palette.background))
        root = BootResumeContentView(this, palette)
        setContentView(root)
        root.viewTreeObserver.addOnDrawListener(drawListener)
        root.postDelayed(timeout, admission.remainingMillis(SystemClock.elapsedRealtime()))
    }

    override fun onResume() {
        super.onResume()
        if (::root.isInitialized) maybeBind()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!::root.isInitialized || closed) return
        if (hasFocus) maybeBind()
        else if (admission.departed(SystemClock.elapsedRealtime())) closeAttempt()
    }

    override fun onPause() {
        // Android may queue a sleep pause before the initial window has ever become visible.
        // Once foreground was observed, departure is terminal even while its IO check is pending.
        if (::root.isInitialized && (isFinishing || admission.departed(SystemClock.elapsedRealtime()))) closeAttempt()
        super.onPause()
    }

    override fun onDestroy() {
        closed = true
        admission.abandon()
        releaseOwners()
        super.onDestroy()
    }

    private fun visible(): Boolean = !closed && !isFinishing && !isDestroyed && drawn &&
        lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
        root.isAttachedToWindow && root.isShown && root.hasWindowFocus() &&
        getSystemService(PowerManager::class.java)?.isInteractive == true &&
        storageUnlocked(this)

    private fun maybeBind() {
        if (isFinishing || isDestroyed) { closeAttempt(); return }
        if (admission.captureConfirmed) return
        val now = SystemClock.elapsedRealtime()
        if (!admission.current(now)) { closeAttempt(); return }
        if (!admission.admitted && !admission.current(now, launchCurrent())) { closeAttempt(); return }
        if (!admission.observeForeground(visible(), now) || admission.admitted || checkJob != null) return
        checkJob = lifecycleScope.launch {
            val enabled = withContext(Dispatchers.IO) {
                runCatching { readDurableCaptureIntentPreferences(getRecorderPreferences(applicationContext)).enabled }
                    .onFailure { RecordingIncidentStore.recordUnexpectedErrorInBackground(applicationContext,
                        "Read boot recording intent", it) }.getOrNull()
            }
            checkJob = null
            if (!admission.current(SystemClock.elapsedRealtime())) { closeAttempt(); return@launch }
            if (enabled != true || !launchCurrent() || !permissionsAvailable(this@BootResumeActivity)) {
                closeAttempt()
                return@launch
            }
            if (!admission.admit(visible(), SystemClock.elapsedRealtime())) return@launch
            startedAfterNanos = System.nanoTime()
            Log.i("BootResumeActivity", "visible-admission after-draw elapsedMs=${SystemClock.elapsedRealtime()}")
            binding = serviceUiOwner.bind(this@BootResumeActivity, this@BootResumeActivity, root,
                isAlive = { !closed && admission.withinDeadline(SystemClock.elapsedRealtime()) && visible() },
                callbacks = object : CaptureServiceUiOwner.BindingCallbacks {
                    override fun connected(connectedService: ReverbService) {
                        if (!admission.current(SystemClock.elapsedRealtime()) || !visible() || !admission.bindAccepted) {
                            closeAttempt(); return
                        }
                        foreground = serviceUiOwner.foregroundLease(connectedService,
                            this@BootResumeActivity, root, foregroundOwner)
                        confirmCapture(connectedService)
                    }
                    override fun unavailable(markSavingAsCancelRequested: Boolean) { closeAttempt() }
                    override fun stopped() = Unit
                },
                canBind = {
                    // This main-thread dispatch accepts the automatic start. Later opt-out
                    // affects future starts; a real Stop still invalidates the Service's PCM proof.
                    admission.acceptServiceBind(visible(), launchCurrent() && permissionsAvailable(this@BootResumeActivity),
                        SystemClock.elapsedRealtime())
                        .also { if (!it) closeAttempt() }
                })
        }
    }

    private fun launchCurrent(): Boolean = launch?.current(startedMains.visibilityRevision) == true

    private fun confirmCapture(service: ReverbService) {
        confirmation = service.confirmBootResumeCapture(startedAfterNanos) {
            if (visible() && admission.confirmCapture(SystemClock.elapsedRealtime())) {
                Log.i("BootResumeActivity", "finish-after-confirmed-pcm elapsedMs=${SystemClock.elapsedRealtime()}")
                root.removeCallbacks(timeout)
                root.showSuccess()
                root.postDelayed(successTimeout, admission.successRemainingMillis(SystemClock.elapsedRealtime()))
            }
        }
    }

    private fun closeAttempt() {
        if (closed) return
        closed = true
        admission.abandon()
        releaseOwners()
        finish()
    }

    private fun releaseOwners() {
        checkJob?.cancel(); checkJob = null
        confirmation?.close(); confirmation = null
        foreground?.close(); foreground = null
        binding?.close(); binding = null
        if (::root.isInitialized) {
            root.removeCallbacks(timeout)
            root.removeCallbacks(successTimeout)
            if (root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnDrawListener(drawListener)
        }
    }

    companion object {
        private const val EXTRA_LAUNCH = "boot-resume-launch"
        private const val TIMEOUT_MILLIS = 15_000L
        private val pendingLaunch = AtomicReference<BootResumeLaunch?>()
        private val launchHandler = Handler(Looper.getMainLooper())
        private val startedMains = StartedMainOwners()

        internal fun mainStarted(owner: Any, visible: () -> Boolean) {
            startedMains.start(owner, visible)
        }

        internal fun mainVisibilityChanged(owner: Any) {
            if (startedMains.contains(owner))
                retirePendingBootLaunch(pendingLaunch, startedMains.refreshVisibility())
        }

        internal fun mainStopped(owner: Any) { startedMains.stop(owner) }

        private fun storageUnlocked(context: Context): Boolean =
            context.getSystemService(UserManager::class.java)?.isUserUnlocked == true

        internal fun overlayAvailable(context: Context): Boolean = runCatching {
            Settings.canDrawOverlays(context)
        }.getOrDefault(false)

        internal fun overlayReviewIntent(context: Context): Intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.fromParts("package", context.packageName, null))

        private fun permissionsAvailable(context: Context): Boolean = overlayAvailable(context) &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        internal fun launch(context: Context, allowed: () -> Boolean) {
            if (!allowed()) return
            val autoStartRevision = AutoStartAfterRestart.admission(context) ?: return
            val launchAllowed = { allowed() && AutoStartAfterRestart.policy.currentAdmission(autoStartRevision) }
            if (!storageUnlocked(context) || !permissionsAvailable(context) ||
                !readDurableCaptureIntentPreferences(getRecorderPreferences(context)).enabled) {
                return
            }
            if (!launchAllowed()) return
            val token = BootResumeLaunch(UUID.randomUUID().toString(), SystemClock.elapsedRealtime(), launchAllowed,
                startedMains.visibilityRevision)
            if (!pendingLaunch.compareAndSet(null, token)) return
            launchHandler.postDelayed({ pendingLaunch.compareAndSet(token, null) }, TIMEOUT_MILLIS)
            launchHandler.post {
                // Recheck live View/lifecycle state on main after the durable read. STARTED alone
                // is not visible authority: a locked or occluded Main still needs the helper route.
                retirePendingBootLaunch(pendingLaunch, startedMains.refreshVisibility())
                if (!launchAllowed() || pendingLaunch.get() !== token) {
                    pendingLaunch.compareAndSet(token, null)
                    return@post
                }
                try {
                    context.startActivity(Intent(context, BootResumeActivity::class.java)
                        .putExtra(EXTRA_LAUNCH, token.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (error: Exception) {
                    pendingLaunch.compareAndSet(token, null)
                    if (error !is SecurityException && error !is android.content.ActivityNotFoundException)
                        RecordingIncidentStore.recordUnexpectedErrorInBackground(context, "Open boot recording window", error)
                }
            }
        }
    }
}

internal data class BootResumeLaunch(val id: String, val createdAtMillis: Long, val allowed: () -> Boolean,
    val mainVisibilityRevision: Long = 0L) {
    fun current(visibleMainRevision: Long): Boolean = allowed() && mainVisibilityRevision == visibleMainRevision
}

internal fun retirePendingBootLaunch(pending: AtomicReference<BootResumeLaunch?>, mainVisible: Boolean) {
    if (!mainVisible) return
    val candidate = pending.get() ?: return
    pending.compareAndSet(candidate, null)
}

internal fun mainWindowCanResumeCapture(resumed: Boolean, attached: Boolean, shown: Boolean,
    focused: Boolean, interactive: Boolean, keyguardLocked: Boolean): Boolean =
    resumed && attached && shown && focused && interactive && !keyguardLocked

/** Main-thread identity ownership; the IO admission path reads only the volatile aggregate. */
internal class StartedMainOwners {
    private val owners = IdentityHashMap<Any, () -> Boolean>()
    @Volatile var anyStarted = false
        private set
    @Volatile var visibilityRevision = 0L
        private set
    private var lastVisible = false
    fun start(owner: Any, visible: () -> Boolean = { false }) { owners[owner] = visible; anyStarted = true }
    fun contains(owner: Any): Boolean = owners.containsKey(owner)
    fun refreshVisibility(): Boolean {
        val visible = owners.values.any { it() }
        if (visible && !lastVisible) visibilityRevision++
        lastVisible = visible
        return visible
    }
    fun stop(owner: Any): Boolean {
        val removed = owners.remove(owner) != null
        anyStarted = owners.isNotEmpty()
        if (!anyStarted) lastVisible = false
        return removed && !anyStarted
    }
}

/** Shared with isolated UI rendering; constructing this View has no recorder or lifecycle work. */
internal class BootResumeContentView(
    context: Context,
    palette: ReverbPalette,
) : LinearLayout(context) {
    private val controls = ControlPalette(palette.surface, palette.surfaceContainer, palette.onBackground,
        palette.onSurfaceVariant, palette.primary, palette.onPrimary, palette.primaryContainer, palette.outline)
    private val detail = NativeText(context, controls, NativeTextStyle(20f, lineHeightSp = 28f))
    private val progress = ProgressBar(context)

    init {
        orientation = VERTICAL
        setBackgroundColor(palette.background)
        ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        val column = LinearLayout(context).apply { orientation = VERTICAL; gravity = Gravity.CENTER }
        val padding = (32 * resources.displayMetrics.density).toInt()
        column.setPadding(padding, padding, padding, padding)
        column.addView(ReverbBrandMarkView(context, context.getString(R.string.app_name)), LayoutParams(padding * 2, padding * 2))
        column.addView(detail.apply {
            text = context.getString(R.string.boot_resume_in_progress)
            gravity = Gravity.CENTER
            setPadding(0, padding / 2, 0, padding / 2)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            isAccessibilityHeading = true
        })
        column.addView(progress)
        addView(ScrollView(context).apply {
            isFillViewport = true
            addView(column, android.widget.FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun showSuccess() {
        detail.text = context.getString(R.string.boot_resume_success)
        // Keep the centered logo and message anchored during the success hold.
        progress.visibility = View.INVISIBLE
    }
}

internal class BootResumeActivityAdmission(
    private val createdAtMillis: Long = 0L,
    private val timeoutMillis: Long = 15_000L,
) {
    var admitted = false
        private set
    var bindAccepted = false
        private set
    private var abandoned = false
    private var foregroundObserved = false
    private var confirmedAtMillis: Long? = null
    val captureConfirmed: Boolean get() = confirmedAtMillis != null
    fun withinDeadline(nowMillis: Long): Boolean = nowMillis - createdAtMillis in 0L until timeoutMillis
    fun current(nowMillis: Long, allowed: Boolean = true): Boolean {
        if (!allowed || !withinDeadline(nowMillis)) abandoned = true
        return !abandoned
    }
    fun remainingMillis(nowMillis: Long): Long =
        (timeoutMillis - (nowMillis - createdAtMillis)).coerceIn(0L, timeoutMillis)
    fun observeForeground(visible: Boolean, nowMillis: Long): Boolean {
        if (!current(nowMillis) || !visible) return false
        foregroundObserved = true
        return true
    }
    fun departed(nowMillis: Long): Boolean {
        if (!current(nowMillis) || foregroundObserved) { abandon(); return true }
        return false
    }
    fun admit(visible: Boolean, nowMillis: Long = 0L): Boolean {
        if (!observeForeground(visible, nowMillis) || admitted) return false
        admitted = true
        return true
    }
    fun confirmCapture(nowMillis: Long): Boolean {
        if (!admitted || captureConfirmed || !current(nowMillis)) return false
        confirmedAtMillis = nowMillis
        return true
    }
    fun acceptServiceBind(visible: Boolean, allowed: Boolean, nowMillis: Long): Boolean {
        if (!admitted || bindAccepted || !current(nowMillis, allowed) || !visible) return false
        bindAccepted = true
        return true
    }
    fun successRemainingMillis(nowMillis: Long): Long {
        val confirmedAt = confirmedAtMillis ?: return 0L
        return if (abandoned) 0L else (500L - (nowMillis - confirmedAt)).coerceIn(0L, 500L)
    }
    fun abandon() { abandoned = true }
}
