package com.nextbench.core.designsystem

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.launch

fun Modifier.pressScale(
    targetScale: Float = 0.94f,
    onTap: (() -> Unit)? = null,
): Modifier = composed {
    val scale = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    val gestureModifier = this
        .graphicsLayer {
            scaleX = scale.value
            scaleY = scale.value
        }
        .pointerInput(onTap) {
            detectTapGestures(
                onPress = {
                    scope.launch { scale.animateTo(targetScale, NbMotion.pressSpring()) }
                    tryAwaitRelease()
                    // Tiny spring overshoot on release makes the tap feel satisfying.
                    scope.launch { scale.animateTo(1f, NbMotion.bounceSpring()) }
                },
                onTap = { onTap?.invoke() },
            )
        }

    if (onTap == null) {
        gestureModifier
    } else {
        gestureModifier.semantics {
            onClick {
                onTap()
                true
            }
        }
    }
}

fun Modifier.shimmer(
    baseColor: Color = Color(0x1A000000),
    highlightColor: Color = Color(0x33FFFFFF),
): Modifier = composed {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val progress by transition.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Restart),
        label = "shimmer_progress",
    )
    drawWithContent {
        drawContent()
        val w = size.width
        val x = w * progress
        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(baseColor, highlightColor, baseColor),
                start = Offset(x - w * 0.5f, 0f),
                end = Offset(x + w * 0.5f, size.height),
            ),
        )
    }
}

fun Modifier.staggeredEntrance(index: Int): Modifier = composed {
    val alphaAnim = remember { Animatable(0f) }
    val tyAnim    = remember { Animatable(16f) }
    val scaleAnim = remember { Animatable(0.96f) }
    LaunchedEffect(index) {
        val delay = (index * NbDuration.Stagger).toLong()
        launch {
            kotlinx.coroutines.delay(delay)
            alphaAnim.animateTo(1f, NbMotion.entryTween())
        }
        launch {
            kotlinx.coroutines.delay(delay)
            tyAnim.animateTo(0f, NbMotion.floatSpring())
        }
        launch {
            kotlinx.coroutines.delay(delay)
            scaleAnim.animateTo(1f, NbMotion.floatSpring())
        }
    }
    graphicsLayer {
        alpha        = alphaAnim.value
        translationY = tyAnim.value
        scaleX       = scaleAnim.value
        scaleY       = scaleAnim.value
    }
}
