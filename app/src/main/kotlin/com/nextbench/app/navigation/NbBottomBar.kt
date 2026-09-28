package com.nextbench.app.navigation

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.nextbench.core.designsystem.NbCountBadge
import com.nextbench.core.designsystem.NbDimens
import com.nextbench.core.designsystem.NbGlassBar
import com.nextbench.core.designsystem.NbMotion
import com.nextbench.core.designsystem.NbTheme
import com.nextbench.core.designsystem.pressScale

/**
 * The frosted 5-tab bar. The centre Create tab is a raised solid action rather
 * than an icon-and-label column, so it reads as an action, not a destination.
 */
@Composable
fun NbBottomBar(
    selected: NbTab,
    onSelect: (NbTab) -> Unit,
    modifier: Modifier = Modifier,
    unreadMessages: Int = 0,
) {
    NbGlassBar(modifier = modifier, applyNavBarInsets = true) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(NbDimens.bottomBarHeight),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NbTab.entries.forEach { tab ->
                if (tab.isAccent) {
                    CreateTabItem(
                        tab = tab,
                        onSelect = onSelect,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    TabItem(
                        tab = tab,
                        selected = tab == selected,
                        badgeCount = if (tab == NbTab.Messages) unreadMessages else 0,
                        onSelect = onSelect,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun TabItem(
    tab: NbTab,
    selected: Boolean,
    badgeCount: Int,
    onSelect: (NbTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = NbTheme.colors

    val tint by animateColorAsState(
        targetValue = if (selected) colors.brandPink else colors.inkMuted,
        animationSpec = NbMotion.interactionTween(),
        label = "tab_tint",
    )
    // Spring-driven scale: selected icon pops up slightly with a bounce
    val iconScale by animateFloatAsState(
        targetValue = if (selected) 1.18f else 1f,
        animationSpec = NbMotion.bounceSpring(),
        label = "tab_icon_scale",
    )
    // Pill indicator width: expands when selected
    val pillWidth by animateDpAsState(
        targetValue = if (selected) 24.dp else 0.dp,
        animationSpec = NbMotion.floatSpring(),
        label = "tab_pill_w",
    )

    Column(
        modifier = modifier
            .selectable(
                selected = selected,
                onClick = { onSelect(tab) },
                role = Role.Tab,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            )
            .padding(vertical = NbDimens.space8),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(NbDimens.space2),
    ) {
        // Animated selection pill above icon
        Box(
            modifier = Modifier
                .height(3.dp)
                .width(pillWidth)
                .clip(RoundedCornerShape(NbDimens.radiusFull))
                .background(colors.brandPink),
        )

        Box(contentAlignment = Alignment.TopEnd) {
            Icon(
                imageVector = tab.icon,
                contentDescription = tab.label,
                tint = tint,
                modifier = Modifier
                    .size(24.dp)
                    .graphicsLayer {
                        scaleX = iconScale
                        scaleY = iconScale
                    },
            )
            NbCountBadge(
                count = badgeCount,
                modifier = Modifier.offset(x = 10.dp, y = (-6).dp),
            )
        }
        Text(
            text = tab.label,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
        )
    }
}

@Composable
private fun CreateTabItem(
    tab: NbTab,
    onSelect: (NbTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = NbTheme.colors
    Box(
        modifier = modifier.padding(vertical = NbDimens.space8),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(width = 52.dp, height = 36.dp)
                .pressScale(targetScale = 0.90f, onTap = { onSelect(tab) })
                .clip(RoundedCornerShape(NbDimens.radiusFull))
                .background(colors.brandPink)
                .semantics { role = Role.Button },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = tab.icon,
                contentDescription = tab.label,
                tint = Color.White,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}
