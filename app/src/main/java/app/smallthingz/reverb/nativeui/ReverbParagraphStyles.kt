package app.smallthingz.reverb.nativeui

internal object ReverbParagraphStyles {
    operator fun get(role: ReverbTextRole) = reverbAppearance.typography[role]
}
