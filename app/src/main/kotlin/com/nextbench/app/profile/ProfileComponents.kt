package com.nextbench.app.profile

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nextbench.core.designsystem.NbDimens
import com.nextbench.core.designsystem.NbIcons
import com.nextbench.core.designsystem.NbMotion
import com.nextbench.core.designsystem.NbTheme
import com.nextbench.core.designsystem.pressScale

@Composable
internal fun ProfileStatsRow(
    followers: Int,
    following: Int,
    listings: Int,
    posts: Int,
    onFollowers: (() -> Unit)? = null,
    onFollowing: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = NbDimens.space20),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProfileStat(followers, "Followers", onFollowers, Modifier.weight(1f))
        StatDivider()
        ProfileStat(following, "Following", onFollowing, Modifier.weight(1f))
        StatDivider()
        ProfileStat(listings, "Listings", null, Modifier.weight(1f))
        StatDivider()
        ProfileStat(posts, "Posts", null, Modifier.weight(1f))
    }
}

@Composable
private fun ProfileStat(value: Int, label: String, onClick: (() -> Unit)?, modifier: Modifier) {
    val base = modifier
        .clip(RoundedCornerShape(NbDimens.radiusSm))
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .padding(vertical = NbDimens.space8)
    Column(modifier = base, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(NbDimens.space2)) {
        Text(value.coerceAtLeast(0).toString(), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = NbTheme.colors.ink)
        Text(label, style = MaterialTheme.typography.labelSmall, color = NbTheme.colors.inkMuted)
    }
}

@Composable
private fun StatDivider() {
    Box(Modifier.width(1.dp).height(28.dp).background(NbTheme.colors.border))
}

@Composable
internal fun ProfileActionStrip(actions: List<ProfileAction>, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = NbDimens.space16),
        horizontalArrangement = Arrangement.spacedBy(NbDimens.space8),
    ) {
        actions.forEach { action ->
            Column(
                modifier = Modifier
                    .weight(1f)
                    .pressScale(onTap = action.onClick)
                    .clip(RoundedCornerShape(NbDimens.radiusSm))
                    .background(NbTheme.colors.surfaceSoft)
                    .padding(vertical = NbDimens.space12)
                    .semantics { role = Role.Button; contentDescription = action.label },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(NbDimens.space4),
            ) {
                Icon(action.icon, contentDescription = null, tint = action.tint ?: NbTheme.colors.ink, modifier = Modifier.size(20.dp))
                Text(action.label, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold), color = NbTheme.colors.inkMuted)
            }
        }
    }
}

internal data class ProfileAction(
    val icon: ImageVector,
    val label: String,
    val onClick: () -> Unit,
    val tint: Color? = null,
)

@Composable
internal fun ProfileActivityTabs(
    selected: ProfileTab,
    listingsCount: Int,
    postsCount: Int,
    onSelect: (ProfileTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth()) {
        ProfileTab.entries.forEach { tab ->
            val active = selected == tab
            val color by animateColorAsState(
                targetValue = if (active) NbTheme.colors.ink else NbTheme.colors.inkMuted,
                animationSpec = NbMotion.interactionTween(),
                label = "profile_tab_color",
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable(role = Role.Tab) { onSelect(tab) }
                    .semantics { role = Role.Tab; contentDescription = tab.label() }
                    .padding(top = NbDimens.space12),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(NbDimens.space8),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NbDimens.space8)) {
                    Icon(if (tab == ProfileTab.Listings) NbIcons.Marketplace else NbIcons.List, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
                    Text(
                        "${tab.label()} ${if (tab == ProfileTab.Listings) listingsCount else postsCount}",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = if (active) FontWeight.Bold else FontWeight.Medium),
                        color = color,
                    )
                }
                HorizontalDivider(thickness = 2.dp, color = if (active) NbTheme.colors.brandPink else Color.Transparent)
            }
        }
    }
}

internal fun ProfileTab.label(): String = if (this == ProfileTab.Listings) "Listings" else "Posts"
