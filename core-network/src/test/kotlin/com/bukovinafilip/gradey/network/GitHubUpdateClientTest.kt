package com.bukovinafilip.gradey.network

import com.google.common.truth.Truth.assertThat
import java.util.ArrayDeque
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import org.junit.Test

class GitHubUpdateClientTest {
    private val release = UpdateRelease(
        versionCode = 4,
        versionName = "0.2.2",
        packageName = "com.bukovinafilip.gradey",
        minSdk = 26,
        apkUrl = "$ASSET_ROOT/gradey-0.2.2.apk",
        sha256 = "abcdef0123456789".repeat(4),
        sizeBytes = 42_000_000,
        releaseNotes = "A clearer timetable and bug fixes.",
    )

    @Test fun validManifestPreservesMetadataAndNormalizesHash() {
        val parsed = UpdateReleaseParser.parse(manifest(release.copy(sha256 = release.sha256.uppercase())))
        assertThat(parsed).isEqualTo(release)
    }

    @Test fun rejectsWrongPackageAndInvalidVersionNumbers() {
        listOf(
            release.copy(packageName = "com.other.app"),
            release.copy(versionCode = 0),
            release.copy(versionName = " "),
            release.copy(minSdk = -1),
        ).forEach { assertInvalid(it) }
    }

    @Test fun requiresPhoneTargetEvenWhenWearSharesThePackageName() {
        for (source in listOf(Json.encodeToString(release), manifest(release).replace("\"phone\"", "\"wear\""))) {
            assertThat(runCatching { UpdateReleaseParser.parse(source) }.exceptionOrNull())
                .isInstanceOf(UpdateCheckException::class.java)
        }
    }

    @Test fun rejectsMalformedHashesAndUnboundedApkSizes() {
        listOf(
            release.copy(sha256 = "a".repeat(63)),
            release.copy(sha256 = "z".repeat(64)),
            release.copy(sizeBytes = 0),
            release.copy(sizeBytes = UpdateReleaseParser.MAX_APK_BYTES + 1),
        ).forEach { assertInvalid(it) }
    }

    @Test fun refusesForeignRepositoriesUnsafeOriginsAndAmbiguousAssetUrls() {
        listOf(
            "http://github.com/FBukovina/Gradey-android/releases/download/v0.2.2/app.apk",
            "https://github.com.evil.example/FBukovina/Gradey-android/releases/download/v0.2.2/app.apk",
            "https://github.com/another/Gradey-android/releases/download/v0.2.2/app.apk",
            "https://github.com/FBukovina/another/releases/download/v0.2.2/app.apk",
            "https://user@github.com/FBukovina/Gradey-android/releases/download/v0.2.2/app.apk",
            "https://github.com:443/FBukovina/Gradey-android/releases/download/v0.2.2/app.apk",
            "$ASSET_ROOT/app.apk#fragment",
            "$ASSET_ROOT/app.apk?download=1",
            "$ASSET_ROOT/a%2Fb.apk",
            "$ASSET_ROOT/app.zip",
            "https://objects.githubusercontent.com/app.apk",
        ).forEach { assertInvalid(release.copy(apkUrl = it)) }
    }

    @Test fun enforcesManifestAndNotesBounds() {
        assertInvalid(release.copy(releaseNotes = "a".repeat(8_001)))
        val error = runCatching { UpdateReleaseParser.parse(" ".repeat(UpdateReleaseParser.MAX_MANIFEST_BYTES + 1)) }.exceptionOrNull()
        assertThat(error).isInstanceOf(UpdateCheckException::class.java)
    }

    @Test fun downloadsLatestStableReleaseWithoutCredentials() = runTest {
        val fake = FakeHttp()
        fake.enqueue(releaseMetadata())
        fake.enqueue(manifest(release))
        assertThat(client(fake).checkLatest(currentVersionCode = 3, sdkInt = 35)).isEqualTo(release)
        assertThat(fake.urls).containsExactly(GitHubUpdateClient.LATEST_RELEASE_URL, "$ASSET_ROOT/gradey-update.json").inOrder()
        assertThat(fake.authorizationHeaders).containsExactly(null, null)
    }

