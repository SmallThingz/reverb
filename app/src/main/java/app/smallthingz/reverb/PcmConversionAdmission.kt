package app.smallthingz.reverb

private val pcmConversionAdmission = ThreadLocal<(() -> Unit)>()

internal fun checkPcmConversionAdmission() {
    pcmConversionAdmission.get()?.invoke()
}

/** Worker-local admission only; nested reads retain their caller's cancellation boundary. */
internal fun <T> withPcmConversionAdmission(checkAdmission: () -> Unit, operation: () -> T): T {
    val previous = pcmConversionAdmission.get()
    val current: () -> Unit = if (previous == null) checkAdmission else {
        { previous(); checkAdmission() }
    }
    pcmConversionAdmission.set(current)
    try {
        checkPcmConversionNotInterrupted()
        val result = operation()
        checkPcmConversionNotInterrupted()
        return result
    } finally {
        if (previous == null) pcmConversionAdmission.remove()
        else pcmConversionAdmission.set(previous)
    }
}
