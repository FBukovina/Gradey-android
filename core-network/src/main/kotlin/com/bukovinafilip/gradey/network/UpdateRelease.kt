package com.bukovinafilip.gradey.network

import java.io.IOException
import java.net.URI
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Public release metadata. APK bytes must also pass hash, package and signing checks before install. */
@Serializable
data class UpdateRelease(
    val versionCode: Int,
    val versionName: String,
    val packageName: String,
    val minSdk: Int,
    val apkUrl: String,
    val sha256: String,
    val sizeBytes: Long,
    val releaseNotes: String? = null,
)

class UpdateCheckException(message: String, cause: Throwable? = null) : IOException(message, cause)

object UpdateReleaseParser {
    const val MAX_APK_BYTES: Long = 200L * 1024 * 1024
    const val MAX_MANIFEST_BYTES: Int = 64 * 1024
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(source: String, expectedPackageName: String = "com.bukovinafilip.gradey"): UpdateRelease {
        if (source.toByteArray(Charsets.UTF_8).size > MAX_MANIFEST_BYTES) invalid()
        val release = try {
            // Phone and Wear intentionally share a package ID for the Data Layer. A package ID
            // alone cannot distinguish their updates, so the published target is mandatory.
            val manifest = json.parseToJsonElement(source) as? JsonObject ?: invalid()
            if (manifest["target"] != JsonPrimitive("phone")) invalid()
            json.decodeFromString<UpdateRelease>(source)
        } catch (error: Exception) {
            throw UpdateCheckException("The published update information is invalid.", error)
        }
        if (release.versionCode <= 0 || release.versionName.isBlank() || release.versionName.length > 64 ||
            release.packageName != expectedPackageName || release.minSdk !in 1..10_000 ||
            release.sizeBytes !in 1..MAX_APK_BYTES || !release.sha256.matches(Regex("[0-9a-fA-F]{64}")) ||
            (release.releaseNotes?.length ?: 0) > 8_000 || !isTrustedReleaseAssetUrl(release.apkUrl) ||
            !release.apkUrl.substringBefore('?').endsWith(".apk", ignoreCase = true)
        ) invalid()
        return release.copy(sha256 = release.sha256.lowercase())
    }

    /** Restricts the initial URL to an asset in this repository; excludes user info and URL ambiguity. */
    fun isTrustedReleaseAssetUrl(value: String): Boolean {
        val url = secureUrl(value) ?: return false
        val segments = url.pathSegments
        return url.host == "github.com" && url.query == null && segments.size == 6 &&
            segments[0].equals("FBukovina", ignoreCase = true) &&
            segments[1].equals("Gradey-android", ignoreCase = true) &&
            segments[2] == "releases" && segments[3] == "download" &&
            segments.drop(4).all { it.isNotBlank() && '/' !in it && '\\' !in it && it != "." && it != ".." }
    }

    /** GitHub's release storage uses signed URLs on these exact hosts. No arbitrary subdomains. */
    fun isTrustedDownloadUrl(value: String): Boolean {
        val url = secureUrl(value) ?: return false
        return isTrustedReleaseAssetUrl(value) ||
            url.host == "release-assets.githubusercontent.com" || url.host == "objects.githubusercontent.com"
    }

    private fun secureUrl(value: String): HttpUrl? {
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        if (uri.scheme != "https" || uri.rawUserInfo != null || uri.port != -1 || uri.rawFragment != null ||
            '\\' in value || value.any { it.isISOControl() }
        ) return null
        return value.toHttpUrlOrNull()?.takeIf {
            it.isHttps && it.username.isEmpty() && it.password.isEmpty() && it.port == 443 && it.fragment == null
        }
    }

    private fun invalid(): Nothing = throw UpdateCheckException("The published update information is invalid.")
}
