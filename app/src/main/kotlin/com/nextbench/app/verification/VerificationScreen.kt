package com.nextbench.app.verification

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.nextbench.core.designsystem.NbBottomSheet
import com.nextbench.core.designsystem.NbButton
import com.nextbench.core.designsystem.NbButtonVariant
import com.nextbench.core.designsystem.NbDimens
import com.nextbench.core.designsystem.NbIcons
import com.nextbench.core.designsystem.NbLogo
import com.nextbench.core.designsystem.NbMotion
import com.nextbench.core.designsystem.NbTheme
import com.nextbench.data.firebase.VerificationStage
import com.nextbench.data.model.AccountType
import com.nextbench.data.model.UserData
import com.nextbench.data.model.VerificationStatus
import java.io.File

@Composable
fun VerificationScreen(
    user: UserData,
    onClose: () -> Unit,
    onContinue: () -> Unit,
    viewModel: VerificationViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var photoTarget by remember { mutableStateOf<VerificationPhotoTarget?>(null) }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
        viewModel::completeCapture,
    )
    val pickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        val target = photoTarget
        photoTarget = null
        if (uri != null && target != null) viewModel.preparePickedPhoto(target, uri)
    }

    fun openCamera(target: VerificationPhotoTarget) {
        viewModel.createCapture(target).onSuccess { uri ->
            try {
                cameraLauncher.launch(uri)
            } catch (_: Exception) {
                viewModel.completeCapture(false)
                viewModel.setExternalError("No compatible camera app is available. Choose a photo instead.")
            }
        }
    }

    fun openGallery(target: VerificationPhotoTarget) {
        photoTarget = target
        pickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    LaunchedEffect(user) { viewModel.syncAccount(user) }

    fun navigateBack() {
        when {
            state.isSubmitting -> Unit
            state.step == VerificationStep.Selfie -> viewModel.backToId()
            else -> onClose()
        }
    }
    BackHandler(enabled = !state.isSubmitting, onBack = ::navigateBack)

    Surface(
        color = NbTheme.colors.surfaceBase,
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
        ) {
            VerificationTopBar(
                canGoBack = state.step == VerificationStep.Selfie,
                enabled = !state.isSubmitting,
                onBack = ::navigateBack,
                onClose = onClose,
            )
            VerificationProgress(step = state.step)

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = NbDimens.space20,
                    end = NbDimens.space20,
                    top = NbDimens.space20,
                    bottom = NbDimens.space40,
                ),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .widthIn(max = 560.dp),
                    ) {
                        AnimatedVisibility(
                            visible = state.error != null && state.step != VerificationStep.Status,
                            enter = fadeIn(NbMotion.interactionTween()),
                            exit = fadeOut(NbMotion.interactionTween()),
                        ) {
                            state.error?.let {
                                VerificationNotice(
                                    message = it,
                                    accent = NbTheme.colors.brandPink,
                                    onDismiss = viewModel::clearError,
                                )
                            }
                        }

                        AnimatedContent(
                            targetState = state.step,
                            transitionSpec = {
                                val forward = targetState.ordinal > initialState.ordinal
                                val direction = if (forward) 1 else -1
                                (fadeIn(NbMotion.interactionTween()) +
                                    slideInHorizontally(NbMotion.interactionTween()) { direction * it / 12 })
                                    .togetherWith(
                                        fadeOut(NbMotion.interactionTween()) +
                                            slideOutHorizontally(NbMotion.interactionTween()) { -direction * it / 12 },
                                    )
                            },
                            label = "verificationStep",
                        ) { step ->
                            when (step) {
                                VerificationStep.StudentId -> StudentIdStep(
                                    user = user,
                                    file = state.idCard,
                                    preparing = state.preparingTarget == VerificationPhotoTarget.StudentId,
                                    rejectionReason = state.rejectionReason,
                                    onCamera = { openCamera(VerificationPhotoTarget.StudentId) },
                                    onGallery = { openGallery(VerificationPhotoTarget.StudentId) },
                                    onContinue = viewModel::continueFromId,
                                )
                                VerificationStep.Selfie -> SelfieStep(
                                    file = state.selfie,
                                    preparing = state.preparingTarget == VerificationPhotoTarget.Selfie,
                                    onCamera = { openCamera(VerificationPhotoTarget.Selfie) },
                                    onGallery = { openGallery(VerificationPhotoTarget.Selfie) },
                                    onSubmit = { viewModel.submit(user) },
                                )
                                VerificationStep.Status -> VerificationStatusStep(
                                    user = user,
                                    state = state,
                                    onContinue = onContinue,
                                    onRetrySubmission = viewModel::retrySubmission,
                                    onRestart = viewModel::restartAfterRejection,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VerificationTopBar(
    canGoBack: Boolean,
    enabled: Boolean,
    onBack: () -> Unit,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(60.dp)
            .padding(horizontal = NbDimens.space12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (canGoBack) {
            IconButton(onClick = onBack, enabled = enabled) {
                Icon(NbIcons.Back, "Previous verification step", tint = NbTheme.colors.inkMuted)
            }
        } else {
            NbLogo(size = 32.dp, modifier = Modifier.padding(start = NbDimens.space4))
        }
        Text(
            text = "Identity verification",
            style = MaterialTheme.typography.titleMedium,
            color = NbTheme.colors.ink,
            modifier = Modifier
                .weight(1f)
                .padding(start = NbDimens.space12),
        )
        IconButton(onClick = onClose, enabled = enabled) {
            Icon(NbIcons.Close, "Close verification", tint = NbTheme.colors.inkMuted)
        }
    }
}

@Composable
private fun VerificationProgress(step: VerificationStep) {
    val activeIndex = step.ordinal + 1
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = NbDimens.space20, vertical = NbDimens.space8),
        horizontalArrangement = Arrangement.spacedBy(NbDimens.space8),
    ) {
        repeat(3) { index ->
            val progress by animateFloatAsState(
                targetValue = if (activeIndex > index) 1f else 0f,
                animationSpec = NbMotion.interactionTween(),
                label = "verificationProgress$index",
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(NbTheme.colors.inkFaint),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress)
                        .height(4.dp)
                        .background(NbTheme.colors.brandTeal),
                )
            }
        }
    }
}

