package com.bukovinafilip.gradey.network

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** A read-only, unauthenticated stable release feed. Never sends school or Gradey account tokens. */
class GitHubUpdateClient(
    private val expectedPackageName: String = "com.bukovinafilip.gradey",
    okHttpClient: OkHttpClient = OkHttpClient(),
) {
    private val client = okHttpClient.newBuilder()
        .cookieJar(CookieJar.NO_COOKIES)
        .authenticator(Authenticator.NONE)
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    /** Null means no newer stable update has been published for this app. */
    suspend fun checkLatest(currentVersionCode: Int, sdkInt: Int): UpdateRelease? {
        val metadata = get(LATEST_RELEASE_URL, MAX_METADATA_BYTES)
        if (metadata.statusCode == 404) return null
        requireSuccess(metadata.statusCode)
        val latest = try {
            json.decodeFromString<GitHubRelease>(metadata.body)
        } catch (error: Exception) {
            throw UpdateCheckException("GitHub returned invalid update information.", error)
        }
        if (latest.draft || latest.prerelease) return null
        val manifests = latest.assets.filter { it.name == "gradey-update.json" }
        if (manifests.isEmpty()) return null // Older releases may predate the updater.
        if (manifests.size != 1) throw UpdateCheckException("The published update information is ambiguous.")
        val manifest = manifests.single()
        if (!UpdateReleaseParser.isTrustedReleaseAssetUrl(manifest.browser_download_url) ||
            manifest.browser_download_url.toHttpUrl().pathSegments.last() != "gradey-update.json"
        ) throw UpdateCheckException("The update manifest does not come from Gradey's release repository.")

        val contents = downloadManifest(manifest.browser_download_url)
        val release = UpdateReleaseParser.parse(contents, expectedPackageName)
        if (release.versionCode <= currentVersionCode) return null
        if (release.minSdk > sdkInt) throw UpdateCheckException(
            "Gradey ${release.versionName} requires a newer Android version (API ${release.minSdk}).",
        )
        // The manifest and APK must belong to the same release, not another repository asset.
        val apk = latest.assets.singleOrNull { it.browser_download_url == release.apkUrl }
            ?: throw UpdateCheckException("The update APK is missing from the published release.")
        if (apk.size != release.sizeBytes) throw UpdateCheckException("The published update size does not match its APK.")
        return release
    }

    private suspend fun downloadManifest(initialUrl: String): String {
        var url = initialUrl
        repeat(6) {
            if (!UpdateReleaseParser.isTrustedDownloadUrl(url)) {
                throw UpdateCheckException("The update download redirected to an untrusted address.")
            }
            val response = get(url, UpdateReleaseParser.MAX_MANIFEST_BYTES)
            if (response.statusCode in REDIRECT_CODES) {
                val location = response.location
                    ?: throw UpdateCheckException("The update download returned an invalid redirect.")
                url = url.toHttpUrl().resolve(location)?.toString()
                    ?: throw UpdateCheckException("The update download returned an invalid redirect.")
            } else {
                requireSuccess(response.statusCode)
                return response.body
            }
        }
        throw UpdateCheckException("The update download redirected too many times.")
    }

    private suspend fun get(url: String, maxBytes: Int): FeedResponse = suspendCancellableCoroutine { continuation ->
        val request = Request.Builder().url(url)
            .header("Accept", if (url == LATEST_RELEASE_URL) "application/vnd.github+json" else "application/json")
            .header("User-Agent", "Gradey-Android-Updater")
            .apply { if (url == LATEST_RELEASE_URL) header("X-GitHub-Api-Version", "2022-11-28") }
            .build()
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) continuation.resumeWithException(
                    UpdateCheckException("Could not check for updates. Check your connection and try again.", error),
                )
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.use {
                        val body = if (it.code == 200) readBounded(it, maxBytes) else ""
                        FeedResponse(it.code, it.header("Location"), body)
                    }
                    if (continuation.isActive) continuation.resume(result)
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(
                        if (error is UpdateCheckException) error else UpdateCheckException("Could not read the update information.", error),
                    )
                }
            }
        })
    }

    private fun readBounded(response: Response, maxBytes: Int): String {
        val body = response.body ?: throw UpdateCheckException("The update information is empty.")
        if (body.contentLength() > maxBytes) throw UpdateCheckException("The update information is too large.")
        val output = ByteArrayOutputStream()
        body.byteStream().use { input ->
            val buffer = ByteArray(8_192)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count == -1) break
                total += count
                if (total > maxBytes) throw UpdateCheckException("The update information is too large.")
                output.write(buffer, 0, count)
            }
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private fun requireSuccess(statusCode: Int) {
        if (statusCode == 200) return
        if (statusCode == 403 || statusCode == 429) throw UpdateCheckException("GitHub's update limit was reached. Try again later.")
        throw UpdateCheckException("Could not load the published update (HTTP $statusCode).")
    }

    @Serializable
    private data class GitHubRelease(val draft: Boolean, val prerelease: Boolean, val assets: List<GitHubAsset>)

    @Serializable
    private data class GitHubAsset(val name: String, val browser_download_url: String, val size: Long)

    private data class FeedResponse(val statusCode: Int, val location: String?, val body: String)

    companion object {
        const val LATEST_RELEASE_URL = "https://api.github.com/repos/FBukovina/Gradey-android/releases/latest"
        private const val MAX_METADATA_BYTES = 512 * 1024
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}
