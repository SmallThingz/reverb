package app.smallthingz.reverb.nativeui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Released TextStringSimpleNode substitution lifecycle; original semantics never become translated. */
class ReverbTextSubstitutionStateTest {
    @Test
    fun responseWaitsForShowAndUpdatedResponseKeepsVisibility() {
        var invalidations = 0
        val state = ReverbTextSubstitutionState("Original") { invalidations++ }
        state.setSubstitution("Translated")
        assertEquals("Original", state.original)
        assertEquals("Original", state.displayed)
        assertTrue(state.showSubstitution(true))
        assertEquals("Translated", state.displayed)
        state.setSubstitution("Updated translation")
        assertEquals("Updated translation", state.displayed)
        assertEquals("Original", state.original)
        assertEquals(3, invalidations)
    }

    @Test
    fun hideReshowAndClearRestoreOriginalWithoutChangingItsIdentity() {
        val state = ReverbTextSubstitutionState("Original") {}
        state.setSubstitution("Translation")
        state.showSubstitution(true)
        state.showSubstitution(false)
        assertEquals("Original", state.displayed)
        assertEquals("Translation", state.substitution)
        state.showSubstitution(true)
        assertEquals("Translation", state.displayed)
        state.clearSubstitution()
        assertEquals("Original", state.displayed)
        assertNull(state.substitution)
        assertFalse(state.isShowingSubstitution)
        assertFalse(state.showSubstitution(true))
    }

    @Test
    fun onlyChangingOriginalRetiresThePriorTranslation() {
        val state = ReverbTextSubstitutionState("Original") {}
        state.setSubstitution("Translation")
        state.showSubstitution(true)
        assertFalse(state.replaceOriginal("Original"))
        assertEquals("Translation", state.displayed)
        assertTrue(state.replaceOriginal("New original"))
        assertEquals("New original", state.original)
        assertEquals("New original", state.displayed)
        assertNull(state.substitution)
        assertFalse(state.isShowingSubstitution)
    }

    @Test
    fun emptyTranslationIsAValidDisplayedValue() {
        val state = ReverbTextSubstitutionState("Original") {}
        state.setSubstitution("")
        assertTrue(state.showSubstitution(true))
        assertEquals("", state.displayed)
        assertEquals("Original", state.original)
    }

    @Test
    fun repeatedSuccessfulResponsesStillInvalidateAsReleasedSemanticsDo() {
        var invalidations = 0
        val state = ReverbTextSubstitutionState("Original") { invalidations++ }
        state.setSubstitution("Translation")
        state.setSubstitution("Translation")
        state.showSubstitution(true)
        state.showSubstitution(true)
        assertEquals(4, invalidations)
    }
}
