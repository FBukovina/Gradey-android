package com.bukovinafilip.gradey.feature.support

/** Only the public Android installation key is accepted; never an iOS key or server secret. */
class SupportConfiguration private constructor(val androidApiKey: String, val appID: String) {
    companion object {
        fun from(androidApiKey: String?, appID: String?): SupportConfiguration? {
            val key = androidApiKey?.trim().orEmpty()
            val id = appID?.trim().orEmpty()
            if (!key.matches(Regex("android_sdk-[A-Za-z0-9_-]+")) ||
                !id.matches(Regex("[A-Za-z0-9_-]+"))) return null
            return SupportConfiguration(key, id)
        }
    }
}

enum class SupportOpenResult { OPENED, FAILED, UNAVAILABLE, BUSY, CANCELLED }

fun interface SupportRequest { fun cancel() }

/** Narrow seam ensures this module cannot send Gradey identity, school data or custom events. */
internal interface AnonymousSupportClient {
    fun hideAndLogout()
    fun login(onComplete: (Boolean) -> Unit)
    fun present()
}

/**
 * A cancelled login must settle before another starts: the SDK has no login cancellation API.
 * This prevents a late callback from opening support after a sign-out or logging out a newer request.
 * Production calls/callbacks run on the main thread; synchronization also serializes test fakes.
 */
class SupportMessenger internal constructor(private val client: AnonymousSupportClient?) {
    private class Pending(val callback: (SupportOpenResult) -> Unit) {
        var cancelled = false
        var delivered = false
    }
    private var pending: Pending? = null
    val isConfigured: Boolean get() = client != null

    @Synchronized
    fun openAnonymous(onResult: (SupportOpenResult) -> Unit): SupportRequest {
        val sdk = client ?: return complete(onResult, SupportOpenResult.UNAVAILABLE)
        if (pending != null) return complete(onResult, SupportOpenResult.BUSY)
        val request = Pending(onResult)
        pending = request
        try {
            // iOS also starts a new anonymous support session on each explicit presentation.
            sdk.hideAndLogout()
            sdk.login { success -> finish(request, success) }
        } catch (_: Exception) {
            finish(request, false)
        }
        return SupportRequest { cancel(request) }
    }

    @Synchronized
    fun reset() {
        pending?.let { cancel(it) }
        runCatching { client?.hideAndLogout() }
    }

    @Synchronized
    private fun cancel(request: Pending) {
        if (pending !== request || request.cancelled) return
        request.cancelled = true
        runCatching { client?.hideAndLogout() }
        deliver(request, SupportOpenResult.CANCELLED)
        // Keep pending until the old SDK request responds; it may still restore a session.
    }

    @Synchronized
    private fun finish(request: Pending, success: Boolean) {
        if (pending !== request) return // Defensive against a duplicate SDK callback.
        pending = null
        if (request.cancelled) {
            runCatching { client?.hideAndLogout() }
            return
        }
        val result = if (success) {
            try {
                client?.present()
                SupportOpenResult.OPENED
            } catch (_: Exception) {
                runCatching { client?.hideAndLogout() }
                SupportOpenResult.FAILED
            }
        } else {
            runCatching { client?.hideAndLogout() }
            SupportOpenResult.FAILED
        }
        deliver(request, result)
    }

    private fun deliver(request: Pending, result: SupportOpenResult) {
        if (!request.delivered) {
            request.delivered = true
            request.callback(result)
        }
    }

    private fun complete(callback: (SupportOpenResult) -> Unit, result: SupportOpenResult): SupportRequest {
        callback(result)
        return SupportRequest {}
    }
}
