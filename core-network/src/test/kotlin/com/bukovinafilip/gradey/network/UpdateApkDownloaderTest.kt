package com.bukovinafilip.gradey.network

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.After
import org.junit.Test

class UpdateApkDownloaderTest {
    private val directory = Files.createTempDirectory("gradey-update-test").toFile()
    private val destination = File(directory, "download.part")
    private val payload = "Example binary APK bytes".toByteArray()

    @After fun cleanup() {
        directory.deleteRecursively()
    }

    @Test fun verifiesExactBytesAndChecksumBeforeCompleting() = runBlocking<Unit> {
        val fake = FakeHttp().apply { enqueue(payload.toResponseBody()) }
        val progress = mutableListOf<Long>()
        downloader(fake).download(release(), destination) { progress += it }
        assertThat(destination.readBytes()).isEqualTo(payload)
        assertThat(progress.first()).isEqualTo(0)
        assertThat(progress.last()).isEqualTo(payload.size.toLong())
        assertThat(fake.urls).containsExactly(APK_URL)
    }

    @Test fun followsGitHubStorageRedirectAndVerifiesUnknownLengthBody() = runBlocking<Unit> {
        val fake = FakeHttp().apply {
            enqueue(status = 302, location = "https://release-assets.githubusercontent.com/release/123?signature=abc")
            enqueue(unknownLengthBody(payload))
        }
        downloader(fake).download(release(), destination)
        assertThat(destination.readBytes()).isEqualTo(payload)
        assertThat(fake.urls).hasSize(2)
    }

    @Test fun rejectsOversizedStreamEvenWithoutContentLengthAndRemovesPartial() = runBlocking<Unit> {
        val fake = FakeHttp().apply { enqueue(unknownLengthBody(payload + payload)) }
        val error = runCatching { downloader(fake).download(release(), destination) }.exceptionOrNull()
        assertThat(error?.message).contains("larger")
        assertThat(destination.exists()).isFalse()
    }

    @Test fun rejectsTruncatedStreamAndRemovesPartial() = runBlocking<Unit> {
        val fake = FakeHttp().apply { enqueue(unknownLengthBody(payload.copyOf(payload.size - 1))) }
        val error = runCatching { downloader(fake).download(release(), destination) }.exceptionOrNull()
        assertThat(error?.message).contains("incomplete")
        assertThat(destination.exists()).isFalse()
    }

    @Test fun rejectsChecksumMismatchAndRemovesPartial() = runBlocking<Unit> {
        val fake = FakeHttp().apply { enqueue(payload.toResponseBody()) }
        val error = runCatching { downloader(fake).download(release().copy(sha256 = "0".repeat(64)), destination) }.exceptionOrNull()
        assertThat(error?.message).contains("checksum")
        assertThat(destination.exists()).isFalse()
    }

    @Test fun rejectsUnexpectedDeclaredLengthAndNeverTrustsPartialResponses() = runBlocking<Unit> {
        for (fake in listOf(
            FakeHttp().apply { enqueue((payload + payload).toResponseBody()) },
            FakeHttp().apply { enqueue(payload.toResponseBody(), status = 206) },
        )) {
            destination.writeText("old partial")
            assertThat(runCatching { downloader(fake).download(release(), destination) }.exceptionOrNull())
                .isInstanceOf(UpdateCheckException::class.java)
            assertThat(destination.exists()).isFalse()
        }
    }

    @Test fun refusesUnsafeInitialUrlBeforeAnyNetworkRequest() = runBlocking<Unit> {
        val fake = FakeHttp()
        val error = runCatching {
            downloader(fake).download(release().copy(apkUrl = "https://evil.example/app.apk"), destination)
        }.exceptionOrNull()
        assertThat(error).isInstanceOf(UpdateCheckException::class.java)
        assertThat(fake.urls).isEmpty()
        assertThat(destination.exists()).isFalse()
    }

