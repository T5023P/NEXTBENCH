package com.nextbench.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.unit.dp
import com.nextbench.core.designsystem.NbDimens
import com.nextbench.core.designsystem.NbDuration
import com.nextbench.core.designsystem.NbLogo
import com.nextbench.core.designsystem.NbMotion
import com.nextbench.core.designsystem.NbTheme
import kotlinx.coroutines.delay

/**
 * First frame the user sees. The logo settles, then [onFinished] hands off to the
 * real graph; the delay is the animation length, not an artificial wait.
 */
@Composable
fun SplashScreen(
    appReady: Boolean,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var settled by remember { mutableStateOf(false) }
    var animationFinished by remember { mutableStateOf(false) }
    var handedOff by remember { mutableStateOf(false) }

    val logoScale by animateFloatAsState(
        targetValue = if (settled) 1f else 0.82f,
        animationSpec = NbMotion.entryTween(),
        label = "splashLogoScale",
    )
    val contentAlpha by animateFloatAsState(
        targetValue = if (settled) 1f else 0f,
        animationSpec = NbMotion.entryTween(),
        label = "splashAlpha",
    )

    LaunchedEffect(Unit) {
        settled = true
        delay(NbDuration.Entry.toLong())
        animationFinished = true
    }

    LaunchedEffect(animationFinished, appReady) {
        if (animationFinished && appReady && !handedOff) {
            handedOff = true
            onFinished()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(NbTheme.colors.surfaceBase),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.alpha(contentAlpha),
        ) {
            NbLogo(
                size = 88.dp,
                modifier = Modifier.scale(logoScale),
            )
            Spacer(modifier = Modifier.height(NbDimens.space20))
            Text(
                text = "NextBench",
                style = MaterialTheme.typography.headlineMedium,
                color = NbTheme.colors.ink,
            )
            Spacer(modifier = Modifier.height(NbDimens.space8))
            Text(
                text = "Your campus, connected",
                style = MaterialTheme.typography.bodyMedium,
                color = NbTheme.colors.inkMuted,
            )
        }
    }
}
