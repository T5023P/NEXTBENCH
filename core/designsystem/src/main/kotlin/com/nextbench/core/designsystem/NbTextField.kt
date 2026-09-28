package com.nextbench.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun NbTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    label: String = "",
    error: String = "",
    enabled: Boolean = true,
    singleLine: Boolean = true,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    focusRequester: FocusRequester? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    val colors = NbTheme.colors
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val hasError = error.isNotEmpty()

    val borderColor = when {
        hasError -> colors.brandPink
        focused -> colors.brandTeal
        else -> colors.border
    }
    val shape = RoundedCornerShape(NbDimens.radiusMd)

    Column(modifier = modifier) {
        if (label.isNotEmpty()) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = if (hasError) colors.brandPink else colors.inkMuted,
                modifier = Modifier.padding(bottom = NbDimens.space4),
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = singleLine,
            maxLines = maxLines,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            visualTransformation = visualTransformation,
            interactionSource = interactionSource,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.ink),
            cursorBrush = SolidColor(colors.brandTeal),
            modifier = Modifier
                .fillMaxWidth()
                .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier),
            decorationBox = { innerField ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.surfaceCard, shape)
                        .border(1.dp, borderColor, shape)
                        .padding(horizontal = NbDimens.space16, vertical = NbDimens.space14),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (leadingIcon != null) {
                        Box(Modifier.padding(end = NbDimens.space8)) { leadingIcon() }
                    }
                    Box(Modifier.weight(1f)) {
                        if (value.isEmpty()) {
                            Text(
                                text = placeholder,
                                style = MaterialTheme.typography.bodyLarge,
                                color = colors.inkMuted,
                            )
                        }
                        innerField()
                    }
                    if (trailingIcon != null) {
                        Box(Modifier.padding(start = NbDimens.space8)) { trailingIcon() }
                    }
                }
            },
        )
        if (hasError) {
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = colors.brandPink,
                modifier = Modifier.padding(top = NbDimens.space4, start = NbDimens.space4),
            )
        }
    }
}
