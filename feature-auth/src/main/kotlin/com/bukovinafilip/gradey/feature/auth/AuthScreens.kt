package com.bukovinafilip.gradey.feature.auth

import com.bukovinafilip.gradey.ui.HugeIcons
import com.bukovinafilip.gradey.ui.HugeIcon

import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.bukovinafilip.gradey.ui.GradeyHero
import com.bukovinafilip.gradey.ui.GradeyScreen
import com.bukovinafilip.gradey.ui.GradeySectionCard
import com.bukovinafilip.gradey.ui.GradeySpacing

@Composable
fun GradeyIdLoginScreen(
    isLoading: Boolean,
    onGoogleSignIn: () -> Unit,
    onTestingBypass: () -> Unit,
    modifier: Modifier = Modifier,
    isConfigured: Boolean = true,
    errorMessage: String? = null,
    onOpenUpdates: (() -> Unit)? = null,
    appleSignInAvailable: Boolean = false,
    onAppleSignIn: (() -> Unit)? = null,
    onContinueWithoutAccount: (() -> Unit)? = null,
    onOpenHelp: (() -> Unit)? = null,
) {
    GradeyScreen(modifier = modifier) {
        GradeyHero(
            title = stringResource(R.string.auth_gradey_id_f6019),
            subtitle = stringResource(R.string.auth_keep_your_linked_school_accounts_and_school_information_togeth_d8e41),
        )

        onOpenHelp?.let { action ->
            androidx.compose.material3.TextButton(onClick = action, modifier = Modifier.fillMaxWidth()) {
                HugeIcon(HugeIcons.HelpCircle, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                Text(stringResource(R.string.auth_help_signing_in))
            }
        }
        GradeySectionCard(title = stringResource(R.string.auth_sign_in_ada2e)) {
            Text(
                if (appleSignInAvailable) stringResource(R.string.auth_choose_identity_provider)
                else stringResource(R.string.auth_use_your_google_account_to_create_or_open_a_gradey_id_school_p_84ea1),
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading && isConfigured,
                onClick = onGoogleSignIn,
            ) {
                HugeIcon(HugeIcons.Google, contentDescription = null, modifier = Modifier.padding(end = 8.dp).size(18.dp))
                Text(if (isLoading) stringResource(R.string.auth_signing_in_3819f) else stringResource(R.string.auth_continue_with_google_ccc5b))
            }
            if (appleSignInAvailable && onAppleSignIn != null) {
                androidx.compose.material3.OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isLoading,
                    onClick = onAppleSignIn,
                ) {
                    HugeIcon(HugeIcons.Apple, contentDescription = null, modifier = Modifier.padding(end = 8.dp).size(18.dp))
                    Text(stringResource(R.string.auth_continue_with_apple))
                }
                Text(stringResource(R.string.auth_existing_apple_account), style = MaterialTheme.typography.bodySmall)
            }
            if (!isConfigured && !appleSignInAvailable && onContinueWithoutAccount == null) Text(stringResource(R.string.auth_this_build_only_supports_demo_data_to_connect_your_own_school__4f094), style = MaterialTheme.typography.bodySmall)
            onContinueWithoutAccount?.let { continueLocally ->
                androidx.compose.material3.OutlinedButton(
                    modifier = Modifier.fillMaxWidth(), enabled = !isLoading, onClick = continueLocally,
                ) { Text(stringResource(R.string.auth_continue_without_id)) }
                Text(stringResource(R.string.auth_local_school_description), style = MaterialTheme.typography.bodySmall)
            }
            errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading,
                onClick = onTestingBypass,
            ) {
                Text(stringResource(R.string.auth_use_demo_data_7c3ca))
            }
        }

        GradeySectionCard {
            Column(verticalArrangement = Arrangement.spacedBy(GradeySpacing.sm)) {
                HugeIcon(HugeIcons.SquareLock02, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.auth_private_by_default_282ef), fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.auth_gradey_uses_encrypted_local_storage_and_only_registers_push_to_0d945))
            }
        }
        onOpenUpdates?.let { action ->
            androidx.compose.material3.TextButton(onClick = action, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.auth_app_updates_16213)) }
        }
    }
}
