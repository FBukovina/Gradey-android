package com.bukovinafilip.gradey.network

import com.bukovinafilip.gradey.domain.AccountFailureException
import com.bukovinafilip.gradey.domain.AccountFailureReason
import com.bukovinafilip.gradey.domain.CanteenAccountClient
import com.bukovinafilip.gradey.model.GradeyAuthSession
import com.bukovinafilip.gradey.model.LinkedCanteenAccount
import com.bukovinafilip.gradey.model.StravaCZStoredSession
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** Matches hosted link-stravacz-account v7; credentials/passwords are never in this contract. */
class SupabaseCanteenAccountClient(
    private val configuration: SupabaseConfiguration,
    client: OkHttpClient = OkHttpClient(),
) : CanteenAccountClient {
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false).build()

    override suspend fun accounts(auth: GradeyAuthSession): List<LinkedCanteenAccount> {
        val url = configuration.url.appendPath("rest/v1/linked_accounts").toHttpUrl().newBuilder()
            .addQueryParameter("select", "id,provider,display_name,canteen_name,provider_user_id,status,action_required_reason")
            .addQueryParameter("user_id", "eq.${auth.account.id}")
            .addQueryParameter("provider", "eq.stravaCZ")
            .addQueryParameter("order", "created_at.asc").build()
        val rows: List<CanteenRow> = decodeSupabaseResponse(safeSupabaseResponse(client, request(auth).url(url).get().build()))
        return rows.map { it.model() }
    }

    override suspend fun link(auth: GradeyAuthSession, session: StravaCZStoredSession): LinkedCanteenAccount {
        if (session.sessionID.isBlank() || session.username.isBlank() || session.canteenNumber.isBlank())
            throw AccountFailureException(AccountFailureReason.REQUEST_REJECTED)
        val url = runCatching { session.serviceURL.toHttpUrl() }.getOrNull()
        if (url == null || url.scheme != "https" || url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null)
            throw AccountFailureException(AccountFailureReason.REQUEST_REJECTED)
        val body = LinkCanteenRequest(session.fullName?.takeIf(String::isNotBlank) ?: session.username,
            session.canteenNumber, session.canteenName, session.username, session.serviceURL, session.sessionID)
        val response: LinkedCanteenResponse = decodeSupabaseResponse(safeSupabaseResponse(client,
            request(auth).url(configuration.url.appendPath("functions/v1/link-stravacz-account"))
                .post(jsonBody(GradeyJson.encodeToString(body))).build()))
        if (response.provider != "stravaCZ" || response.id.isBlank()) throw SupabaseApiException(502, AccountFailureReason.MALFORMED_RESPONSE)
        return LinkedCanteenAccount(response.id, response.displayName, response.canteenName, response.providerUserID, response.status, response.actionRequiredReason)
    }

    override suspend fun unlink(auth: GradeyAuthSession, accountID: String) {
        if (accountID.isBlank()) throw AccountFailureException(AccountFailureReason.ACCOUNT_NOT_FOUND)
        safeSupabaseResponse(client, request(auth).url(configuration.url.appendPath("functions/v1/unlink-account"))
            .post(jsonBody(GradeyJson.encodeToString(UnlinkCanteenRequest(accountID)))).build())
    }

    private fun request(auth: GradeyAuthSession): Request.Builder {
        if (!configuration.isConfigured) throw AccountFailureException(AccountFailureReason.NOT_CONFIGURED)
        return Request.Builder().header("apikey", configuration.anonKey).header("Authorization", auth.authorizationHeader).header("Accept", "application/json")
    }
}

@Serializable
private data class LinkCanteenRequest(
    @SerialName("display_name") val displayName: String,
    @SerialName("canteen_number") val canteenNumber: String,
    @SerialName("canteen_name") val canteenName: String?,
    val username: String,
    @SerialName("service_url") val serviceURL: String,
    @SerialName("session_id") val sessionID: String,
)
@Serializable private data class UnlinkCanteenRequest(val id: String)
@Serializable private data class LinkedCanteenResponse(
    val id: String, val provider: String, val displayName: String, val canteenName: String? = null,
    val providerUserID: String? = null, val status: String = "active", val actionRequiredReason: String? = null,
)
@Serializable private data class CanteenRow(
    val id: String, val provider: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("canteen_name") val canteenName: String? = null,
    @SerialName("provider_user_id") val providerUserID: String? = null,
    val status: String = "active",
    @SerialName("action_required_reason") val actionRequiredReason: String? = null,
) {
    fun model(): LinkedCanteenAccount {
        if (provider != "stravaCZ" || id.isBlank()) throw SupabaseApiException(502, AccountFailureReason.MALFORMED_RESPONSE)
        return LinkedCanteenAccount(id, displayName, canteenName, providerUserID, status, actionRequiredReason)
    }
}