    @Test fun refusesUnsafeRedirectBeforeRequestingIt() = runBlocking<Unit> {
        for (location in listOf("https://evil.example/app.apk", "http://release-assets.githubusercontent.com/app.apk")) {
            val fake = FakeHttp().apply { enqueue(status = 302, location = location) }
            assertThat(runCatching { downloader(fake).download(release(), destination) }.exceptionOrNull())
                .isInstanceOf(UpdateCheckException::class.java)
            assertThat(fake.urls).containsExactly(APK_URL)
            assertThat(destination.exists()).isFalse()
        }
    }

    @Test fun rejectsUnboundedMetadataBeforeAnyNetworkRequest() = runBlocking<Unit> {
        for (size in listOf(0L, UpdateReleaseParser.MAX_APK_BYTES + 1)) {
            val fake = FakeHttp()
            assertThat(runCatching { downloader(fake).download(release().copy(sizeBytes = size), destination) }.exceptionOrNull())
                .isInstanceOf(UpdateCheckException::class.java)
            assertThat(fake.urls).isEmpty()
        }
    }

    @Test fun cancellationClosesAnActiveBodyCancelsCallAndRemovesPartialBeforeReturning() = runBlocking<Unit> {
        val body = BlockingBody()
        val fake = FakeHttp().apply { enqueue(body) }
        val job = launch { downloader(fake).download(release(), destination) }
        withTimeout(3_000) { body.readStarted.await() }
        assertThat(destination.exists()).isTrue()
        withTimeout(3_000) { job.cancelAndJoin() }
        assertThat(fake.lastCall?.isCanceled()).isTrue()
        assertThat(body.wasClosed).isTrue()
        assertThat(destination.exists()).isFalse()
    }

    private fun release() = UpdateRelease(
        versionCode = 4, versionName = "0.2.2", packageName = "com.bukovinafilip.gradey", minSdk = 26,
        apkUrl = APK_URL, sha256 = hash(payload), sizeBytes = payload.size.toLong(),
    )

    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    private fun downloader(fake: FakeHttp) = UpdateApkDownloader(OkHttpClient.Builder().addInterceptor(fake).build())

    private fun unknownLengthBody(bytes: ByteArray) = object : ResponseBody() {
        private val buffer = Buffer().write(bytes)
        override fun contentType() = "application/octet-stream".toMediaType()
        override fun contentLength(): Long = -1
        override fun source(): BufferedSource = buffer
    }

    private class BlockingBody : ResponseBody() {
        val readStarted = CompletableDeferred<Unit>()
        private val closed = CountDownLatch(1)
        @Volatile var wasClosed = false
        private val buffered = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long {
                readStarted.complete(Unit)
                if (!closed.await(5, TimeUnit.SECONDS)) throw IOException("Test body was not closed by cancellation")
                throw IOException("Body closed")
            }
            override fun timeout() = Timeout.NONE
            override fun close() {
                wasClosed = true
                closed.countDown()
            }
        }.buffer()
        override fun contentType() = "application/octet-stream".toMediaType()
        override fun contentLength(): Long = -1
        override fun source(): BufferedSource = buffered
    }

    private class FakeHttp : Interceptor {
        private val queue = ArrayDeque<Reply>()
        val urls = mutableListOf<String>()
        var lastCall: Call? = null

        fun enqueue(body: ResponseBody = ByteArray(0).toResponseBody(), status: Int = 200, location: String? = null) {
            queue.add(Reply(body, status, location))
        }

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            lastCall = chain.call()
            urls += request.url.toString()
            val reply = queue.removeFirst()
            return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(reply.status)
                .message("Fake response").body(reply.body)
                .apply { reply.location?.let { header("Location", it) } }.build()
        }

        private data class Reply(val body: ResponseBody, val status: Int, val location: String?)
    }

    companion object {
        private const val APK_URL = "https://github.com/FBukovina/Gradey-android/releases/download/v0.2.2/gradey-0.2.2.apk"
    }
}