@Composable
private fun StudentIdStep(
    user: UserData,
    file: File?,
    preparing: Boolean,
    rejectionReason: String?,
    onCamera: () -> Unit,
    onGallery: () -> Unit,
    onContinue: () -> Unit,
) {
    var sourceSheetOpen by remember { mutableStateOf(false) }
    VerificationStepHeader(
        icon = NbIcons.Shield,
        accent = NbTheme.colors.brandTeal,
        title = "Photograph your student ID",
        body = "Keep the card flat and make sure your name, institute, and photo are readable.",
    )
    rejectionReason?.takeIf(String::isNotBlank)?.let {
        Spacer(Modifier.height(NbDimens.space16))
        VerificationNotice(message = it, accent = NbTheme.colors.brandPink)
    }
    Spacer(Modifier.height(NbDimens.space24))
    VerificationPhotoCard(
        file = file,
        preparing = preparing,
        icon = NbIcons.Upload,
        title = "Add student ID",
        action = if (file == null) "Camera or photo library" else "Replace photo",
        aspectRatio = 1.58f,
        onClick = { sourceSheetOpen = true },
    )
    Spacer(Modifier.height(NbDimens.space16))
    PrivacyNote(
        text = "Your verification photos are private. They are used only to verify your campus identity and are never shown on your public profile.",
    )
    Spacer(Modifier.height(NbDimens.space24))
    ProfileMatchRow(user)
    Spacer(Modifier.height(NbDimens.space24))
    NbButton(
        text = "Continue to selfie",
        onClick = onContinue,
        enabled = file != null && !preparing,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
    )

    if (sourceSheetOpen) {
        PhotoSourceSheet(
            title = "Add student ID",
            onDismiss = { sourceSheetOpen = false },
            onCamera = {
                sourceSheetOpen = false
                onCamera()
            },
            onGallery = {
                sourceSheetOpen = false
                onGallery()
            },
        )
    }
}

