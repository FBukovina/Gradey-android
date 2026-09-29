package com.bukovinafilip.gradey

import androidx.compose.ui.res.stringResource
import com.bukovinafilip.gradey.R
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.bukovinafilip.gradey.model.LinkedSchoolAccount

@Composable
internal fun SchoolAccountSwitcher(
    state: GradeyState,
    onActivate: (String) -> Unit,
    onReconnect: (LinkedSchoolAccount) -> Unit,
) {
    if (state.linkedAccounts.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    val selected = state.linkedAccounts.find { it.id == state.school?.linkedAccountID }
    Box {
        Surface(onClick = { expanded = true }, enabled = !state.loading, shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainer) {
            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Default.School, null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f)) {
                    Text(selected?.displayName ?: stringResource(R.string.app_choose_school), style = MaterialTheme.typography.titleSmall)
                    Text(selected?.schoolName ?: selected?.provider?.displayName ?: stringResource(R.string.app_school_connected), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.Default.ExpandMore, stringResource(R.string.app_switch_school))
            }
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            state.linkedAccounts.forEach { account ->
                DropdownMenuItem(
                    text = { Column {
                        Text(account.displayName)
                        Text(if (account.status == "active") account.schoolName ?: account.provider.displayName else stringResource(R.string.app_reconnect_school), style = MaterialTheme.typography.bodySmall)
                    } },
                    leadingIcon = { Icon(if (account.id == selected?.id) Icons.Default.CheckCircle else Icons.Default.School, null) },
                    onClick = { expanded = false; if (account.status == "active") onActivate(account.id) else onReconnect(account) },
                    enabled = !state.loading,
                )
            }
        }
    }
}
