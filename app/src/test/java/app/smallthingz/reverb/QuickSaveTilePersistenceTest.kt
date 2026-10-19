package app.smallthingz.reverb

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import java.util.ArrayDeque
import org.junit.Assert.*
import org.junit.Test

class QuickSaveTilePersistenceTest {
    private val tile = QuickSaveTileOption(0, "a".repeat(32))
    private val originalHistory = QuickSaveTileAddedHistory(
        mapOf(0 to QuickSaveTileAddedObservation(tile.revision, tile.revision, QuickSaveTileAddedState.NOT_ADDED)),
        setOf(0, 1),
    )
    private fun initial() = linkedMapOf<String, Any?>(
        PrefKey.QUICK_SAVE_TILE_OPTIONS.name to encodeQuickSaveTileOptions(listOf(tile)),
        PrefKey.QUICK_SAVE_TILE_ADDED_STATES.name to encodeQuickSaveTileAddedStates(originalHistory),
        "unrelated" to 42,
    )

    @Test fun failedEditRestoresBothKeysBeforeAnyDefinitionOrStatePublication() {
        val original = initial()
        val fake = FakePreferences(original, false, true)
        val edited = tile.copy(revision = "b".repeat(32), amount = 3)
        val history = quickSaveTileAddedStatesAfterEdit(listOf(tile), listOf(edited), originalHistory)
        var definitions = listOf(tile)
        var publishedHistory = originalHistory
        var publications = 0
        assertFalse(commitQuickSaveTilePreferences(fake.preferences, listOf(edited), history) {
            publications++; definitions = listOf(edited); publishedHistory = history
        })
        assertNotEquals(original, fake.commitMaps.first()) // Fake models Android's map-before-I/O behavior.
        assertEquals(original, fake.values)
        assertEquals(2, fake.commitMaps.size)
        assertEquals(0, publications)
        assertEquals(listOf(tile), definitions)
        assertEquals(originalHistory, publishedHistory)
    }

    @Test fun failedDeleteAndFailedRollbackStillRestoreExactProcessMapWithoutPublishing() {
        val original = initial()
        val fake = FakePreferences(original, false, false)
        val deleted = quickSaveTileAddedStatesAfterEdit(listOf(tile), emptyList(), originalHistory)
        var publications = 0
        assertFalse(commitQuickSaveTilePreferences(fake.preferences, emptyList(), deleted) { publications++ })
        assertEquals(encodeQuickSaveTileOptions(emptyList()), fake.commitMaps.first()[PrefKey.QUICK_SAVE_TILE_OPTIONS.name])
        assertEquals(original, fake.values)
        assertEquals(0, publications)
    }

    @Test fun thrownCommitPreservesOriginalFailureAndRestoresBothKeys() {
        val original = initial()
        val failure = IllegalStateException("synthetic disk failure")
        val fake = FakePreferences(original, failure, true)
        var publications = 0
        val thrown = assertThrows(IllegalStateException::class.java) {
            commitQuickSaveTilePreferences(fake.preferences, emptyList(),
                quickSaveTileAddedStatesAfterEdit(listOf(tile), emptyList(), originalHistory)) { publications++ }
        }
        assertSame(failure, thrown)
        assertEquals(original, fake.values)
        assertEquals(0, publications)
    }

    @Test fun firstSaveFailureAndMalformedOldMetadataPreserveExactAbsenceAndTypes() {
        for (original in listOf<Map<String, Any?>>(mapOf("unrelated" to 42),
            mapOf(PrefKey.QUICK_SAVE_TILE_OPTIONS.name to 17L,
                PrefKey.QUICK_SAVE_TILE_ADDED_STATES.name to setOf("malformed"), "unrelated" to 42))) {
            val fake = FakePreferences(original, false, true)
            var publications = 0
            assertFalse(commitQuickSaveTilePreferences(fake.preferences, listOf(tile), originalHistory) { publications++ })
            assertEquals(original, fake.values)
            assertEquals(0, publications)
        }
    }

