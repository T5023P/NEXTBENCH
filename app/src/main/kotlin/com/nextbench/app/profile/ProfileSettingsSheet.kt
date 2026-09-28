package com.nextbench.app.profile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nextbench.core.designsystem.NbButton
import com.nextbench.core.designsystem.NbButtonVariant
import com.nextbench.core.designsystem.NbDimens
import com.nextbench.core.designsystem.NbIcons
import com.nextbench.core.designsystem.NbTheme
import com.nextbench.data.model.UserData

@Composable
internal fun ProfileSettingsSheet(
    user: UserData,
    onToggleTheme: () -> Unit,
    onToggleFollowersOnly: (Boolean) -> Unit,
    onToggleHideLastSeen: (Boolean) -> Unit,
    onOpenSaved: () -> Unit,
    onOpenInvite: () -> Unit,
    onOpenNotifications: () -> Unit,
    onEditProfile: () -> Unit,
    onSignOut: () -> Unit,
    onDeleteAccount: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(horizontal = NbDimens.space20, vertical = NbDimens.space4),
        verticalArrangement = Arrangement.spacedBy(NbDimens.space16),
    ) {
        Text("Settings", style = MaterialTheme.typography.titleLarge, color = NbTheme.colors.ink)
        SettingsToggleRow(
            icon = if (NbTheme.colors.isDark) NbIcons.Sun else NbIcons.Moon,
            title = "Appearance",
            subtitle = if (NbTheme.colors.isDark) "Use light theme" else "Use dark theme",
            checked = NbTheme.colors.isDark,
            onCheckedChange = { onToggleTheme() },
        )
        SettingsToggleRow(
            icon = NbIcons.Shield,
            title = "Followers-only messages",
            subtitle = "People outside your network send requests",
            checked = user.chatPrivacy?.followersOnly == true,
            onCheckedChange = onToggleFollowersOnly,
        )
        SettingsToggleRow(
            icon = NbIcons.Clock,
            title = "Hide last seen",
            subtitle = "Others won't see when you were last active",
            checked = user.hideLastSeen,
            onCheckedChange = onToggleHideLastSeen,
        )
        SettingsActionRow(NbIcons.Profile, "Edit profile", onEditProfile)
        SettingsActionRow(NbIcons.Bookmark, "Saved posts", onOpenSaved)
        SettingsActionRow(NbIcons.Share, "Invite friends", onOpenInvite)
        SettingsActionRow(NbIcons.Bell, "Notifications", onOpenNotifications)
        Text("Account and privacy controls stay synced with the website.", style = MaterialTheme.typography.bodySmall, color = NbTheme.colors.inkMuted)
        NbButton("Sign out", onClick = onSignOut, modifier = Modifier.fillMaxWidth(), variant = NbButtonVariant.Ghost)
        // Destructive and irreversible, so it is visually separated from everyday actions and
        // leads to a typed confirmation rather than deleting on tap.
        NbButton(
            "Delete account",
            onClick = onDeleteAccount,
            modifier = Modifier.fillMaxWidth(),
            variant = NbButtonVariant.Ghost,
        )
    }
}

@Composable
private fun SettingsToggleRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = NbTheme.colors.brandTeal, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(NbDimens.space12))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(NbDimens.space2)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = NbTheme.colors.ink)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = NbTheme.colors.inkMuted)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SettingsActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    onClick: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = NbDimens.space4), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = NbTheme.colors.inkMuted, modifier = Modifier.size(21.dp))
        Text(title, style = MaterialTheme.typography.bodyMedium, color = NbTheme.colors.ink, modifier = Modifier.padding(start = NbDimens.space12).weight(1f))
        Icon(NbIcons.ArrowRight, contentDescription = null, tint = NbTheme.colors.inkFaint, modifier = Modifier.size(18.dp))
    }
}
