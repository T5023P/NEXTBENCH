package com.nextbench.app.auth

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.navigation.compose.hiltViewModel
import com.nextbench.core.designsystem.NbButton
import com.nextbench.core.designsystem.NbDimens
import com.nextbench.core.designsystem.NbIcons
import com.nextbench.core.designsystem.NbLogo
import com.nextbench.core.designsystem.NbMotion
import com.nextbench.core.designsystem.NbTextField
import com.nextbench.core.designsystem.NbTheme
import com.nextbench.core.designsystem.pressScale
import kotlinx.coroutines.launch

@Composable
fun OrganizationSignupScreen(
    onBack: () -> Unit,
    onTerms: () -> Unit,
    onPrivacy: () -> Unit,
    onStudentSignup: () -> Unit,
    onSignIn: () -> Unit,
    onRegistered: () -> Unit,
    viewModel: OrganizationSignupViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialHelper = remember(context) { GoogleCredentialHelper(CredentialManager.create(context)) }
    var credentialPickerOpen by remember { mutableStateOf(false) }
    val documentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::prepareDocument)
    }

    fun navigateBack() {
        if (!viewModel.goBack()) onBack()
    }

    fun register() {
        if (credentialPickerOpen || state.isSubmitting || state.isPreparingDocument) return
        organizationSubmissionError(state)?.let {
            viewModel.setExternalError(it)
            return
        }
        scope.launch {
            credentialPickerOpen = true
            try {
                viewModel.registerWithGoogle(credentialHelper.getIdToken(context))
            } catch (_: GetCredentialCancellationException) {
                // Closing the account picker leaves the completed draft intact.
            } catch (error: Exception) {
                viewModel.setExternalError(error.authMessage())
            } finally {
                credentialPickerOpen = false
            }
        }
    }

    LaunchedEffect(state.completedSession) {
        if (state.completedSession == null) return@LaunchedEffect
        viewModel.clearCompletedSession()
        onRegistered()
    }

    BackHandler(onBack = ::navigateBack)

    Surface(color = NbTheme.colors.surfaceBase, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding(),
        ) {
            OrganizationTopBar(onBack = ::navigateBack)
            OrganizationProgress(state.step)
            AnimatedContent(
                targetState = state.step,
                transitionSpec = {
                    val forward = targetState.ordinal > initialState.ordinal
                    val direction = if (forward) 1 else -1
                    (fadeIn(NbMotion.interactionTween()) +
                        slideInHorizontally(NbMotion.interactionTween()) { it * direction / 12 })
                        .togetherWith(
                            fadeOut(NbMotion.interactionTween()) +
                                slideOutHorizontally(NbMotion.interactionTween()) { -it * direction / 12 },
                        )
                },
                label = "organizationSignupStep",
                modifier = Modifier.weight(1f),
            ) { step ->
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = NbDimens.space24,
                        end = NbDimens.space24,
                        top = NbDimens.space24,
                        bottom = NbDimens.space24,
                    ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    item {
                        Column(
                            modifier = Modifier.fillMaxWidth().widthIn(max = 520.dp),
                            verticalArrangement = Arrangement.spacedBy(NbDimens.space20),
                        ) {
                            OrganizationHeader(step)
                            state.error?.let { OrganizationError(it) }
                            when (step) {
                                OrganizationSignupStep.Type -> OrganizationTypeStep(state.type, viewModel::selectType)
                                OrganizationSignupStep.Details -> OrganizationDetailsStep(state, viewModel)
                                OrganizationSignupStep.Document -> OrganizationDocumentStep(
                                    state = state,
                                    onChoose = { documentPicker.launch(arrayOf("image/*", "application/pdf")) },
                                )
                                OrganizationSignupStep.Review -> OrganizationReviewStep(
                                    state = state,
                                    onTermsAccepted = viewModel::setTermsAccepted,
                                    onTerms = onTerms,
                                    onPrivacy = onPrivacy,
                                )
                            }
                        }
                    }
                }
            }
            OrganizationActions(
                state = state,
                credentialPickerOpen = credentialPickerOpen,
                onContinue = viewModel::continueForward,
                onRegister = ::register,
                onStudentSignup = onStudentSignup,
                onSignIn = onSignIn,
            )
        }
    }
}

