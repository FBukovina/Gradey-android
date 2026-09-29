package com.bukovinafilip.gradey.data

import com.bukovinafilip.gradey.model.*
import org.junit.Assert.*
import org.junit.Test

class SchoolAccountActivationTest {
    private fun bakalari() = StoredSession(
        "cloud-token", "cloud-refresh", "Bearer", 1_000,
        "https://school.example/", linkedAccountID = "linked-student",
        linkedAccountDisplayName = "Current display name", linkedAccountSchoolName = "Current school name",
    )

    private fun parent(student: String = "student1", username: String = "parent") = StoredSession(
        "cloud-cookie", "", "Cookie", Long.MAX_VALUE, "https://school.edupage.org/",
        provider = SchoolProvider.EDU_PAGE,
        eduPage = EduPageSessionData("cloud-cookie", username, "", "hash", "rodic123", activeStudent = SchoolStudentProfile(student, student)),
        linkedAccountID = "linked-parent",
    )

    @Test fun cloudParentCookieCannotBeActivatedWithoutAnOwnedLocalLogin() {
        try { schoolSessionForActivation(parent(), null); fail("Shared cloud cookie should be rejected") }
        catch (expected: IllegalStateException) { assertTrue(expected.message.orEmpty().contains("this device")) }
    }

    @Test fun parentActivationRejectsDifferentLocalChildOrIdentity() {
        for (different in listOf(parent(student = "student2"), parent(username = "different-parent"), parent().copy(baseURL = "https://other.edupage.org/"))) {
            try { schoolSessionForActivation(parent(), different); fail("Wrong local identity should be rejected") }
            catch (_: IllegalStateException) { }
        }
    }

    @Test fun matchingParentActivationKeepsDeviceCookieAndPassword() {
        val ownSession = parent().copy(accessToken = "device-cookie", eduPage = parent().eduPage!!.copy(sessionID = "device-cookie", password = "local-password"))
        val activated = schoolSessionForActivation(parent(), ownSession)
        assertEquals("device-cookie", activated.accessToken)
        assertEquals("local-password", activated.eduPage?.password)
    }

    @Test fun sanitizedBakalariActivationKeepsNewerLocalTokensAndCredentials() {
        val cloud = bakalari()
        val local = cloud.copy(
            accessToken = "newer-device-token", refreshToken = "newer-device-refresh", expiresAtEpochMillis = 2_000,
            baseURL = cloud.baseURL.trimEnd('/'), bakalari = BakalariCredentials("student", "local-password"),
            linkedAccountDisplayName = "Old name", linkedAccountSchoolName = "Old school name",
        )
        val activated = schoolSessionForActivation(cloud, local)
        assertEquals("newer-device-token", activated.accessToken)
        assertEquals("newer-device-refresh", activated.refreshToken)
        assertEquals(2_000, activated.expiresAtEpochMillis)
        assertEquals(local.bakalari, activated.bakalari)
        assertEquals(cloud.linkedAccountDisplayName, activated.linkedAccountDisplayName)
        assertEquals(cloud.linkedAccountSchoolName, activated.linkedAccountSchoolName)
    }

    @Test fun sanitizedBakalariActivationKeepsNewerCloudTokensAndLocalCredentials() {
        val cloud = bakalari()
        val local = cloud.copy(
            accessToken = "older-device-token", refreshToken = "older-device-refresh", expiresAtEpochMillis = 500,
            bakalari = BakalariCredentials("student", "local-password"),
        )
        val activated = schoolSessionForActivation(cloud, local)
        assertEquals(cloud.accessToken, activated.accessToken)
        assertEquals(cloud.refreshToken, activated.refreshToken)
        assertEquals(cloud.expiresAtEpochMillis, activated.expiresAtEpochMillis)
        assertEquals(local.bakalari, activated.bakalari)
    }

    @Test fun bakalariActivationNeverMergesDifferentLinkedAccountsSchoolsOrProviders() {
        val cloud = bakalari()
        val local = cloud.copy(expiresAtEpochMillis = 2_000, bakalari = BakalariCredentials("student", "private-password"))
        val mismatches = listOf(
            local.copy(linkedAccountID = "another-account"),
            local.copy(linkedAccountID = null),
            local.copy(baseURL = "https://other.example/"),
            local.copy(baseURL = "https://school.example/another-installation/"),
            local.copy(provider = SchoolProvider.EDU_PAGE),
        )
        mismatches.forEach { assertEquals(cloud, schoolSessionForActivation(cloud, it)) }
        assertEquals(cloud, schoolSessionForActivation(cloud, null))
        val unlinked = cloud.copy(linkedAccountID = null)
        assertEquals(unlinked, schoolSessionForActivation(unlinked, local.copy(linkedAccountID = null)))
        val identifiedCloud = cloud.copy(bakalari = BakalariCredentials("other-student", "other-password"))
        assertEquals(identifiedCloud, schoolSessionForActivation(identifiedCloud, local))
    }
    @Test fun dedicatedPollingActivationKeepsEvenExpiredDeviceRefreshToken() {
        val cloud = bakalari().copy(refreshToken = "")
        val local = bakalari().copy(accessToken = "device", refreshToken = "device-refresh", expiresAtEpochMillis = 0)
        val activated = schoolSessionForActivation(cloud, local)
        assertEquals("device", activated.accessToken)
        assertEquals("device-refresh", activated.refreshToken)
    }

    @Test fun newDeviceMustAuthenticateInsteadOfAdoptingUnrefreshablePollingSession() {
        val cloud = bakalari().copy(refreshToken = "")
        assertTrue(runCatching { schoolSessionForActivation(cloud, null) }.exceptionOrNull() is IllegalStateException)
    }

}