@Composable
private fun SelfieStep(
    file: File?,
    preparing: Boolean,
    onCamera: () -> Unit,
    onGallery: () -> Unit,
    onSubmit: () -> Unit,
) {
    var sourceSheetOpen by remember { mutableStateOf(false) }
    VerificationStepHeader(
        icon = NbIcons.Camera,
        accent = NbTheme.colors.brandPink,
        title = "Take a live selfie",
        body = "Hold the same ID beside your face. Use even lighting and keep your face unobstructed.",
    )
    Spacer(Modifier.height(NbDimens.space24))
    VerificationPhotoCard(
        file = file,
        preparing = preparing,
        icon = NbIcons.Camera,
        title = "Selfie holding your ID",
        action = if (file == null) "Open camera" else "Retake selfie",
        aspectRatio = 3f / 4f,
        contentScale = ContentScale.Crop,
        onClick = { sourceSheetOpen = true },
    )
    Spacer(Modifier.height(NbDimens.space16))
    CaptureTips()
    Spacer(Modifier.height(NbDimens.space24))
    NbButton(
        text = "Submit for verification",
        onClick = onSubmit,
        enabled = file != null && !preparing,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
    )

    if (sourceSheetOpen) {
        PhotoSourceSheet(
            title = "Add selfie",
            onDismiss = { sourceSheetOpen = false },
            onCamera = {
                sourceSheetOpen = false
                onCamera()
            },
            onGallery = {
                sourceSheetOpen = false
                onGallery()
            },
        )
    }
}

@Composable
private fun VerificationStatusStep(
    user: UserData,
    state: VerificationUiState,
    onContinue: () -> Unit,
    onRetrySubmission: () -> Unit,
    onRestart: () -> Unit,
) {
    when {
        state.isSubmitting || state.stage == VerificationStage.Uploading || state.stage == VerificationStage.Reviewing ->
            ReviewingState(stage = state.stage ?: VerificationStage.Uploading)
        state.error != null && state.outcome == null -> SubmissionErrorState(
            message = state.error,
            onRetry = onRetrySubmission,
        )
        else -> {
            val status = VerificationStatus.from(state.outcome?.status ?: user.verificationStatus)
            when (status) {
                VerificationStatus.Approved -> ApprovedState(onContinue)
                VerificationStatus.Rejected -> RejectedState(
                    reason = state.outcome?.reason ?: state.rejectionReason,
                    onRestart = onRestart,
                )
                VerificationStatus.FlaggedManual -> PendingState(
                    isOrganization = AccountType.from(user.accountType) == AccountType.Organization,
                    reason = state.outcome?.reason ?: state.rejectionReason,
                    manualReview = true,
                    onContinue = onContinue,
                )
                VerificationStatus.Pending -> PendingState(
                    isOrganization = AccountType.from(user.accountType) == AccountType.Organization,
                    reason = state.outcome?.reason,
                    manualReview = false,
                    onContinue = onContinue,
                )
            }
        }
    }
}

@Composable
private fun VerificationStepHeader(
    icon: ImageVector,
    accent: Color,
    title: String,
    body: String,
) {
    Surface(
        color = accent.copy(alpha = 0.10f),
        shape = RoundedCornerShape(NbDimens.radiusLg),
    ) {
        Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = accent, modifier = Modifier.size(28.dp))
        }
    }
    Spacer(Modifier.height(NbDimens.space16))
    Text(title, style = MaterialTheme.typography.headlineMedium, color = NbTheme.colors.ink)
    Spacer(Modifier.height(NbDimens.space8))
    Text(body, style = MaterialTheme.typography.bodyMedium, color = NbTheme.colors.inkMuted)
}

