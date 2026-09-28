package com.nextbench.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import coil.compose.AsyncImage

@Composable
fun NbAvatar(
    imageUrl: String?,
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = NbDimens.avatarMd,
    hasStory: Boolean = false,
    storySeen: Boolean = false,
) {
    val colors = NbTheme.colors
    val ringWidth = 2.dp
    val gap = if (hasStory) 3.dp else 0.dp

    val ringBrush = when {
        !hasStory -> Brush.linearGradient(listOf(Color.Transparent, Color.Transparent))
        storySeen -> Brush.linearGradient(listOf(colors.border, colors.border))
        else -> Brush.linearGradient(listOf(colors.brandPink, colors.brandTeal))
    }

    Box(
        modifier = modifier
            .size(size)
            .then(if (hasStory) Modifier.border(ringWidth, ringBrush, CircleShape) else Modifier)
            .padding(if (hasStory) ringWidth + gap else 0.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (imageUrl.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .size(size)
                    .clip(CircleShape)
                    .background(colors.surfaceSoft),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = name.take(1).uppercase(),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.inkMuted,
                )
            }
        } else {
            AsyncImage(
                model = imageUrl,
                contentDescription = name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(size)
                    .clip(CircleShape)
                    .background(colors.surfaceSoft),
            )
        }
    }
}
