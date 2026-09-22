package com.bukovinafilip.gradey.feature.ai

import com.bukovinafilip.gradey.model.DashboardData
import com.bukovinafilip.gradey.model.Mark
import com.bukovinafilip.gradey.model.MarksResponse
import com.bukovinafilip.gradey.model.StoredSession
import com.bukovinafilip.gradey.model.Subject
import com.bukovinafilip.gradey.model.SubjectInfo
import com.bukovinafilip.gradey.model.UserResponse
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GradeyAIContractTest {
    @Test fun missingServerStatusFailsClosed() {
        val status = AIContract.status(emptyMap())
        assertThat(status.enabled).isFalse()
        assertThat(status.consentRequired).isTrue()
        assertThat(status.canSend).isFalse()
    }

    @Test fun statusSupportsBothServerKeyFormatsAndResetTimestamps() {
        val status = AIContract.status(mapOf("enabled" to true, "consent_required" to false,
            "terms_version" to "v2", "daily_limit" to "5", "daily_used" to 3,
            "reset_at" to mapOf("_seconds" to 1_800_000_000L)))
        assertThat(status.remaining).isEqualTo(2)
        assertThat(status.canSend).isTrue()
        assertThat(status.resetAt).isEqualTo(1_800_000_000_000L)
    }

    @Test fun contextContainsOnlyExplicitlySelectedData() {
        val dashboard = dashboard()
        val withoutMarks = AIContext.build("school_safe", dashboard, emptyList(), false, false, now = 123L)
        assertThat(withoutMarks["subjects"] as List<*>).isEmpty()
        val withMarks = AIContext.build("school_safe", dashboard, emptyList(), true, false, now = 123L)
        val text = withMarks.toString()
        assertThat(text).contains("Mathematics")
        assertThat(text).doesNotContain("Private Student Name")
        assertThat(text).doesNotContain("Secret school")
        assertThat(text).doesNotContain("teacher-private-id")
        assertThat(withMarks["unavailableSections"] as List<*>).containsExactly("trends", "timetable")
    }

    @Test fun contextBoundsMarksAndEncodedSize() {
        val repeated = dashboard().marksResponse.subjects.single()
        val data = DashboardData(MarksResponse((0..99).map { repeated.copy(subjectInfo = SubjectInfo("$it", name = "Subject $it"),
            marks = (0..20).map { repeated.marks.single().copy(id = "m$it", caption = "large text ".repeat(1000)) }) }))
        val context = AIContext.build("school_safe", data, emptyList(), true, false)
        val subjects = (context["subjects"] as List<*>).map { it.objectMap() }
        assertThat(subjects.sumOf { (it["recentMarks"] as List<*>).size }).isAtMost(80)
        assertThat(subjects.all { (it["recentMarks"] as List<*>).size <= 5 }).isTrue()
        assertThat(AIContext.encodedSize(context)).isAtMost(AIContext.MAX_CONTEXT_BYTES)
    }

    @Test fun scopeIsStablePerSchoolAndInstallationWithoutLeakingTokens() {
        val session = StoredSession("sensitive-token", "refresh-token", "Bearer", 1, "https://private.school")
        val salt = ByteArray(32) { 7 }
        val scope = AIContext.schoolScope(session, salt)
        assertThat(scope).matches("school_[0-9a-f]{64}")
        assertThat(AIContext.schoolScope(session.copy(accessToken = "rotated"), salt)).isEqualTo(scope)
        assertThat(AIContext.schoolScope(session.copy(baseURL = "https://different.school"), salt)).isNotEqualTo(scope)
        assertThat(AIContext.schoolScope(session, ByteArray(32) { 8 })).isNotEqualTo(scope)
    }

    @Test fun decodesPersistedChatHistoryAndSkipsMalformedMessages() {
        val detail = AIContract.detail(mapOf(
            "conversation" to mapOf("id" to "chat-1", "title" to "Revision", "updated_at" to "2026-09-22T10:00:00Z"),
            "history" to listOf(
                mapOf("id" to "reply", "role" to "assistant", "content" to "Study plan", "created_at" to 2_000),
                mapOf("unexpected" to "entry"),
                mapOf("id" to "question", "role" to "user", "text" to "Help me revise", "created_at" to 1_000),
            ),
        ))
        assertThat(detail.chat.id).isEqualTo("chat-1")
        assertThat(detail.messages.map { it.id }).containsExactly("question", "reply").inOrder()
        assertThat(detail.messages.first().text).isEqualTo("Help me revise")
    }

    private fun dashboard() = DashboardData(
        MarksResponse(listOf(Subject(
            subjectInfo = SubjectInfo("math", "M", "Mathematics"),
            marks = listOf(Mark(id = "1", subjectID = "math", markText = "1", markDate = "2026-09-22",
                weight = 1.0, caption = "Algebra", teacherID = "teacher-private-id")),
        ))), user = UserResponse("Private Student Name", "Secret school"),
    )
}
