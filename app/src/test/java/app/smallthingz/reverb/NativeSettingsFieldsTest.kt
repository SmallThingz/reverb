package app.smallthingz.reverb

import org.junit.Assert.*
import org.junit.Test

class NativeSettingsFieldsTest {
    private val routes = listOf(InputRouteMode.AUTO)
    private val sources = listOf(AudioSourceMode.MIC)
    private val base = SettingsSnapshot(
        format = ExportFormat.WAV, codec = ExportCodec.PCM_16,
        source = AudioSourceMode.MIC, channelMode = ChannelMode.MONO,
        route = InputRouteMode.AUTO, sampleRate = 48_000,
    )

    @Test fun restoringFieldsRetainsAllFourRawStringsAndExactBackingValues() {
        val saved = SettingsUiDraft.normalized(base.copy(
            oneShotRetentionTime = 123, oneShotRetentionSizeBytes = 4097,
            loopingRetentionTime = 456, loopingRetentionSizeBytes = 8193,
        )).copy(oneShotTimeText = "unfinished:", oneShotSizeText = "0.00",
            loopingTimeText = "", loopingSizeText = " 1,2 ")
        val actual = resolveNativeSettingsRestoredDraft(saved, routes, sources)
        assertEquals(saved, actual)
    }

    @Test fun restoredNonpositiveRateUsesTheFreshBaselineRate() {
        val saved = SettingsUiDraft.normalized(base.copy(sampleRate = 0))
        val actual = resolveNativeSettingsRestoredDraft(saved, routes, sources, fallbackRate = 32_000)
        assertEquals(32_000, actual.snapshot.sampleRate)
        assertEquals(32_000, nativeSettingsAudioOptions(actual.snapshot, routes, sources).rates.first())
    }

    @Test fun positiveCustomRateRemainsAnExactOption() {
        val actual = resolveNativeSettingsRestoredDraft(
            SettingsUiDraft.normalized(base.copy(sampleRate = 12_345)), routes, sources)
        assertEquals(12_345, actual.snapshot.sampleRate)
        val options = nativeSettingsAudioOptions(actual.snapshot, routes, sources)
        assertEquals(12_345, options.rates.first())
        assertEquals(1, options.rates.count { it == 12_345 })
    }

    @Test fun restoredRouteKeepsTheReleasedUnconditionalRouteWhileSourceIsResolved() {
        val saved = SettingsUiDraft.normalized(base.copy(
            route = InputRouteMode.BUILTIN_MIC, source = AudioSourceMode.VOICE_CALL))
        val actual = resolveNativeSettingsRestoredDraft(saved, routes, sources)
        assertEquals(InputRouteMode.BUILTIN_MIC, actual.snapshot.route)
        assertEquals(AudioSourceMode.MIC, actual.snapshot.source)
    }
}
