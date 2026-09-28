package com.nextbench.core.designsystem

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

private val ButtonShape = RoundedCornerShape(NbDimens.radiusMd)

@Composable
fun NbButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    variant: NbButtonVariant = NbButtonVariant.Primary,
) {
    val scale = remember { Animatable(1f) }
    val colors = NbTheme.colors

    val containerColor = when (variant) {
        NbButtonVariant.Primary -> colors.brandPink
        NbButtonVariant.Secondary -> colors.brandTeal
        NbButtonVariant.Ghost -> Color.Transparent
    }
    val contentColor = when (variant) {
        NbButtonVariant.Ghost -> colors.brandTeal
        else -> Color.White
    }

    val buttonModifier = modifier
        .graphicsLayer { scaleX = scale.value; scaleY = scale.value }
        .pressScale(targetScale = 0.96f)

    when (variant) {
        NbButtonVariant.Ghost -> TextButton(
            onClick = onClick,
            enabled = enabled && !loading,
            modifier = buttonModifier,
            colors = ButtonDefaults.textButtonColors(contentColor = contentColor),
        ) { ButtonContent(text, loading, contentColor) }

        else -> Button(
            onClick = onClick,
            enabled = enabled && !loading,
            modifier = buttonModifier,
            shape = ButtonShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = containerColor,
                contentColor = contentColor,
                disabledContainerColor = containerColor.copy(alpha = 0.38f),
                disabledContentColor = contentColor.copy(alpha = 0.38f),
            ),
            contentPadding = PaddingValues(horizontal = NbDimens.space24, vertical = NbDimens.space12),
        ) { ButtonContent(text, loading, contentColor) }
    }
}

@Composable
fun NbOutlinedButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = NbTheme.colors
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.pressScale(targetScale = 0.96f),
        shape = ButtonShape,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.brandTeal),
        contentPadding = PaddingValues(horizontal = NbDimens.space24, vertical = NbDimens.space12),
    ) {
        Text(text, style = androidx.compose.material3.MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun ButtonContent(text: String, loading: Boolean, contentColor: Color) {
    Box(contentAlignment = Alignment.Center) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                color = contentColor,
                strokeWidth = 2.dp,
            )
        } else {
            Text(text, style = androidx.compose.material3.MaterialTheme.typography.labelLarge)
        }
    }
}

enum class NbButtonVariant { Primary, Secondary, Ghost }
