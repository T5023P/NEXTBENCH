package com.nextbench.core.designsystem

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

enum class NbToastKind { Info, Success, Error }

data class NbToastState(
    val message: String = "",
    val kind: NbToastKind = NbToastKind.Info,
    val visible: Boolean = false,
)

@Composable
fun NbToast(
    state: NbToastState,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    durationMs: Long = 3000L,
) {
    val colors = NbTheme.colors
    val containerColor = when (state.kind) {
        NbToastKind.Success -> colors.brandMint
        NbToastKind.Error -> colors.brandPink
        NbToastKind.Info -> colors.ink
    }

    LaunchedEffect(state.visible, state.message) {
        if (state.visible) {
            delay(durationMs)
            onDismiss()
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = NbDimens.space16, vertical = NbDimens.space8),
        contentAlignment = Alignment.BottomCenter,
    ) {
        AnimatedVisibility(
            visible = state.visible,
            enter = fadeIn(NbMotion.interactionTween()) + slideInVertically(NbMotion.interactionTween()) { it },
            exit = fadeOut(NbMotion.interactionTween()) + slideOutVertically(NbMotion.interactionTween()) { it },
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(NbDimens.radiusMd))
                    .background(containerColor)
                    .padding(horizontal = NbDimens.space16, vertical = NbDimens.space12),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
