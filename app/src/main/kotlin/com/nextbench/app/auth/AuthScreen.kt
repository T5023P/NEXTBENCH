package com.nextbench.app.auth

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.credentials.CredentialManager
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.nextbench.app.navigation.NbRoute
import com.nextbench.core.designsystem.NbBottomSheet
import com.nextbench.core.designsystem.NbButton
import com.nextbench.core.designsystem.NbButtonVariant
import com.nextbench.core.designsystem.NbDimens
import com.nextbench.core.designsystem.NbIcons
import com.nextbench.core.designsystem.NbLogo
import com.nextbench.core.designsystem.NbMotion
import com.nextbench.core.designsystem.NbTextField
import com.nextbench.core.designsystem.NbTheme
import com.nextbench.core.designsystem.pressScale
import com.nextbench.data.firebase.AuthFailure
import com.nextbench.data.firebase.AuthFailureKind
import com.nextbench.data.model.School
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val OtpLength = 6
private const val ResendSeconds = 60

@Composable
fun AuthScreen(
    authViewModel: AuthViewModel,
    initialMode: OtpMode,
    navController: NavHostController,
) {
    val state by authViewModel.state.collectAsStateWithLifecycle()
    val deletionPending by authViewModel.pendingDeletion.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val credentialHelper = remember(context) {
        GoogleCredentialHelper(CredentialManager.create(context))
    }
    var credentialPickerOpen by remember { mutableStateOf(false) }

    LaunchedEffect(initialMode) {
        authViewModel.setMode(initialMode)
    }

    LaunchedEffect(state.completedSession) {
        if (state.completedSession == null) return@LaunchedEffect
        val signedUp = state.mode == OtpMode.Signup
        authViewModel.clearCompletedSession()
        if (signedUp) {
            navController.navigate(NbRoute.Verification.path) {
                popUpTo(initialMode.routePath()) { inclusive = true }
                launchSingleTop = true
            }
            return@LaunchedEffect
        }
        // Signing in again to finish a deletion must land back on the profile screen that owns
        // the deletion request; the auth gate pushed this screen on top of it, so restoring the
        // caller is the normal case and the explicit destination is the fallback.
        if (navController.popBackStack()) return@LaunchedEffect
        navController.navigate(authResumeDestination(authViewModel.pendingDeletion.value)) {
            popUpTo(NbRoute.Splash.path) { inclusive = true }
            launchSingleTop = true
        }
    }

    fun closeAuth() {
        if (state.step == AuthStep.Otp) {
            authViewModel.backToEmail()
            return
        }

        // Backing out of sign-in abandons the deletion for now; clearing the intent keeps it
        // from reopening the delete sheet after some later, unrelated sign-in.
        authViewModel.clearPendingDeletion()

        val destination = authExitDestination(
            callerRoute = navController.previousBackStackEntry?.destination?.route,
        )
        if (destination == null && navController.popBackStack()) return

        navController.navigate(destination ?: NbRoute.Feed.path) {
            popUpTo(NbRoute.Feed.path) { inclusive = false }
            launchSingleTop = true
        }
    }

    fun openGooglePicker() {
        if (state.mode == OtpMode.Signup && !authViewModel.validateSignupDetails()) return
        if (credentialPickerOpen || state.isLoading || state.isGoogleLoading) return
        scope.launch {
            credentialPickerOpen = true
            try {
                val idToken = credentialHelper.getIdToken(context)
                if (state.mode == OtpMode.Signup) {
                    authViewModel.completeGoogleSignup(idToken)
                } else {
                    authViewModel.completeGoogleLogin(idToken)
                }
            } catch (_: GetCredentialCancellationException) {
                // Dismissing the platform account picker is not an authentication error.
            } catch (error: Exception) {
                authViewModel.setExternalError(error.authMessage())
            } finally {
                credentialPickerOpen = false
            }
        }
    }

    BackHandler(onBack = ::closeAuth)

    Surface(
        color = NbTheme.colors.surfaceBase,
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding(),
        ) {
            AuthTopBar(onClose = ::closeAuth)

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = NbDimens.space24,
                    end = NbDimens.space24,
                    top = NbDimens.space20,
                    bottom = NbDimens.space32,
                ),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .widthIn(max = 480.dp),
                    ) {
                        AuthHeader(mode = state.mode)
                        Spacer(Modifier.height(NbDimens.space24))

                        AnimatedVisibility(
                            visible = deletionPending,
                            enter = fadeIn(NbMotion.interactionTween()),
                            exit = fadeOut(NbMotion.interactionTween()),
                        ) {
                            AuthReauthNotice()
                        }

                        AnimatedVisibility(
                            visible = state.error != null,
                            enter = fadeIn(NbMotion.interactionTween()),
                            exit = fadeOut(NbMotion.interactionTween()),
                        ) {
                            state.error?.let { AuthErrorBanner(it) }
                        }

                        AnimatedVisibility(
                            visible = state.accountNotFound,
                            enter = fadeIn(NbMotion.interactionTween()),
                            exit = fadeOut(NbMotion.interactionTween()),
                        ) {
                            AccountNotFoundCard(
                                onCreateAccount = {
                                    replaceAuthRoute(navController, NbRoute.Signup.path)
                                },
                            )
                        }

                        AnimatedContent(
                            targetState = state.step,
                            transitionSpec = {
                                if (targetState == AuthStep.Otp) {
                                    (fadeIn(NbMotion.interactionTween()) +
                                        slideInHorizontally(NbMotion.interactionTween()) { it / 12 })
                                        .togetherWith(
                                            fadeOut(NbMotion.interactionTween()) +
                                                slideOutHorizontally(NbMotion.interactionTween()) { -it / 12 },
                                        )
                                } else {
                                    (fadeIn(NbMotion.interactionTween()) +
                                        slideInHorizontally(NbMotion.interactionTween()) { -it / 12 })
                                        .togetherWith(
                                            fadeOut(NbMotion.interactionTween()) +
                                                slideOutHorizontally(NbMotion.interactionTween()) { it / 12 },
                                        )
                                }
                            },
                            label = "authStep",
                        ) { step ->
                            when (step) {
                                AuthStep.Email -> AuthDetailsStep(
                                    state = state,
                                    credentialPickerOpen = credentialPickerOpen,
                                    onEmailChange = authViewModel::setEmail,
                                    onNameChange = authViewModel::setName,
                                    onSchoolChange = authViewModel::setSchool,
                                    onCityChange = authViewModel::setCity,
                                    onReferralChange = authViewModel::setReferralCode,
                                    onTermsChange = authViewModel::setTermsAccepted,
                                    onSendOtp = authViewModel::sendOtp,
                                    onGoogle = ::openGooglePicker,
                                    onTerms = { navController.navigate(NbRoute.Terms.path) },
                                    onPrivacy = { navController.navigate(NbRoute.Privacy.path) },
                                )

                                AuthStep.Otp -> AuthOtpStep(
                                    state = state,
                                    onOtpChange = authViewModel::setOtp,
                                    onVerify = authViewModel::verifyOtp,
                                    onChangeEmail = authViewModel::backToEmail,
                                    onResendAvailable = authViewModel::enableResend,
                                    onResend = authViewModel::sendOtp,
                                )
                            }
                        }

                        Spacer(Modifier.height(NbDimens.space32))
                        AuthFooter(
                            mode = state.mode,
                            onSwitchMode = {
                                val route = if (state.mode == OtpMode.Login) {
                                    NbRoute.Signup.path
                                } else {
                                    NbRoute.Login.path
                                }
                                replaceAuthRoute(navController, route)
                            },
                            onOrganizationSignup = {
                                navController.navigate(NbRoute.OrgSignup.path)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AuthTopBar(onClose: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(60.dp)
            .padding(horizontal = NbDimens.space16),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NbLogo(size = 32.dp)
        Spacer(Modifier.width(NbDimens.space8))
        Text(
            text = "NextBench",
            style = MaterialTheme.typography.titleMedium,
            color = NbTheme.colors.ink,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onClose) {
            Icon(
                imageVector = NbIcons.Close,
                contentDescription = "Close authentication",
                tint = NbTheme.colors.inkMuted,
            )
        }
    }
}

@Composable
private fun AuthHeader(mode: OtpMode) {
    val signup = mode == OtpMode.Signup
    Column(horizontalAlignment = Alignment.Start) {
        Surface(
            color = NbTheme.colors.brandMint.copy(alpha = 0.12f),
            shape = RoundedCornerShape(NbDimens.radiusFull),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = NbDimens.space12, vertical = NbDimens.space8),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NbDimens.space8),
            ) {
                Icon(
                    imageVector = NbIcons.Shield,
                    contentDescription = null,
                    tint = NbTheme.colors.brandMint,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = if (signup) "VERIFIED MEMBERSHIP" else "SECURE SIGN IN",
                    style = MaterialTheme.typography.labelSmall,
                    color = NbTheme.colors.ink,
                )
            }
        }
        Spacer(Modifier.height(NbDimens.space16))
        Text(
            text = if (signup) "Join your campus." else "Welcome back.",
            style = MaterialTheme.typography.headlineLarge,
            color = NbTheme.colors.ink,
        )
        Spacer(Modifier.height(NbDimens.space8))
        Text(
            text = if (signup) {
                "Create your student identity, then verify it once."
            } else {
                "Continue where your campus left off."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = NbTheme.colors.inkMuted,
        )
    }
}

