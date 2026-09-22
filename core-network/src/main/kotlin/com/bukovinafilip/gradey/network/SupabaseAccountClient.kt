package com.bukovinafilip.gradey.network

import com.bukovinafilip.gradey.domain.GradeHistoryTrend
import com.bukovinafilip.gradey.model.GradeyAuthSession
import com.bukovinafilip.gradey.model.LinkedSchoolAccount
import com.bukovinafilip.gradey.model.NotificationPreferences
import com.bukovinafilip.gradey.model.SchoolProvider
import com.bukovinafilip.gradey.model.StoredSession
import com.bukovinafilip.gradey.model.BakalariCredentials
import com.bukovinafilip.gradey.model.EduPageSessionData
import com.bukovinafilip.gradey.model.EduPageSubjectProfile
import com.bukovinafilip.gradey.model.SchoolStudentProfile
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
) {
    suspend fun linkedAccounts(session: GradeyAuthSession): List<LinkedSchoolAccount> {
        val rows: List<LinkedAccountRow> = get(
            "linked_accounts", session,
            mapOf(
                "select" to "id,provider,display_name,school_name,status,notifications_enabled",
                "user_id" to "eq.${session.account.id}",
                "provider" to "in.(bakalari,eduPage)",
                "order" to "created_at.asc",
            ),
        )
        return rows.map { it.toAccount() }
    }

    suspend fun linkSchoolAccount(auth: GradeyAuthSession, session: StoredSession, displayName: String): LinkedSchoolAccount =
        post("link-school-account", auth, LinkSchoolRequest(
            provider = session.provider,
            providerUserID = session.eduPage?.activeStudent?.id ?: session.eduPage?.userID ?: session.bakalari?.username,
            baseURL = session.baseURL,
            displayName = displayName,
            schoolName = session.linkedAccountSchoolName ?: session.eduPage?.schoolName,
            tokenPayload = SchoolSecretPayload.fromSession(session),
        ))

    suspend fun activateSchoolAccount(auth: GradeyAuthSession, accountID: String): StoredSession {
        val response: ActivatedAccountResponse = post("activate-school-account", auth, AccountIDRequest(accountID))
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
        // Baselines describe the starting point, not a change. Sum each subject's changes in the requested window.
        return response.events.filter { it.eventType == "changed" }
            .groupBy { it.subjectID }
            .map { (id, events) ->
                GradeHistoryTrend(
                    subjectID = id,
                    subjectName = events.last().subjectName ?: events.last().subjectAbbrev ?: id,
                    averageDelta = events.sumOf { it.averageDelta ?: 0.0 },
                    markCountDelta = events.sumOf { it.markCountDelta },
                )
            }
    }

    suspend fun notificationPreferences(auth: GradeyAuthSession): NotificationPreferences? {
        val rows: List<NotificationPreferenceRow> = get(
            "notification_preferences", auth,
            mapOf("select" to "new_marks_enabled,quiet_hours_enabled,quiet_hours_start_minute,quiet_hours_end_minute", "user_id" to "eq.${auth.account.id}", "limit" to "1"),
        )
        return rows.firstOrNull()?.let {
            NotificationPreferences(it.newMarksEnabled, it.quietHoursEnabled, minutesToTime(it.quietHoursStartMinute), minutesToTime(it.quietHoursEndMinute))
        }
    }

    private suspend inline fun <reified T> get(table: String, session: GradeyAuthSession, parameters: Map<String, String>): T {
        ensureConfigured()
        val url = configuration.url.appendPath("rest/v1/$table").toHttpUrl().newBuilder().apply {
            parameters.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build()
        val request = authorize(Request.Builder().url(url).get(), session).build()
        return GradeyJson.decodeFromString(safeSupabaseResponse(okHttpClient, request))
    }

    private suspend inline fun <reified Body, reified Result> post(function: String, session: GradeyAuthSession, body: Body): Result {
        ensureConfigured()
        val request = authorize(Request.Builder()
            .url(configuration.url.appendPath("functions/v1/$function"))
            .post(jsonBody(GradeyJson.encodeToString(body))), session).build()
        return GradeyJson.decodeFromString(safeSupabaseResponse(okHttpClient, request))
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
) {
    fun toAccount() = LinkedSchoolAccount(id, provider, displayName, schoolName, status, notificationsEnabled)
}

@Serializable
private data class LinkSchoolRequest(
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
private data class ActivatedAccountResponse(
    val account: LinkedSchoolAccount,
    @SerialName("token_payload") val tokenPayload: SchoolSecretPayload,
)

/** Shared cloud payload used by the iOS app. Local-only metadata and EduPage passwords stay on device. */
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
    val bakalari: BakalariCredentials? = null,
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
        bakalari = bakalari,
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
            bakalari = session.bakalari,
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
)

@Serializable
private data class NotificationPreferenceRow(
    @SerialName("new_marks_enabled") val newMarksEnabled: Boolean = true,
    @SerialName("quiet_hours_enabled") val quietHoursEnabled: Boolean = false,
    @SerialName("quiet_hours_start_minute") val quietHoursStartMinute: Int = 1320,
    @SerialName("quiet_hours_end_minute") val quietHoursEndMinute: Int = 360,
)
