package com.bukovinafilip.gradey.updates

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bukovinafilip.gradey.BuildConfig
import com.bukovinafilip.gradey.network.UpdateRelease
import android.os.Build
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the real Android package parser, without opening or confirming the installer. */
@RunWith(AndroidJUnit4::class)
class ApkUpdateVerifierInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun acceptsANewerPhoneApkSignedWithTheInstalledSigningKey() = runBlocking {
        val path = InstrumentationRegistry.getArguments().getString("gradey.updateTestApkPath")
        assumeTrue("Supply gradey.updateTestApkPath to verify a future same-signer APK fixture.", path != null)
        val file = File(requireNotNull(path))
        assertTrue("The provided future APK fixture must be readable by the app.", file.isFile && file.canRead())
        @Suppress("DEPRECATION")
        val candidate = requireNotNull(context.packageManager.getPackageArchiveInfo(file.absolutePath, 0))
        @Suppress("DEPRECATION")
        val versionCode = if (Build.VERSION.SDK_INT >= 28) candidate.longVersionCode else candidate.versionCode.toLong()
        assertTrue("The fixture must have a newer version code.", versionCode > BuildConfig.VERSION_CODE)
        val metadata = release(file).copy(
            versionCode = versionCode.toInt(),
            versionName = requireNotNull(candidate.versionName),
            minSdk = requireNotNull(candidate.applicationInfo).minSdkVersion,
        )
        // Performs the actual PackageManager metadata and signing-certificate comparison.
        ApkUpdateVerifier(context).verify(file, metadata)
    }

    @Test fun rejectsTheInstalledApkBecauseItIsNotANewerVersion() = runBlocking {
        val copy = File.createTempFile("current-version", ".apk", context.cacheDir)
        try {
            File(context.applicationInfo.sourceDir).copyTo(copy, overwrite = true)
            val error = rejected { ApkUpdateVerifier(context).verify(copy, release(copy)) }
            assertEquals("This update is not newer than the installed version.", error.message)
        } finally {
            copy.delete()
        }
    }

    @Test fun rejectsArbitraryBytesEvenWhenTheirDigestMatches() = runBlocking {
        val file = File.createTempFile("invalid-update", ".apk", context.cacheDir)
        try {
            file.writeText("This file is not an APK.")
            val error = rejected { ApkUpdateVerifier(context).verify(file, release(file)) }
            assertEquals("The downloaded file is not a valid signed Android app.", error.message)
        } finally {
            file.delete()
        }
    }

    @Test fun rejectsWrongDigestBeforeParsingTheArchive() = runBlocking {
        val file = File.createTempFile("altered-update", ".apk", context.cacheDir)
        try {
            file.writeText("This file is not an APK.")
            val error = rejected { ApkUpdateVerifier(context).verify(file, release(file).copy(sha256 = "0".repeat(64))) }
            assertEquals("The downloaded update failed its integrity check. Please download it again.", error.message)
        } finally {
            file.delete()
        }
    }

    private fun release(file: File): UpdateRelease {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count == -1) break
                digest.update(buffer, 0, count)
            }
        }
        return UpdateRelease(
            versionCode = BuildConfig.VERSION_CODE,
            versionName = BuildConfig.VERSION_NAME,
            packageName = context.packageName,
            minSdk = context.applicationInfo.minSdkVersion,
            apkUrl = "https://github.com/FBukovina/Gradey-android/releases/download/test/gradey.apk",
            sha256 = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) },
            sizeBytes = file.length(),
        )
    }

    private suspend fun rejected(action: suspend () -> Unit): UpdateVerificationException {
        try {
            action()
            fail("Expected update verification to reject the APK")
        } catch (error: UpdateVerificationException) {
            return error
        }
        error("Unreachable")
    }
}
