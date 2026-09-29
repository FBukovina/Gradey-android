package com.bukovinafilip.gradey

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.bukovinafilip.gradey.ui.GradeyTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class WelcomeFlowTest {
    @get:Rule val compose = createComposeRule()

    @Test fun parentConsentAndPolicyAcceptanceAreBothRequiredForUnderThirteen() {
        var accepted: String? = null
        compose.setContent { GradeyTheme { WelcomeScreen { accepted = it } } }
        compose.onNodeWithText("I’m under 13").performScrollTo().performClick()
        compose.onNodeWithText("Continue to Gradey").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("I have read and agree to the Privacy Policy and Terms of Use.").performScrollTo().performClick()
        compose.onNodeWithText("Continue to Gradey").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("My parent or guardian has reviewed this information and agrees to my use of Gradey.").performScrollTo().performClick()
        compose.onNodeWithText("Continue to Gradey").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals("underThirteen", accepted) }
    }

    @Test fun changingAgeClearsPriorConsent() {
        compose.setContent { GradeyTheme { WelcomeScreen {} } }
        compose.onNodeWithText("I’m 16 or older").performScrollTo().performClick()
        compose.onNodeWithText("I have read and agree to the Privacy Policy and Terms of Use.").performScrollTo().performClick()
        compose.onNodeWithText("Continue to Gradey").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("Change age group").performScrollTo().performClick()
        compose.onNodeWithText("I’m 13–15").performScrollTo().performClick()
        compose.onNodeWithText("Continue to Gradey").performScrollTo().assertIsNotEnabled()
    }
}