    @Test fun successPublishesBothValuesExactlyOnceAfterTheirSingleCommit() {
        val fake = FakePreferences(initial(), true)
        val edited = tile.copy(revision = "b".repeat(32), amount = 3)
        val history = quickSaveTileAddedStatesAfterEdit(listOf(tile), listOf(edited), originalHistory)
        var publications = 0
        assertTrue(commitQuickSaveTilePreferences(fake.preferences, listOf(edited), history) {
            assertEquals(1, fake.commitMaps.size)
            assertEquals(encodeQuickSaveTileOptions(listOf(edited)), fake.values[PrefKey.QUICK_SAVE_TILE_OPTIONS.name])
            assertEquals(encodeQuickSaveTileAddedStates(history), fake.values[PrefKey.QUICK_SAVE_TILE_ADDED_STATES.name])
            publications++
        })
        assertEquals(1, publications)
    }

    @Test fun failedObservationRetryDurablyPublishesUnknownWithoutChangingDefinitionsOrTombstones() {
        val original = initial()
        val fake = FakePreferences(original, false, true, true)
        val added = originalHistory.copy(observations = originalHistory.observations.mapValues { (_, value) ->
            value.copy(state = QuickSaveTileAddedState.ADDED)
        })
        var publications = 0
        assertFalse(commitQuickSaveTilePreferences(fake.preferences, null, added) { publications++ })
        assertEquals(original, fake.values)
        val unknown = originalHistory.copy(observations = originalHistory.observations.mapValues { (_, value) ->
            value.copy(state = QuickSaveTileAddedState.UNKNOWN)
        })
        assertTrue(commitQuickSaveTilePreferences(fake.preferences, null, unknown) { publications++ })
        assertEquals(1, publications)
        assertEquals(original[PrefKey.QUICK_SAVE_TILE_OPTIONS.name], fake.values[PrefKey.QUICK_SAVE_TILE_OPTIONS.name])
        assertEquals(unknown, decodeQuickSaveTileAddedStates(fake.values[PrefKey.QUICK_SAVE_TILE_ADDED_STATES.name] as String))
        assertEquals(originalHistory.usedSlots, unknown.usedSlots)
    }

    private class FakePreferences(initial: Map<String, Any?>, vararg outcomes: Any) {
        val values = LinkedHashMap(initial)
        val commitMaps = mutableListOf<Map<String, Any?>>()
        private val outcomes = ArrayDeque(outcomes.toList())
        val preferences: SharedPreferences = proxy(SharedPreferences::class.java) { method, _ ->
            when (method) {
                "getAll" -> LinkedHashMap(values)
                "edit" -> editor()
                else -> error("Unexpected preference read $method")
            }
        }

        private fun editor(): SharedPreferences.Editor {
            val pending = linkedMapOf<String, Any?>()
            lateinit var editor: SharedPreferences.Editor
            editor = proxy(SharedPreferences.Editor::class.java) { method, args ->
                when (method) {
                    "putString", "putBoolean", "putInt", "putLong", "putFloat", "putStringSet" -> {
                        pending[args[0] as String] = args[1]; editor
                    }
                    "remove" -> { pending[args[0] as String] = null; editor }
                    "commit" -> {
                        pending.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
                        commitMaps += LinkedHashMap(values)
                        when (val result = if (outcomes.isEmpty()) true else outcomes.removeFirst()) {
                            is Throwable -> throw result
                            else -> result as Boolean
                        }
                    }
                    else -> error("Unexpected preference mutation $method")
                }
            }
            return editor
        }

        @Suppress("UNCHECKED_CAST")
        private fun <T> proxy(type: Class<T>, invoke: (String, Array<out Any?>) -> Any?): T =
            Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
                invoke(method.name, args ?: emptyArray())
            } as T
    }
}
