package com.nextbench.app.clubs

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nextbench.core.designsystem.NbButton
import com.nextbench.core.designsystem.NbButtonVariant
import com.nextbench.core.designsystem.NbAvatar
import com.nextbench.core.designsystem.NbBottomSheet
import com.nextbench.core.designsystem.NbDimens
import com.nextbench.core.designsystem.NbIcons
import com.nextbench.core.designsystem.NbTheme
import com.nextbench.data.model.Club
import com.nextbench.data.model.ClubSettings
import com.nextbench.data.model.UserData

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClubSettingsScreen(
    user: UserData?,
    onBack: () -> Unit,
    onLeave: () -> Unit,
    viewModel: ClubSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirmLeave by remember { mutableStateOf(false) }
    val userId = user?.uid
    LaunchedEffect(userId) { viewModel.syncViewer(user) }
    LaunchedEffect(state.leftClub) { if (state.leftClub) onLeave() }
    LaunchedEffect(state.notice?.id) {
        val id = state.notice?.id ?: return@LaunchedEffect
        kotlinx.coroutines.delay(2600)
        viewModel.dismissNotice(id)
    }
    BackHandler(onBack = onBack)

    Column(modifier = Modifier.fillMaxSize().background(NbTheme.colors.surfaceBase).statusBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = NbDimens.space8, vertical = NbDimens.space4),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(NbIcons.Back, contentDescription = "Go back", tint = NbTheme.colors.ink) }
            Text("Club settings", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold), color = NbTheme.colors.ink)
        }
        HorizontalDivider(color = NbTheme.colors.border)

        Box(modifier = Modifier.weight(1f)) {
            when {
                state.isLoading && state.club == null -> ClubSettingsLoading()
                state.club == null -> ClubSettingsError(state.error.orEmpty())
                else -> {
                    val club = requireNotNull(state.club)
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = NbDimens.space20, vertical = NbDimens.space20),
                        verticalArrangement = Arrangement.spacedBy(NbDimens.space16),
                    ) {
                        item { ClubSettingsSummary(club) }
                        item { ClubInviteCard(context, club, state.isLead(userId)) }
                        item {
                            ClubMemberManagement(
                                club = club,
                                viewerId = userId,
                                members = state.members,
                                managing = state.isManagingRole,
                                onOpenActions = viewModel::openMemberActions,
                            )
                        }
                        item {
                            if (state.isLead(userId)) {
                                ClubLeadSettings(
                                    club = club,
                                    saving = state.isSaving,
                                    onVisibility = viewModel::updateVisibility,
                                    onSettings = viewModel::updateSettings,
                                )
                            } else {
                                Surface(color = NbTheme.colors.surfaceSoft, shape = RoundedCornerShape(NbDimens.radiusMd)) {
                                    Row(modifier = Modifier.fillMaxWidth().padding(NbDimens.space16), verticalAlignment = Alignment.Top) {
                                        Icon(NbIcons.Shield, contentDescription = null, tint = NbTheme.colors.brandTeal, modifier = Modifier.size(20.dp))
                                        Spacer(Modifier.width(NbDimens.space12))
                                        Column(verticalArrangement = Arrangement.spacedBy(NbDimens.space4)) {
                                            Text("Lead-only controls", style = MaterialTheme.typography.labelLarge, color = NbTheme.colors.ink)
                                            Text("Only the club lead can change shared moderation settings.", style = MaterialTheme.typography.bodySmall, color = NbTheme.colors.inkMuted)
                                        }
                                    }
                                }
                            }
                        }
                        item {
                            Surface(color = NbTheme.colors.surfaceCard, shape = RoundedCornerShape(NbDimens.radiusMd), border = BorderStroke(1.dp, NbTheme.colors.border)) {
                                Column(modifier = Modifier.fillMaxWidth().padding(NbDimens.space16), verticalArrangement = Arrangement.spacedBy(NbDimens.space12)) {
                                    Text(if (state.isLead(userId)) "Leadership" else "Leave club", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold), color = NbTheme.colors.ink)
                                    Text(
                                        if (state.isLead(userId)) "Transfer leadership to another member before leaving, or manage the club from the NextBench website."
                                        else "You will stop receiving club updates and lose access to its conversation.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = NbTheme.colors.inkMuted,
                                    )
                                    if (!state.isLead(userId)) {
                                        NbButton("Leave club", onClick = { confirmLeave = true }, variant = NbButtonVariant.Ghost, loading = state.isLeaving)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            state.notice?.let { notice ->
                Surface(
                    color = if (notice.isError) NbTheme.colors.brandPink else NbTheme.colors.brandTeal,
                    shape = RoundedCornerShape(NbDimens.radiusMd),
                    modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = NbDimens.space20, vertical = NbDimens.space12).fillMaxWidth().clickable { viewModel.dismissNotice(notice.id) },
                ) { Text(notice.message, color = Color.White, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(NbDimens.space14)) }
            }
        }
    }
    if (confirmLeave) {
        NbBottomSheet(onDismiss = { if (!state.isLeaving) confirmLeave = false }) {
            Column(modifier = Modifier.padding(horizontal = NbDimens.space20, vertical = NbDimens.space8), verticalArrangement = Arrangement.spacedBy(NbDimens.space16)) {
                Text("Leave ${state.club?.name.orEmpty()}?", style = MaterialTheme.typography.titleLarge, color = NbTheme.colors.ink)
                Text("You can rejoin later if the club is public or you have a valid invite link.", style = MaterialTheme.typography.bodyMedium, color = NbTheme.colors.inkMuted)
                NbButton("Leave club", onClick = { viewModel.leaveClub() }, modifier = Modifier.fillMaxWidth(), loading = state.isLeaving)
                NbButton("Cancel", onClick = { confirmLeave = false }, modifier = Modifier.fillMaxWidth(), enabled = !state.isLeaving, variant = NbButtonVariant.Ghost)
            }
        }
    }
    val roleAction = state.roleAction
    if (roleAction != null && state.roleTargetId != null) {
        NbBottomSheet(onDismiss = viewModel::cancelRoleAction) {
            Column(
                modifier = Modifier.padding(horizontal = NbDimens.space20, vertical = NbDimens.space8),
                verticalArrangement = Arrangement.spacedBy(NbDimens.space16),
            ) {
                Text(
                    text = when (roleAction) {
                        ClubRoleAction.Promote -> "Promote member?"
                        ClubRoleAction.Demote -> "Demote co-lead?"
                        ClubRoleAction.Transfer -> "Transfer leadership?"
                        ClubRoleAction.Remove -> "Remove member?"
                    },
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = NbTheme.colors.ink,
                )
                Text(
                    text = when (roleAction) {
                        ClubRoleAction.Promote -> "They will be able to help manage this club."
                        ClubRoleAction.Demote -> "They will keep membership but lose co-lead permissions."
                        ClubRoleAction.Transfer -> "You will remain a member and they will become the new club lead."
                        ClubRoleAction.Remove -> "They will lose access to this club and its conversation."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = NbTheme.colors.inkMuted,
                )
                NbButton(
                    text = when (roleAction) {
                        ClubRoleAction.Promote -> "Promote"
                        ClubRoleAction.Demote -> "Demote"
                        ClubRoleAction.Transfer -> "Transfer leadership"
                        ClubRoleAction.Remove -> "Remove member"
                    },
                    onClick = { viewModel.confirmRoleAction() },
                    loading = state.isManagingRole,
                    modifier = Modifier.fillMaxWidth(),
                )
                NbButton("Cancel", onClick = viewModel::cancelRoleAction, enabled = !state.isManagingRole, modifier = Modifier.fillMaxWidth(), variant = NbButtonVariant.Ghost)
            }
        }
    }
    val actionTargetId = state.memberActionTargetId
    if (actionTargetId != null) {
        val member = state.members[actionTargetId]
        NbBottomSheet(onDismiss = viewModel::closeMemberActions) {
            Column(
                modifier = Modifier.padding(horizontal = NbDimens.space20, vertical = NbDimens.space8),
                verticalArrangement = Arrangement.spacedBy(NbDimens.space12),
            ) {
                Text(member?.name?.ifBlank { "Member actions" } ?: "Member actions", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold), color = NbTheme.colors.ink)
                ClubRoleAction.entries.filter { action -> state.canBeginRoleAction(userId, action, actionTargetId) }.forEach { action ->
                    NbButton(
                        text = when (action) {
                            ClubRoleAction.Promote -> "Promote to co-lead"
                            ClubRoleAction.Demote -> "Demote to member"
                            ClubRoleAction.Transfer -> "Transfer leadership"
                            ClubRoleAction.Remove -> "Remove from club"
                        },
                        onClick = { viewModel.beginRoleAction(action, actionTargetId) },
                        modifier = Modifier.fillMaxWidth(),
                        variant = if (action == ClubRoleAction.Remove) NbButtonVariant.Ghost else NbButtonVariant.Secondary,
                    )
                }
                NbButton("Cancel", onClick = viewModel::closeMemberActions, modifier = Modifier.fillMaxWidth(), variant = NbButtonVariant.Ghost)
            }
        }
    }
}

@Composable
private fun ClubMemberManagement(
    club: Club,
    viewerId: String?,
    members: Map<String, UserData>,
    managing: Boolean,
    onOpenActions: (String) -> Unit,
) {
    val canManage = viewerId == club.leadId || viewerId in club.coLeadIds
    Surface(color = NbTheme.colors.surfaceCard, shape = RoundedCornerShape(NbDimens.radiusMd), border = BorderStroke(1.dp, NbTheme.colors.border)) {
        Column(modifier = Modifier.fillMaxWidth().padding(NbDimens.space16), verticalArrangement = Arrangement.spacedBy(NbDimens.space12)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(NbIcons.Profile, contentDescription = null, tint = NbTheme.colors.brandTeal, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(NbDimens.space8))
                Text("Members", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold), color = NbTheme.colors.ink)
                Spacer(Modifier.weight(1f))
                Text("${club.memberCount}", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold), color = NbTheme.colors.inkMuted)
            }
            if (!canManage) {
                Text("Only the lead can change roles. Leads and co-leads can remove ordinary members.", style = MaterialTheme.typography.bodySmall, color = NbTheme.colors.inkMuted)
            }
            club.memberIds.take(50).forEach { memberId ->
                val role = when {
                    memberId == club.leadId -> "Lead"
                    memberId in club.coLeadIds -> "Co-lead"
                    else -> "Member"
                }
                val isSelf = memberId == viewerId
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NbDimens.space8)) {
                    val member = members[memberId]
                    NbAvatar(imageUrl = member?.profilePicture, name = member?.name.orEmpty().ifBlank { "Member" }, size = 38.dp)
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(NbDimens.space2)) {
                        Text(if (isSelf) "You" else member?.name?.ifBlank { "Member" } ?: "Member", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = NbTheme.colors.ink)
                        Text(role, style = MaterialTheme.typography.labelSmall, color = if (role == "Lead") NbTheme.colors.brandPink else NbTheme.colors.inkMuted)
                    }
                    val hasActions = !managing && ClubRoleAction.entries.any { action ->
                        when (action) {
                            ClubRoleAction.Promote -> viewerId == club.leadId && role == "Member"
                            ClubRoleAction.Demote -> viewerId == club.leadId && role == "Co-lead"
                            ClubRoleAction.Transfer -> viewerId == club.leadId && !isSelf && role != "Lead"
                            ClubRoleAction.Remove -> !isSelf && role != "Lead" && (viewerId == club.leadId || viewerId in club.coLeadIds) && !(viewerId in club.coLeadIds && role == "Co-lead")
                        }
                    }
                    if (hasActions) {
                        IconButton(onClick = { onOpenActions(memberId) }, modifier = Modifier.size(44.dp).semantics { contentDescription = "Actions for ${member?.name.orEmpty().ifBlank { "member" }}" }) {
                            Icon(NbIcons.More, contentDescription = null, tint = NbTheme.colors.inkMuted, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
            if (club.memberIds.size > 50) Text("Showing the first 50 members.", style = MaterialTheme.typography.labelSmall, color = NbTheme.colors.inkFaint)
        }
    }
}

