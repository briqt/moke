package com.briqt.moke.ui

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TmuxNameDraftTest {
    @Test
    fun creationSelectsThePrefilledName() {
        val draft = TmuxNameDraft("moke", selectAll = true)

        assertEquals("moke", draft.value.text)
        assertEquals(TextRange(0, 4), draft.value.selection)
        assertNull(draft.value.composition)
    }

    @Test
    fun renameKeepsTheStringFieldsOriginalInitialSelection() {
        val draft = TmuxNameDraft("existing", selectAll = false)

        assertEquals("existing", draft.value.text)
        assertEquals(TextRange.Zero, draft.value.selection)
        assertNull(draft.value.composition)
    }

    @Test
    fun emptyNameHasAnEmptySelection() {
        val draft = TmuxNameDraft("", selectAll = true)

        assertEquals("", draft.value.text)
        assertEquals(TextRange.Zero, draft.value.selection)
        assertNull(draft.value.composition)
    }

    @Test
    fun fullSelectionUsesUtf16OffsetsAndDoesNotTrimTheName() {
        val draft = TmuxNameDraft(" 墨🚀é ", selectAll = true)

        assertEquals(" 墨🚀é ", draft.value.text)
        assertEquals(TextRange(0, 7), draft.value.selection)
    }

    @Test
    fun replacementIsStoredWithoutSelectingItAgain() {
        val draft = TmuxNameDraft("default", selectAll = true)
        val replacement = TextFieldValue("new", selection = TextRange(3))

        draft.update(replacement)

        assertEquals(replacement, draft.value)
    }

    @Test
    fun caretAndReversedSelectionChangesAreRetainedWithoutTextChanges() {
        val draft = TmuxNameDraft("default", selectAll = true)
        val caret = TextFieldValue("default", selection = TextRange(2))
        draft.update(caret)
        assertEquals(caret, draft.value)

        val selection = caret.copy(selection = TextRange(6, 1))
        draft.update(selection)
        assertEquals(selection, draft.value)
    }

    @Test
    fun editsKeepTheIncomingSelectionAndImeComposition() {
        val draft = TmuxNameDraft("default", selectAll = true)
        val composing = TextFieldValue(
            " 墨🚀 ", selection = TextRange(4), composition = TextRange(1, 4),
        )
        draft.update(composing)
        assertEquals(composing, draft.value)
        assertEquals(TextRange(1, 4), draft.value.composition)

        val selectionOnly = composing.copy(selection = TextRange(4, 1))
        draft.update(selectionOnly)
        assertEquals(selectionOnly, draft.value)
        assertEquals(TextRange(1, 4), draft.value.composition)

        val committed = selectionOnly.copy(selection = TextRange(4), composition = null)
        draft.update(committed)
        assertEquals(committed, draft.value)
        assertNull(draft.value.composition)
    }
}
