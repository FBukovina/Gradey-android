package com.bukovinafilip.gradey.feature.login

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.bukovinafilip.gradey.domain.SchoolDirectorySearch
import com.bukovinafilip.gradey.domain.SchoolLoginStep
import com.bukovinafilip.gradey.model.SchoolDirectorySchool
import com.bukovinafilip.gradey.model.SchoolProvider
import com.bukovinafilip.gradey.ui.BrandTextField
import com.bukovinafilip.gradey.ui.DisclosureIcon
import com.bukovinafilip.gradey.ui.GradeyIcon
import com.bukovinafilip.gradey.ui.GradeyPrimaryButton
import com.bukovinafilip.gradey.ui.GradeySpacing
import com.bukovinafilip.gradey.ui.GradeyType
import com.bukovinafilip.gradey.ui.HugeIcons
import com.bukovinafilip.gradey.ui.InlineNotice
import com.bukovinafilip.gradey.ui.LoadingState
import com.bukovinafilip.gradey.ui.PlainLink
import com.bukovinafilip.gradey.ui.PlainRow
import com.bukovinafilip.gradey.ui.SectionHeader
import com.bukovinafilip.gradey.ui.SegmentedControl
import com.bukovinafilip.gradey.ui.gradeyColors

/** Column of a sign-in step: a display title and one line of explanation, capped at 520 dp. */
@Composable
private fun SignInColumn(title: String, subtitle: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Box(modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.ime)), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 520.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = GradeySpacing.xl),
            verticalArrangement = Arrangement.spacedBy(GradeySpacing.lg),
        ) {
            Column(Modifier.padding(top = GradeySpacing.sm), verticalArrangement = Arrangement.spacedBy(GradeySpacing.sm)) {
                Text(title, Modifier.semantics { heading() }, style = GradeyType.display(32), color = MaterialTheme.colorScheme.onSurface)
                Text(subtitle, style = GradeyType.callout, color = MaterialTheme.gradeyColors.secondaryLabel)
            }
            content()
        }
    }
}

private enum class LoginStage { SCHOOL, CREDENTIALS }

/** "School › Sign in": the two stages of linking a school, the current one in the brand colour. */
@Composable
private fun StageTrail(stage: LoginStage) {
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.gradeyColors.secondaryLabel
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.sm)) {
        Text(
            stringResource(R.string.login_stage_school), style = if (stage == LoginStage.SCHOOL) GradeyType.captionBold else GradeyType.caption,
            color = if (stage == LoginStage.SCHOOL) primary else secondary,
        )
        GradeyIcon(HugeIcons.ArrowRight01, size = 10.dp, tint = MaterialTheme.gradeyColors.tertiaryLabel)
        Text(
            stringResource(R.string.login_stage_credentials), style = if (stage == LoginStage.CREDENTIALS) GradeyType.captionBold else GradeyType.caption,
            color = if (stage == LoginStage.CREDENTIALS) primary else secondary,
        )
    }
}

/**
 * The school step of onboarding (iOS 2.8.1 `LoginView`), in two stages: choose the school — system
 * on a segmented control, the school search, or the address typed by hand — then sign in to it.
 * A full-screen surface that handles the system bars and the keyboard itself.
 */
