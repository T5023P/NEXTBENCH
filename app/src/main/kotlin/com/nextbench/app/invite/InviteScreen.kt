package com.nextbench.app.invite

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nextbench.core.designsystem.NbButton
import com.nextbench.core.designsystem.NbButtonVariant
import com.nextbench.core.designsystem.NbCard
import com.nextbench.core.designsystem.NbDimens
import com.nextbench.core.designsystem.NbEmptyState
import com.nextbench.core.designsystem.NbIcons
import com.nextbench.core.designsystem.NbMotion
import com.nextbench.core.designsystem.NbPill
import com.nextbench.core.designsystem.NbSkeletonLine
import com.nextbench.core.designsystem.NbTextField
import com.nextbench.core.designsystem.NbTheme
import com.nextbench.core.designsystem.NbToast
import com.nextbench.core.designsystem.NbToastKind
import com.nextbench.core.designsystem.NbToastState
import com.nextbench.data.model.UserData

@Composable
fun InviteScreen(
    user: UserData?,
    modifier: Modifier = Modifier,
    viewModel: InviteViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(user?.uid) { viewModel.syncViewer(user) }

    Box(modifier = modifier.fillMaxSize().background(NbTheme.colors.surfaceBase)) {
        when {
            state.isLoading -> InviteLoading()
            state.error != null && state.inviteCode == null -> NbEmptyState(
                icon = NbIcons.Refresh,
                title = "Invites are taking a moment",
                message = state.error.orEmpty(),
                action = {
                    Text(
                        "Tap to retry",
                        color = NbTheme.colors.brandTeal,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.clickable(onClick = viewModel::retry).padding(NbDimens.space8),
                    )
                },
                modifier = Modifier.fillMaxSize(),
            )
            else -> InviteContent(
                state = state,
                onGenerate = viewModel::generateCode,
                onCopy = {
                    state.inviteLink?.let {
                        copyToClipboard(context, it)
                        viewModel.inviteLinkCopied()
                    }
                },
                onShare = { state.inviteLink?.let { shareInvite(context, it) } },
                onRedemptionCodeChange = viewModel::setRedemptionCode,
                onRedeem = viewModel::redeemCode,
                modifier = Modifier.fillMaxSize(),
            )
        }

        state.notice?.let { notice ->
            NbToast(
                state = NbToastState(
                    message = notice.message,
                    kind = when (notice.kind) {
                        InviteNoticeKind.Success -> NbToastKind.Success
                        InviteNoticeKind.Error -> NbToastKind.Error
                        InviteNoticeKind.Info -> NbToastKind.Info
                    },
                    visible = true,
                ),
                onDismiss = { viewModel.dismissNotice(notice.id) },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

@Composable
private fun InviteContent(
    state: InviteUiState,
    onGenerate: () -> Boolean,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onRedemptionCodeChange: (String) -> Unit,
    onRedeem: () -> Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(horizontal = NbDimens.space16, vertical = NbDimens.space20),
        verticalArrangement = Arrangement.spacedBy(NbDimens.space16),
    ) {
        AnimatedVisibility(visible = true, enter = fadeIn(NbMotion.entryTween()) + slideInVertically(NbMotion.entryTween()) { it / 3 }) {
            Column(verticalArrangement = Arrangement.spacedBy(NbDimens.space4)) {
                NbPill(label = "Bring your people", contentColor = NbTheme.colors.brandTeal)
                Text("Grow your campus circle", style = MaterialTheme.typography.headlineSmall, color = NbTheme.colors.ink)
                Text("Invite classmates into a community built around your campus, your interests, and your everyday life.", style = MaterialTheme.typography.bodyMedium, color = NbTheme.colors.inkMuted)
            }
        }

        NbCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(NbDimens.space20), verticalArrangement = Arrangement.spacedBy(NbDimens.space16)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NbDimens.space12)) {
                    Box(Modifier.size(52.dp).clip(CircleShape).background(NbTheme.colors.brandTeal.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                        Icon(NbIcons.Share, contentDescription = null, tint = NbTheme.colors.brandTeal, modifier = Modifier.size(25.dp))
                    }
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(NbDimens.space4)) {
                        Text("Your invite link", style = MaterialTheme.typography.titleMedium, color = NbTheme.colors.ink)
                        Text("One link is all they need to find NextBench.", style = MaterialTheme.typography.bodySmall, color = NbTheme.colors.inkMuted)
                    }
                    Text("${state.referralCount} joined", style = MaterialTheme.typography.labelSmall, color = NbTheme.colors.brandTeal)
                }

                if (state.inviteCode == null) {
                    Text("Generate a personal link once, then share it anywhere you already talk to friends.", style = MaterialTheme.typography.bodyMedium, color = NbTheme.colors.inkMuted)
                    NbButton(text = "Generate invite link", onClick = { onGenerate() }, loading = state.isGenerating, modifier = Modifier.fillMaxWidth(), variant = NbButtonVariant.Secondary)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(NbDimens.space8)) {
                        Text("INVITE CODE", style = MaterialTheme.typography.labelSmall, color = NbTheme.colors.inkMuted)
                        Text(state.inviteCode, style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold), color = NbTheme.colors.ink)
                        Text(state.inviteLink.orEmpty(), style = MaterialTheme.typography.bodySmall, color = NbTheme.colors.inkMuted, maxLines = 1)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(NbDimens.space8), verticalAlignment = Alignment.CenterVertically) {
                        NbButton(text = "Share invite", onClick = onShare, modifier = Modifier.weight(1f), variant = NbButtonVariant.Primary)
                        IconButton(onClick = onCopy, modifier = Modifier.size(52.dp).clip(RoundedCornerShape(NbDimens.radiusMd)).background(NbTheme.colors.brandTeal.copy(alpha = 0.10f)).semantics { contentDescription = "Copy invite link" }) {
                            Icon(NbIcons.Copy, contentDescription = null, tint = NbTheme.colors.brandTeal)
                        }
                    }
                }
            }
        }

        ReferralStatCard(count = state.referralCount)

        if (state.hasUsedReferral) {
            NbCard(modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.padding(NbDimens.space16), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NbDimens.space12)) {
                    Box(Modifier.size(42.dp).clip(CircleShape).background(NbTheme.colors.brandMint.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                        Icon(NbIcons.Check, contentDescription = null, tint = NbTheme.colors.brandMint, modifier = Modifier.size(21.dp))
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(NbDimens.space4)) {
                        Text("Invite applied", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold), color = NbTheme.colors.ink)
                        Text("You are already connected to the person who invited you.", style = MaterialTheme.typography.bodySmall, color = NbTheme.colors.inkMuted)
                    }
                }
            }
        } else {
            NbCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(NbDimens.space20), verticalArrangement = Arrangement.spacedBy(NbDimens.space12)) {
                    Text("Have an invite?", style = MaterialTheme.typography.titleMedium, color = NbTheme.colors.ink)
                    Text("Enter a friend's code to connect your accounts. This is available during your first 24 hours.", style = MaterialTheme.typography.bodySmall, color = NbTheme.colors.inkMuted)
                    NbTextField(
                        value = state.redemptionCode,
                        onValueChange = onRedemptionCodeChange,
                        label = "Invite code",
                        placeholder = "e.g. NB12AB34",
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Done),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    NbButton(text = "Use invite code", onClick = { onRedeem() }, loading = state.isRedeeming, enabled = state.canRedeem, modifier = Modifier.fillMaxWidth(), variant = NbButtonVariant.Ghost)
                }
            }
        }

        if (state.error != null) {
            Text(state.error, style = MaterialTheme.typography.bodySmall, color = NbTheme.colors.brandPink, modifier = Modifier.padding(horizontal = NbDimens.space4))
        }
        Spacer(Modifier.height(NbDimens.space16))
    }
}

