package com.nextbench.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign

/**
 * A centered empty / zero-state: a large brand-tinted glyph, a Playfair title,
 * a muted message, and an optional action slot (e.g. a button).
 */
@Composable
fun NbEmptyState(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(NbDimens.space32),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(NbDimens.space16, Alignment.CenterVertically),
    ) {
        Box(
            modifier = Modifier
                .size(NbDimens.avatarXl)
                .clip(CircleShape)
                .background(NbTheme.colors.brandTeal.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = NbTheme.colors.brandTeal,
                modifier = Modifier.size(NbDimens.avatarMd),
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = NbTheme.colors.ink,
            textAlign = TextAlign.Center,
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = NbTheme.colors.inkMuted,
            textAlign = TextAlign.Center,
        )
        action?.invoke()
    }
}
