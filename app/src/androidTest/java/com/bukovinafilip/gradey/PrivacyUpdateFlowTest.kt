package com.bukovinafilip.gradey

import androidx.compose.ui.test.*
import androidx.compose.foundation.layout.height
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.bukovinafilip.gradey.ui.GradeyTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PrivacyUpdateFlowTest {
    @get:Rule val compose = createComposeRule()

    @Test fun policyUpdateRequiresReadingWithoutRepeatingAgeConfirmation() {
        var accepted = false
        // A short screen, so the summary cannot be seen whole: on a tall phone revision 3's three entries
        // fit without scrolling, and what is already in view counts as read.
        compose.setContent {
            GradeyTheme { androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.height(420.dp)) { PrivacyUpdateScreen(onAccept = { accepted = true }) } }
        }
        compose.onNodeWithText("I’m 16 or older").assertDoesNotExist()
        compose.onNodeWithContentDescription("Done").assertDoesNotExist()
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
        // A row opens the full explanation behind its one-line summary.
        compose.onNodeWithTag("privacyChange-messages").performScrollTo().performClick()
        compose.onNodeWithText("Your device loads Komens messages, noticeboard posts and their attachments", substring = true).assertExists()
        compose.onNodeWithText("Effective November 5, 2026").assertExists()
        compose.onNodeWithContentDescription("Done").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(closed); assertFalse(accepted) }
    }
}
