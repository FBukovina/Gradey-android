package com.bukovinafilip.gradey.domain

import com.bukovinafilip.gradey.model.GradeyAuthSession
import com.bukovinafilip.gradey.model.LinkedCanteenAccount
import com.bukovinafilip.gradey.model.StravaCZStoredSession

interface CanteenAccountClient {
    suspend fun accounts(auth: GradeyAuthSession): List<LinkedCanteenAccount>
    suspend fun link(auth: GradeyAuthSession, session: StravaCZStoredSession): LinkedCanteenAccount
    suspend fun unlink(auth: GradeyAuthSession, accountID: String)
}

interface CanteenAccountRepository {
    suspend fun cachedAccounts(): List<LinkedCanteenAccount>
    suspend fun refreshAccounts(): List<LinkedCanteenAccount>
    suspend fun linkedAccountID(session: StravaCZStoredSession): String?
    suspend fun linkCurrent(session: StravaCZStoredSession): LinkedCanteenAccount
    suspend fun unlinkAccount(accountID: String)
    suspend fun clearLocalAccounts()
}
