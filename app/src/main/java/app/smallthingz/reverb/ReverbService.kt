package app.smallthingz.reverb

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Log

import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.io.InterruptedIOException
import java.io.PrintWriter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

@SuppressLint("ImplicitSamInstance")
class ReverbService : Service() {
    @Volatile
    private var sampleRate = ReverbConfig.PREFERRED_DEFAULT_SAMPLE_RATE

    @Volatile
    private var fillRate = 96_000L

    @Volatile
    private var audioSource = AudioSourceMode.defaultMode().sourceValue

    @Volatile
    private var sourceMode = AudioSourceMode.defaultMode()

    @Volatile
    private var channelMode = ChannelMode.MONO

    @Volatile
    private var pcmSampleFormat = PcmSampleFormat.PCM_16

    @Volatile
    private var outputFormat = ExportFormat.WAV

    @Volatile
    private var outputCodec = ExportCodec.PCM_16

    @Volatile
    private var inputRouteMode = InputRouteMode.AUTO

    @Volatile
    private var state = STATE_READY

    @Volatile
    private var audioRecord: AudioRecord? = null

    @Volatile
    private var activeExportToken: ExportCancellationToken? = null

    @Volatile
    private var activeExportFuture: Future<*>? = null

    @Volatile
    private var activeExportReceiver: AudioFileReceiver? = null

    private val exportStateLock = Any()

    @Volatile
    private var cachedRetentionSampleBytes = 0L

    @Volatile
    private var cachedConfigSnapshot: RecorderConfigurationSnapshot? = null

    @Volatile
    private var visualizationCallback: VisualizationCallback? = null

    private val captureScratch = ByteArray(CAPTURE_SCRATCH_BYTES)
    private val captureBuffer = ByteBuffer.allocateDirect(CAPTURE_SCRATCH_BYTES)
        .order(ByteOrder.nativeOrder())
    private val visualizationAnalyzer = AudioVisualizationAnalyzer()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pendingVisualizationFrame = AtomicReference<VisualizationFrame?>(null)
    private val visualizationDispatchScheduled = AtomicBoolean(false)
    private var visualizationFaulted = false
    private var lastVisualizationAnalysisNanos = 0L

    private lateinit var audioThread: HandlerThread
    private lateinit var audioHandler: Handler
    private lateinit var exportWorkExecutor: ExecutorService
    private lateinit var loopingAudioChunkStore: PersistentAudioChunkStore
    private lateinit var oneShotAudioChunkStore: PersistentAudioChunkStore

    @Volatile private var wakeLock: PowerManager.WakeLock? = null

    private val pendingError = AtomicReference<String?>(null)

