package app.smallthingz.reverb

/** Released renderer keeps its original MediaPlayer-ready admission contract. */
internal fun canEnterInlineTrim(prepared: Boolean, durationMillis: Int): Boolean =
    prepared && durationMillis > 0