@Composable
private fun ClubSettingsSummary(club: Club) {
    Column(verticalArrangement = Arrangement.spacedBy(NbDimens.space4)) {
        Text(club.name.ifBlank { "Campus club" }, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold), color = NbTheme.colors.ink)
        Text(
            listOfNotNull("${club.memberCount} members", club.city?.takeIf(String::isNotBlank), if (club.type == "public") "Public" else "Private").joinToString("  ·  "),
            style = MaterialTheme.typography.bodySmall,
            color = NbTheme.colors.inkMuted,
        )
        club.description?.takeIf(String::isNotBlank)?.let { description ->
            Text(description, style = MaterialTheme.typography.bodyMedium, color = NbTheme.colors.inkMuted, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ClubInviteCard(context: Context, club: Club, isLead: Boolean) {
    val link = "https://nextbench.in/club/join/${club.inviteCode.orEmpty()}"
    Surface(color = NbTheme.colors.surfaceCard, shape = RoundedCornerShape(NbDimens.radiusMd), border = BorderStroke(1.dp, NbTheme.colors.border)) {
        Column(modifier = Modifier.fillMaxWidth().padding(NbDimens.space16), verticalArrangement = Arrangement.spacedBy(NbDimens.space12)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(NbIcons.Share, contentDescription = null, tint = NbTheme.colors.brandTeal, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(NbDimens.space8))
                Text("Invite link", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold), color = NbTheme.colors.ink)
            }
            Text(link, style = MaterialTheme.typography.bodySmall, color = NbTheme.colors.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(NbDimens.space8)) {
                NbButton("Copy", onClick = { copyToClipboard(context, link) }, modifier = Modifier.weight(1f), variant = NbButtonVariant.Secondary)
                NbButton("Share", onClick = { shareInvite(context, link) }, modifier = Modifier.weight(1f), variant = NbButtonVariant.Ghost)
            }
            Text(if (isLead) "Share this link with verified students you trust." else "Ask the club lead for a fresh invite if this link expires.", style = MaterialTheme.typography.labelSmall, color = NbTheme.colors.inkMuted)
        }
    }
}

@Composable
private fun ClubLeadSettings(
    club: Club,
    saving: Boolean,
    onVisibility: () -> Boolean,
    onSettings: ((ClubSettings) -> ClubSettings) -> Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(NbDimens.space12)) {
        Text("Shared controls", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold), color = NbTheme.colors.ink)
        SettingToggle(
            icon = if (club.type == "public") NbIcons.Home else NbIcons.Shield,
            title = "Club visibility",
            subtitle = if (club.type == "public") "Anyone can discover and join" else "Join by invite link only",
            checked = club.type == "public",
            enabled = !saving,
            onCheckedChange = { onVisibility() },
        )
        SettingToggle(
            icon = NbIcons.Profile,
            title = "Hide member list",
            subtitle = "Hide members when the club has more than 50",
            checked = club.settings.hideMembersAbove50,
            enabled = !saving,
            onCheckedChange = { value -> onSettings { it.copy(hideMembersAbove50 = value) } },
        )
        SettingToggle(
            icon = NbIcons.Messages,
            title = "Announcement mode",
            subtitle = "Only leads and co-leads can post",
            checked = club.settings.onlyLeadsCanPost,
            enabled = !saving,
            onCheckedChange = { value -> onSettings { it.copy(onlyLeadsCanPost = value) } },
        )
    }
}

