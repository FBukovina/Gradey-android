package com.bukovinafilip.gradey.updates

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ApkUpdateVerifierTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun acceptsOnlyMatchingLengthAndDigest() = runBlocking {
        val bytes = ByteArray(70_000) { (it % 251).toByte() }
        val file = file(bytes)
        verifyApkBytes(file, bytes.size.toLong(), digest(bytes).uppercase())
    }

    @Test fun rejectsChangedBytesEvenWhenLengthMatches() = runBlocking {
        val original = byteArrayOf(0, 1, 2, 3)
        val changed = original.clone().apply { this[2] = 9 }
        assertRejected { verifyApkBytes(file(changed), original.size.toLong(), digest(original)) }
    }

    @Test fun rejectsTruncatedAndOversizedFiles() = runBlocking {
        val original = byteArrayOf(0, 1, 2, 3)
        assertRejected { verifyApkBytes(file(original.copyOf(3)), 4, digest(original)) }
        assertRejected { verifyApkBytes(file(original.copyOf(5)), 4, digest(original)) }
    }

    @Test fun rejectsMissingFileAndMalformedMetadata() = runBlocking {
        assertRejected { verifyApkBytes(File(folder.root, "missing.apk"), 4, "0".repeat(64)) }
        assertRejected { verifyApkBytes(file(byteArrayOf(1)), 0, "0".repeat(64)) }
        assertRejected { verifyApkBytes(file(byteArrayOf(1)), 1, "not-a-digest") }
    }

    @Test fun matchesTheCompleteSignerSetWithoutDependingOnOrder() {
        val first = byteArrayOf(1, 2, 3)
        val second = byteArrayOf(4, 5, 6)
        assertTrue(hasMatchingSigningCertificates(listOf(first, second), listOf(second.clone(), first.clone())))
        assertFalse(hasMatchingSigningCertificates(listOf(first, second), listOf(first)))
        assertFalse(hasMatchingSigningCertificates(listOf(first), listOf(first, second)))
        assertFalse(hasMatchingSigningCertificates(listOf(first), listOf(second)))
    }

    @Test fun rejectsMissingAndEmptySigningCertificates() {
        assertFalse(hasMatchingSigningCertificates(emptyList(), emptyList()))
        assertFalse(hasMatchingSigningCertificates(listOf(byteArrayOf(1)), emptyList()))
        assertFalse(hasMatchingSigningCertificates(listOf(byteArrayOf()), listOf(byteArrayOf())))
    }

    private fun file(bytes: ByteArray): File = folder.newFile().apply { writeBytes(bytes) }

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private suspend fun assertRejected(action: suspend () -> Unit) {
        try {
            action()
            fail("Expected update verification to reject the file")
        } catch (_: UpdateVerificationException) {
            // Expected; other exception types must still fail the test.
        }
    }
}