@Composable
private fun AuthDetailsStep(
    state: AuthUiState,
    credentialPickerOpen: Boolean,
    onEmailChange: (String) -> Unit,
    onNameChange: (String) -> Unit,
    onSchoolChange: (String) -> Unit,
    onCityChange: (String) -> Unit,
    onReferralChange: (String) -> Unit,
    onTermsChange: (Boolean) -> Unit,
    onSendOtp: () -> Unit,
    onGoogle: () -> Unit,
    onTerms: () -> Unit,
    onPrivacy: () -> Unit,
) {
    val isSignup = state.mode == OtpMode.Signup
    val busy = state.isLoading || state.isGoogleLoading || credentialPickerOpen
    var manualSchool by rememberSaveable(isSignup) { mutableStateOf(false) }
    val knownSchool = state.schools.firstOrNull {
        it.name.equals(state.school.trim(), ignoreCase = true)
    }
    val showManualSchool = manualSchool || (knownSchool == null && state.school.isNotBlank())

    Column(verticalArrangement = Arrangement.spacedBy(NbDimens.space16)) {
        if (isSignup) {
            if (showManualSchool) {
                NbTextField(
                    value = state.school,
                    onValueChange = onSchoolChange,
                    label = "School or institute",
                    placeholder = "Your institute name",
                    enabled = !busy,
                    singleLine = true,
                    leadingIcon = { AuthFieldIcon(NbIcons.Building) },
                )
                NbTextField(
                    value = state.city,
                    onValueChange = onCityChange,
                    label = "City",
                    placeholder = "Institute city",
                    enabled = !busy,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Words,
                        imeAction = ImeAction.Next,
                    ),
                )
                TextButton(
                    onClick = {
                        manualSchool = false
                        onSchoolChange("")
                        onCityChange("")
                    },
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Text("Choose a listed institute")
                }
            } else {
                SchoolSelector(
                    selectedSchool = knownSchool,
                    schools = state.schools,
                    enabled = !busy,
                    onSelect = { school ->
                        onSchoolChange(school.name)
                        onCityChange(school.city)
                    },
                    onManual = {
                        manualSchool = true
                        onSchoolChange("")
                        onCityChange("")
                    },
                )
            }

            NbTextField(
                value = state.name,
                onValueChange = onNameChange,
                label = "Full name",
                placeholder = "As shown on your student ID",
                enabled = !busy,
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Words,
                    imeAction = ImeAction.Next,
                ),
                leadingIcon = { AuthFieldIcon(NbIcons.Profile) },
            )
        }

        NbTextField(
            value = state.email,
            onValueChange = onEmailChange,
            label = "Email address",
            placeholder = "you@example.com",
            enabled = !busy,
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Email,
                imeAction = if (isSignup) ImeAction.Next else ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(
                onDone = { if (!isSignup) onSendOtp() },
            ),
            leadingIcon = { AuthFieldIcon(NbIcons.Mail) },
        )

        if (isSignup) {
            NbTextField(
                value = state.referralCode,
                onValueChange = onReferralChange,
                label = "Invite code (optional)",
                placeholder = "NB123",
                enabled = !busy,
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Characters,
                    imeAction = ImeAction.Done,
                ),
            )
            TermsConsent(
                checked = state.termsAccepted,
                enabled = !busy,
                onCheckedChange = onTermsChange,
                onTerms = onTerms,
                onPrivacy = onPrivacy,
            )
        }

        NbButton(
            text = if (isSignup) "Send verification code" else "Send one-time code",
            onClick = onSendOtp,
            enabled = state.email.isNotBlank() && !busy,
            loading = state.isLoading,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
        )

        AuthDivider()

        GoogleButton(
            loading = state.isGoogleLoading || credentialPickerOpen,
            enabled = !busy,
            onClick = onGoogle,
        )
    }
}

