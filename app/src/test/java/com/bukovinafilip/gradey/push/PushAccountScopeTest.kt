package com.bukovinafilip.gradey.push

import com.bukovinafilip.gradey.model.LinkedSchoolAccount
import com.bukovinafilip.gradey.model.SchoolProvider
import org.junit.Assert.*
import org.junit.Test

class PushAccountScopeTest {
    @Test fun digestRequiresEverySchoolToRemainOwnedAndEnabled() {
        val scope = requireNotNull(PushAccountScope.parse(mapOf("type" to "new_marks", "schemaVersion" to "1", "accountIDs" to "[\"school-a\",\"school-b\"]")))
        val first = account("school-a")
        val second = account("school-b")
        assertTrue(scope.allEnabledIn(listOf(first, second)))
        assertFalse(scope.allEnabledIn(listOf(first)))
        assertFalse(scope.allEnabledIn(listOf(first, second.copy(notificationsEnabled = false))))
        assertFalse(scope.allEnabledIn(listOf(account("other-owner-school"))))
    }

    @Test fun legacySingleSchoolRemainsCompatibleButConflictingDeclarationsFail() {
        assertEquals(setOf("school-a"), PushAccountScope.parse(mapOf("accountID" to "school-a"))?.accountIDs)
        assertEquals(setOf("school-a"), PushAccountScope.parse(mapOf("linkedAccountID" to "school-a"))?.accountIDs)
        assertNotNull(PushAccountScope.parse(mapOf("accountID" to "school-a", "accountIDs" to "[\"school-a\"]")))
        assertNull(PushAccountScope.parse(mapOf("accountID" to "school-a", "linkedAccountID" to "school-b")))
        assertNull(PushAccountScope.parse(mapOf("accountID" to "school-a", "accountIDs" to "[\"school-a\",\"school-b\"]")))
    }

    @Test fun missingAndMalformedDigestCannotFallBackToOneEnabledSchool() {
        assertNull(PushAccountScope.parse(emptyMap()))
        listOf("", "null", "{}", "[]", "[1]", "[null]", "[{}]", "[\"school-a\",\"school-a\"]", "[\" school-a\"]", "[\"\"]", "not-json").forEach { encoded ->
            assertNull(encoded, PushAccountScope.parse(mapOf("accountID" to "school-a", "accountIDs" to encoded)))
        }
        assertNull(PushAccountScope.parse(mapOf("accountID" to "")))
        assertNull(PushAccountScope.parse(mapOf("accountID" to "a".repeat(129))))
    }

    @Test fun countAndEncodedSizeAreBoundedWithoutTruncatingAccounts() {
        fun encoded(ids: List<String>) = ids.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }
        assertNotNull(PushAccountScope.parse(mapOf("accountIDs" to encoded((1..32).map { "school-$it" }))))
        assertNull(PushAccountScope.parse(mapOf("accountIDs" to encoded((1..33).map { "school-$it" }))))
        assertNull(PushAccountScope.parse(mapOf("accountIDs" to encoded((1..17).map { "a".repeat(125) + it.toString().padStart(3, '0') }))))
    }

    @Test fun unknownMessageKindsAndVersionsDoNotBecomeMarkAlerts() {
        assertNull(PushAccountScope.parse(mapOf("type" to "other", "accountID" to "school-a")))
        assertNull(PushAccountScope.parse(mapOf("schemaVersion" to "2", "accountID" to "school-a")))
    }

    private fun account(id: String) = LinkedSchoolAccount(id, SchoolProvider.BAKALARI, id)
}
