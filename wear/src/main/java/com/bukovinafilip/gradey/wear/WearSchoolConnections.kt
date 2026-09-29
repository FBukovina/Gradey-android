package com.bukovinafilip.gradey.wear

import android.content.Context
import com.bukovinafilip.gradey.domain.*
import com.bukovinafilip.gradey.model.*
import com.bukovinafilip.gradey.network.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** One owner of watch-only refresh tokens prevents concurrent refresh-family redemption. */
object WearSchoolConnections {
    private val mutex = Mutex()
    private var clientGrant: String? = null
    private var client: WatchSchoolNetworkClient? = null

    suspend fun refresh(context: Context, code: String? = null, checkApproval: Boolean = false): WatchSchoolRefresh = mutex.withLock {
        withContext(Dispatchers.IO) {
            val grant = WearSchoolGrantStore.load(context) ?: throw WearSchoolException(WearSchoolFailure.DISABLED)
            val connection = connection(grant)
            val result = withTimeout(60_000) {
                when {
                    code != null -> connection.completeCode(code)
                    checkApproval -> connection.checkApproval()
                    else -> connection.refresh(grant.credentials, grant.session)
                }
            }
            if (!WearSchoolGrantStore.isCurrent(context, grant)) throw WearSchoolException(WearSchoolFailure.DISABLED)
            if (result is WatchSchoolRefresh.Loaded) {
                WearSchoolGrantStore.updateSession(context, grant, result.session)
                val snapshot = CompanionSnapshots.wear(result.week, result.user)
                WearSnapshotStore.saveIndependent(context, grant, snapshot)
            }
            result
        }
    }
    suspend fun resend(context: Context) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val grant = WearSchoolGrantStore.load(context) ?: throw WearSchoolException(WearSchoolFailure.DISABLED)
            withTimeout(20_000) { connection(grant).resendApproval() }
        }
    }
    @Synchronized
    fun invalidate() { client = null; clientGrant = null }

    @Synchronized
    private fun connection(grant: WearSchoolStoredGrant): WatchSchoolNetworkClient {
        val key = "${grant.scope.ownerID}\u0000${grant.scope.schoolScope}\u0000${grant.grantID}\u0000${grant.connectionID}"
        if (clientGrant != key || client == null) {
            val http = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()
            client = WatchSchoolNetworkClient(BakalariNetworkClient(http), OkHttpEduPageClient(http))
            clientGrant = key
        }
        return client!!
    }
}
