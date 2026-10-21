package app.smallthingz.reverb.nativeui

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeRenameFieldMetricsTest {
    @Test fun defaultBodyLineFitsReleased56dpContainer() {
        assertEquals(147,nativeRenameFieldHeight(63,0,2.625f,147,1000))
    }
    @Test fun floatPaddingSumRoundsOnceUnlikePerEdgeConstraints() {
        // Source calculateHeight32.64px padding, not two individually rounded16px edges.
        assertEquals(57,nativeRenameFieldHeight(24,0,1.02f,0,1000))
    }
    @Test fun supportingTextIsOutsideBorderHeightAndIncludedInTotal() {
        assertEquals(76,nativeRenameFieldHeight(24,20,1f,56,1000))
    }
    @Test fun sourceMinAndMaxHeightStillConstrainOversizedContent() {
        assertEquals(90,nativeRenameFieldHeight(24,0,1f,90,1000))
        assertEquals(28,nativeRenameFieldHeight(24,20,1f,0,28))
    }
    @Test fun asciiIsOnlyKeyboardHintAndRetainsSourceAutocorrect() {
        val hints=nativeRenameImeContract()
        assertEquals(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT,hints.inputType)
        assertTrue(hints.autoCorrect)
    }
    @Test fun doneContractDoesNotInheritNativeNextPreviousOrExtractUiFlags() {
        assertEquals(EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_FORCE_ASCII or EditorInfo.IME_FLAG_NO_FULLSCREEN,
            nativeRenameImeContract().imeOptions)
    }
}
