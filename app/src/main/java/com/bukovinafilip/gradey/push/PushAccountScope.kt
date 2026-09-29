package com.bukovinafilip.gradey.push

import com.bukovinafilip.gradey.model.LinkedSchoolAccount
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/** Mirrors the staged FCM digest contract; never attribute a multi-school alert to one school. */
internal class PushAccountScope private constructor(val accountIDs: Set<String>) {
    fun allEnabledIn(accounts: List<LinkedSchoolAccount>): Boolean {
        val enabled = accounts.filter { it.notificationsEnabled }.map { it.id }.toSet()
        return accountIDs.all { it in enabled }
    }

    companion object {
        private const val maximumAccounts = 32
        private const val maximumScopeBytes = 2048
        private val validID = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,127}")

        fun parse(data: Map<String, String>): PushAccountScope? {
            if (data["type"]?.let { it != "new_marks" } == true || data["schemaVersion"]?.let { it != "1" } == true) return null
            val single = data["accountID"]
            val legacy = data["linkedAccountID"]
            if (listOfNotNull(single, legacy).any { !validID.matches(it) } || (single != null && legacy != null && single != legacy)) return null
            val ids = if (data.containsKey("accountIDs")) {
                val raw = data["accountIDs"] ?: return null
                if (raw.length > maximumScopeBytes || raw.toByteArray(Charsets.UTF_8).size > maximumScopeBytes) return null
                val array = runCatching { Json.parseToJsonElement(raw) }.getOrNull() as? JsonArray ?: return null
                if (array.isEmpty() || array.size > maximumAccounts) return null
                val parsed = array.map { element ->
                    val value = element as? JsonPrimitive ?: return null
                    if (!value.isString || !validID.matches(value.content)) return null
                    value.content
                }
                if (parsed.toSet().size != parsed.size) return null
                parsed.toSet()
            } else setOf(single ?: legacy ?: return null)
            val declaredSingle = single ?: legacy
            if (declaredSingle != null && ids != setOf(declaredSingle)) return null
            return PushAccountScope(ids)
        }
    }
}
