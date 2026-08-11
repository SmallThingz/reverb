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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob

@SuppressLint("ImplicitSamInstance")
class ReverbService : Service() {
    @Volatile
    private var sampleRate = ReverbConfig.PREFERRED_DEFAULT_SAMPLE_RATE

    @Volatile
    private var fillRate = 96_000

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
    private var recordingTarget: RecordingOutputTarget? = null

    @Volatile
    private var audioRecord: AudioRecord? = null

    @Volatile
    private var audioFileWriter: AudioFileWriter? = null

    @Volatile
    private var activeExportToken: ExportCancellationToken? = null

    @Volatile
    private var activeExportFuture: Future<*>? = null

    @Volatile
    private var activeExportReceiver: AudioFileReceiver? = null

    @Volatile
    private var cachedRetentionSampleBytes = 0L

    @Volatile
    private var cachedPersistentPcmSizeBytes = 0L

    @Volatile
    private var cachedConfigSnapshot: RecorderConfigurationSnapshot? = null

    private val captureScratch = ByteArray(CAPTURE_SCRATCH_BYTES)
    private val captureBuffer = ByteBuffer.allocateDirect(CAPTURE_SCRATCH_BYTES)
        .order(ByteOrder.nativeOrder())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var audioThread: HandlerThread
    private lateinit var audioHandler: Handler
    private lateinit var exportWorkExecutor: ExecutorService
    private lateinit var persistentAudioRingStore: PersistentAudioRingStore

    @Volatile private var wakeLock: PowerManager.WakeLock? = null

    private val pendingError = AtomicReference<String?>(null)

    override fun onCreate() {
        loadConfiguration()
        persistentAudioRingStore = PersistentAudioRingStore(this)
        createNotificationChannel()
        refreshCachedBufferSizing()
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
            configurePersistentBuffer()
        }