@Composable
fun SchoolLoginScreen(
    isLoading: Boolean,
    onLogin: (SchoolProvider, String, String, String) -> Unit,
    modifier: Modifier = Modifier,
    availableProviders: List<SchoolProvider> = listOf(SchoolProvider.BAKALARI),
    errorMessage: String? = null,
    onBack: (() -> Unit)? = null,
    initialProvider: SchoolProvider? = null,
    initialSchoolURL: String? = null,
    isReconnecting: Boolean = false,
    accountPicker: (@Composable () -> Unit)? = null,
    directorySchools: List<SchoolDirectorySchool> = emptyList(),
    directoryLoading: Boolean = false,
    directoryError: String? = null,
    onLoadDirectory: (() -> Unit)? = null,
    onOpenHelp: (() -> Unit)? = null,
) {
    var provider by rememberSaveable(initialProvider) { mutableStateOf(initialProvider ?: availableProviders.firstOrNull() ?: SchoolProvider.BAKALARI) }
    var school by rememberSaveable(initialSchoolURL) { mutableStateOf(initialSchoolURL.orEmpty()) }
    var stage by rememberSaveable(isReconnecting, initialSchoolURL) {
        mutableStateOf(if (isReconnecting || !initialSchoolURL.isNullOrBlank()) LoginStage.CREDENTIALS else LoginStage.SCHOOL)
    }
    var username by rememberSaveable { mutableStateOf("") }
    var password by remember(initialProvider, initialSchoolURL) { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var directoryQuery by rememberSaveable { mutableStateOf("") }
    var guideOpen by rememberSaveable { mutableStateOf(false) }
    val directoryResults = remember(directoryQuery, directorySchools) {
        if (directoryQuery.trim().length < 2) emptyList() else SchoolDirectorySearch.results(directoryQuery, directorySchools)
    }
    LaunchedEffect(provider, isReconnecting) {
        if (provider == SchoolProvider.BAKALARI && !isReconnecting) onLoadDirectory?.invoke()
    }
    val focusManager = LocalFocusManager.current
    val canSubmit = !isLoading && school.isNotBlank() && username.isNotBlank() && password.isNotEmpty()
    val submit = {
        if (canSubmit) {
            focusManager.clearFocus()
            onLogin(provider, school.trim(), username.trim(), password)
        }
    }
    val canChangeSchool = !isReconnecting && !isLoading
    BackHandler(enabled = stage == LoginStage.CREDENTIALS && canChangeSchool) { stage = LoginStage.SCHOOL }
    val secondary = MaterialTheme.gradeyColors.secondaryLabel
    val eduPage = provider == SchoolProvider.EDU_PAGE

    if (stage == LoginStage.SCHOOL) SignInColumn(
        title = stringResource(R.string.login_choose_school_title), subtitle = stringResource(R.string.login_choose_school_body),
        modifier = modifier.testTag("schoolLogin"),
    ) {
        StageTrail(stage)
        accountPicker?.invoke()
        if (availableProviders.size > 1) Column(verticalArrangement = Arrangement.spacedBy(GradeySpacing.sm)) {
            SectionHeader(stringResource(R.string.login_school_system), Modifier.padding(horizontal = GradeySpacing.xs))
            SegmentedControl(
                options = availableProviders.map { it.displayName }, selectedIndex = availableProviders.indexOf(provider).coerceAtLeast(0),
                onSelect = { if (!isLoading) provider = availableProviders[it] }, modifier = Modifier.fillMaxWidth().testTag("schoolProviderPicker"),
            )
        }
        if (!eduPage && onLoadDirectory != null) Column(verticalArrangement = Arrangement.spacedBy(GradeySpacing.sm)) {
            BrandTextField(
                value = directoryQuery, onValueChange = { directoryQuery = it }, modifier = Modifier.fillMaxWidth().testTag("schoolSearch"),
                placeholder = stringResource(R.string.login_search_schools), enabled = !isLoading,
                imeAction = ImeAction.Search, onImeAction = { focusManager.clearFocus() }, leadingIcon = HugeIcons.Search01,
            )
            if (directoryLoading) LoadingState()
            if (directoryResults.isNotEmpty()) Column {
                directoryResults.forEach { result ->
                    androidx.compose.runtime.key(result.id) {
                        PlainRow(
                            onClick = { school = result.schoolURL; directoryQuery = result.name; focusManager.clearFocus(); stage = LoginStage.CREDENTIALS },
                            enabled = !isLoading,
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(result.name, style = GradeyType.subheadlineBold, color = MaterialTheme.colorScheme.onSurface)
                                Text(
                                    stringResource(R.string.login_school_result_detail, result.trimmedTown, result.trimmedSchoolURL.removePrefix("https://").removePrefix("http://")),
                                    style = GradeyType.caption, color = secondary, maxLines = 2,
                                )
                            }
                            DisclosureIcon()
                        }
                    }
                }
            }
            if (directoryQuery.trim().length >= 2 && directoryResults.isEmpty() && !directoryLoading && directoryError == null) Text(
                stringResource(R.string.login_no_schools_found), Modifier.padding(horizontal = GradeySpacing.xs), style = GradeyType.caption, color = secondary,
            )
            if (directoryError != null) InlineNotice(
                stringResource(R.string.login_school_search_is_unavailable_you_can_still_enter_the_address_b_a82df), color = MaterialTheme.gradeyColors.orange,
            ) { PlainLink(stringResource(R.string.login_retry), onLoadDirectory, enabled = !directoryLoading, showsArrow = false) }
        }
        Column(verticalArrangement = Arrangement.spacedBy(GradeySpacing.sm)) {
            if (!eduPage && onLoadDirectory != null) Text(
                stringResource(R.string.login_manual_address), Modifier.padding(horizontal = GradeySpacing.xs), style = GradeyType.footnote, color = secondary,
            )
            BrandTextField(
                value = school, onValueChange = { school = it }, modifier = Modifier.fillMaxWidth().testTag("schoolAddress"),
                label = stringResource(if (eduPage) R.string.login_edupage_url else R.string.login_school_url),
                placeholder = if (eduPage) "my-school.edupage.org" else "https://bakalari.school.cz",
                enabled = !isLoading, keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done,
                onImeAction = { if (school.isNotBlank()) { focusManager.clearFocus(); stage = LoginStage.CREDENTIALS } },
            )
            if (eduPage) Text(stringResource(R.string.login_edupage_hint), Modifier.padding(horizontal = GradeySpacing.xs), style = GradeyType.caption, color = secondary)
            else PlainRow(
                Modifier.animateContentSize(tween(250)), onClick = { guideOpen = !guideOpen }, verticalAlignment = Alignment.Top, showDivider = false,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(GradeySpacing.sm)) {
                    Text(stringResource(R.string.login_url_guide_title), style = GradeyType.subheadlineSemibold, color = MaterialTheme.colorScheme.primary)
                    if (guideOpen) {
                        listOf(R.string.login_url_guide_step1, R.string.login_url_guide_step2, R.string.login_url_guide_step3, R.string.login_url_guide_step4)
                            .forEachIndexed { index, step ->
                                Row(horizontalArrangement = Arrangement.spacedBy(GradeySpacing.sm)) {
                                    Text("${index + 1}.", style = GradeyType.footnoteBold, color = secondary)
                                    Text(stringResource(step), Modifier.weight(1f), style = GradeyType.footnote, color = secondary)
                                }
                            }
                        Text(stringResource(R.string.login_url_guide_example), style = GradeyType.footnoteBold, color = secondary)
                    }
                }
                GradeyIcon(
                    HugeIcons.ArrowDown01, Modifier.padding(top = GradeySpacing.xs).graphicsLayer { rotationZ = if (guideOpen) 180f else 0f },
                    size = 13.dp, tint = MaterialTheme.gradeyColors.tertiaryLabel,
                )
            }
        }
        GradeyPrimaryButton(
            stringResource(R.string.login_continue_to_sign_in), { focusManager.clearFocus(); stage = LoginStage.CREDENTIALS },
            Modifier.testTag("schoolContinue"), enabled = !isLoading && school.isNotBlank(),
        )
        Column {
            onOpenHelp?.let { help -> PlainLink(stringResource(R.string.login_help_signing_in), { focusManager.clearFocus(); help() }) }
            if (onBack != null) PlainLink(stringResource(R.string.login_back_b52b3), onBack, enabled = !isLoading, color = secondary, showsArrow = false)
        }
    } else SignInColumn(
        title = stringResource(if (isReconnecting) R.string.login_reconnect_school_14a5a else R.string.login_credentials_title),
        subtitle = stringResource(
            if (isReconnecting) R.string.login_sign_in_to_the_same_school_account_to_restore_its_connection_a_6194d else R.string.login_credentials_body,
        ),
        modifier = modifier.testTag("schoolCredentials"),
    ) {
        if (!isReconnecting) StageTrail(stage)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = GradeySpacing.xs), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(GradeySpacing.md),
        ) {
            GradeyIcon(HugeIcons.University, size = 18.dp, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(provider.displayName, style = GradeyType.captionSemibold, color = secondary)
                Text(school.trim(), style = GradeyType.subheadlineBold, color = MaterialTheme.colorScheme.onSurface, maxLines = 2)
            }
            if (canChangeSchool) PlainLink(stringResource(R.string.login_change_school), { stage = LoginStage.SCHOOL }, showsArrow = false)
        }
        Column(verticalArrangement = Arrangement.spacedBy(GradeySpacing.md)) {
            BrandTextField(
                value = username, onValueChange = { username = it }, modifier = Modifier.fillMaxWidth().testTag("schoolUsername"),
                label = stringResource(R.string.login_username_84c29), enabled = !isLoading,
                imeAction = ImeAction.Next, onImeAction = { focusManager.moveFocus(FocusDirection.Down) },
            )
            BrandTextField(
                value = password, onValueChange = { password = it }, modifier = Modifier.fillMaxWidth().testTag("schoolPassword"),
                label = stringResource(R.string.login_password_8be3c), enabled = !isLoading, keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done, onImeAction = submit,
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailing = {
                    val label = stringResource(if (passwordVisible) R.string.login_hide_password_e4012 else R.string.login_show_password_044b8)
                    Box(
                        Modifier.size(28.dp).clip(CircleShape).clickable(role = Role.Button) { passwordVisible = !passwordVisible }.semantics { contentDescription = label },
                        contentAlignment = Alignment.Center,
                    ) { GradeyIcon(if (passwordVisible) HugeIcons.ViewOff else HugeIcons.View, size = 17.dp, tint = secondary) }
                },
            )
        }
        errorMessage?.let { InlineNotice(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        GradeyPrimaryButton(
            stringResource(if (isLoading) R.string.login_connecting_fd3e7 else if (isReconnecting) R.string.login_reconnect_school_14a5a else R.string.login_connect_school_85e9d),
            submit, Modifier.testTag("schoolConnect"), enabled = canSubmit, loading = isLoading,
        )
        Column {
            onOpenHelp?.let { help -> PlainLink(stringResource(R.string.login_help_signing_in), { focusManager.clearFocus(); help() }) }
            if (onBack != null) PlainLink(stringResource(R.string.login_back_b52b3), onBack, enabled = !isLoading, color = secondary, showsArrow = false)
        }
    }
}

