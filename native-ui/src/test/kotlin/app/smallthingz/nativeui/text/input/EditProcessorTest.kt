package app.smallthingz.nativeui.text.input

import app.smallthingz.nativeui.text.TextRange
import org.junit.Assert.*
import org.junit.Test

class EditProcessorTest {
    private fun processor(text: String, start: Int, end: Int = start) = EditProcessor().also {
        it.reset(TextFieldValue(text, TextRange(start, end)), null)
    }

    @Test fun snapshotClampsEndpointsWithoutReversingSelection() {
        val value = TextFieldValue("abcde", TextRange(10, 3), TextRange(1, 9))
        assertEquals(TextRange(5, 3), value.selection)
        assertEquals(TextRange(1, 5), value.composition)
    }

    @Test fun commitReplacesTheSelection() {
        val result = processor("abcdef", 5, 2).apply(listOf(CommitTextCommand("XY", 1)))
        assertEquals("abXYf", result.text)
        assertEquals(TextRange(4), result.selection)
    }

    @Test fun selectionCommandsPreserveTheOriginalHandleDirection() {
        val result = processor("abcd", 3, 0).apply(listOf(SetSelectionCommand(0, 2)))
        assertEquals(TextRange(2, 0), result.selection)
    }

    @Test fun compositionRegionClampsAndSortsItsEndpoints() {
        val result = processor("abc", 1).apply(listOf(SetComposingRegionCommand(9, -4)))
        assertEquals(TextRange(0, 3), result.composition)
    }

    @Test fun commitReplacesCompositionBeforeSelection() {
        val result = processor("abcdef", 0).apply(listOf(
            SetComposingRegionCommand(2, 5), CommitTextCommand("XY", 1)))
        assertEquals("abXYf", result.text)
        assertEquals(TextRange(4), result.selection)
        assertNull(result.composition)
    }

    @Test fun emptyComposingReplacementRemovesThePreviousComposition() {
        val result = processor("abcdef", 0).apply(listOf(
            SetComposingRegionCommand(2, 5), SetComposingTextCommand("", 1)))
        assertEquals("abf", result.text)
        assertEquals(TextRange(2), result.selection)
        assertNull(result.composition)
    }

    @Test fun cursorPositionsRemainRelativeToTheInsertedText() {
        for ((requested, expected) in listOf(-1 to 1, 0 to 2, 1 to 4, 2 to 5, Int.MAX_VALUE to 0)) {
            val result = processor("abcd", 2).apply(listOf(CommitTextCommand("XY", requested)))
            assertEquals("requested=$requested", TextRange(expected), result.selection)
        }
    }

    @Test fun surroundingDeletionExcludesTheSelectedText() {
        val result = processor("abcdefgh", 2, 5).apply(listOf(DeleteSurroundingTextCommand(2, 1)))
        assertEquals("cdegh", result.text)
        assertEquals(TextRange(0, 3), result.selection)
    }

    @Test fun codePointDeletionConsumesWholeSurrogatePairs() {
        val result = processor("a\uD83D\uDE00XY\uD83D\uDE00b", 3, 5)
            .apply(listOf(DeleteSurroundingTextInCodePointsCommand(1, 1)))
        assertEquals("aXYb", result.text)
        assertEquals(TextRange(1, 3), result.selection)
    }

    @Test fun loneSurrogatesKeepTheReleasedCommandBehavior() {
        // The released implementation consumes each unpaired UTF-16 unit, despite its KDoc.
        val result = processor("a\uD800X\uDC00b", 2, 3)
            .apply(listOf(DeleteSurroundingTextInCodePointsCommand(1, 1)))
        assertEquals("aXb", result.text)
        assertEquals(TextRange(1, 2), result.selection)
    }

    @Test fun hugeSurroundingLengthsKeepSelectionAndCannotWrap() {
        for (command in listOf(DeleteSurroundingTextCommand(Int.MAX_VALUE, Int.MAX_VALUE),
            DeleteSurroundingTextInCodePointsCommand(Int.MAX_VALUE, Int.MAX_VALUE))) {
            val result = processor("abcdefgh", 3, 5).apply(listOf(command))
            assertEquals("de", result.text)
            assertEquals(TextRange(0, 2), result.selection)
        }
    }