@Composable
private fun OrganizationTopBar(onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().height(60.dp).padding(horizontal = NbDimens.space8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(NbIcons.Back, contentDescription = "Go back", tint = NbTheme.colors.ink)
        }
        NbLogo(size = 30.dp)
        Spacer(Modifier.width(NbDimens.space8))
        Text(
            "Organization registration",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = NbTheme.colors.ink,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun OrganizationProgress(step: OrganizationSignupStep) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = NbDimens.space24, vertical = NbDimens.space8),
        horizontalArrangement = Arrangement.spacedBy(NbDimens.space8),
    ) {
        OrganizationSignupStep.entries.forEach { item ->
            Box(
                Modifier
                    .weight(1f)
                    .height(3.dp)
                    .clip(CircleShape)
                    .background(
                        if (item.ordinal <= step.ordinal) NbTheme.colors.brandTeal
                        else NbTheme.colors.border,
                    ),
            )
        }
    }
}

@Composable
private fun OrganizationHeader(step: OrganizationSignupStep) {
    val title = when (step) {
        OrganizationSignupStep.Type -> "What kind of organization is this?"
        OrganizationSignupStep.Details -> "Organization details"
        OrganizationSignupStep.Document -> "Verify the organization"
        OrganizationSignupStep.Review -> "Review and register"
    }
    val body = when (step) {
        OrganizationSignupStep.Type -> "Choose the closest match so the review team can verify the right document."
        OrganizationSignupStep.Details -> "Use the public name and location students should see on NextBench."
        OrganizationSignupStep.Document -> "Official documents are private and used only for account verification."
        OrganizationSignupStep.Review -> "Confirm the details below before continuing with your authorized Google account."
    }
    Column(verticalArrangement = Arrangement.spacedBy(NbDimens.space8)) {
        Surface(color = NbTheme.colors.brandMint.copy(alpha = 0.14f), shape = CircleShape) {
            Icon(
                if (step == OrganizationSignupStep.Document) NbIcons.Shield else NbIcons.Building,
                contentDescription = null,
                tint = NbTheme.colors.brandTeal,
                modifier = Modifier.padding(NbDimens.space12).size(24.dp),
            )
        }
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            color = NbTheme.colors.ink,
        )
        Text(body, style = MaterialTheme.typography.bodyMedium, color = NbTheme.colors.inkMuted)
    }
}

@Composable
private fun OrganizationTypeStep(selectedId: String, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(NbDimens.space12)) {
        OrganizationTypeOptions.forEach { option ->
            val selected = option.id == selectedId
            Surface(
                color = if (selected) NbTheme.colors.brandTeal.copy(alpha = 0.08f) else NbTheme.colors.surfaceCard,
                shape = RoundedCornerShape(NbDimens.radiusMd),
                border = BorderStroke(
                    1.dp,
                    if (selected) NbTheme.colors.brandTeal else NbTheme.colors.border,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(NbDimens.radiusMd))
                    .clickable { onSelect(option.id) }
                    .pressScale()
                    .semantics {
                        role = Role.RadioButton
                        contentDescription = "${option.label}, ${if (selected) "selected" else "not selected"}"
                    },
            ) {
                Row(
                    modifier = Modifier.padding(NbDimens.space16),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        color = if (selected) NbTheme.colors.brandTeal else NbTheme.colors.surfaceSoft,
                        shape = CircleShape,
                    ) {
                        Icon(
                            if (selected) NbIcons.Check else NbIcons.Building,
                            contentDescription = null,
                            tint = if (selected) androidx.compose.ui.graphics.Color.White else NbTheme.colors.inkMuted,
                            modifier = Modifier.padding(NbDimens.space8).size(19.dp),
                        )
                    }
                    Spacer(Modifier.width(NbDimens.space12))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(NbDimens.space2)) {
                        Text(option.label, style = MaterialTheme.typography.titleSmall, color = NbTheme.colors.ink)
                        Text(option.description, style = MaterialTheme.typography.bodySmall, color = NbTheme.colors.inkMuted)
                    }
                }
            }
        }
    }
}

