package com.nextbench.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun NbSkeletonBox(
    modifier: Modifier = Modifier,
    radius: Dp = NbDimens.radiusMd,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(radius))
            .background(NbTheme.colors.surfaceSoft)
            .shimmer(),
    )
}

@Composable
fun NbSkeletonLine(
    modifier: Modifier = Modifier,
    widthFraction: Float = 1f,
    height: Dp = 14.dp,
) {
    Spacer(
        modifier = modifier
            .fillMaxWidth(widthFraction)
            .height(height)
            .clip(RoundedCornerShape(NbDimens.radiusFull))
            .background(NbTheme.colors.surfaceSoft)
            .shimmer(),
    )
}
