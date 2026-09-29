package com.bukovinafilip.gradey.network

import com.bukovinafilip.gradey.model.GradeyAccount
import com.bukovinafilip.gradey.model.GradeyAuthSession
import com.bukovinafilip.gradey.model.NotificationPreferences
import com.bukovinafilip.gradey.model.StoredSession
import com.bukovinafilip.gradey.model.SchoolProvider
import com.bukovinafilip.gradey.model.EduPageSessionData
import com.bukovinafilip.gradey.model.BakalariCredentials
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SupabaseAccountClientTest {
    private lateinit var server: MockWebServer
    private lateinit var config: SupabaseConfiguration
    private val auth = GradeyAuthSession("user-token", account = GradeyAccount("owner-1"))

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        config = SupabaseConfiguration(server.url("/").toString(), "public-key")
    }

    @After fun tearDown() { server.shutdown() }

    @Test fun listUsesOwnerAndProviderFiltersAndDecodesDatabaseColumns() = runTest {
        server.enqueue(MockResponse().setBody("""[{"id":"school-1","provider":"bakalari","display_name":"My school","school_name":"Gymnasium","status":"active","notifications_enabled":false}]"""))
        val accounts = SupabaseAccountClient(config).linkedAccounts(auth)
        assertEquals("My school", accounts.single().displayName)
        assertFalse(accounts.single().notificationsEnabled)
        val request = server.takeRequest()
        assertEquals("Bearer user-token", request.getHeader("Authorization"))
        assertEquals("eq.owner-1", request.requestUrl?.queryParameter("user_id"))
        assertEquals("in.(bakalari,eduPage)", request.requestUrl?.queryParameter("provider"))
        assertFalse(request.requestUrl?.queryParameter("select").orEmpty().contains("secret"))
    }

    @Test fun historyIgnoresBaselineAndAddsChangesPerSubject() = runTest {
        server.enqueue(MockResponse().setBody("""{"events":[
            {"subject_id":"math","subject_name":"Math","event_type":"baseline","average_delta":null,"mark_count_delta":0},
            {"subject_id":"math","subject_name":"Math","event_type":"changed","average_delta":-0.3,"mark_count_delta":1},
            {"subject_id":"math","subject_name":"Math","event_type":"changed","average_delta":0.1,"mark_count_delta":2},
            {"subject_id":"art","event_type":"baseline","mark_count_delta":0}
        ],"recentNewMarkEvents":[]}"""))
        val trends = SupabaseAccountClient(config).gradeHistory(auth, "school-1")
        assertEquals(1, trends.size)
        assertEquals(-0.2, trends.single().averageDelta, 0.00001)
        assertEquals(3, trends.single().markCountDelta)
        val request = server.takeRequest()
        assertEquals("school-1", GradeyJson.parseToJsonElement(request.body.readUtf8()).jsonObject["linked_account_id"]?.jsonPrimitive?.content)
    }

    @Test fun parentHistoryRestrictionIsShownInsteadOfMixingSiblingGrades() = runTest {
        server.enqueue(MockResponse().setBody("""{"events":[],"unavailableReason":"Grade history is unavailable for EduPage parent accounts."}"""))
        try {
            SupabaseAccountClient(config).gradeHistory(auth, "parent-account")
            fail("The unsupported response must be explained")
        } catch (error: IllegalStateException) {
            assertEquals("Grade history is unavailable for EduPage parent accounts.", error.message)
        }
    }

    @Test fun activationRestoresLinkedMetadataFromAuthoritativeAccount() = runTest {
        server.enqueue(MockResponse().setBody("""{
            "account":{"id":"school-1","provider":"bakalari","displayName":"My school","schoolName":"Gymnasium"},
            "token_payload":{"accessToken":"school-token","refreshToken":"refresh","tokenType":"Bearer","expiresAtEpochMillis":123,"baseURL":"https://school.example/","provider":"bakalari"}
        }"""))
        val session = SupabaseAccountClient(config).activateSchoolAccount(auth, "school-1")
        assertEquals("school-1", session.linkedAccountID)
        assertEquals("Gymnasium", session.linkedAccountSchoolName)
        assertEquals("school-token", session.accessToken)
        assertEquals("/functions/v1/activate-school-account", server.takeRequest().path)
    }

    @Test fun preferenceTimesUseBackendMinuteContract() = runTest {
        server.enqueue(MockResponse().setBody("{}"))
        SupabaseDevicePushTokenClient(config).updateNotificationPreferences(
            NotificationPreferences(quietHoursEnabled = true, quietHoursStart = "21:45", quietHoursEnd = "07:15"), auth,
        )
        val body = GradeyJson.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals("1305", body["quietHoursStartMinute"]?.jsonPrimitive?.content)
        assertEquals("435", body["quietHoursEndMinute"]?.jsonPrimitive?.content)
        assertNull(body["quietHoursStart"])
    }

    @Test fun activationAcceptsIOSPayloadWithoutLocalOnlyFields() = runTest {
        server.enqueue(MockResponse().setBody("""{
            "account":{"id":"school-ios","provider":"eduPage","displayName":"Student"},
            "token_payload":{"accessToken":"cookie","tokenType":"Cookie","baseURL":"https://school.edupage.org/","provider":"eduPage","eduPage":{"sessionID":"cookie","username":"parent","gsecHash":"hash","userID":"parent-id","linkedStudents":[],"subjects":[]}}
        }"""))
        val session = SupabaseAccountClient(config).activateSchoolAccount(auth, "school-ios")
        assertEquals("", session.eduPage?.password)
        assertEquals(Long.MAX_VALUE, session.expiresAtEpochMillis)
        assertEquals("", session.refreshToken)
    }

    @Test fun activationAcceptsSanitizedBakalariPayloadWithISOExpiry() = runTest {
        server.enqueue(MockResponse().setBody("""{
            "account":{"id":"school-1","provider":"bakalari","displayName":"Student"},
            "token_payload":{"accessToken":"school-token","refreshToken":"school-refresh","tokenType":"Bearer","expiresAt":"2030-01-01T00:00:00Z","baseURL":"https://school.example/","provider":"bakalari"}
        }"""))
        val session = SupabaseAccountClient(config).activateSchoolAccount(auth, "school-1")
        assertEquals(java.time.Instant.parse("2030-01-01T00:00:00Z").toEpochMilli(), session.expiresAtEpochMillis)
        assertEquals("school-refresh", session.refreshToken)
        assertNull(session.bakalari)
    }

    @Test fun linkDoesNotUploadBakalariPasswordOrLocalMetadata() = runTest {
        server.enqueue(MockResponse().setBody("""{"id":"school-1","provider":"bakalari","displayName":"Student"}"""))
        val pollingClient = object : com.bukovinafilip.gradey.domain.BakalariClient by DemoBakalariClient() {
            override suspend fun login(baseURL: String, username: String, password: String) =
                com.bukovinafilip.gradey.model.LoginResponse("polling-token", "polling-refresh", "Bearer", 3600)
        }
        val now = java.time.Instant.parse("2029-12-31T23:00:00Z").toEpochMilli()
        SupabaseAccountClient(config, bakalariClient = pollingClient, nowProvider = { now }).linkSchoolAccount(auth, StoredSession(
            accessToken = "school-token", refreshToken = "school-refresh", tokenType = "Bearer",
            expiresAtEpochMillis = java.time.Instant.parse("2030-01-01T00:00:00Z").toEpochMilli(),
            baseURL = "https://school.example/", bakalari = BakalariCredentials("student", "local-password"),
            linkedAccountID = "local-metadata",
        ), "Student")
        val body = server.takeRequest().body.readUtf8()
        val request = GradeyJson.parseToJsonElement(body).jsonObject
        val payload = request.getValue("token_payload").jsonObject
        assertFalse(body.contains("local-password"))
        assertFalse(body.contains("local-metadata"))
        assertFalse(payload.containsKey("bakalari"))
        assertFalse(payload.containsKey("expiresAtEpochMillis"))
        assertEquals("student", request["provider_user_id"]?.jsonPrimitive?.content)
        assertEquals("polling-token", payload["accessToken"]?.jsonPrimitive?.content)
        assertEquals("polling-refresh", payload["refreshToken"]?.jsonPrimitive?.content)
        assertEquals("2029-12-31T23:00:00Z", payload["pollingSessionEstablishedAt"]?.jsonPrimitive?.content)
        assertEquals("2030-01-01T00:00:00Z", payload["expiresAt"]?.jsonPrimitive?.content)
    }

    @Test fun linkDoesNotUploadEduPagePasswordOrLocalMetadata() = runTest {
        server.enqueue(MockResponse().setBody("""{"id":"school-1","provider":"eduPage","displayName":"Student"}"""))
        SupabaseAccountClient(config).linkSchoolAccount(auth, StoredSession(
            accessToken = "cookie", refreshToken = "", tokenType = "Cookie", expiresAtEpochMillis = Long.MAX_VALUE,
            baseURL = "https://school.edupage.org/", provider = SchoolProvider.EDU_PAGE,
            eduPage = EduPageSessionData("cookie", "parent", "local-password", "hash", "parent-id"),
            linkedAccountID = "local-metadata",
        ), "Student")
        val body = server.takeRequest().body.readUtf8()
        assertFalse(body.contains("local-password"))
        assertFalse(body.contains("local-metadata"))
        assertFalse(body.contains("expiresAtEpochMillis"))
        assertTrue(body.contains("token_payload"))
    }

    @Test fun invalidQuietHourIsRejectedBeforeNetworkRequest() = runTest {
        try {
            SupabaseDevicePushTokenClient(config).updateNotificationPreferences(NotificationPreferences(quietHoursStart = "25:00"), auth)
            fail("Invalid time must not be sent")
        } catch (_: IllegalArgumentException) { }
        assertEquals(0, server.requestCount)
    }

    @Test fun providerErrorDoesNotExposeResponseBody() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("sensitive credentials: private-school-token"))
        try {
            SupabaseAccountClient(config).linkedAccounts(auth)
            fail("Server failure must surface")
        } catch (error: GradeyApiException) {
            assertEquals(500, error.statusCode)
            assertFalse(error.message.orEmpty().contains("private-school-token"))
        }
    }

    @Test fun expiredAuthSessionRefreshesAndAcceptsBooleanMetadata() = runTest {
        var stored: GradeyAuthSession? = auth.copy(refreshToken = "refresh", expiresAtEpochMillis = 1)
        server.enqueue(MockResponse().setBody("""{"access_token":"new-token","refresh_token":"new-refresh","expires_in":3600,"user":{"id":"owner-1","email":"test@example.com","user_metadata":{"full_name":"Student","email_verified":true}}}"""))
        val repository = SupabaseGradeyAuthRepository(config, sessionStore = { stored = it }, sessionLoader = { stored })
        assertEquals("new-token", repository.bootstrapSession()?.accessToken)
        assertEquals("new-refresh", stored?.refreshToken)
        assertEquals("Student", stored?.account?.fullName)
        assertEquals("/auth/v1/token?grant_type=refresh_token", server.takeRequest().path)
        repository.bootstrapSession()
        assertEquals(1, server.requestCount)
    }

    @Test fun readingCachedIdentityDoesNotAttemptToRefreshOrAuthorizeAnything() = runTest {
        val stored = auth.copy(refreshToken = "refresh", expiresAtEpochMillis = 1)
        val repository = SupabaseGradeyAuthRepository(config, sessionStore = {}, sessionLoader = { stored })
        assertEquals("owner-1", repository.cachedSession()?.account?.id)
        assertEquals(0, server.requestCount)
    }

    @Test fun revokedRefreshTokenClearsSessionInsteadOfReturningExpiredAccessToken() = runTest {
        var stored: GradeyAuthSession? = auth.copy(refreshToken = "revoked", expiresAtEpochMillis = 1)
        server.enqueue(MockResponse().setResponseCode(400).setBody("invalid refresh token"))
        val repository = SupabaseGradeyAuthRepository(config, sessionStore = { stored = it }, sessionLoader = { stored })
        assertNull(repository.bootstrapSession())
        assertNull(stored)
    }

    @Test fun publicAPIKeysAreNeverSentAsUserBearerTokens() = runTest {
        server.enqueue(MockResponse().setBody("""{"access_token":"signed-in-token","expires_in":3600,"user":{"id":"owner-1"}}"""))
        val repository = SupabaseGradeyAuthRepository(config.copy(anonKey = "sb_publishable_public"), sessionStore = {}, sessionLoader = { null })
        repository.signInWithGoogle("google-id-token")
        val request = server.takeRequest()
        assertEquals("sb_publishable_public", request.getHeader("apikey"))
        assertNull(request.getHeader("Authorization"))
    }
    @Test fun reconnectKeepsIDAndUsesSelectedStudentIdentityWithoutUploadingPasswords() = runTest {
        server.enqueue(MockResponse().setBody("""{"id":"school-1","provider":"eduPage","displayName":"Student"}"""))
        val session = StoredSession("cookie", "", "Cookie", Long.MAX_VALUE, "https://school.edupage.org/", SchoolProvider.EDU_PAGE,
            eduPage = EduPageSessionData("cookie", "parent", "local-password", "hash", "parent-id", activeStudent = com.bukovinafilip.gradey.model.SchoolStudentProfile("student-2", "Student")))
        SupabaseAccountClient(config).reconnectSchoolAccount(auth, "school-1", session, "Student")
        val request = server.takeRequest()
        assertEquals("/functions/v1/relink-school-account", request.path)
        val body = request.body.readUtf8()
        assertFalse(body.contains("local-password"))
        val payload = GradeyJson.parseToJsonElement(body).jsonObject
        assertEquals("school-1", payload["id"]?.jsonPrimitive?.content)
        assertEquals("student-2", payload["provider_user_id"]?.jsonPrimitive?.content)
    }

    @Test fun linkedNotificationPreferenceUsesFunctionContract() = runTest {
        server.enqueue(MockResponse().setBody("""{"id":"school-1","provider":"bakalari","displayName":"Student","notificationsEnabled":false}"""))
        assertFalse(SupabaseAccountClient(config).updateNotificationsEnabled(auth, "school-1", false).notificationsEnabled)
        val request = server.takeRequest()
        assertEquals("/functions/v1/update-linked-account-preferences", request.path)
        assertEquals("""{"id":"school-1","notificationsEnabled":false}""", request.body.readUtf8())
    }

    @Test fun cloudSyncRejectsSharedRefreshTokenBeforeUploadingIt() = runTest {
        val schoolClient = object : com.bukovinafilip.gradey.domain.BakalariClient by DemoBakalariClient() {
            override suspend fun login(baseURL: String, username: String, password: String) =
                com.bukovinafilip.gradey.model.LoginResponse("token", "shared-refresh", "Bearer", 3600)
        }
        val school = StoredSession("token", "shared-refresh", "Bearer", 1_000, "https://school.example/", bakalari = BakalariCredentials("student", "password"))
        val result = runCatching { SupabaseAccountClient(config, bakalariClient = schoolClient).linkSchoolAccount(auth, school, "Student") }
        assertTrue(result.exceptionOrNull() is IllegalStateException)
        assertEquals(0, server.requestCount)
    }

    @Test fun historyProvidesActualEndpointsForAIWithoutInventingBaselineValues() = runTest {
        server.enqueue(MockResponse().setBody("""{"events":[
            {"subject_id":"math","event_type":"baseline","average_value":2.5,"mark_count":3},
            {"subject_id":"math","event_type":"changed","average_value":2.0,"mark_count":4,"average_delta":-0.5,"mark_count_delta":1}
        ]}"""))
        val trend = SupabaseAccountClient(config).gradeHistory(auth, "school-1").single()
        assertEquals(2.5, trend.firstAverage!!, 0.001)
        assertEquals(2.0, trend.latestAverage!!, 0.001)
        assertEquals(3, trend.firstMarkCount)
        assertEquals(4, trend.latestMarkCount)
    }

}