@Composable
private fun ReferralStatCard(count: Int) {
    NbCard(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(NbDimens.space16), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NbDimens.space12)) {
            Box(Modifier.size(42.dp).clip(CircleShape).background(NbTheme.colors.brandPink.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                Icon(NbIcons.Profile, contentDescription = null, tint = NbTheme.colors.brandPink, modifier = Modifier.size(21.dp))
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(NbDimens.space4)) {
                Text("Your campus reach", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold), color = NbTheme.colors.ink)
                Text("People who joined through your link", style = MaterialTheme.typography.bodySmall, color = NbTheme.colors.inkMuted)
            }
            Text(count.toString(), style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold), color = NbTheme.colors.brandPink)
        }
    }
}

@Composable
private fun InviteLoading() {
    Column(modifier = Modifier.fillMaxWidth().padding(NbDimens.space20), verticalArrangement = Arrangement.spacedBy(NbDimens.space16)) {
        NbSkeletonLine(widthFraction = 0.35f, height = 14.dp)
        NbSkeletonLine(widthFraction = 0.72f, height = 28.dp)
        NbSkeletonLine(widthFraction = 0.94f)
        NbSkeletonLine(widthFraction = 1f, height = 180.dp)
        NbSkeletonLine(widthFraction = 1f, height = 88.dp)
    }
}

private fun copyToClipboard(context: Context, link: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("NextBench invite", link))
}

private fun shareInvite(context: Context, link: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "Join me on NextBench")
        putExtra(Intent.EXTRA_TEXT, "Join me on NextBench, the campus community for students.\n$link")
    }
    context.startActivity(Intent.createChooser(intent, "Share invite"))
}
