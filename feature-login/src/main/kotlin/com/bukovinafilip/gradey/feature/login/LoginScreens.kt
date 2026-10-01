package com.bukovinafilip.gradey.feature.login

import com.bukovinafilip.gradey.ui.HugeIcons
import com.bukovinafilip.gradey.ui.HugeIcon

import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.bukovinafilip.gradey.domain.SchoolDirectorySearch
import com.bukovinafilip.gradey.model.SchoolDirectorySchool
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.bukovinafilip.gradey.model.SchoolProvider
import com.bukovinafilip.gradey.ui.GradeyHero
import com.bukovinafilip.gradey.ui.GradeyScreen
import com.bukovinafilip.gradey.ui.GradeySectionCard
import com.bukovinafilip.gradey.ui.GradeySpacing

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

    GradeyScreen(modifier = modifier) {
        GradeyHero(
            title = if (isReconnecting) stringResource(R.string.login_reconnect_school_14a5a) else stringResource(R.string.login_link_your_school_100dd),
            subtitle = if (isReconnecting) stringResource(R.string.login_sign_in_to_the_same_school_account_to_restore_its_connection_a_6194d) else stringResource(R.string.login_your_marks_timetable_and_absences_together_in_one_place_saved__2edfe),
        )
        onOpenHelp?.let { SchoolSignInHelp(stringResource(R.string.login_help_signing_in), it) }
        accountPicker?.invoke()
        GradeySectionCard(title = stringResource(R.string.login_provider_7ceee)) {
            Row(horizontalArrangement = Arrangement.spacedBy(GradeySpacing.sm)) {
                availableProviders.forEach { candidate ->
                    FilterChip(
                        selected = provider == candidate,
                        onClick = { provider = candidate },
                        enabled = !isLoading && !isReconnecting,
                        label = { Text(candidate.displayName) },
                    )
                }
            }
        }
        if (provider == SchoolProvider.BAKALARI && !isReconnecting && onLoadDirectory != null) {
            GradeySectionCard(title = stringResource(R.string.login_find_your_school_77687)) {
                OutlinedTextField(
                    value = directoryQuery,
                    onValueChange = { directoryQuery = it },
                    label = { Text(stringResource(R.string.login_school_name_or_city_6fdc3)) },
                    placeholder = { Text(stringResource(R.string.login_for_example_gymn_zium_brno_1c7e4)) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !isLoading,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                )
                if (directoryLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                directoryResults.forEach { result ->
                    androidx.compose.material3.TextButton(
                        onClick = { school = result.schoolURL; directoryQuery = result.name; focusManager.clearFocus() },
                        modifier = Modifier.fillMaxWidth(), enabled = !isLoading,
                    ) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(result.name, style = MaterialTheme.typography.titleSmall)
                            Text(result.town, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (directoryQuery.trim().length >= 2 && directoryResults.isEmpty() && !directoryLoading) {
                    Text(stringResource(R.string.login_no_matching_school_found_you_can_enter_its_bakal_i_address_bel_2a6b5), style = MaterialTheme.typography.bodySmall)
                }
                directoryError?.let {
                    Text(stringResource(R.string.login_school_search_is_unavailable_you_can_still_enter_the_address_b_a82df), style = MaterialTheme.typography.bodySmall)
                    androidx.compose.material3.TextButton(onClick = onLoadDirectory, enabled = !directoryLoading) { Text(stringResource(R.string.login_try_school_search_again_fc765)) }
                }
            }
        }
        GradeySectionCard(title = stringResource(R.string.login_credentials_dd097)) {
            Column(verticalArrangement = Arrangement.spacedBy(GradeySpacing.md)) {
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = school,
                    onValueChange = { school = it },
                    label = { Text(if (provider == SchoolProvider.EDU_PAGE) stringResource(R.string.login_edupage_school_6060b) else stringResource(R.string.login_school_address_a2e60)) },
                    placeholder = { Text(if (provider == SchoolProvider.EDU_PAGE) "school.edupage.org" else "bakalari.your-school.cz") },
                    supportingText = { Text(stringResource(R.string.login_use_the_address_where_you_normally_sign_in_to_your_school_b79ba)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next, autoCorrectEnabled = false),
                    keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
                    singleLine = true,
                    enabled = !isLoading,
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = username,
                    onValueChange = { username = it },
                    label = { Text(stringResource(R.string.login_username_84c29)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next, autoCorrectEnabled = false),
                    keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
                    singleLine = true,
                    enabled = !isLoading,
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.login_password_8be3c)) },
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            HugeIcon(if (passwordVisible) HugeIcons.ViewOff else HugeIcons.View,
                                contentDescription = if (passwordVisible) stringResource(R.string.login_hide_password_e4012) else stringResource(R.string.login_show_password_044b8))
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    singleLine = true,
                    enabled = !isLoading,
                )
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = canSubmit,
                    onClick = { submit() },
                ) {
                    HugeIcon(HugeIcons.Link04, contentDescription = null)
                    Text(if (isLoading) stringResource(R.string.login_connecting_fd3e7) else if (isReconnecting) stringResource(R.string.login_reconnect_school_14a5a) else stringResource(R.string.login_connect_school_85e9d))
                }
                if (isLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                Text(stringResource(R.string.login_your_school_password_stays_encrypted_on_this_device_61039), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (onBack != null) androidx.compose.material3.TextButton(onClick = onBack, enabled = !isLoading) { Text(stringResource(R.string.login_back_b52b3)) }
            }
        }
    }
}


@Composable
fun SchoolVerificationScreen(
    step: com.bukovinafilip.gradey.domain.SchoolLoginStep,
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
    GradeyScreen(modifier = modifier) {
        GradeyHero(stringResource(R.string.login_connect_edupage_17a62), if (step is com.bukovinafilip.gradey.domain.SchoolLoginStep.TwoFactor) stringResource(R.string.login_verify_your_sign_in_29777) else stringResource(R.string.login_choose_a_student_6d1fa))
        onOpenHelp?.let { SchoolSignInHelp(stringResource(R.string.login_help_verification), it) }
        GradeySectionCard {
            when (step) {
                is com.bukovinafilip.gradey.domain.SchoolLoginStep.TwoFactor -> {
                    Text(stringResource(R.string.login_complete_verification_using_your_edupage_account_98a6c))
                    if (step.prompt.supportsCode) {
                        OutlinedTextField(value = code, onValueChange = { code = it.trim() }, label = { Text(stringResource(R.string.login_verification_code_80f2f)) }, singleLine = true, enabled = !isLoading, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
                        Button(onClick = { onSubmitCode(code) }, enabled = !isLoading && code.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.login_verify_code_91a22)) }
                    }
                    if (step.prompt.supportsDeviceApproval) {
                        Text(stringResource(R.string.login_approve_the_request_in_edupage_then_check_approval_here_41b93))
                        Button(onClick = onCheckApproval, enabled = !isLoading, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.login_check_approval_81ddf)) }
                        androidx.compose.material3.TextButton(onClick = onResend, enabled = !isLoading) { Text(stringResource(R.string.login_resend_approval_request_03ff9)) }
                    }
                }
                is com.bukovinafilip.gradey.domain.SchoolLoginStep.StudentSelection -> {
                    step.students.forEach { student ->
                        Button(onClick = { onSelectStudent(student.id) }, enabled = !isLoading, modifier = Modifier.fillMaxWidth()) { Text(listOfNotNull(student.fullName, student.className).joinToString(" · ")) }
                    }
                }
                else -> Unit
            }
            if (isLoading) androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth())
            errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            androidx.compose.material3.TextButton(onClick = onBack, enabled = !isLoading) { Text(stringResource(R.string.login_back_to_school_login_9573e)) }
        }
    }
}

/** Help remains usable while a school request is pending, without submitting the form. */
@Composable
private fun SchoolSignInHelp(label: String, onOpenHelp: () -> Unit) {
    val focusManager = LocalFocusManager.current
    androidx.compose.material3.TextButton(
        onClick = { focusManager.clearFocus(); onOpenHelp() }, modifier = Modifier.fillMaxWidth(),
    ) {
        HugeIcon(HugeIcons.HelpCircle, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
        Text(label)
    }
}