@Composable
private fun SchoolSelector(
    selectedSchool: School?,
    schools: List<School>,
    enabled: Boolean,
    onSelect: (School) -> Unit,
    onManual: () -> Unit,
) {
    var sheetOpen by rememberSaveable { mutableStateOf(false) }

    Column {
        Text(
            text = "School or institute",
            style = MaterialTheme.typography.labelMedium,
            color = NbTheme.colors.inkMuted,
            modifier = Modifier.padding(bottom = NbDimens.space4),
        )
        Surface(
            onClick = { if (enabled) sheetOpen = true },
            enabled = enabled,
            color = NbTheme.colors.surfaceCard,
            shape = RoundedCornerShape(NbDimens.radiusMd),
            border = androidx.compose.foundation.BorderStroke(1.dp, NbTheme.colors.border),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = NbDimens.space16, vertical = NbDimens.space14),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AuthFieldIcon(NbIcons.Building)
                Spacer(Modifier.width(NbDimens.space8))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = selectedSchool?.name ?: "Choose your institute",
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (selectedSchool == null) NbTheme.colors.inkMuted else NbTheme.colors.ink,
                        maxLines = 1,
                    )
                    selectedSchool?.city?.takeIf(String::isNotBlank)?.let { city ->
                        Text(
                            text = city,
                            style = MaterialTheme.typography.bodySmall,
                            color = NbTheme.colors.inkMuted,
                        )
                    }
                }
                Icon(
                    imageVector = NbIcons.ChevronDown,
                    contentDescription = null,
                    tint = NbTheme.colors.inkMuted,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }

    if (sheetOpen) {
        SchoolPickerSheet(
            schools = schools,
            onDismiss = { sheetOpen = false },
            onSelect = {
                onSelect(it)
                sheetOpen = false
            },
            onManual = {
                onManual()
                sheetOpen = false
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SchoolPickerSheet(
    schools: List<School>,
    onDismiss: () -> Unit,
    onSelect: (School) -> Unit,
    onManual: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = remember(schools, query) {
        val needle = query.trim()
        if (needle.isBlank()) schools else schools.filter {
            it.name.contains(needle, ignoreCase = true) ||
                it.city.contains(needle, ignoreCase = true)
        }
    }

    NbBottomSheet(onDismiss = onDismiss) {
        Text(
            text = "Choose your institute",
            style = MaterialTheme.typography.titleLarge,
            color = NbTheme.colors.ink,
            modifier = Modifier.padding(horizontal = NbDimens.space24),
        )
        Spacer(Modifier.height(NbDimens.space16))
        NbTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = "Search school or city",
            leadingIcon = { AuthFieldIcon(NbIcons.Search) },
            modifier = Modifier.padding(horizontal = NbDimens.space24),
        )
        Spacer(Modifier.height(NbDimens.space12))

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 360.dp),
            contentPadding = PaddingValues(horizontal = NbDimens.space12),
        ) {
            items(filtered, key = { it.id.ifBlank { "${it.name}:${it.city}" } }) { school ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = { onSelect(school) })
                        .padding(horizontal = NbDimens.space12, vertical = NbDimens.space14),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        color = NbTheme.colors.brandTeal.copy(alpha = 0.10f),
                        shape = CircleShape,
                    ) {
                        Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = NbIcons.Building,
                                contentDescription = null,
                                tint = NbTheme.colors.brandTeal,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                    Spacer(Modifier.width(NbDimens.space12))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = school.name,
                            style = MaterialTheme.typography.bodyLarge,
                            color = NbTheme.colors.ink,
                        )
                        if (school.city.isNotBlank()) {
                            Text(
                                text = school.city,
                                style = MaterialTheme.typography.bodySmall,
                                color = NbTheme.colors.inkMuted,
                            )
                        }
                    }
                    Icon(
                        imageVector = NbIcons.ArrowRight,
                        contentDescription = null,
                        tint = NbTheme.colors.inkMuted,
                        modifier = Modifier.size(17.dp),
                    )
                }
            }

            if (filtered.isEmpty()) {
                item {
                    Text(
                        text = "No listed institute matches this search.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = NbTheme.colors.inkMuted,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(NbDimens.space24),
                    )
                }
            }

            item {
                HorizontalDivider(color = NbTheme.colors.border)
                TextButton(
                    onClick = onManual,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = NbDimens.space16),
                ) {
                    Icon(
                        imageVector = NbIcons.Plus,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(NbDimens.space8))
                    Text("Enter institute manually")
                }
            }
        }
    }
}