    @Test fun returnsNoUpdateForMissingReleaseAndManifest() = runTest {
        val missingRelease = FakeHttp().apply { enqueue("", status = 404) }
        assertThat(client(missingRelease).checkLatest(3, 35)).isNull()
        val noManifest = FakeHttp().apply { enqueue(releaseMetadata(includeManifest = false)) }
        assertThat(client(noManifest).checkLatest(3, 35)).isNull()
        assertThat(noManifest.urls).hasSize(1)
    }

    @Test fun neverOffersDowngradesOrReinstalls() = runTest {
        for (installed in listOf(release.versionCode, release.versionCode + 1)) {
            val fake = FakeHttp().apply {
                enqueue(releaseMetadata())
                enqueue(manifest(release))
            }
            assertThat(client(fake).checkLatest(installed, 35)).isNull()
        }
    }

    @Test fun ignoresDraftAndPrereleaseResponses() = runTest {
        for ((draft, preview) in listOf(true to false, false to true)) {
            val fake = FakeHttp().apply { enqueue(releaseMetadata(draft = draft, prerelease = preview)) }
            assertThat(client(fake).checkLatest(3, 35)).isNull()
            assertThat(fake.urls).hasSize(1)
        }
    }

    @Test fun reportsIncompatibleAndroidVersionWithoutOfferingApk() = runTest {
        val fake = FakeHttp().apply {
            enqueue(releaseMetadata())
            enqueue(manifest(release.copy(minSdk = 36)))
        }
        val error = runCatching { client(fake).checkLatest(3, 35) }.exceptionOrNull()
        assertThat(error).isInstanceOf(UpdateCheckException::class.java)
        assertThat(error?.message).contains("newer Android version")
    }

    @Test fun acceptsOnlyBoundedRedirectsToGitHubReleaseStorage() = runTest {
        val fake = FakeHttp().apply {
            enqueue(releaseMetadata())
            enqueue("", status = 302, location = "https://release-assets.githubusercontent.com/github-production-release-asset/123?signature=abc")
            enqueue(manifest(release))
        }
        assertThat(client(fake).checkLatest(3, 35)).isEqualTo(release)
        assertThat(fake.urls).hasSize(3)
    }

    @Test fun rejectsUntrustedAndCleartextRedirectsBeforeRequestingThem() = runTest {
        for (location in listOf("https://evil.example/manifest.json", "http://objects.githubusercontent.com/manifest.json")) {
            val fake = FakeHttp().apply {
                enqueue(releaseMetadata())
                enqueue("", status = 302, location = location)
            }
            val error = runCatching { client(fake).checkLatest(3, 35) }.exceptionOrNull()
            assertThat(error).isInstanceOf(UpdateCheckException::class.java)
            assertThat(error?.message).contains("untrusted")
            assertThat(fake.urls).hasSize(2)
        }
    }

    @Test fun stopsRedirectLoops() = runTest {
        val fake = FakeHttp().apply {
            enqueue(releaseMetadata())
            repeat(6) { enqueue("", status = 302, location = "$ASSET_ROOT/gradey-update.json") }
        }
        val error = runCatching { client(fake).checkLatest(3, 35) }.exceptionOrNull()
        assertThat(error?.message).contains("too many")
        assertThat(fake.urls).hasSize(7)
    }

    @Test fun rejectsForeignManifestBeforeDownloading() = runTest {
        val fake = FakeHttp().apply {
            enqueue(releaseMetadata().replace("$ASSET_ROOT/gradey-update.json", "https://evil.example/gradey-update.json"))
        }
        val error = runCatching { client(fake).checkLatest(3, 35) }.exceptionOrNull()
        assertThat(error?.message).contains("release repository")
        assertThat(fake.urls).hasSize(1)
    }

