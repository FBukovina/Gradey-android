package com.bukovinafilip.gradey.feature.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.bukovinafilip.gradey.ui.GradeyIcon
import com.bukovinafilip.gradey.ui.GradeyPrimaryButton
import com.bukovinafilip.gradey.ui.GradeySpacing
import com.bukovinafilip.gradey.ui.GradeyTonalButton
import com.bukovinafilip.gradey.ui.GradeyType
import com.bukovinafilip.gradey.ui.Hairline
import com.bukovinafilip.gradey.ui.HugeIcons
import com.bukovinafilip.gradey.ui.InlineNotice
import com.bukovinafilip.gradey.ui.PlainLink
import com.bukovinafilip.gradey.ui.gradeyColors

/**
 * The account step of onboarding (iOS 2.8.1 `OnboardingView`, account step): sign in with Google or
 * Apple, or continue without a Gradey ID. No icons on the step, one line per explanation, and every
 * secondary action is a text link. A full-screen surface that handles the system bars itself.
 */
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
    val secondary = MaterialTheme.gradeyColors.secondaryLabel
    Box(modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 520.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = GradeySpacing.xl)
                .testTag("gradeyIdLogin"),
            verticalArrangement = Arrangement.spacedBy(GradeySpacing.lg),
        ) {
            Column(Modifier.padding(top = GradeySpacing.xl), verticalArrangement = Arrangement.spacedBy(GradeySpacing.sm)) {
                Text(stringResource(R.string.auth_gradey_id_f6019), Modifier.semantics { heading() }, style = GradeyType.display(36), color = MaterialTheme.colorScheme.onSurface)
                Text(
                    stringResource(R.string.auth_keep_your_linked_school_accounts_and_school_information_togeth_d8e41),
                    style = GradeyType.callout, color = secondary,
                )
            }
            Column(Modifier.padding(top = GradeySpacing.sm), verticalArrangement = Arrangement.spacedBy(GradeySpacing.md)) {
                GradeyPrimaryButton(
                    stringResource(R.string.auth_continue_with_google_ccc5b), onGoogleSignIn, Modifier.testTag("continueWithGoogle"),
                    enabled = !isLoading && isConfigured, loading = isLoading, icon = HugeIcons.Google,
                )
                if (appleSignInAvailable && onAppleSignIn != null) {
                    GradeyTonalButton(
                        stringResource(R.string.auth_continue_with_apple), onAppleSignIn, Modifier.fillMaxWidth().testTag("continueWithApple"),
                        enabled = !isLoading, icon = HugeIcons.Apple,
                    )
                    Text(stringResource(R.string.auth_existing_apple_account), Modifier.padding(horizontal = GradeySpacing.xs), style = GradeyType.caption, color = secondary)
                }
                if (!isConfigured && !appleSignInAvailable && onContinueWithoutAccount == null) Text(
                    stringResource(R.string.auth_this_build_only_supports_demo_data_to_connect_your_own_school__4f094),
                    Modifier.padding(horizontal = GradeySpacing.xs), style = GradeyType.caption, color = secondary,
                )
                errorMessage?.let { InlineNotice(it) }
            }
            Column {
                onContinueWithoutAccount?.let { continueLocally ->
                    Hairline()
                    Column(Modifier.padding(vertical = GradeySpacing.sm)) {
                        PlainLink(stringResource(R.string.auth_continue_without_id), continueLocally, Modifier.testTag("continueWithoutId"), enabled = !isLoading)
                        Text(stringResource(R.string.auth_local_school_description), Modifier.padding(horizontal = GradeySpacing.xs), style = GradeyType.caption, color = secondary)
                    }
                }
                Hairline()
                PlainLink(stringResource(R.string.auth_use_demo_data_7c3ca), onTestingBypass, Modifier.padding(vertical = GradeySpacing.xs).testTag("useDemoData"), enabled = !isLoading)
                onOpenHelp?.let { action ->
                    Hairline()
                    PlainLink(stringResource(R.string.auth_help_signing_in), action, Modifier.padding(vertical = GradeySpacing.xs))
                }
                onOpenUpdates?.let { action ->
                    Hairline()
                    PlainLink(stringResource(R.string.auth_app_updates_16213), action, Modifier.padding(vertical = GradeySpacing.xs))
                }
            }
            Row(Modifier.padding(horizontal = GradeySpacing.xs), horizontalArrangement = Arrangement.spacedBy(GradeySpacing.sm)) {
                GradeyIcon(HugeIcons.SquareLock02, Modifier.padding(top = 1.dp), size = 14.dp, tint = MaterialTheme.colorScheme.primary)
                Text(
                    stringResource(R.string.auth_gradey_uses_encrypted_local_storage_and_only_registers_push_to_0d945),
                    Modifier.weight(1f), style = GradeyType.footnote, color = secondary,
                )
            }
        }
    }
}
