package com.nextbench.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** A mint circle enclosing a white check — the inline verified tick shown beside names. */
@Composable
fun NbVerifiedBadge(
    modifier: Modifier = Modifier,
    size: Dp = 16.dp,
    color: Color = NbTheme.colors.brandMint,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(color),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = NbIcons.Check,
            contentDescription = "Verified",
            tint = Color.White,
            modifier = Modifier.size(size * 0.68f),
        )
    }
}

/** The pill-shaped "VERIFIED" label: mint text on a translucent mint fill with a mint border. */
@Composable
fun NbVerifiedPill(
    modifier: Modifier = Modifier,
    label: String = "VERIFIED",
    color: Color = NbTheme.colors.brandMint,
) {
    Text(
        text = label,
        style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
        color = color,
        textAlign = TextAlign.Center,
        modifier = modifier
            .clip(RoundedCornerShape(NbDimens.radiusFull))
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.30f), RoundedCornerShape(NbDimens.radiusFull))
            .padding(horizontal = NbDimens.space8, vertical = NbDimens.space2),
    )
}