    @Test fun rejectsMissingApkAndInconsistentReleaseSize() = runTest {
        for (metadata in listOf(releaseMetadata(includeApk = false), releaseMetadata(apkSize = release.sizeBytes + 1))) {
            val fake = FakeHttp().apply {
                enqueue(metadata)
                enqueue(manifest(release))
            }
            assertThat(runCatching { client(fake).checkLatest(3, 35) }.exceptionOrNull())
                .isInstanceOf(UpdateCheckException::class.java)
        }
    }

    @Test fun boundsManifestBodyEvenWithoutContentLength() = runTest {
        val fake = FakeHttp().apply {
            enqueue(releaseMetadata())
            enqueue("x".repeat(UpdateReleaseParser.MAX_MANIFEST_BYTES + 1), unknownLength = true)
        }
        val error = runCatching { client(fake).checkLatest(3, 35) }.exceptionOrNull()
        assertThat(error?.message).contains("too large")
    }

    @Test fun rateLimitErrorsNeverEchoServerContents() = runTest {
        val fake = FakeHttp().apply { enqueue("private server details", status = 403) }
        val error = runCatching { client(fake).checkLatest(3, 35) }.exceptionOrNull()
        assertThat(error?.message).contains("limit")
        assertThat(error?.message).doesNotContain("private server")
    }

    private fun assertInvalid(value: UpdateRelease) {
        assertThat(runCatching { UpdateReleaseParser.parse(manifest(value)) }.exceptionOrNull())
            .isInstanceOf(UpdateCheckException::class.java)
    }

    private fun manifest(value: UpdateRelease): String = buildJsonObject {
        Json.parseToJsonElement(Json.encodeToString(value)).jsonObject.forEach { (key, field) -> put(key, field) }
        put("target", "phone")
    }.toString()

    private fun client(fake: FakeHttp) = GitHubUpdateClient(okHttpClient = OkHttpClient.Builder().addInterceptor(fake).build())

    private fun releaseMetadata(
        draft: Boolean = false,
        prerelease: Boolean = false,
        includeManifest: Boolean = true,
        includeApk: Boolean = true,
        apkSize: Long = release.sizeBytes,
    ): String = buildJsonObject {
        put("draft", draft)
        put("prerelease", prerelease)
        put("assets", buildJsonArray {
            if (includeManifest) add(buildJsonObject {
                put("name", "gradey-update.json")
                put("browser_download_url", "$ASSET_ROOT/gradey-update.json")
                put("size", manifest(release).length)
            })
            if (includeApk) add(buildJsonObject {
                put("name", "gradey-0.2.2.apk")
                put("browser_download_url", release.apkUrl)
                put("size", apkSize)
            })
        })
    }.toString()

    /** Intercepts real production URLs so tests cannot accidentally loosen the allowlist. */
    private class FakeHttp : Interceptor {
        private val queue = ArrayDeque<Reply>()
        val urls = mutableListOf<String>()
        val authorizationHeaders = mutableListOf<String?>()

        fun enqueue(body: String, status: Int = 200, location: String? = null, unknownLength: Boolean = false) {
            queue.add(Reply(body, status, location, unknownLength))
        }

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            urls += request.url.toString()
            authorizationHeaders += request.header("Authorization")
            val next = queue.removeFirst()
            val body = if (next.unknownLength) object : ResponseBody() {
                private val buffer = Buffer().writeUtf8(next.body)
                override fun contentType() = "application/json".toMediaType()
                override fun contentLength(): Long = -1
                override fun source(): BufferedSource = buffer
            } else next.body.toResponseBody("application/json".toMediaType())
            return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(next.status)
                .message("Fake response").body(body)
                .apply { next.location?.let { header("Location", it) } }.build()
        }

        private data class Reply(val body: String, val status: Int, val location: String?, val unknownLength: Boolean)
    }

    companion object {
        private const val ASSET_ROOT = "https://github.com/FBukovina/Gradey-android/releases/download/v0.2.2"
    }
}
