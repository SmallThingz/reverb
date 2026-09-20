package app.smallthingz.reverb

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import java.util.ArrayDeque
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureIntentRollbackTest {
    @Test
    fun failedRepairCommit_restoresExactMalformedProcessValues() {
        val initial = linkedMapOf<String, Any?>(
            PrefKey.AUDIO_MEMORY_ENABLED.name to "malformed-enabled",
            PrefKey.CAPTURE_BUFFER_SLOT.name to 7L,
        )
        val fake = FakeSharedPreferences(initial, false, true)
        val prefs = fake.preferences
        val snapshot = snapshotCaptureIntentPreferences(prefs)

        val committed = prefs.edit()
            .putBoolean(PrefKey.AUDIO_MEMORY_ENABLED.name, true)
            .putInt(PrefKey.CAPTURE_BUFFER_SLOT.name, ReverbService.BufferSlot.LOOPING.storageCode.toInt())
            .commit()
        assertFalse(committed)
        assertTrue(fake.values != initial)

        val rollbackPersisted = restoreCaptureIntentPreferences(
            prefs = prefs,
            snapshot = snapshot,
        )

        assertTrue(rollbackPersisted)
        assertEquals(initial, fake.values)
    }

    @Test
    fun failedSelectionCommit_restoresExactLegacySlotEncoding() {
        val initial = linkedMapOf<String, Any?>(
            PrefKey.AUDIO_MEMORY_ENABLED.name to true,
            PrefKey.CAPTURE_BUFFER_SLOT.name to "ONE_SHOT",
        )
        val fake = FakeSharedPreferences(initial, false, true)
        val prefs = fake.preferences
        val snapshot = snapshotCaptureIntentPreferences(prefs)

        val committed = prefs.edit()
            .putInt(PrefKey.CAPTURE_BUFFER_SLOT.name, ReverbService.BufferSlot.LOOPING.storageCode.toInt())
            .commit()
        assertFalse(committed)

        assertTrue(restoreCaptureIntentPreferences(prefs, snapshot))
        assertEquals(initial, fake.values)
    }

    private class FakeSharedPreferences(
        initial: Map<String, Any?>,
        vararg commitResults: Boolean,
    ) {
        val values = LinkedHashMap(initial)
        private val commitResults = ArrayDeque(commitResults.toList())

        val preferences: SharedPreferences = proxy(SharedPreferences::class.java) { method, args ->
            when (method) {
                "getAll" -> LinkedHashMap(values)
                "contains" -> values.containsKey(args[0] as String)
                "edit" -> editor()
                "getBoolean" -> (values[args[0] as String] as? Boolean) ?: args[1] as Boolean
                "getInt" -> (values[args[0] as String] as? Int) ?: args[1] as Int
                "getLong" -> (values[args[0] as String] as? Long) ?: args[1] as Long
                "getFloat" -> (values[args[0] as String] as? Float) ?: args[1] as Float
                "getString" -> (values[args[0] as String] as? String) ?: args[1] as String?
                "getStringSet" -> @Suppress("UNCHECKED_CAST") ((values[args[0] as String] as? Set<String>) ?: args[1] as Set<String>?)
                "registerOnSharedPreferenceChangeListener", "unregisterOnSharedPreferenceChangeListener" -> Unit
                else -> error("Unexpected SharedPreferences method $method")
            }
        }

        private fun editor(): SharedPreferences.Editor {
            val updates = LinkedHashMap<String, Any?>()
            val removals = LinkedHashSet<String>()
            var clear = false
            lateinit var editor: SharedPreferences.Editor
            editor = proxy(SharedPreferences.Editor::class.java) { method, args ->
                when (method) {
                    "putBoolean", "putInt", "putLong", "putFloat", "putString", "putStringSet" -> {
                        val key = args[0] as String
                        updates[key] = args[1]
                        removals.remove(key)
                        editor
                    }
                    "remove" -> {
                        val key = args[0] as String
                        removals += key
                        updates.remove(key)
                        editor
                    }
                    "clear" -> {
                        clear = true
                        updates.clear()
                        removals.clear()
                        editor
                    }
                    "commit" -> {
                        applyChanges(clear, removals, updates)
                        if (commitResults.isEmpty()) true else commitResults.removeFirst()
                    }
                    "apply" -> {
                        applyChanges(clear, removals, updates)
                        Unit
                    }
                    else -> error("Unexpected Editor method $method")
                }
            }
            return editor
        }

        private fun applyChanges(
            clear: Boolean,
            removals: Set<String>,
            updates: Map<String, Any?>,
        ) {
            if (clear) values.clear()
            removals.forEach(values::remove)
            updates.forEach { (key, value) ->
                if (value == null) values.remove(key) else values[key] = value
            }
        }

        @Suppress("UNCHECKED_CAST")
        private fun <T> proxy(
            type: Class<T>,
            invoke: (method: String, args: Array<out Any?>) -> Any?,
        ): T = Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
            invoke(method.name, args ?: emptyArray())
        } as T
    }
}
