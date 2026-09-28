package com.nextbench.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.unit.dp

/**
 * A frosted-glass surface bar (used for bottom nav and top bars).
 * Blur is a best-effort effect; on older APIs it degrades gracefully to the semi-opaque fill.
 */
@Composable
fun NbGlassBar(
    modifier: Modifier = Modifier,
    applyNavBarInsets: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = NbTheme.colors
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.glassBg)
            .border(
                width = 1.dp,
                color = colors.glassBorder,
                shape = RoundedCornerShape(topStart = NbDimens.radiusLg, topEnd = NbDimens.radiusLg),
            )
            .then(if (applyNavBarInsets) Modifier.windowInsetsPadding(WindowInsets.navigationBars) else Modifier),
        content = content,
    )
}