@Composable
private fun SettingToggle(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = NbTheme.colors.brandTeal, modifier = Modifier.size(21.dp))
        Spacer(Modifier.width(NbDimens.space12))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(NbDimens.space2)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = NbTheme.colors.ink)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = NbTheme.colors.inkMuted)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = NbTheme.colors.brandTeal),
            modifier = Modifier.semantics { contentDescription = title },
        )
    }
}

@Composable
private fun ClubSettingsLoading() {
    Column(modifier = Modifier.padding(NbDimens.space20), verticalArrangement = Arrangement.spacedBy(NbDimens.space16)) {
        Text("Loading settings...", style = MaterialTheme.typography.titleMedium, color = NbTheme.colors.inkMuted)
        repeat(4) { Surface(color = NbTheme.colors.surfaceSoft, shape = RoundedCornerShape(NbDimens.radiusMd), modifier = Modifier.fillMaxWidth().size(68.dp)) {} }
    }
}

@Composable
private fun ClubSettingsError(message: String) {
    Column(modifier = Modifier.fillMaxSize().padding(NbDimens.space32), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(NbIcons.Shield, contentDescription = null, tint = NbTheme.colors.brandPink, modifier = Modifier.size(32.dp))
        Spacer(Modifier.size(NbDimens.space12))
        Text(message.ifBlank { "Club settings are unavailable." }, style = MaterialTheme.typography.bodyMedium, color = NbTheme.colors.inkMuted)
    }
}

private fun copyToClipboard(context: Context, link: String) {
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.setPrimaryClip(ClipData.newPlainText("NextBench club invite", link))
}

private fun shareInvite(context: Context, link: String) {
    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "Join our NextBench club")
        putExtra(Intent.EXTRA_TEXT, "Join our NextBench campus club.\n$link")
    }, "Share club invite"))
}
