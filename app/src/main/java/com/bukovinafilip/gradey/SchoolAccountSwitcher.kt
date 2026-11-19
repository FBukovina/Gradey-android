package com.bukovinafilip.gradey

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.bukovinafilip.gradey.model.LinkedSchoolAccount
import com.bukovinafilip.gradey.ui.GradeyIcon
import com.bukovinafilip.gradey.ui.GradeyMetrics
import com.bukovinafilip.gradey.ui.GradeyRadius
import com.bukovinafilip.gradey.ui.GradeySpacing
import com.bukovinafilip.gradey.ui.GradeyType
import com.bukovinafilip.gradey.ui.HugeIcons
import com.bukovinafilip.gradey.ui.gradeyColors

/** Picker for an already linked school on the school sign-in step: the current account and a menu of the others. */
@Composable
internal fun SchoolAccountSwitcher(
    state: GradeyState,
    onActivate: (String) -> Unit,
    onReconnect: (LinkedSchoolAccount) -> Unit,
) {
    if (state.linkedAccounts.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    val selected = state.linkedAccounts.find { it.id == state.school?.linkedAccountID }
    val tokens = MaterialTheme.gradeyColors
    val switchLabel = stringResource(R.string.app_switch_school)
    Box {
        Row(
            Modifier.fillMaxWidth().heightIn(min = GradeyMetrics.fieldHeight).clip(RoundedCornerShape(GradeyRadius.sm)).background(tokens.tertiaryFill)
                .clickable(enabled = !state.loading, role = Role.DropdownList, onClickLabel = switchLabel) { expanded = true }
                .padding(horizontal = GradeySpacing.md, vertical = GradeySpacing.sm),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.md),
        ) {
            GradeyIcon(HugeIcons.University, size = 18.dp, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(selected?.displayName ?: stringResource(R.string.app_choose_school), style = GradeyType.subheadlineBold, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    selected?.schoolName ?: selected?.provider?.displayName ?: stringResource(R.string.app_school_connected),
                    style = GradeyType.caption, color = tokens.secondaryLabel,
                )
            }
            GradeyIcon(HugeIcons.ArrowDown01, size = 13.dp, tint = tokens.secondaryLabel)
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }, containerColor = tokens.secondaryGroupedBackground) {
            state.linkedAccounts.forEach { account ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(account.displayName, style = GradeyType.body, color = MaterialTheme.colorScheme.onSurface)
                            Text(
                                if (account.status == "active") account.schoolName ?: account.provider.displayName else stringResource(R.string.app_reconnect_school),
                                style = GradeyType.caption, color = tokens.secondaryLabel,
                            )
                        }
                    },
                    leadingIcon = {
                        GradeyIcon(
                            if (account.id == selected?.id) HugeIcons.CheckmarkCircle02 else HugeIcons.University, size = 18.dp,
                            tint = if (account.id == selected?.id) MaterialTheme.colorScheme.primary else tokens.secondaryLabel,
                        )
                    },
                    onClick = { expanded = false; if (account.status == "active") onActivate(account.id) else onReconnect(account) },
                    enabled = !state.loading,
                )
            }
        }
    }
}