@Composable
private fun VerificationPhotoCard(
    file: File?,
    preparing: Boolean,
    icon: ImageVector,
    title: String,
    action: String,
    aspectRatio: Float,
    contentScale: ContentScale = ContentScale.Fit,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(NbDimens.radiusLg)
    Surface(
        onClick = onClick,
        enabled = !preparing,
        shape = shape,
        color = NbTheme.colors.surfaceCard,
        border = BorderStroke(1.dp, NbTheme.colors.borderStrong),
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(aspectRatio),
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (file != null) {
                AsyncImage(
                    model = file,
                    contentDescription = title,
                    contentScale = contentScale,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(NbTheme.colors.overlay.copy(alpha = 0.18f)),
                )
            }
            if (preparing) {
                CircularProgressIndicator(color = NbTheme.colors.brandTeal)
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(
                        color = NbTheme.colors.surfaceCard.copy(alpha = 0.92f),
                        shape = CircleShape,
                    ) {
                        Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
                            Icon(icon, null, tint = NbTheme.colors.brandTeal, modifier = Modifier.size(24.dp))
                        }
                    }
                    Spacer(Modifier.height(NbDimens.space12))
                    Surface(
                        color = NbTheme.colors.surfaceCard.copy(alpha = 0.94f),
                        shape = RoundedCornerShape(NbDimens.radiusFull),
                    ) {
                        Text(
                            text = action,
                            style = MaterialTheme.typography.labelLarge,
                            color = NbTheme.colors.ink,
                            modifier = Modifier.padding(horizontal = NbDimens.space16, vertical = NbDimens.space8),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileMatchRow(user: UserData) {
    Surface(
        color = NbTheme.colors.surfaceSoft,
        shape = RoundedCornerShape(NbDimens.radiusMd),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(NbDimens.space16)) {
            Text("We will match", style = MaterialTheme.typography.labelMedium, color = NbTheme.colors.inkMuted)
            Spacer(Modifier.height(NbDimens.space8))
            Text(user.name, style = MaterialTheme.typography.titleSmall, color = NbTheme.colors.ink)
            Text(user.school, style = MaterialTheme.typography.bodySmall, color = NbTheme.colors.inkMuted)
        }
    }
}

@Composable
private fun PrivacyNote(text: String) {
    Surface(
        color = NbTheme.colors.brandTeal.copy(alpha = 0.07f),
        shape = RoundedCornerShape(NbDimens.radiusMd),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(NbDimens.space16), verticalAlignment = Alignment.Top) {
            Icon(NbIcons.Shield, null, tint = NbTheme.colors.brandTeal, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(NbDimens.space12))
            Text(text, style = MaterialTheme.typography.bodySmall, color = NbTheme.colors.inkMuted)
        }
    }
}

@Composable
private fun CaptureTips() {
    Column(verticalArrangement = Arrangement.spacedBy(NbDimens.space8)) {
        TipRow("Face the light; avoid bright windows behind you.")
        TipRow("Keep your full face and the ID inside the frame.")
        TipRow("Remove sunglasses, masks, and heavy filters.")
    }
}

@Composable
private fun TipRow(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(NbIcons.Check, null, tint = NbTheme.colors.brandMint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(NbDimens.space8))
        Text(text, style = MaterialTheme.typography.bodySmall, color = NbTheme.colors.inkMuted)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhotoSourceSheet(
    title: String,
    onDismiss: () -> Unit,
    onCamera: () -> Unit,
    onGallery: () -> Unit,
) {
    NbBottomSheet(onDismiss = onDismiss) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = NbTheme.colors.ink,
            modifier = Modifier.padding(horizontal = NbDimens.space24),
        )
        Spacer(Modifier.height(NbDimens.space16))
        SourceAction(NbIcons.Camera, "Take a photo", "Use the system camera", onCamera)
        SourceAction(NbIcons.Upload, "Choose a photo", "Use Android's private photo picker", onGallery)
    }
}

@Composable
private fun SourceAction(
    icon: ImageVector,
    title: String,
    body: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = NbDimens.space24, vertical = NbDimens.space14),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(color = NbTheme.colors.surfaceSoft, shape = CircleShape) {
                Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = NbTheme.colors.brandTeal, modifier = Modifier.size(21.dp))
                }
            }
            Spacer(Modifier.width(NbDimens.space12))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = NbTheme.colors.ink)
                Text(body, style = MaterialTheme.typography.bodySmall, color = NbTheme.colors.inkMuted)
            }
            Icon(NbIcons.ArrowRight, null, tint = NbTheme.colors.inkMuted, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun ReviewingState(stage: VerificationStage) {
    StatusShell(
        iconContent = {
            CircularProgressIndicator(
                color = NbTheme.colors.brandTeal,
                strokeWidth = 3.dp,
                modifier = Modifier.size(64.dp),
            )
        },
        title = if (stage == VerificationStage.Uploading) "Securing your photos" else "Checking your identity",
        body = if (stage == VerificationStage.Uploading) {
            "Both photos are uploading over an encrypted connection. Keep the app open for a moment."
        } else {
            "We are matching your name, institute, ID, and selfie. Uncertain results always go to human review."
        },
    ) {
        StageRow("Prepare verification photos", complete = true, active = false)
        StageRow("Upload secure assets", complete = stage != VerificationStage.Uploading, active = stage == VerificationStage.Uploading)
        StageRow("Match identity details", complete = false, active = stage == VerificationStage.Reviewing)
    }
}

@Composable
private fun ApprovedState(onContinue: () -> Unit) {
    StatusShell(
        icon = NbIcons.Check,
        accent = NbTheme.colors.brandMint,
        title = "Your profile is verified",
        body = "Your campus identity is active. You can now create, sell, and message verified members.",
    ) {
        NbButton(
            text = "Enter NextBench",
            onClick = onContinue,
            variant = NbButtonVariant.Secondary,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
        )
    }
}

@Composable
private fun PendingState(
    isOrganization: Boolean,
    reason: String?,
    manualReview: Boolean,
    onContinue: () -> Unit,
) {
    val accent = Color(0xFFFF9F0A)
    StatusShell(
        icon = NbIcons.Shield,
        accent = accent,
        title = "Submission received",
        body = if (isOrganization) {
            "Your organization documents are in the admin queue. Reviews usually finish within 24-48 hours."
        } else {
            "Your campus credentials are in the review queue. Most manual reviews finish within a few hours."
        },
    ) {
        if (manualReview || !reason.isNullOrBlank()) {
            VerificationNotice(
                message = reason ?: "The automated check was not confident enough, so a campus admin will review your submission.",
                accent = accent,
            )
            Spacer(Modifier.height(NbDimens.space16))
        }
        NbButton(
            text = "Browse NextBench",
            onClick = onContinue,
            variant = NbButtonVariant.Secondary,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
        )
    }
}

@Composable
private fun RejectedState(reason: String?, onRestart: () -> Unit) {
    StatusShell(
        icon = NbIcons.Close,
        accent = NbTheme.colors.brandPink,
        title = "We could not verify this submission",
        body = "This is usually caused by glare, unreadable details, or a mismatch between the profile and ID.",
    ) {
        reason?.takeIf(String::isNotBlank)?.let {
            VerificationNotice(message = it, accent = NbTheme.colors.brandPink)
            Spacer(Modifier.height(NbDimens.space16))
        }
        NbButton(
            text = "Try with clearer photos",
            onClick = onRestart,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
        )
    }
}

@Composable
private fun SubmissionErrorState(message: String, onRetry: () -> Unit) {
    StatusShell(
        icon = NbIcons.Refresh,
        accent = NbTheme.colors.brandPink,
        title = "Submission interrupted",
        body = message,
    ) {
        NbButton(
            text = "Review and retry",
            onClick = onRetry,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
        )
    }
}

@Composable
private fun StatusShell(
    title: String,
    body: String,
    icon: ImageVector? = null,
    accent: Color = NbTheme.colors.brandTeal,
    iconContent: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = NbDimens.space32),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (iconContent != null) {
            iconContent()
        } else if (icon != null) {
            Surface(color = accent, shape = CircleShape) {
                Box(Modifier.size(80.dp), contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = Color.White, modifier = Modifier.size(34.dp))
                }
            }
        }
        Spacer(Modifier.height(NbDimens.space24))
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            color = NbTheme.colors.ink,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(NbDimens.space8))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = NbTheme.colors.inkMuted,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(NbDimens.space32))
        Box(Modifier.fillMaxWidth()) { content() }
    }
}

