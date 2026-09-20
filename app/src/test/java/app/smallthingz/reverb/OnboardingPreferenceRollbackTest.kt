package app.smallthingz.reverb

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
        )

        val snapshot = onboardingPreferenceRollbackSnapshot(raw)

        assertEquals("not-a-boolean", snapshot.getValue(PrefKey.ONBOARDING_SHOWN).value)
        assertEquals(9L, snapshot.getValue(PrefKey.RETENTION_MODE).value)
        assertEquals("bad-seconds", snapshot.getValue(PrefKey.ONE_SHOT_RETENTION_SECONDS).value)
        assertEquals(42, snapshot.getValue(PrefKey.RETENTION_CONFIG_DIGEST).value)
    }

    @Test
    fun rollbackSnapshotPreservesMissingKeysWithoutInventingDefaults() {
        val snapshot = onboardingPreferenceRollbackSnapshot(emptyMap<String, Any?>())

        assertTrue(PrefKey.ONBOARDING_SHOWN in snapshot)
        snapshot.values.forEach { value ->
            assertFalse(value.present)
            assertEquals(null, value.value)
        }
    }
}
