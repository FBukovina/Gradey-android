package com.bukovinafilip.gradey.data

import com.bukovinafilip.gradey.domain.GradeyAuthRepository
import com.bukovinafilip.gradey.network.SupabaseAccountClient

class AccountManagementRepository(
    private val client: SupabaseAccountClient,
    private val authRepository: GradeyAuthRepository,
) {
    suspend fun exportAccountData(): String {
        val session = authRepository.bootstrapSession() ?: error("Sign in to Gradey to export your data.")
        return client.exportAccountData(session)
    }

    suspend fun deleteAccount() {
        val session = authRepository.bootstrapSession() ?: error("Sign in to Gradey to delete your account.")
        client.deleteAccount(session)
    }
}
