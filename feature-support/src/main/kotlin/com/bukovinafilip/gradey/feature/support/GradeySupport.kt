package com.bukovinafilip.gradey.feature.support

import android.app.Application
import android.os.Handler
import android.os.Looper
import io.intercom.android.sdk.Intercom
import io.intercom.android.sdk.IntercomError
import io.intercom.android.sdk.IntercomStatusCallback
import io.intercom.android.sdk.ui.theme.ThemeMode

/** Optional process-wide support service. No login occurs during initialization. */
object GradeySupport {
    var messenger: SupportMessenger = SupportMessenger(null)
        private set
    private var initialized = false

    /** Call only from Application.onCreate, before activities, as required by Intercom. */
    fun initialize(application: Application, configuration: SupportConfiguration?): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (initialized) return messenger.isConfigured
        initialized = true
        if (configuration == null) return false
        messenger = try {
            Intercom.initialize(application, configuration.androidApiKey, configuration.appID)
            val sdk = Intercom.client()
            sdk.setLauncherVisibility(Intercom.Visibility.GONE)
            sdk.setInAppMessageVisibility(Intercom.Visibility.GONE)
            sdk.suppressProactiveContent(Intercom.ContentType.entries.toList())
            sdk.setThemeMode(ThemeMode.SYSTEM)
            val adapter = IntercomAnonymousClient()
            // Do not silently resume an anonymous conversation from the previous app process.
            adapter.hideAndLogout()
            SupportMessenger(adapter)
        } catch (_: Exception) {
            SupportMessenger(null)
        } catch (_: LinkageError) {
            // A mispackaged optional SDK must not prevent access to school data or Help Center.
            SupportMessenger(null)
        }
        return messenger.isConfigured
    }

    /** Call before switching Gradey/school ownership, signing out, entering demo or revoking consent. */
    fun reset() {
        // Revoke the pending callback immediately, even for a background ownership change.
        // The adapter dispatches UI cleanup to main without delaying the revocation.
        messenger.reset()
    }
}

private class IntercomAnonymousClient : AnonymousSupportClient {
    private val handler = Handler(Looper.getMainLooper())
    private val revision = java.util.concurrent.atomic.AtomicLong()
    override fun hideAndLogout() {
        val token = revision.incrementAndGet()
        val cleanup = {
            // A queued background reset must not log out a newer explicit session.
            if (revision.get() == token) {
                Intercom.client().hideIntercom()
                Intercom.client().logout()
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) cleanup()
        else handler.post { cleanup() }
    }
    override fun login(onComplete: (Boolean) -> Unit) {
        check(Looper.myLooper() == Looper.getMainLooper())
        revision.incrementAndGet()
        Intercom.client().loginUnidentifiedUser(object : IntercomStatusCallback {
            override fun onSuccess() { deliver(true, onComplete) }
            override fun onFailure(intercomError: IntercomError) { deliver(false, onComplete) }
        })
    }
    private fun deliver(success: Boolean, callback: (Boolean) -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) callback(success)
        else handler.post { callback(success) }
    }
    override fun present() { Intercom.client().present() }
}
