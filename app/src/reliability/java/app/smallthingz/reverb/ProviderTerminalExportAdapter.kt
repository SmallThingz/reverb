package app.smallthingz.reverb

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicBoolean

/** QA lease entry retained outside production; queue, output and terminal handling remain real Service code. */
internal class ProviderTerminalExportAdapter(private val recorder: ReverbService) {
    private val serviceType = ReverbService::class.java
    private val tokenType = serviceType.declaredClasses.single { it.simpleName == "ExportCancellationToken" }
    private val begin = method("beginExport", ReverbService.AudioFileReceiver::class.java)
    private val pending = method("isExportPending", tokenType)
    private val perform = method(
        "performBufferedRangeExport", PersistentAudioChunkStore.RangeLease::class.java,
        ReverbService.AudioFileReceiver::class.java, String::class.java, tokenType,
        RecorderConfigurationSnapshot::class.java, kotlin.jvm.functions.Function0::class.java,
    )
    private val queue = method(
        "queueExportTask", FutureTask::class.java, tokenType, ReverbService.AudioFileReceiver::class.java,
        kotlin.jvm.functions.Function0::class.java,
    )
    private val report = method("reportPersistentStoreFailure", String::class.java, Throwable::class.java)
    private val started = tokenType.getDeclaredField("started").apply { isAccessible = true }

    private fun method(name: String, vararg parameters: Class<*>): Method =
        serviceType.getDeclaredMethod(name, *parameters).apply { isAccessible = true }

    private fun invoke(method: Method, vararg arguments: Any?): Any? = try {
        method.invoke(recorder, *arguments)
    } catch (error: InvocationTargetException) {
        throw error.targetException
    }

    fun begin(receiver: ReverbService.AudioFileReceiver): Any? = invoke(begin, receiver)

    fun submit(
        lease: PersistentAudioChunkStore.RangeLease,
        receiver: ReverbService.AudioFileReceiver,
        newFileName: String,
        exportToken: Any,
        exportConfig: RecorderConfigurationSnapshot,
    ) {
        val leaseClosed = AtomicBoolean(false)
        fun closeLeaseOnce() {
            releaseExportLeaseOnceBestEffort(leaseClosed, lease::close)?.let { error ->
                invoke(report, "release export range", error)
            }
        }
        if (invoke(pending, exportToken) != true) {
            closeLeaseOnce()
            return
        }
        val tokenStarted = started.get(exportToken) as AtomicBoolean
        val close: () -> Unit = ::closeLeaseOnce
        val task = object : FutureTask<Unit>(Callable {
            tokenStarted.set(true)
            invoke(perform, lease, receiver, newFileName, exportToken, exportConfig, close)
            Unit
        }) {
            override fun done() {
                if (!tokenStarted.get()) closeLeaseOnce()
            }
        }
        val afterSubmission: () -> Unit = {}
        invoke(queue, task, exportToken, receiver, afterSubmission)
    }
}