@Composable
private fun TermsConsent(
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onTerms: () -> Unit,
    onPrivacy: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = CheckboxDefaults.colors(
                checkedColor = NbTheme.colors.brandTeal,
                uncheckedColor = NbTheme.colors.borderStrong,
            ),
        )
        Column(Modifier.padding(top = 10.dp)) {
            Text(
                text = "I agree to NextBench's Terms and Privacy Policy.",
                style = MaterialTheme.typography.bodySmall,
                color = NbTheme.colors.inkMuted,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(NbDimens.space12)) {
                TextButton(onClick = onTerms, contentPadding = PaddingValues(0.dp)) {
                    Text("Read terms", style = MaterialTheme.typography.labelMedium)
                }
                TextButton(onClick = onPrivacy, contentPadding = PaddingValues(0.dp)) {
                    Text("Privacy", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun AuthOtpStep(
    state: AuthUiState,
    onOtpChange: (String) -> Unit,
    onVerify: () -> Unit,
    onChangeEmail: () -> Unit,
    onResendAvailable: () -> Unit,
    onResend: () -> Unit,
) {
    var secondsRemaining by remember(state.otpRequestId) { mutableIntStateOf(ResendSeconds) }

    LaunchedEffect(state.otpRequestId) {
        while (secondsRemaining > 0) {
            delay(1_000)
            secondsRemaining--
        }
        onResendAvailable()
    }

    LaunchedEffect(state.otp) {
        if (state.otp.length == OtpLength && !state.isLoading) {
            delay(140)
            onVerify()
        }
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(NbDimens.space20),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            color = NbTheme.colors.brandTeal.copy(alpha = 0.10f),
            shape = CircleShape,
        ) {
            Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = NbIcons.Mail,
                    contentDescription = null,
                    tint = NbTheme.colors.brandTeal,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "Check your inbox",
                style = MaterialTheme.typography.titleMedium,
                color = NbTheme.colors.ink,
            )
            Spacer(Modifier.height(NbDimens.space4))
            Text(
                text = "We sent a 6-digit code to",
                style = MaterialTheme.typography.bodyMedium,
                color = NbTheme.colors.inkMuted,
            )
            Text(
                text = state.email.trim().lowercase(),
                style = MaterialTheme.typography.labelLarge,
                color = NbTheme.colors.ink,
            )
        }

        OtpInput(
            value = state.otp,
            onValueChange = onOtpChange,
            enabled = !state.isLoading,
        )

        NbButton(
            text = if (state.mode == OtpMode.Signup) "Verify and create account" else "Verify code",
            onClick = onVerify,
            enabled = state.otp.length == OtpLength,
            loading = state.isLoading,
            variant = NbButtonVariant.Secondary,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onChangeEmail, enabled = !state.isLoading) {
                Icon(
                    imageVector = NbIcons.Back,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(NbDimens.space4))
                Text("Change email")
            }
            if (state.canResend) {
                TextButton(onClick = onResend, enabled = !state.isLoading) {
                    Icon(
                        imageVector = NbIcons.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(NbDimens.space4))
                    Text("Resend")
                }
            } else {
                Text(
                    text = "Resend in ${secondsRemaining}s",
                    style = MaterialTheme.typography.labelMedium,
                    color = NbTheme.colors.inkMuted,
                )
            }
        }
    }
}

@Composable
private fun OtpInput(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        delay(180)
        focusRequester.requestFocus()
        keyboard?.show()
    }

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.NumberPassword,
            imeAction = ImeAction.Done,
        ),
        visualTransformation = PasswordVisualTransformation(),
        textStyle = TextStyle(color = Color.Transparent, fontSize = 1.sp),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(Color.Transparent),
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .semantics { contentDescription = "Six digit verification code" },
        decorationBox = { innerTextField ->
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .alpha(0f),
                ) {
                    innerTextField()
                }
                val gap = 8.dp
                val available = maxWidth - (gap * (OtpLength - 1))
                val cellWidth = minOf(52.dp, available / OtpLength)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    repeat(OtpLength) { index ->
                        if (index > 0) Spacer(Modifier.width(gap))
                        val active = index == value.length && enabled
                        val filled = index < value.length
                        val border = when {
                            active -> NbTheme.colors.brandTeal
                            filled -> NbTheme.colors.borderStrong
                            else -> NbTheme.colors.border
                        }
                        Box(
                            modifier = Modifier
                                .width(cellWidth)
                                .height(56.dp)
                                .background(
                                    NbTheme.colors.surfaceCard,
                                    RoundedCornerShape(NbDimens.radiusMd),
                                )
                                .border(
                                    width = if (active) 1.5.dp else 1.dp,
                                    color = border,
                                    shape = RoundedCornerShape(NbDimens.radiusMd),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = value.getOrNull(index)?.toString().orEmpty(),
                                style = MaterialTheme.typography.titleLarge,
                                color = NbTheme.colors.ink,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun GoogleButton(
    loading: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled && !loading,
        shape = RoundedCornerShape(NbDimens.radiusMd),
        border = androidx.compose.foundation.BorderStroke(1.dp, NbTheme.colors.borderStrong),
        contentPadding = PaddingValues(horizontal = NbDimens.space20, vertical = NbDimens.space12),
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .pressScale(targetScale = 0.97f),
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = NbTheme.colors.ink,
            )
        } else {
            Icon(
                imageVector = NbIcons.Shield,
                contentDescription = null,
                tint = NbTheme.colors.inkMuted,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(NbDimens.space8))
            Text(
                text = "Continue with Google",
                color = NbTheme.colors.ink,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@Composable
private fun AuthDivider() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HorizontalDivider(Modifier.weight(1f), color = NbTheme.colors.border)
        Text(
            text = "OR",
            style = MaterialTheme.typography.labelSmall,
            color = NbTheme.colors.inkMuted,
            modifier = Modifier.padding(horizontal = NbDimens.space12),
        )
        HorizontalDivider(Modifier.weight(1f), color = NbTheme.colors.border)
    }
}

@Composable
private fun AuthErrorBanner(error: AuthFailure) {
    val title = when (error.kind) {
        AuthFailureKind.Configuration -> "Setup needed"
        AuthFailureKind.Network -> "Connection issue"
        AuthFailureKind.RateLimited -> "Try again shortly"
        else -> "Could not continue"
    }
    Surface(
        color = NbTheme.colors.brandPink.copy(alpha = 0.08f),
        shape = RoundedCornerShape(NbDimens.radiusMd),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            NbTheme.colors.brandPink.copy(alpha = 0.24f),
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = NbDimens.space16),
    ) {
        Row(
            modifier = Modifier.padding(NbDimens.space16),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = NbIcons.Shield,
                contentDescription = null,
                tint = NbTheme.colors.brandPink,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(NbDimens.space12))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    color = NbTheme.colors.ink,
                )
                Spacer(Modifier.height(NbDimens.space2))
                Text(
                    text = error.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = NbTheme.colors.inkMuted,
                )
            }
        }
    }
}

