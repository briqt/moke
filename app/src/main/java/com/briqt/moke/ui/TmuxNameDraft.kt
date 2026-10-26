package com.briqt.moke.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/** Owned by one dialog: only creation starts selected; subsequent edits belong to the user/IME. */
internal class TmuxNameDraft(initial: String, selectAll: Boolean) {
    var value by mutableStateOf(
        TextFieldValue(
            text = initial,
            selection = if (selectAll) TextRange(0, initial.length) else TextRange.Zero,
        ),
    )
        private set

    fun update(value: TextFieldValue) {
        this.value = value
    }
}