@Composable
private fun OrganizationDetailsStep(state: OrganizationSignupUiState, viewModel: OrganizationSignupViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(NbDimens.space16)) {
        NbTextField(
            value = state.name,
            onValueChange = viewModel::setName,
            label = "Organization name",
            placeholder = "e.g. Lucknow Public School",
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
        )
        NbTextField(
            value = state.city,
            onValueChange = viewModel::setCity,
            label = "City",
            placeholder = "Lucknow",
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
        )
        NbTextField(
            value = state.website,
            onValueChange = viewModel::setWebsite,
            label = "Website (optional)",
            placeholder = "https://example.org",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
        )
        NbTextField(
            value = state.description,
            onValueChange = viewModel::setDescription,
            label = "About (optional)",
            placeholder = "Briefly describe the organization",
            singleLine = false,
            maxLines = 4,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
        )
        NbTextField(
            value = state.referralCode,
            onValueChange = viewModel::setReferralCode,
            label = "Referral code (optional)",
            placeholder = "NEXTBENCH",
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Done),
        )
    }
}

@Composable
private fun OrganizationDocumentStep(state: OrganizationSignupUiState, onChoose: () -> Unit) {
    val selectedType = state.selectedType
    Column(verticalArrangement = Arrangement.spacedBy(NbDimens.space16)) {
        selectedType?.let { option ->
            Surface(color = NbTheme.colors.surfaceSoft, shape = RoundedCornerShape(NbDimens.radiusMd)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(NbDimens.space16),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(NbIcons.FileText, contentDescription = null, tint = NbTheme.colors.brandTeal, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(NbDimens.space12))
                    Column(verticalArrangement = Arrangement.spacedBy(NbDimens.space4)) {
                        Text("Recommended document", style = MaterialTheme.typography.labelLarge, color = NbTheme.colors.ink)
                        Text(option.documentHint, style = MaterialTheme.typography.bodySmall, color = NbTheme.colors.inkMuted)
                    }
                }
            }
        }
        Surface(
            color = NbTheme.colors.surfaceCard,
            shape = RoundedCornerShape(NbDimens.radiusLg),
            border = BorderStroke(1.dp, if (state.document != null) NbTheme.colors.brandTeal else NbTheme.colors.border),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(NbDimens.radiusLg))
                .clickable(enabled = !state.isPreparingDocument, onClick = onChoose)
                .pressScale(),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = NbDimens.space20, vertical = NbDimens.space32),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(NbDimens.space12),
            ) {
                Surface(
                    color = NbTheme.colors.brandTeal.copy(alpha = 0.1f),
                    shape = CircleShape,
                ) {
                    Icon(
                        if (state.document == null) NbIcons.Upload else NbIcons.Check,
                        contentDescription = null,
                        tint = NbTheme.colors.brandTeal,
                        modifier = Modifier.padding(NbDimens.space12).size(28.dp),
                    )
                }
                val document = state.document
                Text(
                    when {
                        state.isPreparingDocument -> "Preparing document..."
                        document != null -> document.displayName
                        else -> "Choose verification document"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    color = NbTheme.colors.ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (document != null) "${formatFileSize(document.sizeBytes)} · Tap to replace"
                    else "JPG, PNG, WebP, or PDF · Up to 10 MB",
                    style = MaterialTheme.typography.bodySmall,
                    color = NbTheme.colors.inkMuted,
                )
            }
        }
    }
}

