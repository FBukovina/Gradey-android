package com.bukovinafilip.gradey.feature.support

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** No real SDK initializer, credentials, login, contact or network operation is used. */
class SupportHelpDialogTest {
    @get:Rule val rule = createComposeRule()
    private fun text(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private class FakeClient : AnonymousSupportClient {
        var logins = 0
        var presentations = 0
        var callback: ((Boolean) -> Unit)? = null
        override fun hideAndLogout() = Unit
        override fun login(onComplete: (Boolean) -> Unit) { logins++; callback = onComplete }
        override fun present() { presentations++ }
    }

    @Test fun viewingHelpDoesNotLoginAndDismissalRevokesSuspendedLogin() {
        val sdk = FakeClient()
        val messenger = SupportMessenger(sdk)
        var visible by mutableStateOf(true)
        rule.setContent {
            MaterialTheme {
                if (visible) SupportHelpDialog(
                    onDismiss = { visible = false }, onOpenHelpCenter = {},
                    allowed = true, isDemo = false, sessionKey = "local-only", messenger = messenger,
                )
            }
        }
        rule.runOnIdle { assertEquals(0, sdk.logins) }
        rule.onNodeWithText(text(R.string.support_help_start)).performClick()
        rule.runOnIdle { assertEquals(1, sdk.logins) }
        rule.onNodeWithText(text(R.string.support_help_close)).performClick()
        rule.runOnIdle { requireNotNull(sdk.callback).invoke(true); assertEquals(0, sdk.presentations) }
    }

    @Test fun demoAndAgeRestrictionKeepHelpCenterAvailableWithoutChatLogin() {
        val sdk = FakeClient()
        val messenger = SupportMessenger(sdk)
        var demo by mutableStateOf(true)
        var opened = false
        rule.setContent {
            MaterialTheme {
                SupportHelpDialog(
                    onDismiss = {}, onOpenHelpCenter = { opened = true },
                    allowed = false, isDemo = demo, sessionKey = "local-only", messenger = messenger,
                )
            }
        }
        rule.onNodeWithText(text(R.string.support_help_start)).assertDoesNotExist()
        rule.runOnIdle { demo = false }
        rule.onNodeWithText(text(R.string.support_help_start)).assertDoesNotExist()
        rule.onNodeWithText(text(R.string.support_help_center)).performClick()
        rule.runOnIdle { assertTrue(opened); assertEquals(0, sdk.logins) }
    }
}
