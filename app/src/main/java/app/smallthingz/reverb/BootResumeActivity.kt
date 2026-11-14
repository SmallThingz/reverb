package app.smallthingz.reverb

import android.Manifest
import android.content.ActivityNotFoundException
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
import java.io.Closeable
import java.util.UUID
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import smallthingz.ui.components.NativeButton
import smallthingz.ui.components.NativeButtonStyle
import smallthingz.ui.components.NativeButtonTone
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
    private val captureAdmissionOpen = AtomicBoolean(false)
    private var testControlEpoch: Long? = null
    private val timeout = Runnable { closeAttempt(reportFailure = true) }
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
        root = BootResumeContentView(this, palette, ::openReverb)
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
        val now = SystemClock.elapsedRealtime()
        if (!admission.current(now)) { closeAttempt(reportFailure = true); return }
        if (!admission.admitted && !admission.current(now, launchCurrent())) { closeAttempt(); return }
        if (!admission.observeForeground(visible(), now) || admission.admitted || checkJob != null) return
        checkJob = lifecycleScope.launch {
            val enabled = withContext(Dispatchers.IO) {
                runCatching { readDurableCaptureIntentPreferences(getRecorderPreferences(applicationContext)).enabled }
                    .onFailure { RecordingIncidentStore.recordUnexpectedErrorInBackground(applicationContext,
                        "Read boot recording intent", it) }.getOrNull()
            }
            checkJob = null
            val test = launch?.test
            if (!admission.current(SystemClock.elapsedRealtime())) { closeAttempt(reportFailure = true); return@launch }
            if (enabled != (test == null) || !launchCurrent() || !permissionsAvailable(this@BootResumeActivity)) {
                closeAttempt(reportFailure = enabled == null || !permissionsAvailable(this@BootResumeActivity))
                return@launch
            }
            if (!admission.admit(visible(), SystemClock.elapsedRealtime())) return@launch
            startedAfterNanos = System.nanoTime()
            captureAdmissionOpen.set(true)
            Log.i("BootResumeActivity", "visible-admission after-draw elapsedMs=${SystemClock.elapsedRealtime()}")
            binding = serviceUiOwner.bind(this@BootResumeActivity, this@BootResumeActivity, root,
                isAlive = { !closed && admission.withinDeadline(SystemClock.elapsedRealtime()) && visible() },
                callbacks = object : CaptureServiceUiOwner.BindingCallbacks {
                    override fun connected(connectedService: ReverbService) {
                        if (!admission.current(SystemClock.elapsedRealtime()) || !visible()) { closeAttempt(); return }
                        foreground = serviceUiOwner.foregroundLease(connectedService,
                            this@BootResumeActivity, root, foregroundOwner)
                        if (test == null) confirmCapture(connectedService)
                        else connectedService.requestRestartTest(test, {
                            captureAdmissionOpen.get() && admission.withinDeadline(SystemClock.elapsedRealtime())
                        }) { result ->
                            when (result) {
                                is RestartTestResult.Started -> if (visible()) {
                                    testControlEpoch = result.admission.testControlEpoch
                                    confirmCapture(connectedService, result.admission.foregroundEpoch)
                                } else closeAttempt()
                                is RestartTestResult.Rejected -> {
                                    if (visible() && result.controlEpoch == restartTestControlEpoch())
                                        AppFeedbackCenter.post(result.message ?: getString(R.string.boot_resume_test_rejected),
                                            FeedbackTone.ERROR)
                                    closeAttempt()
                                }
                                RestartTestResult.Superseded -> closeAttempt()
                            }
                        }
                    }
                    override fun unavailable(markSavingAsCancelRequested: Boolean) { closeAttempt(reportFailure = true) }
                    override fun stopped() = Unit
                })
        }
    }

    private fun launchCurrent(): Boolean = launch?.current(startedMains.visibilityRevision) == true

    private fun confirmCapture(service: ReverbService, epoch: Long? = null) {
        confirmation = service.confirmBootResumeCapture(startedAfterNanos, epoch) {
            if (admission.current(SystemClock.elapsedRealtime()) && visible() &&
                (testControlEpoch == null || testControlEpoch == restartTestControlEpoch())) {
                Log.i("BootResumeActivity", "finish-after-confirmed-pcm elapsedMs=${SystemClock.elapsedRealtime()}")
                closeAttempt()
            }
        }
    }

    private fun closeAttempt(reportFailure: Boolean = false) {
        if (closed) return
        closed = true
        val currentTest = testControlEpoch?.let { it == restartTestControlEpoch() }
            ?: (launch?.test?.let(::restartTestCurrent) == true)
        if (reportFailure && currentTest) reportTestFailure(applicationContext)
        admission.abandon()
        releaseOwners()
        finish()
    }

    private fun openReverb() {
        // A user action retires the automatic attempt even if its ordinary app launch is rejected.
        closed = true
        admission.abandon()
        releaseOwners()
        try {
            startActivity(Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            finish()
        } catch (error: ActivityNotFoundException) {
            root.showOpenFailure(error)
        } catch (error: SecurityException) {
            root.showOpenFailure(error)
        }
    }

    private fun releaseOwners() {
        captureAdmissionOpen.set(false)
        launch?.test?.let { restartTests.retire(it) }
        checkJob?.cancel(); checkJob = null
        confirmation?.close(); confirmation = null
        foreground?.close(); foreground = null
        binding?.close(); binding = null
        if (::root.isInitialized) {
            root.removeCallbacks(timeout)
            if (root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnDrawListener(drawListener)
        }
    }

    companion object {
        private const val EXTRA_LAUNCH = "boot-resume-launch"
        private const val TIMEOUT_MILLIS = 15_000L
        private val pendingLaunch = AtomicReference<BootResumeLaunch?>()
        private val restartTests = RestartTestRegistry()
        private val pendingTestDeparture = AtomicReference<Runnable?>()
        private val testHandler = Handler(Looper.getMainLooper())
        private val testScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val startedMains = StartedMainOwners()

        internal fun restartTestArmed(): Boolean = restartTests.current()?.available(SystemClock.elapsedRealtime()) == true
        internal fun restartTestControlEpoch(): Long = restartTests.revision()
        internal fun restartTestCurrent(test: RestartTestArm): Boolean =
            restartTests.current() === test && test.available(SystemClock.elapsedRealtime())
        internal fun claimRestartTest(test: RestartTestArm, stopped: Boolean): Boolean =
            restartTests.claim(test, SystemClock.elapsedRealtime(), stopped)
        internal fun restartPrerequisitesAvailable(context: Context): Boolean =
            storageUnlocked(context) && permissionsAvailable(context)

        internal fun cancelRestartTest() { invalidateRestartTest() }

        internal fun invalidateRestartTest(): Long {
            val epoch = restartTests.invalidate()
            pendingTestDeparture.getAndSet(null)?.let(testHandler::removeCallbacks)
            return epoch
        }

        internal suspend fun armRestartTest(context: Context): Boolean {
            val epoch = invalidateRestartTest()
            val token = RestartTestArm(SystemClock.elapsedRealtime())
            return try { withContext(Dispatchers.IO) {
                synchronized(captureIntentPreferenceLock) {
                    if (!startedMains.anyStarted || !storageUnlocked(context) || !permissionsAvailable(context) ||
                        readDurableCaptureIntentPreferences(getRecorderPreferences(context)).enabled) return@synchronized false
                    // Lifecycle cancellation never waits on this disk-held monitor. A delayed read
                    // must not recreate authority after Main returned or another control won.
                    restartTests.publish(epoch, token) { startedMains.anyStarted }
                }
            } } catch (error: CancellationException) {
                restartTests.retire(token)
                throw error
            }
        }

        internal fun mainStarted(owner: Any, visible: () -> Boolean) {
            startedMains.start(owner, visible)
            cancelRestartTest()
        }

        internal fun mainVisibilityChanged(owner: Any) {
            if (startedMains.contains(owner))
                retirePendingBootLaunch(pendingLaunch, startedMains.refreshVisibility())
        }

        internal fun mainStopped(owner: Any, context: Context) {
            if (!startedMains.stop(owner)) return
            val test = restartTests.current() ?: return
            val app = context.applicationContext
            // Experiment only: leave the recent foreground grace period before exercising BAL.
            // Android/OEM policy still decides admission; this delay is not a permission guarantee.
            val departure = Runnable {
                pendingTestDeparture.set(null)
                testScope.launch {
                    if (!startedMains.anyStarted && restartTests.current() === test && test.available(SystemClock.elapsedRealtime())) {
                        try {
                            launch(app, { !startedMains.anyStarted && restartTestCurrent(test) }, test)
                        } catch (error: Exception) {
                            test.cancel()
                            RecordingIncidentStore.recordUnexpectedErrorInBackground(app, "Read restart test intent", error)
                            reportTestFailure(app, error)
                        }
                    }
                }
            }
            pendingTestDeparture.getAndSet(departure)?.let(testHandler::removeCallbacks)
            testHandler.postDelayed(departure, 30_000L)
            if (!test.available(SystemClock.elapsedRealtime())) testHandler.removeCallbacks(departure)
        }

        private fun storageUnlocked(context: Context): Boolean =
            context.getSystemService(UserManager::class.java)?.isUserUnlocked == true

        private fun reportTestFailure(context: Context, error: Throwable? = null) {
            val message = context.getString(R.string.boot_resume_test_failed)
            AppFeedbackCenter.post(if (error == null) message else actionableErrorMessage(context, message, error),
                FeedbackTone.ERROR)
        }

        internal fun overlayAvailable(context: Context): Boolean = runCatching {
            Settings.canDrawOverlays(context)
        }.getOrDefault(false)

        internal fun overlayReviewIntent(context: Context): Intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.fromParts("package", context.packageName, null))

        private fun permissionsAvailable(context: Context): Boolean = overlayAvailable(context) &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        internal fun launch(context: Context, allowed: () -> Boolean) = launch(context, allowed, null)

        private fun launch(context: Context, allowed: () -> Boolean, test: RestartTestArm?) {
            if (!allowed()) return
            if (!storageUnlocked(context) || !permissionsAvailable(context) ||
                readDurableCaptureIntentPreferences(getRecorderPreferences(context)).enabled != (test == null)) {
                if (test != null && restartTestCurrent(test)) { restartTests.retire(test); reportTestFailure(context) }
                return
            }
            val token = BootResumeLaunch(UUID.randomUUID().toString(), SystemClock.elapsedRealtime(), allowed, test,
                startedMains.visibilityRevision)
            if (!pendingLaunch.compareAndSet(null, token)) return
            testHandler.postDelayed({
                if (pendingLaunch.compareAndSet(token, null) && test != null && restartTestCurrent(test)) {
                    restartTests.retire(test)
                    reportTestFailure(context)
                }
            }, TIMEOUT_MILLIS)
            testHandler.post {
                // Recheck live View/lifecycle state on main after the durable read. STARTED alone
                // is not visible authority: a locked or occluded Main still needs the helper route.
                retirePendingBootLaunch(pendingLaunch, startedMains.refreshVisibility())
                if (!allowed() || pendingLaunch.get() !== token) {
                    pendingLaunch.compareAndSet(token, null)
                    return@post
                }
                try {
                    context.startActivity(Intent(context, BootResumeActivity::class.java)
                        .putExtra(EXTRA_LAUNCH, token.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (error: Exception) {
                    pendingLaunch.compareAndSet(token, null)
                    if (test != null && restartTestCurrent(test)) { restartTests.retire(test); reportTestFailure(context, error) }
                    if (error !is SecurityException && error !is android.content.ActivityNotFoundException)
                        RecordingIncidentStore.recordUnexpectedErrorInBackground(context, "Open boot recording window", error)
                }
            }
        }
    }
}

internal data class BootResumeLaunch(val id: String, val createdAtMillis: Long, val allowed: () -> Boolean,
    val test: RestartTestArm?, val mainVisibilityRevision: Long = 0L) {
    fun current(visibleMainRevision: Long): Boolean = allowed() && mainVisibilityRevision == visibleMainRevision
}

internal fun retirePendingBootLaunch(pending: AtomicReference<BootResumeLaunch?>, mainVisible: Boolean) {
    if (!mainVisible) return
    val candidate = pending.get()?.takeIf { it.test == null } ?: return
    pending.compareAndSet(candidate, null)
}

internal fun mainWindowCanResumeCapture(resumed: Boolean, attached: Boolean, shown: Boolean,
    focused: Boolean, interactive: Boolean, keyguardLocked: Boolean): Boolean =
    resumed && attached && shown && focused && interactive && !keyguardLocked

/** Process-only explicit permission for one OFF-to-ON experiment, never reboot authority. */
internal class RestartTestArm(private val createdAtMillis: Long) {
    private val active = AtomicBoolean(true)
    fun available(nowMillis: Long): Boolean = active.get() && nowMillis - createdAtMillis in 0..120_000L
    fun cancel() { active.set(false) }
    // Caller holds the capture intent transaction monitor through the canonical Start.
    fun consume(nowMillis: Long, stopped: Boolean): Boolean =
        stopped && available(nowMillis) && active.compareAndSet(true, false)
}

internal class RestartTestRegistry(
    private val cancelToken: (RestartTestArm) -> Unit = RestartTestArm::cancel,
) {
    private val epoch = AtomicLong()
    private val arm = AtomicReference<RestartTestArm?>()
    fun current(): RestartTestArm? = arm.get()
    fun revision(): Long = epoch.get()
    fun invalidate(): Long {
        val next = epoch.incrementAndGet()
        arm.getAndSet(null)?.let(cancelToken)
        return next
    }
    fun retire(token: RestartTestArm) {
        if (arm.compareAndSet(token, null)) cancelToken(token)
    }
    fun claim(token: RestartTestArm, nowMillis: Long, stopped: Boolean): Boolean {
        if (!stopped || !token.available(nowMillis) || !arm.compareAndSet(token, null)) return false
        // This CAS shares cancellation's removal boundary. Once claimed, the command is
        // admitted: later UI departure retires feedback, while a later recorder Stop still wins.
        return token.consume(nowMillis, stopped = true)
    }
    // Publication callers hold the capture intent monitor; cancellation stays nonblocking.
    fun publish(expectedEpoch: Long, token: RestartTestArm, mainStarted: () -> Boolean): Boolean {
        if (expectedEpoch != epoch.get() || !mainStarted()) return false
        arm.set(token)
        if (expectedEpoch == epoch.get() && mainStarted()) return true
        retire(token)
        return false
    }
}

internal data class RestartTestAdmission(val foregroundEpoch: Long, val testControlEpoch: Long)

internal sealed interface RestartTestResult {
    data class Started(val admission: RestartTestAdmission) : RestartTestResult
    data class Rejected(val controlEpoch: Long, val message: String? = null) : RestartTestResult
    data object Superseded : RestartTestResult
}

internal fun currentRestartTestResult(result: RestartTestResult, controlEpoch: Long,
    foregroundEpoch: Long, visible: Boolean, healthy: Boolean): RestartTestResult {
    if (!visible) return RestartTestResult.Superseded
    return when (result) {
        is RestartTestResult.Started -> if (healthy && result.admission.foregroundEpoch == foregroundEpoch &&
            result.admission.testControlEpoch == controlEpoch) result else RestartTestResult.Superseded
        is RestartTestResult.Rejected -> if (result.controlEpoch == controlEpoch) result else RestartTestResult.Superseded
        RestartTestResult.Superseded -> result
    }
}

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
    onOpenReverb: () -> Unit,
) : LinearLayout(context) {
    private val controls = ControlPalette(palette.surface, palette.surfaceContainer, palette.onBackground,
        palette.onSurfaceVariant, palette.primary, palette.onPrimary, palette.primaryContainer, palette.outline)
    private val detail = NativeText(context, controls, NativeTextStyle(16f, lineHeightSp = 24f))
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
        column.addView(NativeText(context, controls, NativeTextStyle(28f, lineHeightSp = 36f)).apply {
            text = context.getString(R.string.boot_resume_title)
            gravity = Gravity.CENTER
            isAccessibilityHeading = true
        })
        column.addView(detail.apply {
            text = context.getString(R.string.boot_resume_in_progress)
            gravity = Gravity.CENTER
            setPadding(0, padding / 2, 0, padding / 2)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        })
        column.addView(progress)
        column.addView(NativeButton(context, controls,
            NativeButtonStyle(tone = NativeButtonTone.FILLED, fullCorner = true, minHeightDp = 48f)).apply {
            text = context.getString(R.string.boot_resume_open_app)
            setOnClickListener { onOpenReverb() }
        }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = padding / 2 })
        addView(ScrollView(context).apply {
            isFillViewport = true
            addView(column, android.widget.FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun showOpenFailure(error: Throwable) {
        detail.text = actionableErrorMessage(context, context.getString(R.string.boot_resume_open_failed), error)
        progress.visibility = View.GONE
    }
}

internal class BootResumeActivityAdmission(
    private val createdAtMillis: Long = 0L,
    private val timeoutMillis: Long = 15_000L,
) {
    var admitted = false
        private set
    private var abandoned = false
    private var foregroundObserved = false
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
    fun abandon() { abandoned = true }
}
