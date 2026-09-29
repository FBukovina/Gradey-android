package com.bukovinafilip.gradey

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.bukovinafilip.gradey.ui.GradeyTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PrivacyUpdateFlowTest {
    @get:Rule val compose = createComposeRule()

    @Test fun policyUpdateRequiresReadingWithoutRepeatingAgeConfirmation() {
        var accepted = false
        compose.setContent { GradeyTheme { PrivacyUpdateScreen(onAccept = { accepted = true }) } }
        compose.onNodeWithText("I’m 16 or older").assertDoesNotExist()
        compose.onNodeWithText("Done").assertDoesNotExist()
        compose.onNodeWithText("I’ve read this — accept").assertIsNotEnabled()
        compose.onNodeWithText("Read the full Privacy Policy").performScrollTo()
        compose.onNodeWithText("I’ve read this — accept").assertIsEnabled().performClick()
        compose.runOnIdle { assertTrue(accepted) }
    }

    @Test fun settingsReviewClosesWithoutAcknowledgingPolicy() {
        var accepted = false
        var closed = false
        compose.setContent { GradeyTheme { PrivacyUpdateScreen(onAccept = { accepted = true }, onClose = { closed = true }) } }
        compose.onNodeWithText("I’ve read this — accept").assertDoesNotExist()
        compose.onNodeWithText("Background checks use encrypted session tokens").performScrollTo().performClick()
        compose.onNodeWithText("When you link a Bakaláři account to Gradey ID, the app obtains a separate school session.", substring = true).assertExists()
        compose.onNodeWithText("Done").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(closed); assertFalse(accepted) }
    }
}
