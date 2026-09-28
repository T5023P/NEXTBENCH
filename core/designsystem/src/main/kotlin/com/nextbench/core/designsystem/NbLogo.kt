package com.nextbench.core.designsystem

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Exact brand mark shared with the NextBench website. */
@Composable
fun NbLogo(
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
) {
    Image(
        painter = painterResource(R.drawable.nextbench_logo),
        contentDescription = "NextBench",
        contentScale = ContentScale.Fit,
        modifier = modifier.size(size),
    )
}
