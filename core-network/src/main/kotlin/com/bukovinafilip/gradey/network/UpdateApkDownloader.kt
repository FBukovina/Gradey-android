package com.bukovinafilip.gradey.network

import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** Downloads into the caller's private .part file; caller must verify Android metadata and signers. */
class UpdateApkDownloader(okHttpClient: OkHttpClient = OkHttpClient()) {
    private val client = okHttpClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .cookieJar(CookieJar.NO_COOKIES)
        .authenticator(Authenticator.NONE)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .callTimeout(7, TimeUnit.MINUTES)
        .build()

    /** Returns only after the exact declared length and SHA-256 match. Always removes failed partials. */
    suspend fun download(release: UpdateRelease, destination: File, onProgress: (Long) -> Unit = {}) {
        var finished = false
        try {
            withTimeout(7 * 60_000L) {
                withContext(Dispatchers.IO) {
                    coroutineScope {
                        if (destination.isDirectory) throw UpdateCheckException("The update download path is invalid.")
                        val activeCall = AtomicReference<Call?>()
                        val activeResponse = AtomicReference<Response?>()
                        // Start before blocking network I/O. Parent cancellation closes both the request
                        // and its body, and this scope waits for the streaming cleanup before returning.
                        val cancellationMonitor = launch(start = CoroutineStart.UNDISPATCHED) {
                            try {
                                awaitCancellation()
                            } finally {
                                activeCall.get()?.cancel()
                                runCatching { activeResponse.getAndSet(null)?.close() }
                            }
                        }
                        var verified = false
                        try {
                            if (!UpdateReleaseParser.isTrustedReleaseAssetUrl(release.apkUrl) ||
                                release.sizeBytes !in 1..UpdateReleaseParser.MAX_APK_BYTES ||
                                !release.sha256.matches(Regex("[a-fA-F0-9]{64}"))
                            ) throw UpdateCheckException("The update download information is invalid.")
                            destination.parentFile?.let { parent ->
                                if (!parent.isDirectory && !parent.mkdirs()) throw UpdateCheckException("Could not create the update download folder.")
                            }
                            onProgress(0)
                            stream(release, destination, onProgress, coroutineContext, activeCall, activeResponse)
                            coroutineContext.ensureActive()
                            verified = true
                        } catch (error: Exception) {
                            coroutineContext.ensureActive()
                            if (error is CancellationException || error is UpdateCheckException) throw error
                            throw UpdateCheckException("Could not download the update. Check your connection and try again.", error)
                        } finally {
                            cancellationMonitor.cancel()
                            if (!verified) destination.delete()
                        }
                    }
                }
            }
            finished = true
        } catch (error: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw UpdateCheckException("The update download timed out. Try again on a faster connection.", error)
        } finally {
            if (!finished && !destination.isDirectory) destination.delete()
        }
    }

    private fun stream(
        release: UpdateRelease,
        destination: File,
        onProgress: (Long) -> Unit,
        context: CoroutineContext,
        activeCall: AtomicReference<Call?>,
        activeResponse: AtomicReference<Response?>,
    ) {
        var url = release.apkUrl
        repeat(6) {
            context.ensureActive()
            if (!UpdateReleaseParser.isTrustedDownloadUrl(url)) throw UpdateCheckException("The update redirected to an untrusted address.")
            val request = Request.Builder().url(url)
                .header("User-Agent", "Gradey-Android-Updater")
                .header("Accept", "application/vnd.android.package-archive, application/octet-stream")
                .build()
            val call = client.newCall(request)
            activeCall.set(call)
            context.ensureActive()
            call.execute().use { response ->
                activeResponse.set(response)
                context.ensureActive()
                if (response.code in REDIRECT_CODES) {
                    val location = response.header("Location") ?: throw UpdateCheckException("The update returned an invalid redirect.")
                    url = url.toHttpUrl().resolve(location)?.toString() ?: throw UpdateCheckException("The update returned an invalid redirect.")
                } else {
                    if (response.code != 200) throw UpdateCheckException("Could not download the update (HTTP ${response.code}).")
                    val body = response.body ?: throw UpdateCheckException("The update download is empty.")
                    val contentLength = body.contentLength()
                    if (contentLength != -1L && contentLength != release.sizeBytes) throw UpdateCheckException("The update download size does not match the release.")
                    val digest = MessageDigest.getInstance("SHA-256")
                    var total = 0L
                    FileOutputStream(destination, false).use { output ->
                        body.byteStream().use { input ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                context.ensureActive()
                                val count = input.read(buffer)
                                if (count == -1) break
                                context.ensureActive()
                                total += count
                                if (total > release.sizeBytes || total > UpdateReleaseParser.MAX_APK_BYTES) {
                                    throw UpdateCheckException("The update download is larger than the published release.")
                                }
                                digest.update(buffer, 0, count)
                                output.write(buffer, 0, count)
                                onProgress(total)
                            }
                        }
                        if (total != release.sizeBytes) throw UpdateCheckException("The update download is incomplete. Try again.")
                        val actualHash = digest.digest().joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
                        if (!actualHash.equals(release.sha256, ignoreCase = true)) throw UpdateCheckException("The update failed its security checksum. Download it again.")
                        output.fd.sync()
                    }
                    activeResponse.set(null)
                    return
                }
                activeResponse.set(null)
            }
        }
        throw UpdateCheckException("The update download redirected too many times.")
    }

    private companion object {
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}
