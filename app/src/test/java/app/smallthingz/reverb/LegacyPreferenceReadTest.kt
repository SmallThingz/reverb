package app.smallthingz.reverb

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LegacyPreferenceReadTest {
    @Test
    fun namedNullCaptureAuthorityIsCorruptionRatherThanFirstInstall() {
        for (key in listOf(PrefKey.AUDIO_MEMORY_ENABLED, PrefKey.CAPTURE_BUFFER_SLOT)) {
            assertThrows(IllegalStateException::class.java) {
                decodeDurableCaptureIntentPreferences(mapOf(key.name to null))
            }
        }
        assertEquals(
            DurableCaptureIntentPreferences(false, null),
            decodeDurableCaptureIntentPreferences(emptyMap<String, Any?>()),
        )
    }

    @Test
    fun legacySettingsReadCannotOverwriteConcurrentSave() {
        val values = mutableMapOf<String, Any>(PrefKey.CHANNEL_MODE.name to "stereo")
        val prefs = preferences(values) {
            values[PrefKey.CHANNEL_MODE.name] = ChannelMode.MONO.storageCode.toInt()
        }

        assertEquals(
            ChannelMode.STEREO,
            readByteBackedPreference(
                prefs, PrefKey.CHANNEL_MODE, ChannelMode.MONO,
                ChannelMode::fromStorageCode, ChannelMode::fromLegacyPrefValue,
            ),
        )
        assertEquals(ChannelMode.MONO.storageCode.toInt(), values[PrefKey.CHANNEL_MODE.name])
    }

    @Test
    fun legacyTileReadCannotOverwriteConcurrentCaptureHandoff() {
        val values = mutableMapOf<String, Any>(PrefKey.CAPTURE_BUFFER_SLOT.name to "ONE_SHOT")
        val prefs = preferences(values) {
            values[PrefKey.CAPTURE_BUFFER_SLOT.name] = ReverbService.BufferSlot.LOOPING.storageCode.toInt()
        }

        assertEquals(ReverbService.BufferSlot.ONE_SHOT, readCaptureBufferSlotPreference(prefs))
        assertEquals(
            ReverbService.BufferSlot.LOOPING.storageCode.toInt(),
            values[PrefKey.CAPTURE_BUFFER_SLOT.name],
        )
    }

    @Test
    fun canonicalSettingsRemainReadableWithoutAnEditor() {
        val prefs = preferences(mutableMapOf(PrefKey.CHANNEL_MODE.name to 2))
        assertEquals(
            ChannelMode.STEREO,
            readByteBackedPreference(
                prefs, PrefKey.CHANNEL_MODE, ChannelMode.MONO,
                ChannelMode::fromStorageCode, ChannelMode::fromLegacyPrefValue,
            ),
        )
    }

    private fun preferences(
        values: MutableMap<String, out Any>,
        afterLegacySample: () -> Unit = {},
    ): SharedPreferences = Proxy.newProxyInstance(
        SharedPreferences::class.java.classLoader,
        arrayOf(SharedPreferences::class.java),
    ) { _, method, args ->
        when (method.name) {
            "getInt" -> (values[args!![0]] ?: args[1]) as Int
            "getString" -> ((values[args!![0]] ?: args[1]) as String?).also {
                afterLegacySample()
            }
            "edit" -> error("A preference read must never acquire a writer")
            else -> error("Unexpected preference operation: ${method.name}")
        }
    } as SharedPreferences
}
