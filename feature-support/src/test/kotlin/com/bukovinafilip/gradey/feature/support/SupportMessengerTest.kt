package com.bukovinafilip.gradey.feature.support

import org.junit.Assert.*
import org.junit.Test

class SupportMessengerTest {
    private class FakeClient : AnonymousSupportClient {
        var logins = 0
        var resets = 0
        var presentations = 0
        var callback: ((Boolean) -> Unit)? = null
        var throwOnPresent = false
        override fun hideAndLogout() { resets++ }
        override fun login(onComplete: (Boolean) -> Unit) { logins++; callback = onComplete }
        override fun present() { if (throwOnPresent) error("Cannot present"); presentations++ }
        fun respond(success: Boolean) = requireNotNull(callback).invoke(success)
    }

    @Test fun configurationAcceptsOnlyPublicAndroidKeyAndNonemptyAppID() {
        assertNotNull(SupportConfiguration.from(" android_sdk-public123 ", " app123 "))
        for (key in listOf(null, "", "ios_sdk-key", "sk-secret", "\$(INTERCOM_ANDROID_KEY)", "android_sdk-\${KEY}")) {
            assertNull(SupportConfiguration.from(key, "app123"))
        }
        assertNull(SupportConfiguration.from("android_sdk-public", "\$(INTERCOM_APP_ID)"))
        assertNull(SupportConfiguration.from("android_sdk-public", ""))
    }

    @Test fun absentConfigurationNeverCreatesSDKSession() {
        val results = mutableListOf<SupportOpenResult>()
        val messenger = SupportMessenger(null)
        assertFalse(messenger.isConfigured)
        messenger.openAnonymous(results::add)
        messenger.reset()
        assertEquals(listOf(SupportOpenResult.UNAVAILABLE), results)
    }

    @Test fun constructionDoesNotLoginAndExplicitOpenStartsFreshAnonymousSession() {
        val sdk = FakeClient()
        val messenger = SupportMessenger(sdk)
        assertEquals(0, sdk.logins)
        val results = mutableListOf<SupportOpenResult>()
        messenger.openAnonymous(results::add)
        assertEquals(1, sdk.resets)
        assertEquals(1, sdk.logins)
        assertEquals(0, sdk.presentations)
        sdk.respond(true)
        assertEquals(1, sdk.presentations)
        assertEquals(listOf(SupportOpenResult.OPENED), results)
    }

    @Test fun resetDuringLoginRejectsLateSuccessAndSerializesNextRequest() {
        val sdk = FakeClient()
        val messenger = SupportMessenger(sdk)
        val results = mutableListOf<SupportOpenResult>()
        messenger.openAnonymous(results::add)
        messenger.reset()
        assertEquals(listOf(SupportOpenResult.CANCELLED), results)
        messenger.openAnonymous(results::add)
        assertEquals(SupportOpenResult.BUSY, results.last())
        assertEquals(1, sdk.logins)
        sdk.respond(true)
        assertEquals(0, sdk.presentations)
        messenger.openAnonymous(results::add)
        sdk.respond(true)
        assertEquals(1, sdk.presentations)
        assertEquals(SupportOpenResult.OPENED, results.last())
    }

    @Test fun dismissingDialogCancelsPendingLoginWithoutOpeningBehindAnotherScreen() {
        val sdk = FakeClient()
        val messenger = SupportMessenger(sdk)
        val results = mutableListOf<SupportOpenResult>()
        val request = messenger.openAnonymous(results::add)
        request.cancel()
        request.cancel()
        sdk.respond(true)
        assertEquals(0, sdk.presentations)
        assertEquals(listOf(SupportOpenResult.CANCELLED), results)
    }

    @Test fun completedHandleCannotCancelNewerRequestAndDuplicateCallbackCannotPresentTwice() {
        val sdk = FakeClient()
        val messenger = SupportMessenger(sdk)
        val first = messenger.openAnonymous {}
        val oldCallback = requireNotNull(sdk.callback)
        sdk.respond(true)
        messenger.openAnonymous {}
        first.cancel()
        oldCallback(true)
        sdk.respond(true)
        assertEquals(2, sdk.presentations)
    }

    @Test fun failedLoginOrPresentationClearsSessionAndAllowsRetry() {
        val sdk = FakeClient()
        val messenger = SupportMessenger(sdk)
        val results = mutableListOf<SupportOpenResult>()
        messenger.openAnonymous(results::add)
        sdk.respond(false)
        assertEquals(0, sdk.presentations)
        assertEquals(2, sdk.resets)
        sdk.throwOnPresent = true
        messenger.openAnonymous(results::add)
        sdk.respond(true)
        assertEquals(listOf(SupportOpenResult.FAILED, SupportOpenResult.FAILED), results)
        assertEquals(4, sdk.resets)
    }
}
