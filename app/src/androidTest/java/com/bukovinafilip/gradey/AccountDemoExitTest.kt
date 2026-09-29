package com.bukovinafilip.gradey

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bukovinafilip.gradey.feature.account.AccountScreen
import com.bukovinafilip.gradey.ui.GradeyTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the demo exit with explicit configuration flags and no authentication. */
@RunWith(AndroidJUnit4::class)
class AccountDemoExitTest {
    @get:Rule val compose = createComposeRule()

    @Test fun configuredDemoOffersExitToSignInWithoutEnablingSchoolEditing() {
        var signOutCalls = 0
        compose.setContent {
            GradeyTheme {
                AccountScreen(
                    account = null, linkedAccounts = emptyList(), onSignOut = { signOutCalls++ },
                    isDemo = true, liveSignInAvailable = true,
                )
            }
        }

        compose.onNodeWithText("Connect my school").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, signOutCalls) }
        compose.onNodeWithText("Add school").assertIsNotEnabled()
        compose.onAllNodesWithText("School URL").assertCountEquals(0)
    }

    @Test fun unconfiguredDemoExplainsLimitationWithoutOfferingLiveSignIn() {
        compose.setContent {
            GradeyTheme {
                AccountScreen(
                    account = null, linkedAccounts = emptyList(), onSignOut = {},
                    isDemo = true, liveSignInAvailable = false,
                )
            }
        }

        compose.onNodeWithText("Explore Gradey with sample data. Real school accounts cannot be connected or changed in demo mode.").assertIsDisplayed()
        compose.onAllNodesWithText("Connect my school").assertCountEquals(0)
        compose.onNodeWithText("Add school").assertIsNotEnabled()
    }
}
