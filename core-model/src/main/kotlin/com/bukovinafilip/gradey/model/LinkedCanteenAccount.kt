package com.bukovinafilip.gradey.model

import kotlinx.serialization.Serializable

@Serializable
data class LinkedCanteenAccount(
    val id: String,
    val displayName: String,
    val canteenName: String? = null,
    val providerUserID: String? = null,
    val status: String = "active",
    val actionRequiredReason: String? = null,
)

@Serializable
data class CanteenAccountCache(
    val accounts: List<LinkedCanteenAccount> = emptyList(),
    val linkedIdentities: Map<String, String> = emptyMap(),
)