@Composable
private fun AccountNotFoundCard(onCreateAccount: () -> Unit) {
    Surface(
        color = NbTheme.colors.surfaceCard,
        shape = RoundedCornerShape(NbDimens.radiusMd),
        border = androidx.compose.foundation.BorderStroke(1.dp, NbTheme.colors.border),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = NbDimens.space16),
    ) {
        Column(
            modifier = Modifier.padding(NbDimens.space16),
            horizontalAlignment = Alignment.Start,
        ) {
            Text(
                text = "No NextBench account found",
                style = MaterialTheme.typography.titleSmall,
                color = NbTheme.colors.ink,
            )
            Spacer(Modifier.height(NbDimens.space4))
            Text(
                text = "Create your student profile with this account to continue.",
                style = MaterialTheme.typography.bodySmall,
                color = NbTheme.colors.inkMuted,
            )
            Spacer(Modifier.height(NbDimens.space12))
            NbButton(
                text = "Create account",
                onClick = onCreateAccount,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun AuthFooter(
    mode: OtpMode,
    onSwitchMode: () -> Unit,
    onOrganizationSignup: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (mode == OtpMode.Login) "New to NextBench?" else "Already a member?",
                style = MaterialTheme.typography.bodySmall,
                color = NbTheme.colors.inkMuted,
            )
            TextButton(onClick = onSwitchMode) {
                Text(if (mode == OtpMode.Login) "Create account" else "Sign in")
            }
        }
        if (mode == OtpMode.Signup) {
            HorizontalDivider(color = NbTheme.colors.border)
            Spacer(Modifier.height(NbDimens.space16))
            TextButton(onClick = onOrganizationSignup) {
                Icon(
                    imageVector = NbIcons.Building,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(NbDimens.space8))
                Text("Register an organization")
            }
        }
    }
}

