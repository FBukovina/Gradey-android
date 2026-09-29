package com.bukovinafilip.gradey

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.bukovinafilip.gradey.domain.SchoolLoginStep
import com.bukovinafilip.gradey.feature.auth.GradeyIdLoginScreen
import com.bukovinafilip.gradey.feature.login.SchoolLoginScreen
import com.bukovinafilip.gradey.feature.login.SchoolVerificationScreen
import com.bukovinafilip.gradey.feature.support.SupportHelpDialog
import com.bukovinafilip.gradey.ui.GradeyTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import com.bukovinafilip.gradey.feature.auth.R as AuthR
import com.bukovinafilip.gradey.feature.login.R as LoginR
import com.bukovinafilip.gradey.feature.support.R as SupportR

/** Exercises only local callbacks. Native contact is disabled and the browser callback is a counter. */
class PreLoginHelpTest {
    @get:Rule val compose = createComposeRule()
    private fun text(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    @Test fun allSignInStagesOfferHelpWithoutSubmittingOrStartingContact() {
        var stage by mutableStateOf(0)
        var helpVisible by mutableStateOf(false)
        var browserRequests = 0
        var signInSubmissions = 0
        val labels = listOf(AuthR.string.auth_help_signing_in, LoginR.string.login_help_signing_in, LoginR.string.login_help_verification)
        compose.setContent {
            GradeyTheme {
                val modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                when (stage) {
                    0 -> GradeyIdLoginScreen(
                        isLoading = true, isConfigured = false, onGoogleSignIn = { signInSubmissions++ },
                        onTestingBypass = { signInSubmissions++ }, onOpenHelp = { helpVisible = true }, modifier = modifier,
                    )
                    1 -> SchoolLoginScreen(
                        isLoading = true, onLogin = { _, _, _, _ -> signInSubmissions++ },
                        onOpenHelp = { helpVisible = true }, modifier = modifier,
                    )
                    else -> SchoolVerificationScreen(
                        step = SchoolLoginStep.TwoFactor(), isLoading = true, errorMessage = null,
                        onSubmitCode = { signInSubmissions++ }, onCheckApproval = { signInSubmissions++ },
                        onResend = { signInSubmissions++ }, onSelectStudent = { signInSubmissions++ }, onBack = {},
                        onOpenHelp = { helpVisible = true }, modifier = modifier,
                    )
                }
                if (helpVisible) SupportHelpDialog(
                    onDismiss = { helpVisible = false }, onOpenHelpCenter = { browserRequests++ },
                    allowed = false, isDemo = stage == 0, sessionKey = "local-sign-in-fixture-$stage",
                )
            }
        }
        labels.forEachIndexed { index, label ->
            compose.runOnIdle { stage = index }
            compose.onNodeWithText(text(label)).performScrollTo().assertIsEnabled().performClick()
            compose.onNodeWithText(text(SupportR.string.support_help_start)).assertDoesNotExist()
            compose.runOnIdle { assertEquals(index, browserRequests); assertEquals(0, signInSubmissions) }
            compose.onNodeWithText(text(SupportR.string.support_help_center)).performClick()
            compose.runOnIdle { assertEquals(index + 1, browserRequests); assertEquals(0, signInSubmissions) }
        }
    }
}
