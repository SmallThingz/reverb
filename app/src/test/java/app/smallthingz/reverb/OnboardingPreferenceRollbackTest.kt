package app.smallthingz.reverb

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingPreferenceRollbackTest {
    @Test
    fun rollbackSnapshotPreservesMalformedRawValuesExactly() {
        val raw = mapOf<String, Any?>(
            PrefKey.ONBOARDING_SHOWN.name to "not-a-boolean",
            PrefKey.RETENTION_MODE.name to 9L,
            PrefKey.ONE_SHOT_RETENTION_SECONDS.name to "bad-seconds",
            PrefKey.RETENTION_CONFIG_DIGEST.name to 42,
            PrefKey.OUTPUT_FORMAT.name to "legacy-or-damaged-format",
            PrefKey.SILENCE_GATE.name to 19L,
        )

        val snapshot = onboardingPreferenceRollbackSnapshot(raw)

        assertEquals("not-a-boolean", snapshot.getValue(PrefKey.ONBOARDING_SHOWN).value)
        assertEquals(9L, snapshot.getValue(PrefKey.RETENTION_MODE).value)
        assertEquals("bad-seconds", snapshot.getValue(PrefKey.ONE_SHOT_RETENTION_SECONDS).value)
        assertEquals(42, snapshot.getValue(PrefKey.RETENTION_CONFIG_DIGEST).value)
        assertEquals("legacy-or-damaged-format", snapshot.getValue(PrefKey.OUTPUT_FORMAT).value)
        assertEquals(19L, snapshot.getValue(PrefKey.SILENCE_GATE).value)
    }

    @Test
    fun rollbackSnapshotPreservesMissingKeysWithoutInventingDefaults() {
        val snapshot = onboardingPreferenceRollbackSnapshot(emptyMap<String, Any?>())

        assertTrue(PrefKey.ONBOARDING_SHOWN in snapshot)
        assertTrue(PrefKey.OUTPUT_FORMAT in snapshot)
        assertTrue(PrefKey.SILENCE_GATE in snapshot)
        snapshot.values.forEach { value ->
            assertFalse(value.present)
            assertEquals(null, value.value)
        }
    }

    @Test
    fun rollbackRestoresAudioAndCompletionWithoutClaimingUnsubmittedSettings() {
        val before = linkedMapOf<String, Any?>(
            PrefKey.ONBOARDING_SHOWN.name to false,
            PrefKey.RETENTION_MODE.name to "malformed-mode",
            PrefKey.OUTPUT_FORMAT.name to "FLAC",
            PrefKey.SILENCE_GATE.name to 37,
            PrefKey.FLAC_COMPRESSION_LEVEL.name to 7,
            PrefKey.SILENCE_CALIBRATION_HISTOGRAM.name to "existing measurement",
            PrefKey.AUDIO_MEMORY_ENABLED.name to true,
            PrefKey.PCM_SAMPLE_FORMAT.name to PcmSampleFormat.PCM_FLOAT.storageCode.toInt(),
        )
        val snapshots = onboardingPreferenceRollbackSnapshot(before)
        assertEquals(
            setOf(
                PrefKey.ONBOARDING_SHOWN,
                PrefKey.RETENTION_MODE,
                PrefKey.ONE_SHOT_RETENTION_SECONDS,
                PrefKey.ONE_SHOT_AUDIO_MEMORY_SIZE,
                PrefKey.RETENTION_SECONDS,
                PrefKey.AUDIO_MEMORY_SIZE,
                PrefKey.RETENTION_CONFIG_DIGEST,
                PrefKey.OUTPUT_FORMAT,
                PrefKey.SILENCE_GATE,
            ),
            snapshots.keys,
        )
        val optimisticMap = before.toMutableMap().apply {
            snapshots.keys.forEach { put(it.name, "undurable replacement") }
        }
        val editor = mapEditor(optimisticMap)

        snapshots.forEach { (key, old) -> editor.restoreDurablePreferenceValue(key, old) }

        assertEquals(before, optimisticMap)
    }

    @Test
    fun rollbackKeepsUnrepresentableAudioValuesPresentAndFailClosed() {
        val snapshots = onboardingPreferenceRollbackSnapshot(mapOf(
            PrefKey.OUTPUT_FORMAT.name to null,
            PrefKey.SILENCE_GATE.name to 1.5,
        ))
        val optimisticMap = mutableMapOf<String, Any?>(
            PrefKey.OUTPUT_FORMAT.name to ExportFormat.FLAC.storageCode.toInt(),
            PrefKey.SILENCE_GATE.name to encodeSilenceGate(SilenceGateConfig(enabled = true)),
        )
        val editor = mapEditor(optimisticMap)

        snapshots.forEach { (key, old) -> editor.restoreDurablePreferenceValue(key, old) }

        assertEquals(setOf(PrefKey.OUTPUT_FORMAT.name, PrefKey.SILENCE_GATE.name), optimisticMap.keys)
        assertEquals(emptySet<String>(), optimisticMap[PrefKey.OUTPUT_FORMAT.name])
        assertEquals(emptySet<String>(), optimisticMap[PrefKey.SILENCE_GATE.name])
    }

    private fun mapEditor(values: MutableMap<String, Any?>): SharedPreferences.Editor {
        lateinit var editor: SharedPreferences.Editor
        editor = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java),
        ) { _, method, args ->
            when (method.name) {
                "putString", "putInt", "putLong", "putFloat", "putBoolean", "putStringSet" -> {
                    values[args[0] as String] = args[1]
                    editor
                }
                "remove" -> {
                    values.remove(args[0] as String)
                    editor
                }
                else -> error("Unexpected Editor method ${method.name}")
            }
        } as SharedPreferences.Editor
        return editor
    }
}