@Composable
private fun AuthFieldIcon(icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = NbTheme.colors.inkMuted,
        modifier = Modifier.size(19.dp),
    )
}

private fun replaceAuthRoute(navController: NavHostController, destination: String) {
    val current = navController.currentDestination?.route
    navController.navigate(destination) {
        launchSingleTop = true
        if (current in setOf(NbRoute.Login.path, NbRoute.Signup.path, NbRoute.Auth.path)) {
            popUpTo(requireNotNull(current)) { inclusive = true }
        }
    }
}

private fun OtpMode.routePath(): String = when (this) {
    OtpMode.Login -> NbRoute.Login.path
    OtpMode.Signup -> NbRoute.Signup.path
}

internal fun authExitDestination(callerRoute: String?): String? =
    NbRoute.Feed.path.takeIf {
        callerRoute == null || requirementForRoute(callerRoute) != RouteRequirement.Public
    }

/**
 * Where a completed sign-in lands when the caller cannot simply be restored.
 *
 * A deletion waiting on a fresh sign-in returns to the profile screen, which owns the deletion
 * request; every other sign-in falls back to the feed as before.
 */
internal fun authResumeDestination(deletionPending: Boolean): String =
    if (deletionPending) NbRoute.Profile.path else NbRoute.Feed.path

