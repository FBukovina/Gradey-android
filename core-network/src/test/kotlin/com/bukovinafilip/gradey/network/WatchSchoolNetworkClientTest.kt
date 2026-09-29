package com.bukovinafilip.gradey.network

import com.bukovinafilip.gradey.domain.*
import com.bukovinafilip.gradey.model.*
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class WatchSchoolNetworkClientTest {
    private val now = 1_800_000_000_000L
    private val credentials = WearSchoolCredentials(SchoolProvider.BAKALARI, "https://school.example", "fixture-student", "fixture-password", "student-1")
    private class Baka : BakalariClient by DemoBakalariClient() {
        var logins = 0
        var userID = "student-1"
        var timetableCalls = 0
        val redeemed = mutableListOf<String>()
        var rejectRefresh = false
        override suspend fun login(baseURL: String, username: String, password: String): LoginResponse { logins++; return LoginResponse("watch-access-$logins", "watch-refresh-$logins", "Bearer", 3600) }
        override suspend fun refreshToken(baseURL: String, refreshToken: String): LoginResponse {
            redeemed += refreshToken
            if (rejectRefresh) throw BakalariApiException(401, "redacted", AccountFailureReason.SCHOOL_SESSION_EXPIRED)
            return LoginResponse("watch-renewed", "watch-rotated", "Bearer", 3600)
        }
        override suspend fun fetchUser(baseURL: String, accessToken: String) = UserResponse("Fixture", userUID = userID)
        override suspend fun fetchTimetable(baseURL: String, accessToken: String, date: String): TimetableResponse { timetableCalls++; return DemoData.timetableResponse(date) }
    }
    private class Edu : EduPageClient {
        val student = SchoolStudentProfile("student-1", "Fixture")
        var approval = false
        var selected: String? = null
        var timetableCalls = 0
        var needsVerification = true
        private fun session() = EduPageSessionData("watch-cookie", "fixture-parent", "fixture-password", "watch-gsec", "parent-1", linkedStudents = listOf(student))
        override suspend fun beginLogin(baseURL: String, username: String, password: String): SchoolLoginStep = if (needsVerification) SchoolLoginStep.TwoFactor() else SchoolLoginStep.StudentSelection(listOf(student))
        override suspend fun completeTwoFactor(code: String): SchoolLoginStep = SchoolLoginStep.StudentSelection(listOf(student))
        override suspend fun completeApprovedTwoFactor(): SchoolLoginStep = SchoolLoginStep.StudentSelection(listOf(student))
        override suspend fun isTwoFactorConfirmed() = approval
        override suspend fun resendTwoFactorNotification() {}
        override suspend fun selectStudent(studentID: String): EduPageSessionData { selected = studentID; return session().copy(activeStudent = student) }
        override suspend fun switchStudent(studentID: String, session: EduPageSessionData, baseURL: String) = error("unused")
        override suspend fun restore(stored: EduPageSessionData, baseURL: String) = stored
        override suspend fun fetchMarks(baseURL: String, session: EduPageSessionData) = error("unused")
        override suspend fun fetchAbsences(baseURL: String, session: EduPageSessionData) = error("unused")
        override suspend fun fetchUser(baseURL: String, session: EduPageSessionData) = UserResponse("Fixture", userUID = session.activeStudent?.id)
        override suspend fun fetchTimetable(baseURL: String, session: EduPageSessionData, weekStart: String): TimetableResponse { timetableCalls++; return DemoData.timetableResponse(weekStart) }
    }
    @Test fun newWatchStartsItsOwnFamilyAndOnlyRedeemsItsStoredRefreshToken() = runTest {
        val baka = Baka(); val client = WatchSchoolNetworkClient(baka, Edu()) { now }
        val first = client.refresh(credentials, null) as WatchSchoolRefresh.Loaded
        assertThat(baka.logins).isEqualTo(1)
        assertThat(first.session.refreshToken).isEqualTo("watch-refresh-1")
        val renewed = client.refresh(credentials, first.session.copy(expiresAtEpochMillis = now)) as WatchSchoolRefresh.Loaded
        assertThat(baka.redeemed).containsExactly("watch-refresh-1")
        assertThat(renewed.session.refreshToken).isEqualTo("watch-rotated")
        assertThat(baka.logins).isEqualTo(1)
    }
    @Test fun revokedWatchFamilyRelogsIndependentlyWithExplicitlyProvisionedCredentials() = runTest {
        val baka = Baka(); val client = WatchSchoolNetworkClient(baka, Edu()) { now }
        val first = client.refresh(credentials, null) as WatchSchoolRefresh.Loaded
        baka.rejectRefresh = true
        val renewed = client.refresh(credentials, first.session.copy(expiresAtEpochMillis = now)) as WatchSchoolRefresh.Loaded
        assertThat(baka.logins).isEqualTo(2)
        assertThat(renewed.session.refreshToken).isEqualTo("watch-refresh-2")
    }
    @Test fun canonicalIdentityMismatchStopsBeforeFetchingTimetable() = runTest {
        val baka = Baka().apply { userID = "different-student" }
        val error = runCatching { WatchSchoolNetworkClient(baka, Edu()) { now }.refresh(credentials, null) }.exceptionOrNull()
        assertThat((error as WearSchoolException).reason).isEqualTo(WearSchoolFailure.IDENTITY_MISMATCH)
        assertThat(baka.timetableCalls).isEqualTo(0)
    }
    @Test fun eduPageVerificationUsesWatchSessionAndSelectsOnlyGrantedStudent() = runTest {
        val edu = Edu(); val client = WatchSchoolNetworkClient(Baka(), edu) { now }
        val credentials = WearSchoolCredentials(SchoolProvider.EDU_PAGE, "https://fixture.edupage.org", "parent", "password", "student-1", "student-1")
        assertThat(client.refresh(credentials, null)).isEqualTo(WatchSchoolRefresh.VerificationRequired)
        val error = runCatching { client.checkApproval() }.exceptionOrNull()
        assertThat((error as WearSchoolException).reason).isEqualTo(WearSchoolFailure.NOT_APPROVED)
        edu.approval = true
        val loaded = client.checkApproval() as WatchSchoolRefresh.Loaded
        assertThat(edu.selected).isEqualTo("student-1")
        assertThat(loaded.session.eduPage?.sessionID).isEqualTo("watch-cookie")
        assertThat(edu.timetableCalls).isEqualTo(1)
    }
    @Test fun eduPageCannotChooseAnotherStudentWhenGrantedOneIsMissing() = runTest {
        val edu = Edu().apply { needsVerification = false }
        val credentials = WearSchoolCredentials(SchoolProvider.EDU_PAGE, "https://fixture.edupage.org", "parent", "password", "other", "other")
        val error = runCatching { WatchSchoolNetworkClient(Baka(), edu) { now }.refresh(credentials, null) }.exceptionOrNull()
        assertThat((error as WearSchoolException).reason).isEqualTo(WearSchoolFailure.IDENTITY_MISMATCH)
        assertThat(edu.selected).isNull()
        assertThat(edu.timetableCalls).isEqualTo(0)
    }
}