@Composable
private fun StageRow(text: String, complete: Boolean, active: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = NbDimens.space8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            complete -> Icon(NbIcons.Check, null, tint = NbTheme.colors.brandMint, modifier = Modifier.size(18.dp))
            active -> CircularProgressIndicator(
                color = NbTheme.colors.brandTeal,
                strokeWidth = 2.dp,
                modifier = Modifier.size(18.dp),
            )
            else -> Box(
                Modifier
                    .size(18.dp)
                    .border(1.dp, NbTheme.colors.borderStrong, CircleShape),
            )
        }
        Spacer(Modifier.width(NbDimens.space12))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (active || complete) NbTheme.colors.ink else NbTheme.colors.inkMuted,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
private fun VerificationNotice(
    message: String,
    accent: Color,
    onDismiss: (() -> Unit)? = null,
) {
    Surface(
        color = accent.copy(alpha = 0.08f),
        shape = RoundedCornerShape(NbDimens.radiusMd),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.22f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = NbDimens.space12),
    ) {
        Row(
            modifier = Modifier.padding(NbDimens.space16),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(NbIcons.Shield, null, tint = accent, modifier = Modifier.size(19.dp))
            Spacer(Modifier.width(NbDimens.space12))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = NbTheme.colors.ink,
                modifier = Modifier.weight(1f),
            )
            if (onDismiss != null) {
                IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                    Icon(NbIcons.Close, "Dismiss", tint = NbTheme.colors.inkMuted, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}