/** EduPage's second step: a verification code or device approval, or choosing which student to open. */
@Composable
fun SchoolVerificationScreen(
    step: SchoolLoginStep,
    isLoading: Boolean,
    errorMessage: String?,
    onSubmitCode: (String) -> Unit,
    onCheckApproval: () -> Unit,
    onResend: () -> Unit,
    onSelectStudent: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenHelp: (() -> Unit)? = null,
) {
    var code by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current
    val secondary = MaterialTheme.gradeyColors.secondaryLabel
    BackHandler(enabled = !isLoading, onBack = onBack)
    SignInColumn(
        title = stringResource(R.string.login_connect_edupage_17a62),
        subtitle = stringResource(
            if (step is SchoolLoginStep.TwoFactor) R.string.login_complete_verification_using_your_edupage_account_98a6c else R.string.login_choose_a_student_6d1fa,
        ),
        modifier = modifier.testTag("schoolVerification"),
    ) {
        when (step) {
            is SchoolLoginStep.TwoFactor -> {
                if (step.prompt.supportsCode) Column(verticalArrangement = Arrangement.spacedBy(GradeySpacing.md)) {
                    SectionHeader(stringResource(R.string.login_code_section), Modifier.padding(horizontal = GradeySpacing.xs))
                    BrandTextField(
                        value = code, onValueChange = { code = it.trim() }, modifier = Modifier.fillMaxWidth(),
                        placeholder = stringResource(R.string.login_verification_code_80f2f), enabled = !isLoading, keyboardType = KeyboardType.NumberPassword,
                        onImeAction = { if (code.isNotBlank()) onSubmitCode(code) },
                    )
                    GradeyPrimaryButton(stringResource(R.string.login_verify_code_91a22), { onSubmitCode(code) }, enabled = !isLoading && code.isNotBlank(), loading = isLoading)
                }
                if (step.prompt.supportsDeviceApproval) Column {
                    SectionHeader(stringResource(R.string.login_device_section), Modifier.padding(horizontal = GradeySpacing.xs))
                    Text(
                        stringResource(R.string.login_approve_the_request_in_edupage_then_check_approval_here_41b93),
                        Modifier.padding(horizontal = GradeySpacing.xs, vertical = GradeySpacing.sm), style = GradeyType.subheadline, color = secondary,
                    )
                    PlainLink(stringResource(R.string.login_check_approval_81ddf), onCheckApproval, enabled = !isLoading)
                    PlainLink(stringResource(R.string.login_resend_approval_request_03ff9), onResend, enabled = !isLoading, showsArrow = false)
                }
            }
            is SchoolLoginStep.StudentSelection -> Column {
                step.students.forEach { student ->
                    androidx.compose.runtime.key(student.id) {
                        PlainRow(onClick = { onSelectStudent(student.id) }, enabled = !isLoading) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(student.fullName, style = GradeyType.subheadlineBold, color = MaterialTheme.colorScheme.onSurface)
                                student.className?.let { Text(it, style = GradeyType.caption, color = secondary) }
                            }
                            DisclosureIcon()
                        }
                    }
                }
            }
            else -> Unit
        }
        if (isLoading && step !is SchoolLoginStep.TwoFactor) LoadingState()
        errorMessage?.let { InlineNotice(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        Column {
            onOpenHelp?.let { help -> PlainLink(stringResource(R.string.login_help_verification), { focusManager.clearFocus(); help() }) }
            PlainLink(stringResource(R.string.login_back_b52b3), onBack, enabled = !isLoading, color = secondary, showsArrow = false)
        }
    }
}
