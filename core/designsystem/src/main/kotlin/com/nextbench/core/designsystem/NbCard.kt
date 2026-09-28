package com.nextbench.core.designsystem

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun NbCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = NbTheme.colors
    val shape  = RoundedCornerShape(NbDimens.radiusLg)
    val scope  = rememberCoroutineScope()
    val scale  = remember { Animatable(1f) }

    val pressModifier = if (onClick != null) {
        Modifier
            .graphicsLayer { scaleX = scale.value; scaleY = scale.value }
            .pointerInput(onClick) {
                detectTapGestures(
                    onPress = {
                        scope.launch { scale.animateTo(0.97f, NbMotion.pressSpring()) }
                        tryAwaitRelease()
                        scope.launch { scale.animateTo(1f, NbMotion.bounceSpring()) }
                    },
                    onTap = { onClick() },
                )
            }
            .semantics { role = Role.Button; onClick { onClick(); true } }
    } else Modifier

    Box(
        modifier = modifier
            .then(pressModifier)
            .shadow(
                NbDimens.elevationCard,
                shape,
                ambientColor = colors.shadowBrand,
                spotColor    = colors.shadowBrand,
            )
            .clip(shape)
            .background(colors.surfaceCard)
            .border(1.dp, colors.border, shape),
        content = content,
    )
}