    @Test fun negativeDeleteLengthsAreRejectedBeforeApplyingCommands() {
        assertThrows(IllegalArgumentException::class.java) { DeleteSurroundingTextCommand(-1, 0) }
        assertThrows(IllegalArgumentException::class.java) { DeleteSurroundingTextInCodePointsCommand(0, -1) }
    }

    @Test fun textQueriesUseSelectionMinAndMaxAndClampHugeLengths() {
        val value = TextFieldValue("abcdef", TextRange(4, 2))
        assertEquals("ab", value.getTextBeforeSelection(Int.MAX_VALUE).text)
        assertEquals("ef", value.getTextAfterSelection(Int.MAX_VALUE).text)
        assertEquals("cd", value.getSelectedText().text)
        assertEquals("", value.getTextBeforeSelection(0).text)
    }

    @Test fun reversedTextQueryBoundsPreserveReleasedArgumentRejection() {
        val value = TextFieldValue("abcdef", TextRange(4, 2))
        assertThrows(IllegalArgumentException::class.java) { value.getTextBeforeSelection(-1) }
        assertThrows(IllegalArgumentException::class.java) { value.getTextAfterSelection(-1) }
        assertThrows(IllegalArgumentException::class.java) { value.getTextAfterSelection(Int.MIN_VALUE) }
    }

    @Test fun wholeSelectedTextCanReuseItsImmutableSnapshot() {
        val value = TextFieldValue("abcdef", TextRange(6, 0))
        assertSame(value.annotatedString, value.getSelectedText())
    }

    @Test fun replacingOwnerTextCommitsComposition() {
        val p = processor("abcdef", 2)
        p.apply(listOf(SetComposingRegionCommand(1, 4)))
        p.reset(TextFieldValue("uvwxyz", TextRange(2), TextRange(1, 4)), null)
        assertNull(p.toTextFieldValue().composition)
        assertEquals("uvwxyz", p.apply(emptyList()).text)
    }

    @Test fun changingOnlyTheOwnerCompositionCommitsIt() {
        val p = processor("abcdef", 2)
        val before = p.apply(listOf(SetComposingRegionCommand(1, 4)))
        p.reset(before.copy(composition = TextRange(0, 1)), null)
        assertNull(p.toTextFieldValue().composition)
        assertNull(p.apply(emptyList()).composition)
    }

    @Test fun ownerSelectionChangeCanRetainItsComposition() {
        val p = processor("abcdef", 2)
        p.apply(listOf(SetComposingRegionCommand(1, 4)))
        p.reset(TextFieldValue("abcdef", TextRange(3), TextRange(2, 5)), null)
        assertEquals(TextRange(2, 5), p.apply(emptyList()).composition)
    }

    @Test fun deletionAdjustsTheCompositionWithoutCommittingUnrelatedText() {
        val result = processor("abcdefg", 3, 4).apply(listOf(
            SetComposingRegionCommand(1, 5), DeleteSurroundingTextCommand(1, 1)))
        assertEquals("abdfg", result.text)
        assertEquals(TextRange(2, 3), result.selection)
        assertEquals(TextRange(1, 3), result.composition)
    }

    @Test fun finishCompositionKeepsTextAndSelection() {
        val result = processor("abc", 1, 2).apply(listOf(
            SetComposingRegionCommand(0, 3), FinishComposingTextCommand()))
        assertEquals("abc", result.text)
        assertEquals(TextRange(1, 2), result.selection)
        assertNull(result.composition)
    }

    @Test fun batchFailureMessageDoesNotExposeUserEnteredText() {
        val secret = "private recording name"
        val failure = assertThrows(RuntimeException::class.java) {
            processor("", 0).apply(listOf(CommitTextCommand(secret, 1), object : EditCommand {
                override fun applyTo(buffer: EditingBuffer) { throw IllegalStateException("test failure") }
                override fun toString() = secret
            }))
        }
        assertFalse(failure.message!!.contains(secret))
        assertTrue(failure.message!!.contains("text.length=${secret.length}"))
        assertTrue(failure.cause is IllegalStateException)
    }

    @Test fun resetNotifiesNativeSessionAfterTheNewStateIsInstalled() {
        val p = processor("abc", 1)
        var called = false
        p.reset(TextFieldValue("abcd", TextRange(4)), TextInputSession { old, next ->
            assertEquals("abc", old.text)
            assertEquals("abcd", next.text)
            assertSame(next, p.toTextFieldValue())
            called = true
        })
        assertTrue(called)
    }
}