@Composable
private fun OrganizationReviewStep(
    state: OrganizationSignupUiState,
    onTermsAccepted: (Boolean) -> Unit,
    onTerms: () -> Unit,
    onPrivacy: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(NbDimens.space20)) {
        Column(verticalArrangement = Arrangement.spacedBy(NbDimens.space12)) {
            ReviewRow("Organization", state.name)
            ReviewRow("Type", state.selectedType?.label.orEmpty())
            ReviewRow("Location", state.city)
            state.website.takeIf(String::isNotBlank)?.let { ReviewRow("Website", it) }
            ReviewRow("Document", state.document?.displayName.orEmpty())
        }
        HorizontalDivider(color = NbTheme.colors.border)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(NbDimens.radiusSm))
                .clickable { onTermsAccepted(!state.termsAccepted) }
                .padding(vertical = NbDimens.space4),
            verticalAlignment = Alignment.Top,
        ) {
            Checkbox(
                checked = state.termsAccepted,
                onCheckedChange = onTermsAccepted,
                colors = CheckboxDefaults.colors(
                    checkedColor = NbTheme.colors.brandTeal,
                    uncheckedColor = NbTheme.colors.inkMuted,
                ),
            )
            Column(Modifier.padding(top = NbDimens.space8)) {
                Text(
                    "I am authorized to represent this organization and consent to identity verification.",
                    style = MaterialTheme.typography.bodySmall,
                    color = NbTheme.colors.ink,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onTerms, contentPadding = PaddingValues(end = NbDimens.space8)) {
                        Text("Terms of Service")
                    }
                    Text("and", style = MaterialTheme.typography.bodySmall, color = NbTheme.colors.inkMuted)
                    TextButton(onClick = onPrivacy, contentPadding = PaddingValues(start = NbDimens.space8)) {
                        Text("Privacy Policy")
                    }
                }
            }
        }
    }
}

@Composable
private fun ReviewRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(NbDimens.space16)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = NbTheme.colors.inkMuted, modifier = Modifier.width(92.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = NbTheme.colors.ink, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun OrganizationError(message: String) {
    Surface(
        color = NbTheme.colors.brandPink.copy(alpha = 0.08f),
        shape = RoundedCornerShape(NbDimens.radiusMd),
        border = BorderStroke(1.dp, NbTheme.colors.brandPink.copy(alpha = 0.24f)),
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(NbDimens.space16), verticalAlignment = Alignment.Top) {
            Icon(NbIcons.Shield, contentDescription = null, tint = NbTheme.colors.brandPink, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(NbDimens.space12))
            Text(message, style = MaterialTheme.typography.bodySmall, color = NbTheme.colors.ink)
        }
    }
}

@Composable
private fun OrganizationActions(
    state: OrganizationSignupUiState,
    credentialPickerOpen: Boolean,
    onContinue: () -> Unit,
    onRegister: () -> Unit,
    onStudentSignup: () -> Unit,
    onSignIn: () -> Unit,
) {
    Surface(color = NbTheme.colors.surfaceBase, shadowElevation = NbDimens.elevationCard) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = NbDimens.space24, vertical = NbDimens.space12),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val review = state.step == OrganizationSignupStep.Review
            NbButton(
                text = if (review) "Register with Google" else "Continue",
                onClick = if (review) onRegister else onContinue,
                loading = state.isSubmitting || credentialPickerOpen,
                enabled = !state.isPreparingDocument && !state.isSubmitting,
                modifier = Modifier.fillMaxWidth().widthIn(max = 520.dp),
            )
            if (state.step == OrganizationSignupStep.Type) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onStudentSignup) { Text("Student sign up") }
                    Text("·", color = NbTheme.colors.inkFaint)
                    TextButton(onClick = onSignIn) { Text("Sign in") }
                }
            }
        }
    }
}

private fun formatFileSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / (1024f * 1024f))
    bytes >= 1024L -> "%.0f KB".format(bytes / 1024f)
    else -> "$bytes B"
}
