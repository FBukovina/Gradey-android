package com.bukovinafilip.gradey.network

import com.bukovinafilip.gradey.domain.GradeHistoryTrend
import com.bukovinafilip.gradey.domain.BakalariClient
import com.bukovinafilip.gradey.model.GradeyAuthSession
import com.bukovinafilip.gradey.model.LinkedSchoolAccount
import com.bukovinafilip.gradey.model.NotificationPreferences
import com.bukovinafilip.gradey.model.SchoolProvider
import com.bukovinafilip.gradey.model.StoredSession
import com.bukovinafilip.gradey.model.EduPageSessionData
import com.bukovinafilip.gradey.model.EduPageSubjectProfile
import com.bukovinafilip.gradey.model.SchoolStudentProfile
import com.bukovinafilip.gradey.model.UserResponse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** Contracts match the Edge Functions and owner-scoped tables shipped in supabase/. */
class SupabaseAccountClient(
    private val configuration: SupabaseConfiguration,
    private val okHttpClient: OkHttpClient = OkHttpClient(),
    private val bakalariClient: BakalariClient = BakalariNetworkClient(),
    private val nowProvider: () -> Long = System::currentTimeMillis,
) {
    suspend fun linkedAccounts(session: GradeyAuthSession): List<LinkedSchoolAccount> {
        val rows: List<LinkedAccountRow> = get(
            "linked_accounts", session,
            mapOf(
                "select" to "id,provider,display_name,school_name,status,notifications_enabled,provider_user_id,base_url,action_required_reason",
                "user_id" to "eq.${session.account.id}",
                "provider" to "in.(bakalari,eduPage)",
                "order" to "created_at.asc",
            ),
        )
        return rows.map { it.toAccount() }
    }

    suspend fun linkSchoolAccount(auth: GradeyAuthSession, session: StoredSession, displayName: String, user: UserResponse? = null): LinkedSchoolAccount =
        post("link-school-account", auth, LinkSchoolRequest(
            provider = session.provider,
            providerUserID = session.eduPage?.activeStudent?.id ?: user?.userUID ?: session.eduPage?.userID ?: session.bakalari?.username,
            baseURL = session.baseURL,
            displayName = user?.fullName?.takeIf { it.isNotBlank() } ?: displayName,
            schoolName = user?.schoolName ?: session.linkedAccountSchoolName ?: session.eduPage?.schoolName,
            tokenPayload = schoolPayloadForPolling(session),
        ))

    suspend fun reconnectSchoolAccount(auth: GradeyAuthSession, accountID: String, session: StoredSession, displayName: String, user: UserResponse? = null): LinkedSchoolAccount {
        require(accountID.isNotBlank()) { "Select a school account to reconnect." }
        return post("relink-school-account", auth, LinkSchoolRequest(
            id = accountID,
            provider = session.provider,
            providerUserID = session.eduPage?.activeStudent?.id ?: user?.userUID ?: session.eduPage?.userID ?: session.bakalari?.username,
            baseURL = session.baseURL,
            displayName = user?.fullName?.takeIf { it.isNotBlank() } ?: displayName,
            schoolName = user?.schoolName ?: session.linkedAccountSchoolName ?: session.eduPage?.schoolName,
            tokenPayload = schoolPayloadForPolling(session),
        ))
    }

    suspend fun updateNotificationsEnabled(auth: GradeyAuthSession, accountID: String, enabled: Boolean): LinkedSchoolAccount {
        require(accountID.isNotBlank()) { "Select a school account." }
        return post("update-linked-account-preferences", auth, LinkedAccountPreferencesRequest(accountID, enabled))
    }

    suspend fun activateSchoolAccount(auth: GradeyAuthSession, accountID: String): StoredSession {
        val response: ActivatedAccountResponse = post("activate-school-account", auth, AccountIDRequest(accountID))
        check(response.account.id == accountID && response.account.provider == response.tokenPayload.provider) {
            "Gradey returned a different school account. Refresh your accounts and try again."
        }
        return response.tokenPayload.toSession().copy(
            provider = response.account.provider,
            linkedAccountID = response.account.id,
            linkedAccountDisplayName = response.account.displayName,
            linkedAccountSchoolName = response.account.schoolName,
        )
    }

    suspend fun unlinkAccount(auth: GradeyAuthSession, accountID: String) {
        post<AccountIDRequest, kotlinx.serialization.json.JsonObject>("unlink-account", auth, AccountIDRequest(accountID))
    }

    suspend fun exportAccountData(auth: GradeyAuthSession): String {
        val export: kotlinx.serialization.json.JsonObject = post("request-data-export", auth, kotlinx.serialization.json.JsonObject(emptyMap()))
        return GradeyJson.encodeToString(export)
    }

    suspend fun deleteAccount(auth: GradeyAuthSession) {
        post<kotlinx.serialization.json.JsonObject, kotlinx.serialization.json.JsonObject>("delete-account", auth, kotlinx.serialization.json.JsonObject(emptyMap()))
    }

    suspend fun gradeHistory(auth: GradeyAuthSession, accountID: String?): List<GradeHistoryTrend> {
        val response: GradeHistoryResponse = post("grade-history", auth, GradeHistoryRequest(accountID))
        response.unavailableReason?.let { throw IllegalStateException(it) }
        return response.events.groupBy { it.subjectID }.mapNotNull { (id, events) ->
            val changes = events.filter { it.eventType == "changed" }
            if (changes.isEmpty()) return@mapNotNull null
            val first = events.first()
            val last = events.last()
            GradeHistoryTrend(
                subjectID = id,
                subjectName = last.subjectName ?: last.subjectAbbrev ?: id,
                averageDelta = changes.sumOf { it.averageDelta ?: 0.0 },
                markCountDelta = changes.sumOf { it.markCountDelta },
                firstAverage = first.averageValue?.let { average -> if (first.eventType == "changed") first.averageDelta?.let { average - it } else average },
                latestAverage = last.averageValue,
                firstMarkCount = first.markCount?.let { if (first.eventType == "changed") it - first.markCountDelta else it },
                latestMarkCount = last.markCount,
            )
        }
    }

    suspend fun notificationPreferences(auth: GradeyAuthSession): NotificationPreferences? {
        val rows: List<NotificationPreferenceRow> = get(
            "notification_preferences", auth,
            mapOf("select" to "new_marks_enabled,quiet_hours_enabled,quiet_hours_start_minute,quiet_hours_end_minute,lock_screen_detail,quiet_hours_time_zone", "user_id" to "eq.${auth.account.id}", "limit" to "1"),
        )
        return rows.firstOrNull()?.let {
            NotificationPreferences(it.newMarksEnabled, it.quietHoursEnabled, minutesToTime(it.quietHoursStartMinute), minutesToTime(it.quietHoursEndMinute), it.lockScreenDetail, it.quietHoursTimeZoneIdentifier)
        }
    }

    private suspend fun schoolPayloadForPolling(session: StoredSession): SchoolSecretPayload {
        ensureConfigured()
        if (session.provider != SchoolProvider.BAKALARI) return SchoolSecretPayload.fromSession(session)
        val credentials = session.bakalari
        check(credentials != null && credentials.username.isNotBlank() && credentials.password.isNotEmpty()) {
            "Sign in to Bakaláři on this device to enable account sync."
        }
        // The server polls independently. Sharing the device's rotating refresh token would
        // cause either the app or the background poller to invalidate the other's session.
        val response = bakalariClient.login(session.baseURL, credentials.username, credentials.password)
        check(response.accessToken.isNotBlank() && response.refreshToken.isNotBlank() && response.refreshToken != session.refreshToken) {
            "Bakaláři could not create a separate session for account sync. Sign in again."
        }
        val now = nowProvider()
        return SchoolSecretPayload.fromSession(session.copy(
            accessToken = response.accessToken,
            refreshToken = response.refreshToken,
            tokenType = response.tokenType,
            expiresAtEpochMillis = now + response.expiresIn * 1000L,
        )).copy(pollingSessionEstablishedAt = java.time.Instant.ofEpochMilli(now).toString())
    }

    private suspend inline fun <reified T> get(table: String, session: GradeyAuthSession, parameters: Map<String, String>): T {
        ensureConfigured()
        val url = configuration.url.appendPath("rest/v1/$table").toHttpUrl().newBuilder().apply {
            parameters.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build()
        val request = authorize(Request.Builder().url(url).get(), session).build()
        return decodeSupabaseResponse(safeSupabaseResponse(okHttpClient, request))
    }

    private suspend inline fun <reified Body, reified Result> post(function: String, session: GradeyAuthSession, body: Body): Result {
        ensureConfigured()
        val request = authorize(Request.Builder()
            .url(configuration.url.appendPath("functions/v1/$function"))
            .post(jsonBody(GradeyJson.encodeToString(body))), session).build()
        return decodeSupabaseResponse(safeSupabaseResponse(okHttpClient, request))
    }

    private fun authorize(builder: Request.Builder, session: GradeyAuthSession): Request.Builder = builder
        .header("Accept", "application/json")
        .header("apikey", configuration.anonKey)
        .header("Authorization", session.authorizationHeader)

    private fun ensureConfigured() = check(configuration.isConfigured) { "Gradey cloud is not configured for this build." }
}

private fun minutesToTime(minutes: Int): String = "%02d:%02d".format(java.util.Locale.ROOT, minutes.coerceIn(0, 1439) / 60, minutes.coerceIn(0, 1439) % 60)

@Serializable
private data class LinkedAccountRow(
    val id: String,
    val provider: SchoolProvider,
    @SerialName("display_name") val displayName: String,
    @SerialName("school_name") val schoolName: String? = null,
    val status: String = "active",
    @SerialName("notifications_enabled") val notificationsEnabled: Boolean = true,
    @SerialName("provider_user_id") val providerUserID: String? = null,
    @SerialName("base_url") val baseURL: String? = null,
    @SerialName("action_required_reason") val actionRequiredReason: String? = null,
) {
    fun toAccount() = LinkedSchoolAccount(id, provider, displayName, schoolName, status, notificationsEnabled, providerUserID, baseURL, actionRequiredReason)
}

@Serializable
private data class LinkSchoolRequest(
    val id: String? = null,
    val provider: SchoolProvider,
    @SerialName("provider_user_id") val providerUserID: String?,
    @SerialName("base_url") val baseURL: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("school_name") val schoolName: String?,
    @SerialName("token_payload") val tokenPayload: SchoolSecretPayload,
)

@Serializable
private data class AccountIDRequest(val id: String)

@Serializable
private data class LinkedAccountPreferencesRequest(val id: String, val notificationsEnabled: Boolean)

@Serializable
private data class ActivatedAccountResponse(
    val account: LinkedSchoolAccount,
    @SerialName("token_payload") val tokenPayload: SchoolSecretPayload,
)

/** Shared cloud payload used by the iOS app. Local-only metadata and school passwords stay on device. */
@Serializable
private data class SchoolSecretPayload(
    val provider: SchoolProvider,
    val baseURL: String,
    val accessToken: String,
    val refreshToken: String? = null,
    val tokenType: String,
    val expiresAt: String? = null,
    val expiresAtEpochMillis: Long? = null,
    val eduPage: CloudEduPageSession? = null,
    val pollingSessionEstablishedAt: String? = null,
) {
    fun toSession() = StoredSession(
        accessToken = accessToken,
        refreshToken = refreshToken.orEmpty(),
        tokenType = tokenType,
        expiresAtEpochMillis = expiresAtEpochMillis ?: expiresAt?.let { java.time.Instant.parse(it).toEpochMilli() }
            ?: if (provider == SchoolProvider.EDU_PAGE) Long.MAX_VALUE else 0L,
        baseURL = baseURL,
        provider = provider,
        eduPage = eduPage?.let { EduPageSessionData(it.sessionID, it.username, "", it.gsecHash, it.userID, activeStudent = it.activeStudent, linkedStudents = it.linkedStudents, subjects = it.subjects) },
    )

    companion object {
        fun fromSession(session: StoredSession) = SchoolSecretPayload(
            provider = session.provider,
            baseURL = session.baseURL,
            accessToken = session.accessToken,
            refreshToken = session.refreshToken.takeIf { it.isNotBlank() },
            tokenType = session.tokenType,
            expiresAt = if (session.provider == SchoolProvider.BAKALARI) java.time.Instant.ofEpochMilli(session.expiresAtEpochMillis).toString() else null,
            eduPage = session.eduPage?.let { CloudEduPageSession(it.sessionID, it.username, it.gsecHash, it.userID, it.activeStudent, it.linkedStudents, it.subjects) },
        )
    }
}

@Serializable
private data class CloudEduPageSession(
    val sessionID: String,
    val username: String,
    val gsecHash: String,
    val userID: String,
    val activeStudent: SchoolStudentProfile? = null,
    val linkedStudents: List<SchoolStudentProfile> = emptyList(),
    val subjects: List<EduPageSubjectProfile> = emptyList(),
)

@Serializable
private data class GradeHistoryRequest(@SerialName("linked_account_id") val accountID: String?, val days: Int = 90)

@Serializable
private data class GradeHistoryResponse(val events: List<GradeHistoryEvent> = emptyList(), val unavailableReason: String? = null)

@Serializable
private data class GradeHistoryEvent(
    @SerialName("subject_id") val subjectID: String,
    @SerialName("subject_name") val subjectName: String? = null,
    @SerialName("subject_abbrev") val subjectAbbrev: String? = null,
    @SerialName("event_type") val eventType: String,
    @SerialName("average_delta") val averageDelta: Double? = null,
    @SerialName("mark_count_delta") val markCountDelta: Int = 0,
    @SerialName("average_value") val averageValue: Double? = null,
    @SerialName("mark_count") val markCount: Int? = null,
)

@Serializable
private data class NotificationPreferenceRow(
    @SerialName("lock_screen_detail") val lockScreenDetail: com.bukovinafilip.gradey.model.NotificationLockScreenDetail = com.bukovinafilip.gradey.model.NotificationLockScreenDetail.MARK_AND_SUBJECT,
    @SerialName("quiet_hours_time_zone") val quietHoursTimeZoneIdentifier: String = "Europe/Prague",
    @SerialName("new_marks_enabled") val newMarksEnabled: Boolean = true,
    @SerialName("quiet_hours_enabled") val quietHoursEnabled: Boolean = false,
    @SerialName("quiet_hours_start_minute") val quietHoursStartMinute: Int = 1320,
    @SerialName("quiet_hours_end_minute") val quietHoursEndMinute: Int = 360,
)