        if (isListeningEnabled()) {
            innerStartListening()
        }
    }

    override fun onDestroy() {
        activeExportToken?.cancelled?.set(true)
        activeExportFuture?.cancel(true)
        flushAndPersistBeforeShutdown()
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)

        if (::exportWorkExecutor.isInitialized) {
            exportWorkExecutor.shutdownNow()
            runCatching { exportWorkExecutor.awaitTermination(2, TimeUnit.SECONDS) }
        }
        persistentAudioRingStore.close()
        audioThread.quitSafely()
        serviceScope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        audioHandler.post { persistentAudioRingStore.checkpoint() }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        audioHandler.post { persistentAudioRingStore.checkpoint() }
    }

    override fun onBind(intent: Intent): IBinder = BackgroundRecorderBinder()

    override fun onUnbind(intent: Intent): Boolean = true

    override fun dump(
        fd: FileDescriptor,
        writer: PrintWriter,
        args: Array<out String>,
    ) {
        if (!isDebuggableBuild()) {
            super.dump(fd, writer, args)
            return
        }
        val persisted = if (::persistentAudioRingStore.isInitialized) persistentAudioRingStore.peekSnapshot() else null
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
            "  persisted filled=${persisted?.filledBytes ?: 0} capacity=${persisted?.capacityBytes ?: 0} " +
                "sampleRate=${persisted?.sampleRate ?: 0} channelCount=${persisted?.channelCount ?: 0} " +
                "lastWrite=${persisted?.lastWriteAtMillis ?: 0}",
        )
        writer.println("  rawHistoryFile=${ReverbConfig.BUFFER_PCM_FILE_NAME}")
    }

    fun enableListening() {
        getRecorderPreferences(this).edit()
            .putBoolean(PrefKey.AUDIO_MEMORY_ENABLED, true)
            .apply()
        innerStartListening()
    }

    fun disableListening() {
        getRecorderPreferences(this).edit()
            .putBoolean(PrefKey.AUDIO_MEMORY_ENABLED, false)
            .apply()
        innerStopListening()
    }

    private fun isListeningEnabled(): Boolean {
        return getRecorderPreferences(this).getBoolean(PrefKey.AUDIO_MEMORY_ENABLED, false)
    }

    private fun loadConfiguration() {
        var selectedSourceMode = getConfiguredAudioSourceMode(this)
        var selectedChannelMode = getConfiguredChannelMode(this)
        var selectedRouteMode = getConfiguredInputRouteMode(this)
        var selectedFormat = getConfiguredOutputFormat(this)
        var selectedCodec = getConfiguredOutputCodec(this)
        val selectedSampleFormat = getConfiguredPcmSampleFormat(this)

        val requestedRate = getConfiguredSampleRate(
            this,
            selectedSourceMode,
            selectedRouteMode,
            selectedFormat,
            selectedCodec,
            selectedChannelMode,
            selectedSampleFormat,
        )
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
            getRecorderPreferences(this).edit()
                .putInt(PrefKey.AUDIO_SOURCE, resolvedConfig.sourceMode.sourceValue)
                .putString(PrefKey.CHANNEL_MODE, resolvedConfig.channelMode.prefValue)
                .putString(PrefKey.INPUT_ROUTE, resolvedConfig.routeMode.prefValue)
                .putString(PrefKey.PCM_SAMPLE_FORMAT, resolvedConfig.sampleFormat.prefValue)
                .putInt(PrefKey.SAMPLE_RATE, resolvedConfig.sampleRate)
                .apply()
        }

        sampleRate = resolvedConfig?.sampleRate ?: 48_000
        pcmSampleFormat = resolvedConfig?.sampleFormat ?: PcmSampleFormat.PCM_16
        fillRate = sampleRate * selectedChannelMode.channelCount * pcmSampleFormat.bytesPerSample
        sourceMode = selectedSourceMode
        channelMode = selectedChannelMode
        audioSource = selectedSourceMode.sourceValue
        outputFormat = selectedFormat
        outputCodec = selectedCodec
        inputRouteMode = selectedRouteMode
        cachedConfigSnapshot = null
        refreshCachedBufferSizing()
    }

    private fun refreshCachedBufferSizing() {
        cachedRetentionSampleBytes = getConfiguredMemorySizeBytes(
            context = this,
            sampleRate = sampleRate,
            channelMode = channelMode,
            format = outputFormat,
            codec = outputCodec,
            bitrateKbps = null,
            sampleFormat = pcmSampleFormat,
        )
        cachedPersistentPcmSizeBytes = cachedRetentionSampleBytes
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
            STATE_LISTENING, STATE_RECORDING -> return
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
            .apply()
        state = STATE_READY
        updateWakeLockState()
        showToast(getString(R.string.audio_input_init_failed))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startAudioInputOnAudioThread() {
        check(audioHandler.looper == Looper.myLooper())
        audioHandler.removeCallbacks(audioReader)
        releaseAudioRecord()
        configurePersistentBuffer()

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
            STATE_LISTENING, STATE_RECORDING, STATE_PAUSED -> Unit
            else -> return
        }
        audioHandler.post {
            if (state == STATE_RECORDING) {
                stopRecordingOnAudioThread(NotifyFileReceiver(this@ReverbService, serviceScope))
            }
            audioHandler.removeCallbacks(audioReader)
            state = STATE_READY
            updateWakeLockState()
            persistentAudioRingStore.checkpoint()
            releaseAudioRecord()
            mainHandler.post {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
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
            AudioRecord.Builder()
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
                .also { record ->
                    if (inputRouteMode == InputRouteMode.BUILTIN_MIC) {
                        findBuiltInMicrophone(this)?.let { record.preferredDevice = it }
                    }
                }
        } catch (error: Throwable) {
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
    ) {
        if (!canExportBufferedAudio()) {
            notifyReceiverFailure(receiver, getString(R.string.nothing_to_export))
            return
        }

        audioHandler.post {
            flushAudioRecord()

            val bytesAvailable = availableBufferedSampleBytes()
            val prependBytes = (memorySeconds * fillRate).toLong()
            val skipBytes = maxOf(0L, bytesAvailable - prependBytes)
            val useBytes = bytesAvailable - skipBytes
            exportBufferedRange(skipBytes, useBytes, receiver, newFileName)
        }
    }

    fun dumpRecordingRange(
        startOffsetSeconds: Float,
        endOffsetSeconds: Float,
        receiver: AudioFileReceiver,
        newFileName: String,
    ) {
        if (!canExportBufferedAudio()) {
            notifyReceiverFailure(receiver, getString(R.string.nothing_to_export))
            return
        }

        audioHandler.post {
            flushAudioRecord()

            val bytesAvailable = availableBufferedSampleBytes()
            val boundedStart = startOffsetSeconds.coerceAtLeast(0f)
            val boundedEnd = endOffsetSeconds.coerceAtLeast(boundedStart)
            val skipBytes = (boundedStart * fillRate).toLong().coerceAtMost(bytesAvailable)
            val endBytes = (boundedEnd * fillRate).toLong().coerceAtMost(bytesAvailable)
            val useBytes = (endBytes - skipBytes).coerceAtLeast(0L)
            exportBufferedRange(skipBytes, useBytes, receiver, newFileName)
        }
    }

    fun cancelCurrentExport(): Boolean {
        while (true) {
            val token = activeExportToken ?: return false
            if (activeExportToken !== token) continue
            val future = activeExportFuture
            val receiver = activeExportReceiver
            token.cancelled.set(true)
            if (!token.started.get() && future?.cancel(true) == true) {
                if (activeExportToken === token) {
                    activeExportToken = null
                    activeExportFuture = null
                    activeExportReceiver = null
                }
                notifyReceiverCancelled(receiver)
                return true
            }
            future?.cancel(true)
            return true
        }
    }

    private fun exportBufferedRange(
        skipBytes: Long,
        useBytes: Long,
        receiver: AudioFileReceiver,
        newFileName: String,
    ) {
        if (activeExportToken != null) {
            notifyReceiverFailure(receiver, getString(R.string.export_in_progress))
            return
        }
        val bytesAvailable = availableBufferedSampleBytes()
        val frameBytes = (channelMode.channelCount * pcmSampleFormat.bytesPerSample).coerceAtLeast(1).toLong()
        val alignedAvailable = alignDown(bytesAvailable, frameBytes)
        var boundedSkip = alignDown(skipBytes.coerceIn(0L, alignedAvailable), frameBytes)
        var boundedUse = alignDown(
            useBytes.coerceAtLeast(0L).coerceAtMost(alignedAvailable - boundedSkip),
            frameBytes,
        )
        val maxExportSampleBytes = (exportFileSizeLimitBytes(outputFormat) / frameBytes) * frameBytes
        if (boundedUse > maxExportSampleBytes) {
            boundedSkip += boundedUse - maxExportSampleBytes
            boundedUse = maxExportSampleBytes
        }
        if (boundedUse <= 0L) {
            notifyReceiverFailure(receiver, getString(R.string.nothing_to_export))
            return
        }
        val startedAtMillis =
            System.currentTimeMillis() - 1000L * (alignedAvailable - boundedSkip) / maxOf(fillRate, 1)
        val exportFormat = outputFormat
        val exportCodec = outputCodec
        val exportSampleRate = sampleRate
        val exportChannelMode = channelMode
        val exportSampleFormat = pcmSampleFormat
        val exportFillRate = fillRate
        val exportToken = ExportCancellationToken(nextExportTokenId.getAndIncrement())
        activeExportToken = exportToken
        activeExportReceiver = receiver
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
                            showToast(message)
                            notifyReceiverFailure(receiver, message, e)
                            return@Callable Unit
                        }
                        outTarget = target
                        var durationMillis = 0L
                        val readSucceeded =
                            WavAudioFileWriter(
                                this@ReverbService,
                                target,
                                exportSampleRate,
                                exportChannelMode.channelCount,
                                exportSampleFormat,
                            ).use { writer ->
                                val didRead = readBufferedPcm(boundedSkip, boundedUse) { array, offset, count ->
                                    ensureExportNotCancelled(exportToken)
                                    writer.write(array, offset, count)
                                    count
                                }
                                ensureExportNotCancelled(exportToken)
                                durationMillis = (
                                    writer.totalSampleBytesWritten * 1000f / maxOf(exportFillRate, 1)
                                ).toLong()
                                didRead
                            }
                        if (!readSucceeded) {
                            throw IOException("Requested PCM range not available in raw buffer")
                        }
                        requireExportedOutput(target)
                        ensureExportNotCancelled(exportToken)
                        committed = true
                        notifyReceiver(
                            receiver,
                            buildRecordingEntity(
                                this@ReverbService,
                                target,
                                durationMillis,
                                buildCodecSummary(
                                    this@ReverbService,
                                    exportFormat,
                                    exportCodec,
                                    exportSampleRate,
                                    exportChannelMode.channelCount,
                                    null,
                                    exportSampleFormat,
                                ),
                            ),
                        )
                    } catch (cancelled: InterruptedIOException) {
                        Log.i(TAG, "Export cancelled for ${outTarget?.displayName ?: newFileName}")
                        deleteOutputTarget(outTarget)
                        notifyReceiverCancelled(receiver)
                    } catch (e: Exception) {
                        if (exportToken.cancelled.get()) {
                            Log.i(TAG, "Export cancelled for ${outTarget?.displayName ?: newFileName}", e)
                            deleteOutputTarget(outTarget)
                            notifyReceiverCancelled(receiver)
                            return@Callable Unit
                        }
                        Log.e(TAG, "Error while exporting raw history into ${outTarget?.displayName ?: newFileName}", e)
                        val message = userFacingError(
                            getString(R.string.error_during_writing_history_into) +
                                (outTarget?.displayName ?: newFileName),
                            e,
                        )
                        showToast(message)
                        notifyReceiverFailure(receiver, message, e)
                        deleteOutputTarget(outTarget)
                    } finally {
                        if (exportToken.cancelled.get() && !committed) {
                            deleteOutputTarget(outTarget)
                        }
                        if (activeExportToken === exportToken) {
                            activeExportToken = null
                            activeExportFuture = null
                            activeExportReceiver = null
                        }
                        Thread.interrupted()
                    }
                    Unit
                },
            ) {
                override fun run() {
                    exportToken.started.set(true)
                    super.run()
                }
            }
        activeExportFuture = exportTask
        try {
            exportWorkExecutor.execute(exportTask)
        } catch (e: RejectedExecutionException) {
            exportToken.cancelled.set(true)
            if (activeExportToken === exportToken) {
                activeExportToken = null
                activeExportFuture = null
                activeExportReceiver = null
            }
            Log.w(TAG, "Export rejected because the service is shutting down", e)
            notifyReceiverFailure(receiver, getString(R.string.save_failed), e)
        }
    }

    fun startRecording(prependedMemorySeconds: Float) {
        when (state) {
            STATE_READY -> innerStartListening()
            STATE_LISTENING -> Unit
            STATE_PAUSED -> innerStartListening()
            STATE_RECORDING -> return
            else -> return
        }

        audioHandler.post {
            if (state != STATE_LISTENING) return@post
            flushAudioRecord()

            val frameBytes = (channelMode.channelCount * pcmSampleFormat.bytesPerSample).coerceAtLeast(1).toLong()
            val bytesAvailable = alignDown(availableBufferedSampleBytes(), frameBytes)
            val prependBytes = alignDown(
                (prependedMemorySeconds * fillRate).toLong().coerceAtLeast(0L),
                frameBytes,
            )
            val useBytes = minOf(bytesAvailable, prependBytes)
            val skipBytes = bytesAvailable - useBytes
            val startedAtMillis = System.currentTimeMillis() - 1000L * useBytes / maxOf(fillRate, 1)

            try {
                recordingTarget = createOutputTarget(
                    this@ReverbService,
                    null,
                    startedAtMillis,
                    outputFormat,
                    outputCodec,
                )
                audioFileWriter = createAudioFileWriter(requireNotNull(recordingTarget))
                state = STATE_RECORDING
                updateWakeLockState()
            } catch (e: Exception) {
                Log.e(TAG, "Unable to create recording output", e)
                recordingTarget = null
                audioFileWriter = null
                showToast(userFacingError(getString(R.string.cant_create_file_generic), e))
                return@post
            }

            if (skipBytes >= bytesAvailable) {
                return@post
            }

            try {
                val writer = audioFileWriter
                val readSucceeded = readBufferedPcm(skipBytes, useBytes) { array, offset, count ->
                    writer?.write(array, offset, count)
                    count
                }
                if (!readSucceeded) {
                    throw IOException("Buffered audio was overwritten while starting recording")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error while priming recording into ${recordingTarget?.displayName}", e)
                failActiveRecordingOnAudioThread(e)
            }
        }
    }

    fun getMemorySize(): Long = cachedRetentionSampleBytes

    fun setMemorySize(memorySize: Long) {
        getRecorderPreferences(this).edit()
            .putInt(PrefKey.RETENTION_MODE, RetentionMode.SIZE.ordinal)
            .putLong(PrefKey.AUDIO_MEMORY_SIZE, memorySize.coerceAtLeast(1L))
            .apply()
        reloadConfiguration()
    }

    fun getSamplingRate(): Int = sampleRate

    fun isRecordingActive(): Boolean = state == STATE_RECORDING

    fun setSampleRate(sampleRate: Int) {
        getRecorderPreferences(this).edit()
            .putInt(PrefKey.SAMPLE_RATE, sampleRate)
            .apply()
        reloadConfiguration()
    }

    fun applyUpdatedPreferences(): ApplySettingsResult {
        if (state == STATE_RECORDING) {
            return ApplySettingsResult.BLOCKED_RECORDING
        }

        val newSourceMode = getConfiguredAudioSourceMode(this)
        val newChannelMode = getConfiguredChannelMode(this)
        val newRouteMode = getConfiguredInputRouteMode(this)
        val newFormat = getConfiguredOutputFormat(this)
        val newCodec = getConfiguredOutputCodec(this)
        val newSampleFormat = getConfiguredPcmSampleFormat(this)
        val newSampleRate = resolveOperationalSampleRate(
            this,
            getConfiguredSampleRate(
                this, newSourceMode, newRouteMode, newFormat, newCodec, newChannelMode, newSampleFormat,
            ),
            newSourceMode,
            newRouteMode,
            newFormat,
            newCodec,
            newChannelMode,
            newSampleFormat,
        )
        val captureConfigChanged =
            newSourceMode != sourceMode ||
                newChannelMode != channelMode ||
                newRouteMode != inputRouteMode ||
                newSampleRate != sampleRate ||
                newSampleFormat != pcmSampleFormat
        val restartInput = state == STATE_LISTENING && isListeningEnabled() && captureConfigChanged
        updateWakeLockState()

        audioHandler.post {
            if (restartInput) {
                audioHandler.removeCallbacks(audioReader)
                persistentAudioRingStore.checkpoint()
                releaseAudioRecord()
            }
            loadConfiguration()
            if (restartInput) {
                startAudioInputOnAudioThread()
            } else {
                configurePersistentBuffer()
                updateWakeLockState()
            }
        }
        return ApplySettingsResult.APPLIED_NOW
    }

    fun reloadConfiguration(): Boolean {
        if (state == STATE_RECORDING) return false
        val restartInput = state == STATE_LISTENING && isListeningEnabled()
        audioHandler.post {
            if (restartInput) {
                audioHandler.removeCallbacks(audioReader)
                persistentAudioRingStore.checkpoint()
                releaseAudioRecord()
            }
            loadConfiguration()
            if (restartInput) {
                startAudioInputOnAudioThread()
            } else {
                configurePersistentBuffer()
                updateWakeLockState()
            }
        }
        return true
    }

    fun stopRecording(receiver: AudioFileReceiver?) {
        audioHandler.post { stopRecordingOnAudioThread(receiver) }
    }

    private fun stopRecordingOnAudioThread(receiver: AudioFileReceiver?) {
        check(audioHandler.looper == Looper.myLooper())
        if (state != STATE_RECORDING) return

        state = STATE_LISTENING
        updateWakeLockState()
        flushAudioRecord()

        val writer = audioFileWriter
        val target = recordingTarget
        audioFileWriter = null
        recordingTarget = null

        if (writer == null || target == null) {
            runCatching { writer?.close() }
            deleteOutputTarget(target)
            return
        }

        if (writer.totalSampleBytesWritten <= 0L) {
            runCatching { writer.close() }
            deleteOutputTarget(target)
            notifyReceiverFailure(receiver, getString(R.string.nothing_to_export))
            return
        }

        val runtimeMillis = (writer.totalSampleBytesWritten * bytesToSeconds * 1000f).toLong()
        try {
            writer.close()
            requireExportedOutput(target)
        } catch (error: Exception) {
            Log.e(TAG, "Unable to finish recording ${target.displayName}", error)
            deleteOutputTarget(target)
            notifyReceiverFailure(
                receiver,
                userFacingError(
                    getString(R.string.error_during_writing_history_into) + target.displayName,
                    error,
                ),
                error,
            )
            return
        }

        notifyReceiver(
            receiver,
            buildRecordingEntity(
                this@ReverbService,
                target,
                runtimeMillis,
                currentCodecSummary(),
            ),
        )
    }

    private fun failActiveRecordingOnAudioThread(error: Throwable?) {
        check(audioHandler.looper == Looper.myLooper())
        val writer = audioFileWriter
        val target = recordingTarget
        audioFileWriter = null
        recordingTarget = null
        if (state == STATE_RECORDING) state = STATE_LISTENING
        updateWakeLockState()
        runCatching { writer?.close() }
        deleteOutputTarget(target)
        if (error != null) {
            val name = target?.displayName ?: getString(R.string.app_name)
            showToast(userFacingError(getString(R.string.error_during_recording_into) + name, error))
        }
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

    @Throws(InterruptedIOException::class)
    private fun ensureExportNotCancelled(token: ExportCancellationToken) {
        if (token.cancelled.get()) {
            throw InterruptedIOException("Export cancelled")
        }
    }

    @Throws(IOException::class)
    private fun createAudioFileWriter(target: RecordingOutputTarget): AudioFileWriter {
        return WavAudioFileWriter(this, target, sampleRate, channelMode.channelCount, pcmSampleFormat)
    }

    private fun deleteIfEmpty(target: RecordingOutputTarget?) {
        if (target == null) {
            return
        }
        if (resolveOutputTargetSize(this, target) == 0L) {
            deleteOutputTarget(target)
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
    private fun requireExportedOutput(target: RecordingOutputTarget) {
        val size = resolveOutputTargetSize(this, target)
        if (size > 0L) {
            return
        }
        deleteIfEmpty(target)
        throw IOException("Export produced empty output: ${target.displayName}")
    }

    private fun currentCodecSummary(): String {
        return buildCodecSummary(
            this,
            outputFormat,
            outputCodec,
            sampleRate,
            channelMode.channelCount,
            null,
            pcmSampleFormat,
        )
    }

    private fun alignDown(value: Long, alignment: Long): Long {
        if (value <= 0L || alignment <= 1L) return value.coerceAtLeast(0L)
        return value - value % alignment
    }

    private fun availableBufferedSampleBytes(): Long {
        return if (::persistentAudioRingStore.isInitialized) persistentAudioRingStore.countFilledBytes() else 0L
    }

    private fun readBufferedPcm(
        skipBytes: Long,
        maxBytes: Long,
        reader: PersistentAudioRingStore.Consumer,
    ): Boolean {
        return persistentAudioRingStore.read(skipBytes, maxBytes, reader)
    }

    private fun canExportBufferedAudio(): Boolean {
        return availableBufferedSampleBytes() > 0L || state == STATE_LISTENING || state == STATE_PAUSED
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
        val requestedBytes = captureScratch.size - (captureScratch.size % frameBytes)
        captureBuffer.clear()
        val read = currentRecord.read(captureBuffer, requestedBytes, AudioRecord.READ_NON_BLOCKING)
        if (read == AudioRecord.ERROR_DEAD_OBJECT) {
            if (!restartAudioRecordOnAudioThread()) {
                throw IOException("Audio input disconnected")
            }
            return 0
        }
        if (read < 0) {
            throw IOException("AudioRecord read failed: $read")
        }

        if (read > 0) {
            captureBuffer.position(0)
            captureBuffer.limit(read)
            captureBuffer.get(captureScratch, 0, read)
            persistentAudioRingStore.append(
                array = captureScratch,
                offset = 0,
                count = read,
                requestedCapacityBytes = cachedPersistentPcmSizeBytes,
                requestedSampleRate = sampleRate,
                requestedChannelCount = channelMode.channelCount,
                sampleFormat = pcmSampleFormat,
            )
            val writer = audioFileWriter
            if (writer != null) {
                try {
                    writer.write(captureScratch, 0, read)
                } catch (error: Exception) {
                    Log.e(TAG, "Live recording write failed", error)
                    failActiveRecordingOnAudioThread(error)
                }
            }
        }

        if (state != STATE_LISTENING && state != STATE_RECORDING) return read
        if (audioRecord !== currentRecord) return read
        if (read == requestedBytes) {
            audioHandler.post(audioReader)
        } else {
            val bufferSizeInSeconds = currentRecord.bufferSizeInFrames / maxOf(sampleRate, 1).toFloat()
            val delaySeconds = (bufferSizeInSeconds - 1f)
                .coerceIn(bufferSizeInSeconds * 0.5f, bufferSizeInSeconds * 0.9f)
            audioHandler.postDelayed(audioReader, (delaySeconds * 1000f).toLong())
        }
        return read
    }

    private fun restartAudioRecordOnAudioThread(): Boolean {
        check(audioHandler.looper == Looper.myLooper())
        audioHandler.removeCallbacks(audioReader)
        releaseAudioRecord()
        if (state != STATE_LISTENING && state != STATE_RECORDING) return false
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
        failActiveRecordingOnAudioThread(null)
        getRecorderPreferences(this).edit()
            .putBoolean(PrefKey.AUDIO_MEMORY_ENABLED, false)
            .apply()
        state = STATE_READY
        audioHandler.removeCallbacks(audioReader)
        runCatching { persistentAudioRingStore.checkpoint() }
        releaseAudioRecord()
        updateWakeLockState()
        showToast(if (error == null) message else userFacingError(message, error))
        mainHandler.post {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    fun getState(callback: StateCallback) {
        audioHandler.post {
            val configuredBytes = cachedRetentionSampleBytes
            val memorizedBytes = availableBufferedSampleBytes().coerceAtMost(configuredBytes)
            val recordedBytes = audioFileWriter?.totalSampleBytesWritten ?: 0L
            val listening = state == STATE_LISTENING || state == STATE_RECORDING
            val recording = state == STATE_RECORDING
            mainHandler.post {
                callback.state(
                    listening,
                    recording,
                    memorizedBytes * bytesToSeconds,
                    configuredBytes * bytesToSeconds,
                    recordedBytes * bytesToSeconds,
                )
            }
        }
    }

    fun getConfigurationSnapshot(): RecorderConfigurationSnapshot {
        val cached = cachedConfigSnapshot
        if (cached != null) return cached
        return RecorderConfigurationSnapshot(
            format = outputFormat,
            codec = outputCodec,
            sampleFormat = pcmSampleFormat,
            sampleRate = sampleRate,
            sourceMode = sourceMode,
            channelMode = channelMode,
            routeMode = inputRouteMode,
        ).also { cachedConfigSnapshot = it }
    }

    fun hasBufferedAudio(): Boolean = availableBufferedSampleBytes() > 0L

    fun clearBuffer() {
        if (state == STATE_RECORDING) return
        audioHandler.post { persistentAudioRingStore.clear() }
    }

    private val bytesToSeconds: Float
        get() = if (fillRate > 0) 1f / fillRate else 0f

    inner class BackgroundRecorderBinder : Binder() {
        val service: ReverbService
            get() = this@ReverbService
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (state == STATE_LISTENING || state == STATE_RECORDING) {
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
        audioHandler.post { persistentAudioRingStore.checkpoint() }
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setSmallIcon(R.drawable.ic_notification_recording)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOngoing(true)
            .build()
    }

    fun consumePendingError(): String? = pendingError.getAndSet(null)

    private fun showToast(message: String) {
        pendingError.set(message)
    }

    private fun userFacingError(message: String, error: Throwable): String {
        val detail = error.message?.trim().orEmpty()
        return if (detail.isBlank() || message.contains(detail)) message else "$message $detail"
    }

    private fun configurePersistentBuffer() {
        persistentAudioRingStore.configure(
            requestedCapacityBytes = cachedPersistentPcmSizeBytes,
            requestedSampleRate = sampleRate,
            requestedChannelCount = channelMode.channelCount,
            sampleFormat = pcmSampleFormat,
        )
        if (persistentAudioRingStore.hasData() && !isListeningEnabled() && state == STATE_READY) {
            state = STATE_PAUSED
        }
    }

    private fun flushAndPersistBeforeShutdown() {
        if (!::audioHandler.isInitialized) {
            return
        }
        runOnAudioThreadAndWait {
            audioHandler.removeCallbacks(audioReader)
            if (state == STATE_RECORDING) {
                flushAudioRecord()
                audioHandler.removeCallbacks(audioReader)
                val writer = audioFileWriter
                audioFileWriter = null
                recordingTarget = null
                runCatching { writer?.close() }
                    .onFailure { Log.e(TAG, "Error while closing recording file during shutdown", it) }
            }
            state = STATE_READY
            persistentAudioRingStore.checkpoint()
            releaseAudioRecord()
        }
    }

    private fun runOnAudioThreadAndWait(block: () -> Unit) {
        if (!::audioHandler.isInitialized) {
            block()
            return
        }
        if (Looper.myLooper() == audioHandler.looper) {
            block()
            return
        }

        val latch = CountDownLatch(1)
        audioHandler.post {
            try {
                block()
            } finally {
                latch.countDown()
            }
        }
        if (!latch.await(3, TimeUnit.SECONDS)) {
            Log.w(TAG, "Timed out waiting for audio-thread shutdown work")
        }
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
            when (action) {
                ACTION_DEBUG_ENABLE_LISTENING -> mainHandler.post { enableListening() }
                ACTION_DEBUG_DISABLE_LISTENING -> mainHandler.post { disableListening() }
                ACTION_DEBUG_CLEAR_BUFFER -> if (state != STATE_RECORDING) {
                    persistentAudioRingStore.clear()
                }
                ACTION_DEBUG_INJECT_BUFFER -> injectDebugBuffer(seconds)
                ACTION_DEBUG_FORCE_APP_STORAGE_EXPORTS -> {
                    setConfiguredExportTreeUri(this@ReverbService, null)
                    writeDebugReport("force-app-storage-exports")
                }
                ACTION_DEBUG_EXPORT_FULL -> exportDebug(FULL_BUFFER_SECONDS)
                ACTION_DEBUG_EXPORT_SECONDS -> exportDebug(seconds)
                ACTION_DEBUG_MERGE_ALL -> writeDebugReport("merge-all:0")
                ACTION_DEBUG_APPLY_SETTINGS -> {
                    val result =
                        try {
                            applyUpdatedPreferences().name
                        } catch (t: Throwable) {
                            Log.w(TAG, "Debug apply-settings failed", t)
                            "ERROR:" + t.javaClass.simpleName
                        }
                    writeDebugReport("apply-settings:" + result)
                }
                ACTION_DEBUG_CHECKPOINT -> persistentAudioRingStore.checkpoint()
                ACTION_DEBUG_LOG_STATE -> logDebugState()
                ACTION_DEBUG_DUMP_REPORT -> writeDebugReport("manual-dump")
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
        dumpRecording(seconds, NotifyFileReceiver(this, serviceScope), "")
    }

    private fun injectDebugBuffer(seconds: Float) {
        if (!isDebuggableBuild() || seconds <= 0f || state == STATE_RECORDING) {
            Log.w(TAG, "injectDebugBuffer ignored seconds=$seconds state=$state")
            return
        }
        val logicalRetentionBytes = cachedRetentionSampleBytes
        val persistentBytes = cachedPersistentPcmSizeBytes
        if (persistentBytes <= 0L) {
            Log.w(TAG, "injectDebugBuffer ignored persistent=$persistentBytes")
            return
        }
        val totalBytes = (seconds * fillRate).toLong().coerceAtLeast(0L)
        val chunk = ByteArray(64 * 1024)
        var remaining = totalBytes
        while (remaining > 0L) {
            val count = minOf(chunk.size.toLong(), remaining).toInt()
            persistentAudioRingStore.append(
                array = chunk,
                offset = 0,
                count = count,
                requestedCapacityBytes = persistentBytes,
                requestedSampleRate = sampleRate,
                requestedChannelCount = channelMode.channelCount,
                sampleFormat = pcmSampleFormat,
            )
            remaining -= count.toLong()
        }
        state = STATE_PAUSED
        updateWakeLockState()
        persistentAudioRingStore.checkpoint()
        Log.d(
            TAG,
            "injectDebugBuffer seconds=$seconds logical=$logicalRetentionBytes " +
                "persisted=$persistentBytes " +
                "available=${availableBufferedSampleBytes()}",
        )
        writeDebugReport("inject-buffer-${seconds}s")
    }

    private fun logDebugState() {
        val persisted = persistentAudioRingStore.peekSnapshot()
        Log.d(
            TAG,
            "debug-state state=$state " +
                "sampleRate=$sampleRate channels=${channelMode.channelCount} codec=${outputCodec.prefValue} " +
                "format=${outputFormat.prefValue} logicalRetention=${cachedRetentionSampleBytes} " +
                "persistedFilled=${persisted?.filledBytes ?: 0} persistedCapacity=${persisted?.capacityBytes ?: 0}",
        )
    }

    private fun writeDebugReport(reason: String) {
        val reportFile = resolveDebugReportFile()
        val persisted = persistentAudioRingStore.peekSnapshot()
        val status =
            buildString {
                append("reason=").append(reason)
                append(" state=").append(state)
                append(" sampleRate=").append(sampleRate)
                append(" channelCount=").append(channelMode.channelCount)
                append(" format=").append(outputFormat.prefValue)
                append(" codec=").append(outputCodec.prefValue)
                append(" persistedFilled=").append(persisted?.filledBytes ?: 0)
                append(" persistedCapacity=").append(persisted?.capacityBytes ?: 0)
                append(" persistedLastWrite=").append(persisted?.lastWriteAtMillis ?: 0)
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
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.app_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
    }

    private fun updateWakeLockState() {
        if (!isWakeLockEnabled(this)) {
            releaseWakeLock()
            return
        }

        val shouldHoldWakeLock = state == STATE_LISTENING || state == STATE_RECORDING
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

    interface StateCallback {
        fun state(
            listeningEnabled: Boolean,
            recording: Boolean,
            memorized: Float,
            totalMemory: Float,
            recorded: Float,
        )
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
    )

    enum class ApplySettingsResult {
        APPLIED_NOW,
        BLOCKED_RECORDING,
    }

    companion object {
        val TAG: String = ReverbService::class.java.simpleName
        const val NOTIFICATION_CHANNEL_ID = "ReverbRecorderChannel"
        const val FOREGROUND_NOTIFICATION_ID = 458
        const val MIN_AUDIO_RECORD_BUFFER_SIZE = 16 * 1024
        const val CAPTURE_SCRATCH_BYTES = 64 * 1024
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
        const val ACTION_DEBUG_MERGE_ALL = "${DEBUG_ACTION_PREFIX}MERGE_ALL"
        const val ACTION_DEBUG_APPLY_SETTINGS = "${DEBUG_ACTION_PREFIX}APPLY_SETTINGS"
        const val ACTION_DEBUG_CHECKPOINT = "${DEBUG_ACTION_PREFIX}CHECKPOINT"
        const val ACTION_DEBUG_LOG_STATE = "${DEBUG_ACTION_PREFIX}LOG_STATE"
        const val ACTION_DEBUG_DUMP_REPORT = "${DEBUG_ACTION_PREFIX}DUMP_REPORT"
        const val EXTRA_DEBUG_SECONDS = ReverbConfig.EXTRA_SECONDS
        const val DEBUG_REPORT_FILE_NAME = "debug-report.txt"

        const val WAKE_LOCK_TAG_SUFFIX = ":reverbBuffer"

        const val STATE_READY = 0
        const val STATE_LISTENING = 1
        const val STATE_RECORDING = 2
        const val STATE_PAUSED = 3
    }

}