/**
 * Shown while the user is signing in again to finish an irreversible deletion, so the extra
 * sign-in does not read as an unexplained error or a failed deletion.
 */
@Composable
private fun AuthReauthNotice() {
    Surface(
        color = NbTheme.colors.brandTeal.copy(alpha = 0.08f),
        shape = RoundedCornerShape(NbDimens.radiusMd),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            NbTheme.colors.brandTeal.copy(alpha = 0.24f),
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = NbDimens.space16),
    ) {
        Row(
            modifier = Modifier.padding(NbDimens.space16),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = NbIcons.Shield,
                contentDescription = null,
                tint = NbTheme.colors.brandTeal,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(NbDimens.space12))
            Column {
                Text(
                    text = "Deleting your account",
                    style = MaterialTheme.typography.labelLarge,
                    color = NbTheme.colors.ink,
                )
                Spacer(Modifier.height(NbDimens.space2))
                Text(
                    text = "Please sign in again to continue deleting your account. Nothing has " +
                        "been deleted yet, and you will be asked to confirm once more.",
                    style = MaterialTheme.typography.bodySmall,
                    color = NbTheme.colors.inkMuted,
                )
            }
        }
    }
}

internal fun Exception.authMessage(): String = when (this) {
    is NoCredentialException ->
        "No Google account is available on this device. Add one in Android Settings and try again."
    else -> message
        ?.takeIf(String::isNotBlank)
        ?: "Google sign-in could not start. Please try again."
}
