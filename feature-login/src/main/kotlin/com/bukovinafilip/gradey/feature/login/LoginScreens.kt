package com.bukovinafilip.gradey.feature.login

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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

/**
 * The school step of onboarding (iOS 2.8.1 `LoginView`): Bakaláři or EduPage on a segmented control,
 * the school search, then address, username and password. A full-screen surface that handles the
 * system bars and the keyboard itself.
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
    var username by rememberSaveable { mutableStateOf("") }
    var password by remember(initialProvider, initialSchoolURL) { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var directoryQuery by rememberSaveable { mutableStateOf("") }
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
    val secondary = MaterialTheme.gradeyColors.secondaryLabel
    SignInColumn(
        title = stringResource(if (isReconnecting) R.string.login_reconnect_school_14a5a else R.string.login_link_your_school_100dd),
        subtitle = stringResource(
            if (isReconnecting) R.string.login_sign_in_to_the_same_school_account_to_restore_its_connection_a_6194d
            else R.string.login_your_marks_timetable_and_absences_together_in_one_place_saved__2edfe,
        ),
        modifier = modifier.testTag("schoolLogin"),
    ) {
        accountPicker?.invoke()
        if (availableProviders.size > 1 && !isReconnecting) SegmentedControl(
            options = availableProviders.map { it.displayName }, selectedIndex = availableProviders.indexOf(provider).coerceAtLeast(0),
            onSelect = { if (!isLoading) provider = availableProviders[it] }, modifier = Modifier.fillMaxWidth().testTag("schoolProviderPicker"),
        )
        if (provider == SchoolProvider.BAKALARI && !isReconnecting && onLoadDirectory != null) Column(verticalArrangement = Arrangement.spacedBy(GradeySpacing.sm)) {
            BrandTextField(
                value = directoryQuery, onValueChange = { directoryQuery = it }, modifier = Modifier.fillMaxWidth(),
                label = stringResource(R.string.login_find_your_school_77687), placeholder = stringResource(R.string.login_for_example_gymn_zium_brno_1c7e4),
                enabled = !isLoading, imeAction = ImeAction.Search, onImeAction = { focusManager.clearFocus() }, leadingIcon = HugeIcons.Search01,
            )
            if (directoryLoading) LoadingState()
            if (directoryResults.isNotEmpty()) Column {
                directoryResults.forEach { result ->
                    androidx.compose.runtime.key(result.id) {
                        PlainRow(onClick = { school = result.schoolURL; directoryQuery = result.name; focusManager.clearFocus() }, enabled = !isLoading) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(result.name, style = GradeyType.subheadlineBold, color = MaterialTheme.colorScheme.onSurface)
                                Text(result.town, style = GradeyType.caption, color = secondary)
                            }
                            DisclosureIcon()
                        }
                    }
                }
            }
            if (directoryQuery.trim().length >= 2 && directoryResults.isEmpty() && !directoryLoading) Text(
                stringResource(R.string.login_no_matching_school_found_you_can_enter_its_bakal_i_address_bel_2a6b5),
                Modifier.padding(horizontal = GradeySpacing.xs), style = GradeyType.caption, color = secondary,
            )
            if (directoryError != null) InlineNotice(
                stringResource(R.string.login_school_search_is_unavailable_you_can_still_enter_the_address_b_a82df),
                color = MaterialTheme.gradeyColors.orange,
            ) { PlainLink(stringResource(R.string.login_try_school_search_again_fc765), onLoadDirectory, enabled = !directoryLoading, showsArrow = false) }
        }
        Column(verticalArrangement = Arrangement.spacedBy(GradeySpacing.md)) {
            BrandTextField(
                value = school, onValueChange = { school = it }, modifier = Modifier.fillMaxWidth().testTag("schoolAddress"),
                label = stringResource(if (provider == SchoolProvider.EDU_PAGE) R.string.login_edupage_school_6060b else R.string.login_school_address_a2e60),
                placeholder = if (provider == SchoolProvider.EDU_PAGE) "school.edupage.org" else "bakalari.your-school.cz",
                enabled = !isLoading, keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next, onImeAction = { focusManager.moveFocus(FocusDirection.Down) },
            )
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
            Text(
                stringResource(R.string.login_your_school_password_stays_encrypted_on_this_device_61039),
                Modifier.padding(horizontal = GradeySpacing.xs), style = GradeyType.caption, color = secondary,
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
    SignInColumn(
        title = stringResource(R.string.login_connect_edupage_17a62),
        subtitle = stringResource(if (step is SchoolLoginStep.TwoFactor) R.string.login_verify_your_sign_in_29777 else R.string.login_choose_a_student_6d1fa),
        modifier = modifier.testTag("schoolVerification"),
    ) {
        when (step) {
            is SchoolLoginStep.TwoFactor -> {
                Text(stringResource(R.string.login_complete_verification_using_your_edupage_account_98a6c), style = GradeyType.subheadline, color = secondary)
                if (step.prompt.supportsCode) {
                    BrandTextField(
                        value = code, onValueChange = { code = it.trim() }, modifier = Modifier.fillMaxWidth(),
                        label = stringResource(R.string.login_verification_code_80f2f), enabled = !isLoading, keyboardType = KeyboardType.NumberPassword,
                        onImeAction = { if (code.isNotBlank()) onSubmitCode(code) },
                    )
                    GradeyPrimaryButton(stringResource(R.string.login_verify_code_91a22), { onSubmitCode(code) }, enabled = !isLoading && code.isNotBlank(), loading = isLoading)
                }
                if (step.prompt.supportsDeviceApproval) Column {
                    Text(
                        stringResource(R.string.login_approve_the_request_in_edupage_then_check_approval_here_41b93),
                        Modifier.padding(bottom = GradeySpacing.xs), style = GradeyType.subheadline, color = secondary,
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
            PlainLink(stringResource(R.string.login_back_to_school_login_9573e), onBack, enabled = !isLoading, color = secondary, showsArrow = false)
        }
    }
}
