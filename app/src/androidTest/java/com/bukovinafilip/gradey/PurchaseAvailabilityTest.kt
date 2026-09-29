package com.bukovinafilip.gradey

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.bukovinafilip.gradey.ui.GradeyTheme
import com.revenuecat.purchases.Purchases
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** The upgraded real SDK stays uninitialized when purchases are unavailable. No billing call. */
class PurchaseAvailabilityTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun demoSignedOutAndUnconfiguredStatesDoNotOfferPurchasesOrRestore() {
        assumeTrue(BuildConfig.REVENUECAT_ANDROID_KEY.isBlank())
        var stage by mutableStateOf(0)
        compose.setContent {
            GradeyTheme {
                SupportScreen(accountID = if (stage == 1) null else "local-purchase-fixture", isDemo = stage == 0)
            }
        }
        val explanations = listOf(R.string.app_support_demo, R.string.app_support_signin, R.string.app_support_unconfigured)
        explanations.forEachIndexed { index, explanation ->
            compose.runOnIdle { stage = index }
            compose.onNodeWithText(compose.activity.getString(explanation)).assertIsDisplayed()
            listOf(R.string.app_continue_play, R.string.app_tip_play, R.string.app_restore).forEach { action ->
                compose.onNodeWithText(compose.activity.getString(action)).assertDoesNotExist()
            }
            compose.runOnIdle { assertFalse(Purchases.isConfigured) }
        }
    }
}
