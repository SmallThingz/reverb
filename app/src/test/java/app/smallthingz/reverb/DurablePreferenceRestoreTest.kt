package app.smallthingz.reverb

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DurablePreferenceRestoreTest {
    @Test
    fun representabilityDistinguishesEditorRoundTrippableValues() {
        assertTrue(
            durablePreferenceSnapshotIsExactlyRestorable(
                DurablePreferenceValueSnapshot(present = false, value = null),
            ),
        )
        assertTrue(
            durablePreferenceSnapshotIsExactlyRestorable(
                DurablePreferenceValueSnapshot(present = true, value = "legacy"),
            ),
        )
        assertTrue(
            durablePreferenceSnapshotIsExactlyRestorable(
                DurablePreferenceValueSnapshot(present = true, value = setOf("a", "b")),
            ),
        )
        assertFalse(
            durablePreferenceSnapshotIsExactlyRestorable(
                DurablePreferenceValueSnapshot(present = true, value = null),
            ),
        )
        assertFalse(
            durablePreferenceSnapshotIsExactlyRestorable(
                DurablePreferenceValueSnapshot(present = true, value = 1.5),
            ),
        )
        assertFalse(
            durablePreferenceSnapshotIsExactlyRestorable(
                DurablePreferenceValueSnapshot(present = true, value = setOf(1, 2)),
            ),
        )
    }

    @Test
    fun unrepresentableScalarRollbackUsesWrongTypeSetInsteadOfAbsence() {
        val calls = mutableListOf<EditorCall>()
        val editor = recordingEditor(calls)

        editor.restoreDurablePreferenceValue(
            PrefKey.RETENTION_MODE,
            DurablePreferenceValueSnapshot(present = true, value = null),
        )

        assertEquals(
            listOf(EditorCall("putStringSet", PrefKey.RETENTION_MODE.name, emptySet<String>())),
            calls,
        )
    }

    @Test
    fun unrepresentableStringSetRollbackUsesWrongTypeScalarInsteadOfEmptyJournal() {
        val calls = mutableListOf<EditorCall>()
        val editor = recordingEditor(calls)

        editor.restoreDurablePreferenceValue(
            PrefKey.PENDING_RECORDING_DELETIONS,
            DurablePreferenceValueSnapshot(present = true, value = setOf(1L)),
        )

        assertEquals(1, calls.size)
        assertEquals("putString", calls.single().method)
        assertEquals(PrefKey.PENDING_RECORDING_DELETIONS.name, calls.single().key)
        assertTrue((calls.single().value as String).isNotBlank())
    }

    @Test
    fun absentAndRepresentableValuesStillRestoreExactly() {
        val calls = mutableListOf<EditorCall>()
        val editor = recordingEditor(calls)

        editor.restoreDurablePreferenceValue(
            PrefKey.EXPORT_DIRECTORY_URI,
            DurablePreferenceValueSnapshot(present = false, value = null),
        )
        editor.restoreDurablePreferenceValue(
            PrefKey.CAPTURE_BUFFER_SLOT,
            DurablePreferenceValueSnapshot(present = true, value = "ONE_SHOT"),
        )

        assertEquals(
            listOf(
                EditorCall("remove", PrefKey.EXPORT_DIRECTORY_URI.name, null),
                EditorCall("putString", PrefKey.CAPTURE_BUFFER_SLOT.name, "ONE_SHOT"),
            ),
            calls,
        )
    }

    private data class EditorCall(
        val method: String,
        val key: String,
        val value: Any?,
    )

    @Suppress("UNCHECKED_CAST")
    private fun recordingEditor(calls: MutableList<EditorCall>): SharedPreferences.Editor {
        lateinit var editor: SharedPreferences.Editor
        editor = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java),
        ) { _, method, args ->
            when (method.name) {
                "putString", "putInt", "putLong", "putFloat", "putBoolean", "putStringSet" -> {
                    calls += EditorCall(method.name, args[0] as String, args[1])
                    editor
                }
                "remove" -> {
                    calls += EditorCall(method.name, args[0] as String, null)
                    editor
                }
                else -> error("Unexpected Editor method " + method.name)
            }
        } as SharedPreferences.Editor
        return editor
    }
}
