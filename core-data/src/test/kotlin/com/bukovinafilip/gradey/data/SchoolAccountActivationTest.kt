package com.bukovinafilip.gradey.data

import com.bukovinafilip.gradey.model.*
import org.junit.Assert.*
import org.junit.Test

class SchoolAccountActivationTest {
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
}
