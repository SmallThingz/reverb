package app.smallthingz.reverb

/** The retained released renderer uses its original Compose Parcelables only in QA. */
internal fun released012StateValue(raw: Any): Any? =
    (raw as? androidx.compose.runtime.State<*>)?.value
