package com.briqt.moke.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation

/** Show validation after blur or submit, and clear it as soon as the value is corrected. */
@Composable
internal fun FormTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    error: String? = null,
    submitted: Boolean = false,
    hint: String? = null,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: @Composable (() -> Unit)? = null,
) {
    FormTextFieldFeedback(modifier, error, submitted, hint) { fieldModifier, isError, supportingText ->
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            modifier = fieldModifier,
            enabled = enabled,
            singleLine = singleLine,
            minLines = minLines,
            maxLines = maxLines,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            visualTransformation = visualTransformation,
            trailingIcon = trailingIcon,
            isError = isError,
            supportingText = supportingText,
        )
    }
}

/** Retain caller-owned selection and IME composition without changing String field behavior. */
@Composable
internal fun FormTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    error: String? = null,
    submitted: Boolean = false,
    hint: String? = null,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: @Composable (() -> Unit)? = null,
) {
    FormTextFieldFeedback(modifier, error, submitted, hint) { fieldModifier, isError, supportingText ->
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            modifier = fieldModifier,
            enabled = enabled,
            singleLine = singleLine,
            minLines = minLines,
            maxLines = maxLines,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            visualTransformation = visualTransformation,
            trailingIcon = trailingIcon,
            isError = isError,
            supportingText = supportingText,
        )
    }
}

@Composable
private fun FormTextFieldFeedback(
    modifier: Modifier,
    error: String?,
    submitted: Boolean,
    hint: String?,
    content: @Composable (Modifier, Boolean, @Composable (() -> Unit)?) -> Unit,
) {
    var focusedBefore by remember { mutableStateOf(false) }
    var blurred by remember { mutableStateOf(false) }
    val visibleError = error?.takeIf { submitted || blurred }
    content(
        modifier.fillMaxWidth().onFocusChanged {
            if (it.isFocused) focusedBefore = true else if (focusedBefore) blurred = true
        },
        visibleError != null,
        (visibleError ?: hint)?.let { message -> { Text(message) } },
    )
}
