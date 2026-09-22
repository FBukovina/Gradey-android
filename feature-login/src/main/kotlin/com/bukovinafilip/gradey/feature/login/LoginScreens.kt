package com.bukovinafilip.gradey.feature.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
) {
    var provider by remember { mutableStateOf(SchoolProvider.BAKALARI) }
    var school by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    GradeyScreen(modifier = modifier) {
        GradeyHero(
            title = "Link school",
            subtitle = "Connect your school to see marks, absences, and your timetable, even when you are offline.",
        )
        GradeySectionCard(title = "Provider") {
            Row(horizontalArrangement = Arrangement.spacedBy(GradeySpacing.sm)) {
                availableProviders.forEach { candidate ->
                    FilterChip(
                        selected = provider == candidate,
                        onClick = { provider = candidate },
                        enabled = !isLoading,
                        label = { Text(candidate.displayName) },
                    )
                }
            }
        }
        GradeySectionCard(title = "Credentials") {
            Column(verticalArrangement = Arrangement.spacedBy(GradeySpacing.md)) {
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = school,
                    onValueChange = { school = it },
                    label = { Text(if (provider == SchoolProvider.EDU_PAGE) "EduPage school" else "School URL") },
                    singleLine = true,
                    enabled = !isLoading,
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Username") },
                    singleLine = true,
                    enabled = !isLoading,
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                    enabled = !isLoading,
                )
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isLoading && school.isNotBlank() && username.isNotBlank() && password.isNotBlank(),
                    onClick = { onLogin(provider, school, username, password) },
                ) {
                    Icon(Icons.Default.Link, contentDescription = null)
                    Text(if (isLoading) "Connecting" else "Connect school")
                }
                errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (onBack != null) androidx.compose.material3.TextButton(onClick = onBack, enabled = !isLoading) { Text("Back") }
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
) {
    var code by remember { mutableStateOf("") }
    GradeyScreen(modifier = modifier) {
        GradeyHero("Connect EduPage", if (step is com.bukovinafilip.gradey.domain.SchoolLoginStep.TwoFactor) "Verify your sign-in" else "Choose a student")
        GradeySectionCard {
            when (step) {
                is com.bukovinafilip.gradey.domain.SchoolLoginStep.TwoFactor -> {
                    Text("Complete verification using your EduPage account.")
                    if (step.prompt.supportsCode) {
                        OutlinedTextField(value = code, onValueChange = { code = it.trim() }, label = { Text("Verification code") }, singleLine = true, enabled = !isLoading, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
                        Button(onClick = { onSubmitCode(code) }, enabled = !isLoading && code.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Verify code") }
                    }
                    if (step.prompt.supportsDeviceApproval) {
                        Text("Approve the request in EduPage, then check approval here.")
                        Button(onClick = onCheckApproval, enabled = !isLoading, modifier = Modifier.fillMaxWidth()) { Text("Check approval") }
                        androidx.compose.material3.TextButton(onClick = onResend, enabled = !isLoading) { Text("Resend approval request") }
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
            androidx.compose.material3.TextButton(onClick = onBack, enabled = !isLoading) { Text("Back to school login") }
        }
    }
}
