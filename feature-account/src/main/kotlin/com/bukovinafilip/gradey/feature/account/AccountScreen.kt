package com.bukovinafilip.gradey.feature.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.bukovinafilip.gradey.model.GradeyAccount
import com.bukovinafilip.gradey.model.LinkedSchoolAccount
import com.bukovinafilip.gradey.model.NotificationPreferences
import com.bukovinafilip.gradey.model.SchoolStudentProfile
import com.bukovinafilip.gradey.ui.GradeyHero
import com.bukovinafilip.gradey.ui.GradeySectionCard
import com.bukovinafilip.gradey.ui.GradeySpacing

@Composable
fun AccountScreen(
    account: GradeyAccount?,
    linkedAccounts: List<LinkedSchoolAccount>,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
    preferences: NotificationPreferences = NotificationPreferences.Default,
    onPreferencesChange: (NotificationPreferences) -> Unit = {},
    activeAccountID: String? = null,
    onActivateAccount: (LinkedSchoolAccount) -> Unit = {},
    onUnlinkAccount: (LinkedSchoolAccount) -> Unit = {},
    onAddAccount: () -> Unit = {},
    isLoading: Boolean = false,
    errorMessage: String? = null,
    onRetry: () -> Unit = {},
    isDemo: Boolean = false,
    onNotificationSettings: (() -> Unit)? = null,
    versionName: String? = null,
    onExportData: (() -> Unit)? = null,
    onDeleteAccount: (() -> Unit)? = null,
    students: List<SchoolStudentProfile> = emptyList(),
    activeStudentID: String? = null,
    onSelectStudent: (String) -> Unit = {},
    onOpenUpdates: (() -> Unit)? = null,
) {
    var removing by remember { mutableStateOf<LinkedSchoolAccount?>(null) }
    var editingQuietHours by remember { mutableStateOf(false) }
    var confirmingSignOut by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(GradeySpacing.lg),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(GradeySpacing.lg),
    ) {
        item { GradeyHero("Account", account?.fullName?.takeIf { it.isNotBlank() } ?: "Your Gradey") }
        if (isDemo) item {
            GradeySectionCard {
                Text("Demo mode", style = MaterialTheme.typography.titleMedium)
                Text("Explore Gradey with sample data. Sign out to connect your own school.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (isLoading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        errorMessage?.let { message -> item {
            GradeySectionCard {
                Text(message, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onRetry, enabled = !isLoading) { Text("Try again") }
            }
        } }
        item {
            GradeySectionCard(title = "Profile") {
                Text(account?.fullName ?: "Gradey account", style = MaterialTheme.typography.titleLarge)
                Text(account?.email ?: "Signed in to Gradey", color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = { confirmingSignOut = true }, enabled = !isLoading) { Text("Sign out") }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("School accounts", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                TextButton(onClick = onAddAccount, enabled = !isLoading) { Text("Add school") }
            }
        }
        if (linkedAccounts.isEmpty()) item {
            GradeySectionCard {
                Text("No linked school accounts yet", style = MaterialTheme.typography.titleMedium)
                Text("Connect your school to keep your grades, timetable and attendance together.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = onAddAccount, enabled = !isLoading) { Text("Connect school") }
            }
        }
        items(linkedAccounts, key = { it.id }) { linked ->
            val active = linked.id == activeAccountID
            GradeySectionCard {
                Text(linked.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(listOfNotNull(linked.provider.displayName, linked.schoolName).joinToString(" · "), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    when {
                        linked.status != "active" -> "Reconnect this school to continue syncing."
                        active -> "Current school account"
                        else -> "Ready to switch"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (linked.status != "active") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
                if (!linked.notificationsEnabled) Text("Cloud mark alerts are disabled for this account.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(GradeySpacing.sm)) {
                    if (!active) OutlinedButton(onClick = { onActivateAccount(linked) }, enabled = !isLoading && linked.status == "active") { Text("Switch account") }
                    TextButton(onClick = { removing = linked }, enabled = !isLoading) { Text("Remove") }
                }
            }
        }
        if (students.size > 1) item {
            GradeySectionCard(title = "Student profiles") {
                Text("Choose whose grades and timetable to show.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Background mark alerts and cloud grade history are unavailable for parent profiles. Open Gradey to refresh the selected student’s grades.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                students.forEach { student ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(GradeySpacing.md), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(student.fullName, style = MaterialTheme.typography.titleMedium)
                            student.className?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                        if (student.id == activeStudentID) Text("Current", color = MaterialTheme.colorScheme.primary)
                        else TextButton(onClick = { onSelectStudent(student.id) }, enabled = !isLoading) { Text("Select") }
                    }
                }
            }
        }
        item {
            GradeySectionCard(title = "Notifications") {
                PreferenceSwitch("New marks", "Receive an update when a new mark is available.", preferences.newMarksEnabled, !isLoading) {
                    onPreferencesChange(preferences.copy(newMarksEnabled = it))
                }
                HorizontalDivider()
                PreferenceSwitch("Quiet hours", "Pause mark alerts during your chosen hours.", preferences.quietHoursEnabled, !isLoading) {
                    onPreferencesChange(preferences.copy(quietHoursEnabled = it))
                }
                if (preferences.quietHoursEnabled) {
                    TextButton(onClick = { editingQuietHours = true }, enabled = !isLoading) { Text("${preferences.quietHoursStart} – ${preferences.quietHoursEnd} · Edit hours") }
                    Text("Quiet hours use Prague time (CET/CEST).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                onNotificationSettings?.let { action -> TextButton(onClick = action) { Text("Android notification settings") } }
            }
        }
        if (onExportData != null || onDeleteAccount != null) item {
            GradeySectionCard(title = "Your data") {
                Text("Download your Gradey data or permanently delete your account.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                onExportData?.let { action -> OutlinedButton(onClick = action, enabled = !isLoading) { Text("Export account data") } }
                onDeleteAccount?.let {
                    TextButton(onClick = { confirmingDelete = true }, enabled = !isLoading) {
                        Text("Delete Gradey account", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
        onOpenUpdates?.let { action -> item {
            GradeySectionCard(title = "App updates") {
                Text("Get the latest improvements and manage automatic downloads.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = action) { Text("Open app updates") }
            }
        } }
        versionName?.let { version -> item {
            Text("Gradey $version", modifier = Modifier.padding(vertical = GradeySpacing.sm), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        } }
    }
    removing?.let { linked ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove school account?") },
            text = { Text("${linked.displayName} will be unlinked from Gradey. You can connect it again later.") },
            confirmButton = { TextButton(onClick = { removing = null; onUnlinkAccount(linked) }) { Text("Remove account") } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Keep account") } },
        )
    }
    if (confirmingSignOut) AlertDialog(
        onDismissRequest = { confirmingSignOut = false },
        title = { Text("Sign out of Gradey?") },
        text = { Text("Saved school sessions will be removed from this device. Your linked accounts will stay in Gradey.") },
        confirmButton = { TextButton(onClick = { confirmingSignOut = false; onSignOut() }) { Text("Sign out") } },
        dismissButton = { TextButton(onClick = { confirmingSignOut = false }) { Text("Cancel") } },
    )
    if (confirmingDelete) AlertDialog(
        onDismissRequest = { confirmingDelete = false },
        title = { Text("Permanently delete your account?") },
        text = { Text("Your Gradey profile, linked accounts, saved grade history and notification settings will be deleted. Your school account itself will stay with your school. This cannot be undone.") },
        confirmButton = { TextButton(onClick = { confirmingDelete = false; onDeleteAccount?.invoke() }) { Text("Delete permanently", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text("Keep account") } },
    )
    if (editingQuietHours) QuietHoursDialog(
        preferences = preferences,
        onDismiss = { editingQuietHours = false },
        onSave = { editingQuietHours = false; onPreferencesChange(it) },
    )
}

@Composable
private fun PreferenceSwitch(title: String, description: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(GradeySpacing.md), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(GradeySpacing.xs)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun QuietHoursDialog(preferences: NotificationPreferences, onDismiss: () -> Unit, onSave: (NotificationPreferences) -> Unit) {
    var start by remember { mutableStateOf(preferences.quietHoursStart) }
    var end by remember { mutableStateOf(preferences.quietHoursEnd) }
    val timePattern = remember { Regex("(?:[01]\\d|2[0-3]):[0-5]\\d") }
    val valid = timePattern.matches(start) && timePattern.matches(end) && start != end
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Quiet hours") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(GradeySpacing.md)) {
                Text("Use 24-hour Prague times, for example 22:00 and 06:00. Overnight ranges are supported.")
                OutlinedTextField(value = start, onValueChange = { start = it }, label = { Text("Start (HH:mm)") }, singleLine = true, isError = !timePattern.matches(start))
                OutlinedTextField(value = end, onValueChange = { end = it }, label = { Text("End (HH:mm)") }, singleLine = true, isError = !timePattern.matches(end) || start == end)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(preferences.copy(quietHoursStart = start, quietHoursEnd = end)) }, enabled = valid) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
