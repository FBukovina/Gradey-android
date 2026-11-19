package com.bukovinafilip.gradey.feature.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bukovinafilip.gradey.ui.GradeyColors
import com.bukovinafilip.gradey.ui.GradeyIcon
import com.bukovinafilip.gradey.ui.GradeyMetrics
import com.bukovinafilip.gradey.ui.GradeyRadius
import com.bukovinafilip.gradey.ui.GradeySpacing
import com.bukovinafilip.gradey.ui.GradeyType
import com.bukovinafilip.gradey.ui.HugeIcons
import com.bukovinafilip.gradey.ui.InlineNotice
import com.bukovinafilip.gradey.ui.PlainLink
import com.bukovinafilip.gradey.ui.SettingsBackground
import com.bukovinafilip.gradey.ui.gradeyColors

/** Width of every onboarding column (iOS `OnboardingStepScaffold`, 560 pt). */
private val OnboardingMaxWidth = 560.dp

/** Side padding of onboarding steps (iOS 20 pt). */
private val OnboardingSidePadding = 20.dp

/**
 * The account step of onboarding (iOS 2.8.1 `OnboardingAccountStep` / `GradeyIDLoginView`): the Gradey ID
 * title with one line of explanation, Google and Apple sign-in buttons, and every other action as a
 * text link. A full-screen surface: it draws [SettingsBackground], keeps clear of the system bars and
 * scrolls itself, so the caller passes only a size modifier (for example `Modifier.fillMaxSize()`).
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
    OnboardingStep(
        title = stringResource(R.string.auth_gradey_id_f6019),
        message = stringResource(R.string.auth_keep_your_linked_school_accounts_and_school_information_togeth_d8e41),
        modifier = modifier.testTag("gradeyIdLogin"),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(GradeySpacing.md)) {
            GoogleSignInButton(
                stringResource(R.string.auth_continue_with_google_ccc5b), onGoogleSignIn,
                Modifier.testTag("continueWithGoogle"), enabled = !isLoading && isConfigured,
            )
            if (appleSignInAvailable && onAppleSignIn != null) {
                AppleSignInButton(stringResource(R.string.auth_continue_with_apple), onAppleSignIn, Modifier.testTag("continueWithApple"), enabled = !isLoading)
                Text(stringResource(R.string.auth_existing_apple_account), Modifier.padding(horizontal = GradeySpacing.xs), style = GradeyType.caption, color = secondary)
            }
            if (!isConfigured && !appleSignInAvailable && onContinueWithoutAccount == null) Text(
                stringResource(R.string.auth_this_build_only_supports_demo_data_to_connect_your_own_school__4f094),
                Modifier.padding(horizontal = GradeySpacing.xs), style = GradeyType.caption, color = secondary,
            )
        }
        if (isLoading) SigningIn()
        errorMessage?.let { InlineNotice(it) }
        Column {
            onContinueWithoutAccount?.let { continueLocally ->
                PlainLink(stringResource(R.string.auth_continue_without_id), continueLocally, Modifier.testTag("continueWithoutId"), enabled = !isLoading, fillWidth = true)
            }
            PlainLink(stringResource(R.string.auth_use_demo_data_7c3ca), onTestingBypass, Modifier.testTag("useDemoData"), enabled = !isLoading, fillWidth = true)
            onOpenHelp?.let { PlainLink(stringResource(R.string.auth_help_signing_in), it, fillWidth = true) }
            onOpenUpdates?.let { PlainLink(stringResource(R.string.auth_app_updates_16213), it, fillWidth = true) }
        }
    }
}

/**
 * One onboarding step (iOS `OnboardingStepScaffold`): the Settings background, a column capped at 560 dp
 * with 20 dp sides and 32 dp above and below, a display title over one callout line, then [content]
 * 24 dp apart. Handles the system bars and scrolls.
 */
@Composable
private fun OnboardingStep(title: String, message: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    SettingsBackground(modifier) {
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier.widthIn(max = OnboardingMaxWidth).fillMaxWidth().padding(horizontal = OnboardingSidePadding, vertical = GradeySpacing.xxl),
                verticalArrangement = Arrangement.spacedBy(GradeySpacing.xl),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(GradeySpacing.xs)) {
                    Text(title, Modifier.semantics { heading() }, style = GradeyType.display(36), color = MaterialTheme.colorScheme.onSurface)
                    Text(message, style = GradeyType.callout, color = MaterialTheme.gradeyColors.secondaryLabel)
                }
                content()
            }
        }
    }
}

/** Small spinner and "Signing in…" while an identity provider finishes (iOS `gradeyIDSigningIn`). */
@Composable
private fun SigningIn() {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(GradeySpacing.sm, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(Modifier.size(16.dp), color = MaterialTheme.colorScheme.primary, strokeWidth = 2.dp)
        Text(stringResource(R.string.auth_signing_in_3819f), style = GradeyType.footnote, color = MaterialTheme.gradeyColors.secondaryLabel)
    }
}

/** Sign in with Apple in Apple's black style: 50 dp high, radius 12 (iOS `GradelyAppleSignInButton`). */
@Composable
private fun AppleSignInButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val tokens = MaterialTheme.gradeyColors
    IdentityButton(
        text, HugeIcons.Apple, onClick, modifier, enabled,
        container = GradeyColors.Black, content = GradeyColors.White,
        // The black button keeps a hairline edge on the near-black dark background.
        border = if (tokens.isDark) tokens.separator else null,
    )
}

/** Continue with Google in the same shape as the Apple button, on the light/dark surface with a hairline edge. */
@Composable
private fun GoogleSignInButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val tokens = MaterialTheme.gradeyColors
    IdentityButton(
        text, HugeIcons.Google, onClick, modifier, enabled,
        container = tokens.secondaryGroupedBackground, content = MaterialTheme.colorScheme.onSurface, border = tokens.separator,
    )
}

@Composable
private fun IdentityButton(
    text: String,
    icon: String,
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    container: Color,
    content: Color,
    border: Color?,
) {
    val shape = RoundedCornerShape(GradeyRadius.sm)
    Row(
        modifier.fillMaxWidth().heightIn(min = GradeyMetrics.buttonHeight).alpha(if (enabled) 1f else 0.45f)
            .clip(shape).background(container, shape).then(if (border != null) Modifier.border(1.dp, border, shape) else Modifier)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = GradeySpacing.lg, vertical = GradeySpacing.md),
        horizontalArrangement = Arrangement.spacedBy(GradeySpacing.sm, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically,
    ) {
        GradeyIcon(icon, size = 18.dp, tint = content)
        Text(text, style = GradeyType.headline, color = content, textAlign = TextAlign.Center)
    }
}
