package com.bukovinafilip.gradey.updates

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.bukovinafilip.gradey.network.UpdateRelease
import com.bukovinafilip.gradey.network.UpdateReleaseParser
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** User-safe failure; callers must never install an APK after verification fails. */
class UpdateVerificationException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Verifies a complete APK kept in app-private storage before handing it to Android's installer.
 * Android performs its own final signature and installability checks. Certificate rotation is
 * deliberately unsupported: an update must have exactly the currently installed signing set.
 */
class ApkUpdateVerifier(context: Context) {
    private val appContext = context.applicationContext

    suspend fun verify(file: File, release: UpdateRelease): Unit = withContext(Dispatchers.IO) {
        try {
            verifyApkBytes(file, release.sizeBytes, release.sha256)
            coroutineContext.ensureActive()

            if (release.packageName != appContext.packageName) {
                throw UpdateVerificationException("This update is for a different app.")
            }
            val packageManager = appContext.packageManager
            val candidate = packageManager.archiveInfo(file.absolutePath)
                ?: throw UpdateVerificationException("The downloaded file is not a valid signed Android app.")
            val installed = packageManager.installedInfo(appContext.packageName)
            val candidateVersion = candidate.fullVersionCode()
            if (candidate.packageName != appContext.packageName) {
                throw UpdateVerificationException("This update is for a different app.")
            }
            if (candidateVersion != release.versionCode.toLong() || candidate.versionName != release.versionName) {
                throw UpdateVerificationException("The downloaded app does not match the published version.")
            }
            if (candidateVersion <= installed.fullVersionCode()) {
                throw UpdateVerificationException("This update is not newer than the installed version.")
            }
            val appInfo = candidate.applicationInfo
                ?: throw UpdateVerificationException("The downloaded app information is missing.")
            if (appInfo.minSdkVersion != release.minSdk || release.minSdk !in 1..Build.VERSION.SDK_INT) {
                throw UpdateVerificationException("This update is not compatible with this Android version.")
            }
            // Phone and Wear use the same package and signing key for Data Layer communication.
            // Package/signature checks alone would incorrectly accept a watch APK on the phone.
            if (appInfo.metaData?.getString("gradey.distributionTarget") != "phone") {
                throw UpdateVerificationException("This update is not the Gradey phone app.")
            }
            if (!hasMatchingSigningCertificates(installed.activeCertificates(), candidate.activeCertificates())) {
                throw UpdateVerificationException("This update was not signed with this app's signing key.")
            }
            coroutineContext.ensureActive()
        } catch (error: CancellationException) {
            throw error
        } catch (error: UpdateVerificationException) {
            throw error
        } catch (error: Exception) {
            throw UpdateVerificationException("The downloaded update could not be verified. Please download it again.", error)
        }
    }
}

/** Stream and count independently of File.length(), rejecting partial, oversized, or altered bytes. */
internal suspend fun verifyApkBytes(file: File, expectedBytes: Long, expectedSha256: String) {
    coroutineContext.ensureActive()
    if (expectedBytes !in 1..UpdateReleaseParser.MAX_APK_BYTES ||
        !expectedSha256.matches(Regex("[0-9a-fA-F]{64}"))
    ) {
        throw UpdateVerificationException("The published update information is invalid.")
    }
    if (!file.isFile || file.length() != expectedBytes) {
        throw UpdateVerificationException("The downloaded update is incomplete. Please download it again.")
    }
    val digest = MessageDigest.getInstance("SHA-256")
    var count = 0L
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            coroutineContext.ensureActive()
            val read = input.read(buffer)
            if (read == -1) break
            count += read
            if (count > expectedBytes) {
                throw UpdateVerificationException("The downloaded update has an unexpected size. Please download it again.")
            }
            digest.update(buffer, 0, read)
        }
    }
    if (count != expectedBytes) {
        throw UpdateVerificationException("The downloaded update is incomplete. Please download it again.")
    }
    val actualSha256 = digest.digest().toHex()
    if (!actualSha256.equals(expectedSha256, ignoreCase = true)) {
        throw UpdateVerificationException("The downloaded update failed its integrity check. Please download it again.")
    }
}

/** Compare the complete active signer sets; accepting just one common certificate is insufficient. */
internal fun hasMatchingSigningCertificates(installed: List<ByteArray>, candidate: List<ByteArray>): Boolean {
    if (installed.isEmpty() || candidate.isEmpty() || installed.any { it.isEmpty() } || candidate.any { it.isEmpty() }) {
        return false
    }
    fun List<ByteArray>.digests(): Set<String> = map {
        MessageDigest.getInstance("SHA-256").digest(it).toHex()
    }.toSet()
    return installed.digests() == candidate.digests()
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

@Suppress("DEPRECATION") // API 26–27 lack SigningInfo; API 26–32 lack PackageInfoFlags.
private fun PackageManager.archiveInfo(path: String): PackageInfo? {
    val flags = PackageManager.GET_META_DATA or if (Build.VERSION.SDK_INT >= 28) {
        PackageManager.GET_SIGNING_CERTIFICATES
    } else {
        PackageManager.GET_SIGNATURES
    }
    return if (Build.VERSION.SDK_INT >= 33) {
        getPackageArchiveInfo(path, PackageManager.PackageInfoFlags.of(flags.toLong()))
    } else {
        getPackageArchiveInfo(path, flags)
    }
}

@Suppress("DEPRECATION")
private fun PackageManager.installedInfo(packageName: String): PackageInfo {
    val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
    return if (Build.VERSION.SDK_INT >= 33) {
        getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
    } else {
        getPackageInfo(packageName, flags)
    }
}

@Suppress("DEPRECATION")
private fun PackageInfo.activeCertificates(): List<ByteArray> = if (Build.VERSION.SDK_INT >= 28) {
    signingInfo?.apkContentsSigners?.map { it.toByteArray() }.orEmpty()
} else {
    signatures?.map { it.toByteArray() }.orEmpty()
}

@Suppress("DEPRECATION")
private fun PackageInfo.fullVersionCode(): Long = if (Build.VERSION.SDK_INT >= 28) longVersionCode else versionCode.toLong()