    override fun onCreate() {
        super.onCreate()
        loopingAudioChunkStore = PersistentAudioChunkStore(this)
        oneShotAudioChunkStore = PersistentAudioChunkStore(
            this,
            cacheFolderName = ReverbConfig.ONE_SHOT_BUFFER_CACHE_FOLDER_NAME,
            legacyCacheFolderName = null,
        )
        createNotificationChannel()
        audioThread = HandlerThread(ReverbConfig.THREAD_NAME_AUDIO, Process.THREAD_PRIORITY_AUDIO)
            .also { it.start() }
        audioHandler = Handler(audioThread.looper)
        exportWorkExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, ReverbConfig.THREAD_NAME_EXPORT_WORK).apply {
                priority = Thread.NORM_PRIORITY
                isDaemon = true
            }
        }
        audioHandler.post {
            try {
                loadConfiguredPreferences()
                configurePersistentBuffer()
            } catch (error: Exception) {
                reportPersistentStoreFailure("initialize", error)
                if (isListeningEnabled()) {
                    failListeningOnAudioThread(getString(R.string.recorder_state_persist_failed), error)
                }
                return@post
            }
            mainHandler.post {
                if (isListeningEnabled()) {
                    innerStartListening()
                }
            }
        }
    }

    override fun onDestroy() {
        visualizationCallback = null
        pendingVisualizationFrame.set(null)
        mainHandler.removeCallbacks(visualizationDispatcher)
        visualizationDispatchScheduled.set(false)
        cancelCurrentExport()
        flushAndPersistBeforeShutdown()
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)

        if (::exportWorkExecutor.isInitialized) {
            exportWorkExecutor.shutdownNow()
            try {
                exportWorkExecutor.awaitTermination(2, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        audioThread.quitSafely()
        super.onDestroy()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        audioHandler.post { checkpointPersistentStore("trim-memory checkpoint") }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        audioHandler.post { checkpointPersistentStore("low-memory checkpoint") }
    }

    override fun onBind(intent: Intent): IBinder = BackgroundRecorderBinder()

    override fun onUnbind(intent: Intent): Boolean {
        setVisualizationCallback(null)
        return true
    }

    override fun dump(
        fd: FileDescriptor,
        writer: PrintWriter,
        args: Array<out String>,
    ) {
        if (!isDebuggableBuild()) {
            super.dump(fd, writer, args)
            return
        }
        val persisted = if (::loopingAudioChunkStore.isInitialized) {
            runCatching { loopingAudioChunkStore.peekSnapshot() }.getOrNull()
        } else {
            null
        }
        val oneShot = if (::oneShotAudioChunkStore.isInitialized) {
            runCatching { oneShotAudioChunkStore.peekSnapshot() }.getOrNull()
        } else {
            null
        }
        writer.println("ReverbService")
        writer.println("  state=$state")
        writer.println("  listeningEnabled=${isListeningEnabled()}")
        writer.println("  sampleRate=$sampleRate")
        writer.println("  channelCount=${channelMode.channelCount}")
        writer.println("  format=${outputFormat.prefValue}")
        writer.println("  codec=${outputCodec.prefValue}")
        writer.println("  fillRate=$fillRate")
        writer.println("  exportDir=${describeConfiguredOutputDirectory(this)}")
        writer.println(
            "  persisted filled=${persisted?.filledBytes ?: 0} duration=${persisted?.durationSeconds ?: 0.0} " +
                "chunks=${persisted?.chunkCount ?: 0} sampleRate=${persisted?.currentSampleRate ?: 0} " +
                "channelCount=${persisted?.currentChannelCount ?: 0} " +
                "lastWrite=${persisted?.lastWriteAtMillis ?: 0}",
        )
        writer.println(
            "  oneShot filled=${oneShot?.filledBytes ?: 0} duration=${oneShot?.durationSeconds ?: 0.0} " +
                "chunks=${oneShot?.chunkCount ?: 0}",
        )
        writer.println("  rawHistoryDirectory=${ReverbConfig.BUFFER_CACHE_FOLDER_NAME}/${ReverbConfig.BUFFER_CHUNKS_FOLDER_NAME}")
    }

    fun enableListening() {
        val persisted = getRecorderPreferences(this).edit()
            .putBoolean(PrefKey.AUDIO_MEMORY_ENABLED, true)
            .commit()
        if (!persisted) {
            reportError(getString(R.string.recorder_state_persist_failed))
            return
        }
        innerStartListening()
    }

    fun disableListening() {
        val persisted = getRecorderPreferences(this).edit()
            .putBoolean(PrefKey.AUDIO_MEMORY_ENABLED, false)
            .commit()
        if (!persisted) {
            reportError(getString(R.string.recorder_state_persist_failed))
            return
        }
        innerStopListening()
    }

    private fun isListeningEnabled(): Boolean {
        return getRecorderPreferences(this).getBoolean(PrefKey.AUDIO_MEMORY_ENABLED, false)
    }

    private fun loadConfiguredPreferences() {
        sourceMode = getConfiguredAudioSourceMode(this)
        channelMode = getConfiguredChannelMode(this)
        inputRouteMode = getConfiguredInputRouteMode(this)
        outputFormat = getConfiguredOutputFormat(this)
        outputCodec = getConfiguredOutputCodec(this)
        pcmSampleFormat = getConfiguredPcmSampleFormat(this)
        sampleRate = getConfiguredSampleRate(this).takeIf { it > 0 }
            ?: ReverbConfig.PREFERRED_DEFAULT_SAMPLE_RATE
        audioSource = sourceMode.sourceValue
        fillRate = sampleRate.toLong() * channelMode.channelCount * pcmSampleFormat.bytesPerSample
        publishConfigurationSnapshot()
        refreshCachedBufferSizing()
    }

    private fun loadConfiguration() {
        var selectedSourceMode = getConfiguredAudioSourceMode(this)
        var selectedChannelMode = getConfiguredChannelMode(this)
        var selectedRouteMode = getConfiguredInputRouteMode(this)
        var selectedFormat = getConfiguredOutputFormat(this)
        var selectedCodec = getConfiguredOutputCodec(this)
        val selectedSampleFormat = getConfiguredPcmSampleFormat(this)

        val requestedRate = getConfiguredSampleRate(this)
        val resolvedConfig = resolveOperationalConfiguration(
            preferredSourceMode = selectedSourceMode,
            preferredChannelMode = selectedChannelMode,
            preferredRouteMode = selectedRouteMode,
            preferredFormat = selectedFormat,
            preferredCodec = selectedCodec,
            preferredRate = requestedRate,
            preferredSampleFormat = selectedSampleFormat,
        )

        if (resolvedConfig != null) {
            selectedSourceMode = resolvedConfig.sourceMode
            selectedChannelMode = resolvedConfig.channelMode
            selectedRouteMode = resolvedConfig.routeMode
            selectedFormat = resolvedConfig.format
            selectedCodec = resolvedConfig.codec
        }

        sampleRate = resolvedConfig?.sampleRate ?: 48_000
        pcmSampleFormat = resolvedConfig?.sampleFormat ?: PcmSampleFormat.PCM_16
        fillRate = sampleRate.toLong() * selectedChannelMode.channelCount * pcmSampleFormat.bytesPerSample
        sourceMode = selectedSourceMode
        channelMode = selectedChannelMode
        audioSource = selectedSourceMode.sourceValue
        outputFormat = selectedFormat
        outputCodec = selectedCodec
        inputRouteMode = selectedRouteMode
        publishConfigurationSnapshot()
        refreshCachedBufferSizing()
    }

    private fun publishConfigurationSnapshot() {
        cachedConfigSnapshot = RecorderConfigurationSnapshot(
            format = outputFormat,
            codec = outputCodec,
            sampleFormat = pcmSampleFormat,
            sampleRate = sampleRate,
            sourceMode = sourceMode,
            channelMode = channelMode,
            routeMode = inputRouteMode,
        )
    }

    private fun refreshCachedBufferSizing() {
        cachedRetentionSampleBytes = getConfiguredMemorySizeBytes(
            context = this,
            sampleRate = sampleRate,
            channelMode = channelMode,
            sampleFormat = pcmSampleFormat,
        )
    }

    private fun resolveOperationalConfiguration(
        preferredSourceMode: AudioSourceMode,
        preferredChannelMode: ChannelMode,
        preferredRouteMode: InputRouteMode,
        preferredFormat: ExportFormat,
        preferredCodec: ExportCodec,
        preferredRate: Int,
        preferredSampleFormat: PcmSampleFormat,
    ): OperationalConfig? {
        val formatCandidates = buildList {
            add(preferredFormat); val formats = supportedFormats()
            for (f in formats) if (f != preferredFormat) add(f)
        }
        val routeCandidates = buildList {
            add(preferredRouteMode); val modes = supportedInputRouteModes(this@ReverbService)
            for (m in modes) if (m != preferredRouteMode) add(m)
        }
        val sourceCandidates = buildList {
            add(preferredSourceMode); val modes = AudioSourceMode.availableModes()
            for (m in modes) if (m != preferredSourceMode) add(m)
        }
        val channelCandidates = buildList {
            add(preferredChannelMode)
            for (m in ChannelMode.entries) if (m != preferredChannelMode) add(m)
        }
        val sampleFormatCandidates = buildList {
            add(preferredSampleFormat)
            for (m in PcmSampleFormat.entries) if (m != preferredSampleFormat) add(m)
        }

        formatCandidates.forEach { format ->
            val codecCandidates = listOf(preferredCodec) + supportedCodecs(format).filter { it != preferredCodec }
            codecCandidates.forEach { codec ->
                routeCandidates.forEach { routeMode ->
                    sourceCandidates.forEach { sourceMode ->
                        channelCandidates.forEach { channelMode ->
                            sampleFormatCandidates.forEach { sampleFormat ->
                                val sampleRate = resolveOperationalSampleRate(
                                    this,
                                    preferredRate,
                                    sourceMode,
                                    routeMode,
                                    format,
                                    codec,
                                    channelMode,
                                    sampleFormat,
                                )
                                if (sampleRate > 0 && isCodecSupported(format, codec, sampleRate, channelMode)) {
                                    return OperationalConfig(
                                        sourceMode = sourceMode,
                                        channelMode = channelMode,
                                        routeMode = routeMode,
                                        format = format,
                                        codec = codec,
                                        sampleFormat = sampleFormat,
                                        sampleRate = sampleRate,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        return null
    }

    private fun innerStartListening() {
        when (state) {
            STATE_LISTENING -> return
            STATE_READY, STATE_PAUSED -> Unit
            else -> return
        }

        state = STATE_LISTENING
        updateWakeLockState()
        try {
            ContextCompat.startForegroundService(this, Intent(this, javaClass))
        } catch (error: RuntimeException) {
            Log.e(TAG, "Unable to start recorder foreground service", error)
            failListeningStart()
            return
        }
        audioHandler.post { startAudioInputOnAudioThread() }
    }

    private fun failListeningStart() {
        getRecorderPreferences(this).edit()
            .putBoolean(PrefKey.AUDIO_MEMORY_ENABLED, false)
            .commit()
        state = STATE_READY
        updateWakeLockState()
        reportError(getString(R.string.audio_input_init_failed))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startAudioInputOnAudioThread() {
        check(audioHandler.looper == Looper.myLooper())
        audioHandler.removeCallbacks(audioReader)
        releaseAudioRecord()
        if (state != STATE_LISTENING || !isListeningEnabled()) return
        // Binding the paused UI should not probe microphone hardware. Resolve the
        // requested configuration only when capture is actually about to start.
        try {
            loadConfiguration()
            configurePersistentBuffer()
        } catch (error: Exception) {
            reportPersistentStoreFailure("configure before capture", error)
            failListeningOnAudioThread(getString(R.string.recorder_state_persist_failed), error)
            return
        }

        val record = createAudioRecord()
        audioRecord = record
        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            failListeningOnAudioThread(getString(R.string.audio_input_init_failed), null)
            return
        }

        try {
            record.startRecording()
        } catch (error: RuntimeException) {
            Log.e(TAG, "AudioRecord.startRecording failed", error)
            failListeningOnAudioThread(getString(R.string.audio_input_init_failed), error)
            return
        }
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            failListeningOnAudioThread(getString(R.string.audio_input_init_failed), null)
            return
        }
        audioHandler.post(audioReader)
    }

    private fun innerStopListening() {
        when (state) {
            STATE_READY -> return
            STATE_LISTENING, STATE_PAUSED -> Unit
            else -> return
        }
        audioHandler.post {
            audioHandler.removeCallbacks(audioReader)
            state = STATE_READY
            updateWakeLockState()
            try {
                sealActiveChunks()
            } catch (error: Exception) {
                reportPersistentStoreFailure("seal while stopping", error)
            } finally {
                releaseAudioRecord()
                mainHandler.post {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun createAudioRecord(): AudioRecord? {
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            channelMode.inputChannelMask,
            pcmSampleFormat.audioEncoding,
        )
        if (minBuffer <= 0) {
            Log.e(TAG, "AudioRecord min buffer invalid for $sampleRate Hz")
            return null
        }

        return try {
            val record = AudioRecord.Builder()
                .setAudioSource(audioSource)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(pcmSampleFormat.audioEncoding)
                        .setChannelMask(channelMode.inputChannelMask)
                        .setSampleRate(sampleRate)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(minBuffer * 2, MIN_AUDIO_RECORD_BUFFER_SIZE))
                .build()
            if (inputRouteMode == InputRouteMode.BUILTIN_MIC) {
                val builtInMic = findBuiltInMicrophone(this)
                val routeAccepted = builtInMic != null &&
                    runCatching { record.setPreferredDevice(builtInMic) }.getOrDefault(false)
                if (!routeAccepted) {
                    runCatching { record.release() }
                    return null
                }
            }
            record
        } catch (error: Exception) {
            Log.e(TAG, "Unable to create AudioRecord", error)
            null
        }
    }

    private fun releaseAudioRecord() {
        val record = audioRecord ?: return
        audioRecord = null
        runCatching { record.stop() }
        runCatching { record.release() }
            .onFailure { Log.w(TAG, "AudioRecord.release failed", it) }
    }

    fun dumpRecording(
        memorySeconds: Float,
        receiver: AudioFileReceiver,
        newFileName: String,
        bufferSlot: BufferSlot = BufferSlot.LOOPING,
    ) {
        val exportToken = beginExport(receiver) ?: run {
            notifyReceiverFailure(receiver, getString(R.string.export_in_progress))
            return
        }

        if (!audioHandler.post {
            try {
                if (!isExportPending(exportToken)) return@post
                flushAudioRecord()
                if (!isExportPending(exportToken)) return@post

                val store = chunkStore(bufferSlot)
                val totalDuration = availableBufferedDurationSeconds(bufferSlot)
                val requestedDuration = memorySeconds.toDouble().coerceAtLeast(0.0)
                val end = totalDuration
                val start = maxOf(0.0, end - requestedDuration)
                val lease = acquireExportRange(store, start, end)
                if (lease == null) {
                    clearExportState(exportToken)
                    finishExportFailure(exportToken, receiver, getString(R.string.nothing_to_export))
                    return@post
                }
                exportBufferedRange(lease, receiver, newFileName, exportToken)
            } catch (error: Exception) {
                reportPersistentStoreFailure("prepare export", error)
                clearExportState(exportToken)
                finishExportFailure(exportToken, receiver, getString(R.string.save_failed), error)
            }
        }) {
            clearExportState(exportToken)
            finishExportFailure(exportToken, receiver, getString(R.string.save_failed))
        }
    }

    fun dumpRecordingRange(
        startOffsetSeconds: Float,
        endOffsetSeconds: Float,
        receiver: AudioFileReceiver,
        newFileName: String,
        bufferSlot: BufferSlot = BufferSlot.LOOPING,
    ) {
        val exportToken = beginExport(receiver) ?: run {
            notifyReceiverFailure(receiver, getString(R.string.export_in_progress))
            return
        }

        if (!audioHandler.post {
            try {
                if (!isExportPending(exportToken)) return@post
                flushAudioRecord()
                if (!isExportPending(exportToken)) return@post

                val store = chunkStore(bufferSlot)
                val totalDuration = availableBufferedDurationSeconds(bufferSlot)
                val boundedStart = startOffsetSeconds.toDouble().coerceIn(0.0, totalDuration)
                val boundedEnd = endOffsetSeconds.toDouble().coerceIn(boundedStart, totalDuration)
                val lease = acquireExportRange(store, boundedStart, boundedEnd)
                if (lease == null) {
                    clearExportState(exportToken)
                    finishExportFailure(exportToken, receiver, getString(R.string.nothing_to_export))
                    return@post
                }
                exportBufferedRange(lease, receiver, newFileName, exportToken)
            } catch (error: Exception) {
                reportPersistentStoreFailure("prepare range export", error)
                clearExportState(exportToken)
                finishExportFailure(exportToken, receiver, getString(R.string.save_failed), error)
            }
        }) {
            clearExportState(exportToken)
            finishExportFailure(exportToken, receiver, getString(R.string.save_failed))
        }
    }

    fun acquireTimelineSnapshot(
        bufferSlot: BufferSlot = BufferSlot.LOOPING,
        callback: (TimelineSnapshot?) -> Unit,
    ) {
        if (!audioHandler.post {
            val snapshot = try {
                flushAudioRecord()
                val duration = availableBufferedDurationSeconds(bufferSlot)
                if (duration > 0.0) {
                    chunkStore(bufferSlot).acquireRange(0.0, duration)?.let(::TimelineSnapshot)
                } else {
                    null
                }
            } catch (error: Exception) {
                reportPersistentStoreFailure("acquire timeline snapshot", error)
                null
            }
            mainHandler.post { callback(snapshot) }
        }) {
            mainHandler.post { callback(null) }
        }
    }

    fun dumpRecordingRange(
        snapshot: TimelineSnapshot,
        startOffsetSeconds: Float,
        endOffsetSeconds: Float,
        receiver: AudioFileReceiver,
        newFileName: String,
    ) {
        val exportToken = beginExport(receiver)
        if (exportToken == null) {
            snapshot.close()
            notifyReceiverFailure(receiver, getString(R.string.export_in_progress))
            return
        }

        val exportConfig = getConfigurationSnapshot()
        val lease = try {
            val totalDuration = snapshot.durationSeconds
            val boundedStart = startOffsetSeconds.toDouble().coerceIn(0.0, totalDuration)
            val boundedEnd = endOffsetSeconds.toDouble().coerceIn(boundedStart, totalDuration)
            val targetBytesPerSecond = exportConfig.sampleRate.toLong() *
                exportConfig.channelMode.channelCount.toLong() *
                exportConfig.sampleFormat.bytesPerSample.toLong()
            val maxDuration = exportPayloadLimitBytes(exportConfig.format).toDouble() /
                targetBytesPerSecond.coerceAtLeast(1L).toDouble()
            val clampedStart = maxOf(boundedStart, boundedEnd - maxDuration)
            snapshot.acquireRange(clampedStart, boundedEnd)
        } finally {
            snapshot.close()
        }
        if (lease == null) {
            clearExportState(exportToken)
            finishExportFailure(exportToken, receiver, getString(R.string.nothing_to_export))
            return
        }
        exportBufferedRange(lease, receiver, newFileName, exportToken, exportConfig)
    }

    private fun acquireExportRange(
        store: PersistentAudioChunkStore,
        requestedStartSeconds: Double,
        requestedEndSeconds: Double,
    ): PersistentAudioChunkStore.RangeLease? {
        val targetBytesPerSecond = fillRate.coerceAtLeast(1L).toDouble()
        val maxDuration = exportPayloadLimitBytes(outputFormat).toDouble() / targetBytesPerSecond
        val end = requestedEndSeconds.coerceAtLeast(requestedStartSeconds)
        val start = maxOf(requestedStartSeconds, end - maxDuration)
        return store.acquireRange(start, end)
    }

    fun cancelCurrentExport(): Boolean {
        var receiverToNotify: AudioFileReceiver? = null
        var tokenToNotify: ExportCancellationToken? = null
        val cancelled = synchronized(exportStateLock) {
            val token = activeExportToken ?: return@synchronized false
            if (token.committed.get()) return@synchronized false

            token.cancelled.set(true)
            val future = activeExportFuture
            if (!token.started.get() && (future == null || future.cancel(true))) {
                receiverToNotify = activeExportReceiver
                tokenToNotify = token
                clearExportStateLocked(token)
            } else {
                future?.cancel(true)
            }
            true
        }
        if (cancelled && receiverToNotify != null) {
            finishExportCancelled(tokenToNotify ?: return cancelled, receiverToNotify)
        }
        return cancelled
    }

    private fun exportBufferedRange(
        lease: PersistentAudioChunkStore.RangeLease,
        receiver: AudioFileReceiver,
        newFileName: String,
        exportToken: ExportCancellationToken,
        exportConfig: RecorderConfigurationSnapshot = getConfigurationSnapshot(),
    ) {
        if (!isExportPending(exportToken)) {
            lease.close()
            return
        }
        val startedAtMillis = lease.startedAtMillis
        val exportFormat = exportConfig.format
        val exportCodec = exportConfig.codec
        val exportSampleRate = exportConfig.sampleRate
        val exportChannelMode = exportConfig.channelMode
        val exportSampleFormat = exportConfig.sampleFormat
        val exportFillRate = exportSampleRate.toLong() *
            exportChannelMode.channelCount.toLong() * exportSampleFormat.bytesPerSample.toLong()
        val exportTask =
            object : FutureTask<Unit>(
                Callable {
                    var outTarget: RecordingOutputTarget? = null
                    var committed = false
                    try {
                        ensureExportNotCancelled(exportToken)
                        val target =
                            try {
                                createOutputTarget(
                                    this@ReverbService,
                                    newFileName,
                                    startedAtMillis,
                                    exportFormat,
                                    exportCodec,
                                )
                            } catch (e: Exception) {
                                Log.e(TAG, "Unable to prepare export file", e)
                                val message = userFacingError(getString(R.string.cant_create_file_generic), e)
                                reportError(message)
                                finishExportFailure(exportToken, receiver, message, e)
                                return@Callable Unit
                            }
                        outTarget = target
                        var durationMillis = 0L
                        val writer = WavAudioFileWriter(
                            this@ReverbService,
                            target,
                            exportSampleRate,
                            exportChannelMode.channelCount,
                            exportSampleFormat,
                        )
                        writer.use {
                            lease.readNormalized(
                                targetSampleRate = exportSampleRate,
                                targetChannelCount = exportChannelMode.channelCount,
                                targetSampleFormat = exportSampleFormat,
                            ) { array, offset, count ->
                                ensureExportNotCancelled(exportToken)
                                writer.write(array, offset, count)
                                count
                            }
                            ensureExportNotCancelled(exportToken)
                            if (writer.totalSampleBytesWritten <= 0L) {
                                throw IOException("Requested timeline range produced no PCM")
                            }
                            durationMillis = (
                                writer.totalSampleBytesWritten * 1000.0 / maxOf(exportFillRate, 1L).toDouble()
                            ).toLong()
                        }
                        val expectedOutputBytes = writer.totalFileBytesWritten
                        requireExportedOutput(target, expectedOutputBytes)
                        ensureExportNotCancelled(exportToken)
                        val recording = buildRecordingEntity(
                            this@ReverbService,
                            target,
                            durationMillis,
                            buildCodecSummary(
                                this@ReverbService,
                                exportFormat,
                                exportSampleRate,
                                exportChannelMode.channelCount,
                                exportSampleFormat,
                            ),
                            knownSizeBytes = expectedOutputBytes,
                        )
                        ensureExportNotCancelled(exportToken)
                        committed = markExportCommitted(exportToken)
                        if (!committed) {
                            throw InterruptedIOException("Export cancelled")
                        }
                        notifyReceiver(receiver, recording)
                    } catch (cancelled: InterruptedIOException) {
                        Log.i(TAG, "Export cancelled for ${outTarget?.displayName ?: newFileName}")
                        deleteOutputTarget(outTarget)
                        finishExportCancelled(exportToken, receiver)
                    } catch (e: Exception) {
                        if (exportToken.cancelled.get()) {
                            Log.i(TAG, "Export cancelled for ${outTarget?.displayName ?: newFileName}", e)
                            deleteOutputTarget(outTarget)
                            finishExportCancelled(exportToken, receiver)
                            return@Callable Unit
                        }
                        Log.e(TAG, "Error while exporting audio history into ${outTarget?.displayName ?: newFileName}", e)
                        val message = userFacingError(
                            getString(R.string.error_during_writing_history_into) +
                                (outTarget?.displayName ?: newFileName),
                            e,
                        )
                        reportError(message)
                        finishExportFailure(exportToken, receiver, message, e)
                        deleteOutputTarget(outTarget)
                    } finally {
                        lease.close()
                        if (exportToken.cancelled.get() && !committed) {
                            deleteOutputTarget(outTarget)
                        }
                        clearExportState(exportToken)
                        Thread.interrupted()
                    }
                    Unit
                },
            ) {
                override fun run() {
                    exportToken.started.set(true)
                    super.run()
                }

                override fun done() {
                    if (!exportToken.started.get()) {
                        lease.close()
                    }
                }
            }
        val accepted = synchronized(exportStateLock) {
            if (activeExportToken !== exportToken || exportToken.cancelled.get()) {
                false
            } else {
                activeExportFuture = exportTask
                true
            }
        }
        if (!accepted) {
            lease.close()
            exportTask.cancel(true)
            return
        }
        try {
            exportWorkExecutor.execute(exportTask)
        } catch (e: RejectedExecutionException) {
            lease.close()
            clearExportState(exportToken)
            Log.w(TAG, "Export rejected because the service is shutting down", e)
            finishExportFailure(exportToken, receiver, getString(R.string.save_failed), e)
        }
    }

    private fun beginExport(receiver: AudioFileReceiver): ExportCancellationToken? =
        synchronized(exportStateLock) {
            if (activeExportToken != null) {
                null
            } else {
                ExportCancellationToken(nextExportTokenId.getAndIncrement()).also { token ->
                    activeExportToken = token
                    activeExportFuture = null
                    activeExportReceiver = receiver
                }
            }
        }

    private fun isExportPending(token: ExportCancellationToken): Boolean =
        synchronized(exportStateLock) {
            activeExportToken === token && !token.cancelled.get()
        }

    private fun markExportCommitted(token: ExportCancellationToken): Boolean =
        synchronized(exportStateLock) {
            if (
                activeExportToken !== token ||
                token.cancelled.get() ||
                !token.terminalDelivered.compareAndSet(false, true)
            ) {
                false
            } else {
                token.committed.set(true)
                true
            }
        }

    private fun clearExportState(token: ExportCancellationToken) {
        synchronized(exportStateLock) {
            clearExportStateLocked(token)
        }
    }

    private fun clearExportStateLocked(token: ExportCancellationToken) {
        if (activeExportToken !== token) return
        activeExportToken = null
        activeExportFuture = null
        activeExportReceiver = null
    }

    fun applyUpdatedPreferences() {
        audioHandler.post {
            try {
                applyConfiguredPreferencesOnAudioThread()
            } catch (error: Exception) {
                reportPersistentStoreFailure("apply recorder settings", error)
                if (state == STATE_LISTENING) {
                    failListeningOnAudioThread(getString(R.string.recorder_state_persist_failed), error)
                }
            }
        }
    }

    private fun applyConfiguredPreferencesOnAudioThread() {
        check(audioHandler.looper == Looper.myLooper())
        val newSourceMode = getConfiguredAudioSourceMode(this)
        val newChannelMode = getConfiguredChannelMode(this)
        val newRouteMode = getConfiguredInputRouteMode(this)
        val newFormat = getConfiguredOutputFormat(this)
        val newCodec = getConfiguredOutputCodec(this)
        val newSampleFormat = getConfiguredPcmSampleFormat(this)
        val captureConfigChanged =
            newSourceMode != sourceMode ||
                newChannelMode != channelMode ||
                newRouteMode != inputRouteMode ||
                newFormat != outputFormat ||
                newCodec != outputCodec ||
                getConfiguredSampleRate(this) != sampleRate ||
                newSampleFormat != pcmSampleFormat
        val restartInput = state == STATE_LISTENING && isListeningEnabled() && captureConfigChanged

        if (restartInput) {
            audioHandler.removeCallbacks(audioReader)
            sealActiveChunks()
            releaseAudioRecord()
            startAudioInputOnAudioThread()
        } else {
            loadConfiguredPreferences()
            configurePersistentBuffer()
        }
        updateWakeLockState()
    }

    private fun notifyReceiver(
        receiver: AudioFileReceiver?,
        recording: RecordingEntity,
    ) {
        receiver ?: return
        mainHandler.post { receiver.fileReady(recording) }
    }

    private fun notifyReceiverFailure(
        receiver: AudioFileReceiver?,
        message: String,
        error: Throwable? = null,
    ) {
        receiver ?: return
        mainHandler.post { receiver.fileFailed(message, error) }
    }

    private fun notifyReceiverCancelled(receiver: AudioFileReceiver?) {
        receiver ?: return
        mainHandler.post { receiver.fileCancelled() }
    }

    private fun finishExportFailure(
        token: ExportCancellationToken,
        receiver: AudioFileReceiver?,
        message: String,
        error: Throwable? = null,
    ) {
        if (token.cancelled.get()) {
            finishExportCancelled(token, receiver)
            return
        }
        if (token.terminalDelivered.compareAndSet(false, true)) {
            notifyReceiverFailure(receiver, message, error)
        }
    }

    private fun finishExportCancelled(
        token: ExportCancellationToken,
        receiver: AudioFileReceiver?,
    ) {
        if (token.terminalDelivered.compareAndSet(false, true)) {
            notifyReceiverCancelled(receiver)
        }
    }

    @Throws(InterruptedIOException::class)
    private fun ensureExportNotCancelled(token: ExportCancellationToken) {
        if (token.cancelled.get()) {
            throw InterruptedIOException("Export cancelled")
        }
    }

    private fun deleteOutputTarget(target: RecordingOutputTarget?) {
        if (target == null) {
            return
        }
        runCatching {
            when (target.storageType) {
                RecordingStorageType.FILE -> target.file?.delete()
                RecordingStorageType.DOCUMENT -> {
                    val uri = target.uri ?: return@runCatching
                    androidx.documentfile.provider.DocumentFile.fromSingleUri(this, uri)?.delete()
                }
            }
        }.onFailure { error ->
            Log.w(TAG, "Failed to delete export target ${target.id}", error)
        }
    }

    @Throws(IOException::class)
    private fun requireExportedOutput(target: RecordingOutputTarget, expectedSizeBytes: Long) {
        if (expectedSizeBytes <= 0L) {
            deleteOutputTarget(target)
            throw IOException("Export produced empty output: ${target.displayName}")
        }
        val size = verifyOutputTargetSize(this, target, expectedSizeBytes)
        if (size == expectedSizeBytes) {
            return
        }
        deleteOutputTarget(target)
        throw IOException(
            "Export output size mismatch for ${target.displayName}: expected=$expectedSizeBytes actual=$size",
        )
    }

    private fun alignDown(value: Long, alignment: Long): Long {
        if (value <= 0L || alignment <= 1L) return value.coerceAtLeast(0L)
        return value - value % alignment
    }

    private fun chunkStore(bufferSlot: BufferSlot): PersistentAudioChunkStore {
        return when (bufferSlot) {
            BufferSlot.ONE_SHOT -> oneShotAudioChunkStore
            BufferSlot.LOOPING -> loopingAudioChunkStore
        }
    }

    private fun availableBufferedSampleBytes(bufferSlot: BufferSlot = BufferSlot.LOOPING): Long {
        if (!::loopingAudioChunkStore.isInitialized || !::oneShotAudioChunkStore.isInitialized) return 0L
        return chunkStore(bufferSlot).countFilledBytes()
    }

    private fun availableBufferedDurationSeconds(bufferSlot: BufferSlot = BufferSlot.LOOPING): Double {
        if (!::loopingAudioChunkStore.isInitialized || !::oneShotAudioChunkStore.isInitialized) return 0.0
        return chunkStore(bufferSlot).durationSeconds()
    }

    private fun canExportBufferedAudio(): Boolean {
        return availableBufferedDurationSeconds() > 0.0 || state == STATE_LISTENING || state == STATE_PAUSED
    }

    private fun appendCapturedAudio(array: ByteArray, offset: Int, count: Int) {
        forEachAudioStore { store -> store.append(array, offset, count) }
    }

    private fun sealActiveChunks() {
        forEachAudioStore(PersistentAudioChunkStore::sealActiveChunk)
    }

    private fun checkpointAudioStores(operation: String) {
        try {
            forEachAudioStore(PersistentAudioChunkStore::checkpoint)
        } catch (error: Exception) {
            reportPersistentStoreFailure(operation, error)
        }
    }

    private inline fun forEachAudioStore(action: (PersistentAudioChunkStore) -> Unit) {
        var firstFailure: Exception? = null

        try {
            action(loopingAudioChunkStore)
        } catch (error: Exception) {
            firstFailure = error
        }
        try {
            action(oneShotAudioChunkStore)
        } catch (error: Exception) {
            firstFailure?.addSuppressed(error) ?: run { firstFailure = error }
        }

        firstFailure?.let { throw it }
    }

    private fun flushAudioRecord() {
        check(audioHandler.looper == Looper.myLooper())
        if (audioRecord == null) return
        audioHandler.removeCallbacks(audioReader)
        audioReader.run()
    }

    private fun readCaptureIntoScratch(): Int {
        val currentRecord = audioRecord ?: return 0
        val frameBytes = (channelMode.channelCount * pcmSampleFormat.bytesPerSample).coerceAtLeast(1)
        val targetFrames = maxOf(1L, sampleRate.toLong() * CAPTURE_READ_TARGET_MILLIS / 1000L)
        val requestedBytes = minOf(
            captureScratch.size.toLong(),
            targetFrames * frameBytes.toLong(),
        ).toInt().let { it - it % frameBytes }
        captureBuffer.clear()
        val read = currentRecord.read(captureBuffer, requestedBytes, AudioRecord.READ_BLOCKING)
        if (read == AudioRecord.ERROR_DEAD_OBJECT) {
            if (!restartAudioRecordOnAudioThread()) {
                throw IOException("Audio input disconnected")
            }
            return 0
        }
        if (read < 0) {
            throw IOException("AudioRecord read failed: $read")
        }

        val alignedRead = read - read % frameBytes
        if (alignedRead > 0) {
            captureBuffer.position(0)
            captureBuffer.limit(alignedRead)
            captureBuffer.get(captureScratch, 0, alignedRead)
            publishVisualization(captureScratch, 0, alignedRead)
            appendCapturedAudio(captureScratch, 0, alignedRead)
        }

        if (state != STATE_LISTENING) return read
        if (audioRecord !== currentRecord) return read
        if (read > 0) {
            audioHandler.post(audioReader)
        } else {
            audioHandler.postDelayed(audioReader, EMPTY_READ_RETRY_MILLIS)
        }
        return read
    }

    private fun restartAudioRecordOnAudioThread(): Boolean {
        check(audioHandler.looper == Looper.myLooper())
        audioHandler.removeCallbacks(audioReader)
        sealActiveChunks()
        releaseAudioRecord()
        if (state != STATE_LISTENING) return false
        val record = createAudioRecord() ?: return false
        audioRecord = record
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            releaseAudioRecord()
            return false
        }
        return try {
            record.startRecording()
            if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                audioHandler.post(audioReader)
                true
            } else {
                releaseAudioRecord()
                false
            }
        } catch (error: RuntimeException) {
            Log.e(TAG, "Unable to recover AudioRecord", error)
            releaseAudioRecord()
            false
        }
    }

    private val audioReader = Runnable {
        try {
            readCaptureIntoScratch()
        } catch (error: Exception) {
            Log.e(TAG, "Audio capture failed", error)
            failListeningOnAudioThread(getString(R.string.audio_input_init_failed), error)
        }
    }

    private fun failListeningOnAudioThread(message: String, error: Throwable?) {
        check(audioHandler.looper == Looper.myLooper())
        getRecorderPreferences(this).edit()
            .putBoolean(PrefKey.AUDIO_MEMORY_ENABLED, false)
            .commit()
        state = STATE_READY
        audioHandler.removeCallbacks(audioReader)
        runCatching { sealActiveChunks() }
        releaseAudioRecord()
        updateWakeLockState()
        reportError(if (error == null) message else userFacingError(message, error))
        mainHandler.post {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    fun getState(callback: StateCallback) {
        audioHandler.post {
            try {
                val oneShotSeconds = availableBufferedDurationSeconds(BufferSlot.ONE_SHOT).toFloat()
                val oneShotBytes = availableBufferedSampleBytes(BufferSlot.ONE_SHOT)
                val loopingSeconds = availableBufferedDurationSeconds(BufferSlot.LOOPING).toFloat()
                val loopingBytes = availableBufferedSampleBytes(BufferSlot.LOOPING)
                val listening = state == STATE_LISTENING
                mainHandler.post {
                    callback.state(
                        listening,
                        oneShotSeconds,
                        oneShotBytes,
                        loopingSeconds,
                        loopingBytes,
                    )
                }
            } catch (error: Exception) {
                reportPersistentStoreFailure("read recorder state", error)
                val listening = state == STATE_LISTENING
                mainHandler.post {
                    callback.state(listening, 0f, 0L, 0f, 0L)
                }
            }
        }
    }

    fun setVisualizationCallback(callback: VisualizationCallback?) {
        visualizationCallback = callback
        if (callback == null) {
            pendingVisualizationFrame.set(null)
        }
        if (!::audioHandler.isInitialized) return
        audioHandler.post {
            if (visualizationCallback === callback) {
                visualizationAnalyzer.reset()
                visualizationFaulted = false
                lastVisualizationAnalysisNanos = 0L
            }
        }
    }

    private fun publishVisualization(array: ByteArray, offset: Int, count: Int) {
        val callback = visualizationCallback ?: return
        if (visualizationFaulted) return
        val nowNanos = SystemClock.elapsedRealtimeNanos()
        if (
            lastVisualizationAnalysisNanos != 0L &&
            nowNanos - lastVisualizationAnalysisNanos < VISUALIZATION_ANALYSIS_INTERVAL_NANOS
        ) return
        lastVisualizationAnalysisNanos = nowNanos
        val frame = try {
            visualizationAnalyzer.analyze(
                array = array,
                offset = offset,
                count = count,
                sampleFormat = pcmSampleFormat,
                channelCount = channelMode.channelCount,
                sampleRate = sampleRate,
            )
        } catch (error: Exception) {
            visualizationFaulted = true
            Log.w(TAG, "Audio visualization disabled until the UI reconnects", error)
            return
        }
        if (visualizationCallback !== callback) return
        pendingVisualizationFrame.set(frame)
        if (visualizationDispatchScheduled.compareAndSet(false, true)) {
            mainHandler.post(visualizationDispatcher)
        }
    }

    private val visualizationDispatcher = object : Runnable {
        override fun run() {
            val frame = pendingVisualizationFrame.getAndSet(null)
            val callback = visualizationCallback
            if (frame != null && callback != null) {
                callback.frame(frame)
            }

            visualizationDispatchScheduled.set(false)
            if (
                pendingVisualizationFrame.get() != null &&
                visualizationCallback != null &&
                visualizationDispatchScheduled.compareAndSet(false, true)
            ) {
                mainHandler.post(this)
            }
        }
    }

    fun getConfigurationSnapshot(): RecorderConfigurationSnapshot {
        return cachedConfigSnapshot ?: RecorderConfigurationSnapshot(
            format = ExportFormat.WAV,
            codec = ExportCodec.PCM_16,
            sampleFormat = PcmSampleFormat.PCM_16,
            sampleRate = ReverbConfig.PREFERRED_DEFAULT_SAMPLE_RATE,
            sourceMode = AudioSourceMode.defaultMode(),
            channelMode = ChannelMode.MONO,
            routeMode = InputRouteMode.AUTO,
        )
    }

    fun clearBuffer(bufferSlot: BufferSlot = BufferSlot.LOOPING) {
        audioHandler.post {
            try {
                chunkStore(bufferSlot).clear()
            } catch (error: Exception) {
                reportPersistentStoreFailure("clear history", error)
            }
        }
    }

    inner class BackgroundRecorderBinder : Binder() {
        val service: ReverbService
            get() = this@ReverbService
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        // A platform sticky restart reaches onStartCommand before the audio-thread
        // initialization posted from onCreate has finished. Restore the logical
        // listening state immediately so the foreground-service deadline is met;
        // the queued audio work remains ordered behind initial configuration.
        if (state != STATE_LISTENING && isListeningEnabled()) {
            state = STATE_LISTENING
            updateWakeLockState()
            audioHandler.post { startAudioInputOnAudioThread() }
        }
        if (state == STATE_LISTENING) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    FOREGROUND_NOTIFICATION_ID,
                    buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
                )
            } else {
                startForeground(FOREGROUND_NOTIFICATION_ID, buildNotification())
            }
        } else {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        handleDebugCommand(intent)
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        audioHandler.post { checkpointAudioStores("task-removed checkpoint") }
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, BACKGROUND_NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setSmallIcon(R.drawable.ic_notification_recording)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    fun consumePendingError(): String? = pendingError.getAndSet(null)

    private fun reportError(message: String) {
        pendingError.set(message)
    }

    private fun reportPersistentStoreFailure(operation: String, error: Exception) {
        Log.e(TAG, "Persistent audio store $operation failed", error)
        reportError(userFacingError(getString(R.string.recorder_state_persist_failed), error))
    }

    private fun checkpointPersistentStore(operation: String) {
        checkpointAudioStores(operation)
    }

    private fun userFacingError(message: String, error: Throwable): String {
        val detail = error.message?.trim().orEmpty()
        return if (detail.isBlank() || message.contains(detail)) message else "$message $detail"
    }

    private fun configurePersistentBuffer() {
        val mode = getConfiguredRetentionMode(this)
        val retentionValue = when (mode) {
            RetentionMode.SIZE -> getConfiguredRetentionSizeBytes(this)
            RetentionMode.TIME -> getConfiguredRetentionSeconds(this)
        }
        loopingAudioChunkStore.configure(
            requestedRetentionMode = mode,
            requestedRetentionValue = retentionValue,
            requestedSampleRate = sampleRate,
            requestedChannelCount = channelMode.channelCount,
            sampleFormat = pcmSampleFormat,
        )
        oneShotAudioChunkStore.configure(
            requestedRetentionMode = RetentionMode.SIZE,
            requestedRetentionValue = Long.MAX_VALUE,
            requestedSampleRate = sampleRate,
            requestedChannelCount = channelMode.channelCount,
            sampleFormat = pcmSampleFormat,
        )
        if (
            (loopingAudioChunkStore.hasData() || oneShotAudioChunkStore.hasData()) &&
            !isListeningEnabled() &&
            state == STATE_READY
        ) {
            state = STATE_PAUSED
        }
    }

    private fun flushAndPersistBeforeShutdown() {
        if (!::audioHandler.isInitialized) {
            runCatching { loopingAudioChunkStore.close() }
            runCatching { oneShotAudioChunkStore.close() }
            return
        }
        val storeCloseOwnedByAudioThread = runOnAudioThreadAndWait {
            audioHandler.removeCallbacks(audioReader)
            state = STATE_READY
            try {
                sealActiveChunks()
            } catch (error: Exception) {
                reportPersistentStoreFailure("seal during shutdown", error)
            } finally {
                releaseAudioRecord()
                try {
                    forEachAudioStore(PersistentAudioChunkStore::close)
                } catch (error: Exception) {
                    reportPersistentStoreFailure("close during shutdown", error)
                }
            }
        }
        if (!storeCloseOwnedByAudioThread) {
            runCatching { loopingAudioChunkStore.close() }
            runCatching { oneShotAudioChunkStore.close() }
        }
    }

    private fun runOnAudioThreadAndWait(block: () -> Unit): Boolean {
        if (!::audioHandler.isInitialized) {
            block()
            return true
        }
        if (Looper.myLooper() == audioHandler.looper) {
            block()
            return true
        }

        val latch = CountDownLatch(1)
        val posted = audioHandler.post {
            try {
                block()
            } finally {
                latch.countDown()
            }
        }
        if (!posted) {
            Log.w(TAG, "Audio thread rejected shutdown work")
            return false
        }
        try {
            if (!latch.await(3, TimeUnit.SECONDS)) {
                Log.w(TAG, "Timed out waiting for audio-thread shutdown work")
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            Log.w(TAG, "Interrupted while waiting for audio-thread shutdown work")
        }
        return true
    }

    private fun handleDebugCommand(intent: Intent?) {
        if (!isDebuggableBuild()) {
            return
        }
        val action = intent?.action ?: return
        if (!action.startsWith(DEBUG_ACTION_PREFIX)) {
            return
        }
        Log.d(TAG, "handleDebugCommand action=$action")

        val seconds = intent.getFloatExtra(EXTRA_DEBUG_SECONDS, 0f)
        audioHandler.post {
            try {
                when (action) {
                    ACTION_DEBUG_ENABLE_LISTENING -> mainHandler.post { enableListening() }
                    ACTION_DEBUG_DISABLE_LISTENING -> mainHandler.post { disableListening() }
                    ACTION_DEBUG_CLEAR_BUFFER -> loopingAudioChunkStore.clear()
                    ACTION_DEBUG_INJECT_BUFFER -> injectDebugBuffer(seconds)
                    ACTION_DEBUG_FORCE_APP_STORAGE_EXPORTS -> {
                        setConfiguredExportTreeUri(this@ReverbService, null)
                        writeDebugReport("force-app-storage-exports")
                    }
                    ACTION_DEBUG_EXPORT_FULL -> exportDebug(FULL_BUFFER_SECONDS)
                    ACTION_DEBUG_EXPORT_SECONDS -> exportDebug(seconds)
                    ACTION_DEBUG_APPLY_SETTINGS -> applyConfiguredPreferencesOnAudioThread()
                    ACTION_DEBUG_CHECKPOINT -> checkpointAudioStores("debug checkpoint")
                    ACTION_DEBUG_LOG_STATE -> logDebugState()
                    ACTION_DEBUG_DUMP_REPORT -> writeDebugReport("manual-dump")
                }
                if (action == ACTION_DEBUG_APPLY_SETTINGS) {
                    writeDebugReport("apply-settings")
                }
            } catch (error: Exception) {
                Log.w(TAG, "Debug action failed: $action", error)
                reportPersistentStoreFailure("debug action $action", error)
                runCatching { writeDebugReport("debug-action:error:${error.javaClass.simpleName}") }
            }
        }
    }

    private fun exportDebug(seconds: Float) {
        if (seconds <= 0f) {
            Log.w(TAG, "Debug export ignored; seconds=$seconds")
            return
        }
        if (!canExportBufferedAudio()) {
            Log.w(TAG, "Debug export ignored; state=$state")
            return
        }
        Log.d(TAG, "exportDebug seconds=$seconds available=${availableBufferedSampleBytes()}")
        dumpRecording(seconds, NotifyFileReceiver(this), "")
    }

    private fun injectDebugBuffer(seconds: Float) {
        if (!isDebuggableBuild() || seconds <= 0f) {
            Log.w(TAG, "injectDebugBuffer ignored seconds=$seconds state=$state")
            return
        }
        val logicalRetentionBytes = cachedRetentionSampleBytes
        if (logicalRetentionBytes <= 0L) {
            Log.w(TAG, "injectDebugBuffer ignored retention=$logicalRetentionBytes")
            return
        }
        val frameBytes = (channelMode.channelCount * pcmSampleFormat.bytesPerSample).coerceAtLeast(1).toLong()
        val totalBytes = alignDown((seconds * fillRate).toLong().coerceAtLeast(0L), frameBytes)
        val chunk = ByteArray(64 * 1024)
        var remaining = totalBytes
        while (remaining > 0L) {
            val count = alignDown(minOf(chunk.size.toLong(), remaining), frameBytes).toInt()
            if (count <= 0) break
            appendCapturedAudio(chunk, 0, count)
            remaining -= count.toLong()
        }
        state = STATE_PAUSED
        updateWakeLockState()
        checkpointAudioStores("debug inject checkpoint")
        Log.d(
            TAG,
            "injectDebugBuffer seconds=$seconds logical=$logicalRetentionBytes " +
                "available=${availableBufferedSampleBytes()}",
        )
        writeDebugReport("inject-buffer-${seconds}s")
    }

    private fun logDebugState() {
        val persisted = loopingAudioChunkStore.peekSnapshot()
        val oneShot = oneShotAudioChunkStore.peekSnapshot()
        Log.d(
            TAG,
            "debug-state state=$state " +
                "sampleRate=$sampleRate channels=${channelMode.channelCount} codec=${outputCodec.prefValue} " +
                "format=${outputFormat.prefValue} logicalRetention=${cachedRetentionSampleBytes} " +
                "persistedFilled=${persisted?.filledBytes ?: 0} persistedDuration=${persisted?.durationSeconds ?: 0.0} " +
                "chunks=${persisted?.chunkCount ?: 0} oneShotFilled=${oneShot?.filledBytes ?: 0} " +
                "oneShotDuration=${oneShot?.durationSeconds ?: 0.0}",
        )
    }

    private fun writeDebugReport(reason: String) {
        val reportFile = resolveDebugReportFile()
        val persisted = loopingAudioChunkStore.peekSnapshot()
        val oneShot = oneShotAudioChunkStore.peekSnapshot()
        val status =
            buildString {
                append("reason=").append(reason)
                append(" state=").append(state)
                append(" sampleRate=").append(sampleRate)
                append(" channelCount=").append(channelMode.channelCount)
                append(" format=").append(outputFormat.prefValue)
                append(" codec=").append(outputCodec.prefValue)
                append(" persistedFilled=").append(persisted?.filledBytes ?: 0)
                append(" persistedDuration=").append(persisted?.durationSeconds ?: 0.0)
                append(" persistedChunks=").append(persisted?.chunkCount ?: 0)
                append(" persistedLastWrite=").append(persisted?.lastWriteAtMillis ?: 0)
                append(" oneShotFilled=").append(oneShot?.filledBytes ?: 0)
                append(" oneShotDuration=").append(oneShot?.durationSeconds ?: 0.0)
                append(" oneShotChunks=").append(oneShot?.chunkCount ?: 0)
                append(" exportDir=").append(describeConfiguredOutputDirectory(this@ReverbService))
            }
        reportFile.appendText(status + "\n---\n")
        Log.d(TAG, "writeDebugReport $status path=${reportFile.absolutePath}")
    }

    private fun resolveDebugReportFile(): File {
        val directory = getSavedRecordingsDirectory(this)
        if (!directory.exists() && !directory.mkdirs() && !directory.exists()) {
            throw IOException("Unable to create recordings directory: ${directory.absolutePath}")
        }
        return File(directory, DEBUG_REPORT_FILE_NAME)
    }

    private fun isDebuggableBuild(): Boolean {
        return (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val notificationManager = getSystemService(NotificationManager::class.java) ?: return
        notificationManager.createNotificationChannel(
            NotificationChannel(
                BACKGROUND_NOTIFICATION_CHANNEL_ID,
                getString(R.string.app_name),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private fun updateWakeLockState() {
        if (!isWakeLockEnabled(this)) {
            releaseWakeLock()
            return
        }

        val shouldHoldWakeLock = state == STATE_LISTENING
        if (!shouldHoldWakeLock) {
            releaseWakeLock()
            return
        }
        if (wakeLock?.isHeld == true) {
            return
        }

        val powerManager = getSystemService(PowerManager::class.java) ?: return
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, packageName + WAKE_LOCK_TAG_SUFFIX).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        val lock = wakeLock ?: return
        if (lock.isHeld) {
            lock.release()
        }
        wakeLock = null
    }

    interface AudioFileReceiver {
        fun fileReady(recording: RecordingEntity)

        fun fileFailed(message: String, error: Throwable? = null) = Unit

        fun fileCancelled() = Unit
    }

    enum class BufferSlot {
        ONE_SHOT,
        LOOPING,
    }

    class TimelineSnapshot internal constructor(
        private val lease: PersistentAudioChunkStore.RangeLease,
    ) : java.io.Closeable {
        val durationSeconds: Double
            get() = lease.durationSeconds

        internal fun acquireRange(startSeconds: Double, endSeconds: Double): PersistentAudioChunkStore.RangeLease? =
            lease.acquireSubRange(startSeconds, endSeconds)

        override fun close() {
            lease.close()
        }
    }

    interface StateCallback {
        fun state(
            listeningEnabled: Boolean,
            oneShotSeconds: Float,
            oneShotBytes: Long,
            loopingSeconds: Float,
            loopingBytes: Long,
        )
    }

    fun interface VisualizationCallback {
        fun frame(frame: VisualizationFrame)
    }

    data class VisualizationFrame(
        val activity: Float,
        val bins: FloatArray,
    ) {
        companion object {
            val EMPTY = VisualizationFrame(
                activity = 0f,
                bins = FloatArray(AudioVisualizationAnalyzer.OUTPUT_BINS),
            )
        }
    }

    data class RecorderConfigurationSnapshot(
        val format: ExportFormat,
        val codec: ExportCodec,
        val sampleFormat: PcmSampleFormat,
        val sampleRate: Int,
        val sourceMode: AudioSourceMode,
        val channelMode: ChannelMode,
        val routeMode: InputRouteMode,
    )

    private data class OperationalConfig(
        val sourceMode: AudioSourceMode,
        val channelMode: ChannelMode,
        val routeMode: InputRouteMode,
        val format: ExportFormat,
        val codec: ExportCodec,
        val sampleFormat: PcmSampleFormat,
        val sampleRate: Int,
    )

    private data class ExportCancellationToken(
        val id: Long,
        val cancelled: AtomicBoolean = AtomicBoolean(false),
        // `started` flips in FutureTask.run() before any export work begins so queued
        // cancellations can be completed synchronously without racing the worker body.
        val started: AtomicBoolean = AtomicBoolean(false),
        val committed: AtomicBoolean = AtomicBoolean(false),
        val terminalDelivered: AtomicBoolean = AtomicBoolean(false),
    )

    companion object {
        val TAG: String = ReverbService::class.java.simpleName
        const val NOTIFICATION_CHANNEL_ID = "ReverbRecorderChannel"
        const val BACKGROUND_NOTIFICATION_CHANNEL_ID = "ReverbBackgroundRecorderChannel"
        const val FOREGROUND_NOTIFICATION_ID = 458
        const val MIN_AUDIO_RECORD_BUFFER_SIZE = 16 * 1024
        const val CAPTURE_SCRATCH_BYTES = 256 * 1024
        const val CAPTURE_READ_TARGET_MILLIS = 160L
        const val EMPTY_READ_RETRY_MILLIS = 20L
        const val VISUALIZATION_ANALYSIS_INTERVAL_NANOS = 90_000_000L
        const val FULL_BUFFER_SECONDS = 60f * 60f * 24f * 365f
        const val DEBUG_ACTION_PREFIX = ReverbConfig.DEBUG_ACTION_PREFIX
        val nextExportTokenId = AtomicLong(1L)
        const val ACTION_DEBUG_ENABLE_LISTENING = "${DEBUG_ACTION_PREFIX}ENABLE_LISTENING"
        const val ACTION_DEBUG_DISABLE_LISTENING = "${DEBUG_ACTION_PREFIX}DISABLE_LISTENING"
        const val ACTION_DEBUG_CLEAR_BUFFER = "${DEBUG_ACTION_PREFIX}CLEAR_BUFFER"
        const val ACTION_DEBUG_INJECT_BUFFER = "${DEBUG_ACTION_PREFIX}INJECT_BUFFER"
        const val ACTION_DEBUG_FORCE_APP_STORAGE_EXPORTS = "${DEBUG_ACTION_PREFIX}FORCE_APP_STORAGE_EXPORTS"
        const val ACTION_DEBUG_EXPORT_FULL = "${DEBUG_ACTION_PREFIX}EXPORT_FULL"
        const val ACTION_DEBUG_EXPORT_SECONDS = "${DEBUG_ACTION_PREFIX}EXPORT_SECONDS"
        const val ACTION_DEBUG_APPLY_SETTINGS = "${DEBUG_ACTION_PREFIX}APPLY_SETTINGS"
        const val ACTION_DEBUG_CHECKPOINT = "${DEBUG_ACTION_PREFIX}CHECKPOINT"
        const val ACTION_DEBUG_LOG_STATE = "${DEBUG_ACTION_PREFIX}LOG_STATE"
        const val ACTION_DEBUG_DUMP_REPORT = "${DEBUG_ACTION_PREFIX}DUMP_REPORT"
        const val EXTRA_DEBUG_SECONDS = ReverbConfig.EXTRA_SECONDS
        const val DEBUG_REPORT_FILE_NAME = "debug-report.txt"

        const val WAKE_LOCK_TAG_SUFFIX = ":reverbBuffer"

        const val STATE_READY = 0
        const val STATE_LISTENING = 1
        const val STATE_PAUSED = 2
    }

}
