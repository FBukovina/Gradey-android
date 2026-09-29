package com.bukovinafilip.gradey.feature.ai

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.bukovinafilip.gradey.domain.SchoolRepository
import com.bukovinafilip.gradey.model.StoredSession
import com.bukovinafilip.gradey.ui.GradeyTheme
import java.lang.reflect.Proxy
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.junit.Rule
import org.junit.Test

class GradeyAIInteractionTest {
    @get:Rule val compose = createComposeRule()
    private val client = ScreenAIClient()
    private fun text(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private fun show() {
        val school = Proxy.newProxyInstance(SchoolRepository::class.java.classLoader, arrayOf(SchoolRepository::class.java)) { _, method, _ ->
            when (method.name) {
                "currentStoredSession" -> StoredSession("school-token", "", "Bearer", 1, "https://school.example")
                else -> error("Unexpected school method")
            }
        } as SchoolRepository
        compose.setContent {
            val controller = androidx.compose.runtime.remember { GradeyAIController(client, school, ByteArray(32)) }
            val state by controller.state.collectAsState()
            GradeyTheme { GradeyAIScreen(state, controller, false, true, Modifier) }
        }
    }

    @Test fun sendDisplaysFormattedAnswerAndConversationCanBeDeleted() {
        show()
        compose.onNodeWithText(text(R.string.gradey_ai_new_chat)).performScrollTo().performClick()
        compose.onNode(hasSetTextAction()).performTextInput("Explain fractions")
        compose.onNodeWithContentDescription(text(R.string.gradey_ai_send)).performClick()
        compose.onNodeWithText("First step").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.ai_copy)).performScrollTo().assertIsDisplayed()
        val output = java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "ai-chat.png")
        output.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("cp ${output.absolutePath} /sdcard/Download/gradey-ai-chat.png").use { descriptor ->
            java.io.FileInputStream(descriptor.fileDescriptor).readBytes()
        }
        compose.onNodeWithContentDescription(text(R.string.gradey_ai_options)).performClick()
        compose.onNodeWithText(text(R.string.gradey_ai_conversation_delete_action)).performClick()
        compose.onNodeWithText(text(R.string.ai_delete), useUnmergedTree = true).performClick()
        compose.onNodeWithText(text(R.string.gradey_ai_new_chat)).performScrollTo().assertIsDisplayed()
    }

    @Test fun stopEndsStreamingAndKeepsRecoveryAvailable() {
        client.hold = true
        show()
        compose.onNodeWithText(text(R.string.gradey_ai_new_chat)).performScrollTo().performClick()
        compose.onNode(hasSetTextAction()).performTextInput("Help me revise")
        compose.onNodeWithContentDescription(text(R.string.gradey_ai_send)).performClick()
        compose.onNodeWithContentDescription(text(R.string.gradey_ai_stop)).performClick()
        compose.onNode(hasText(text(R.string.gradey_ai_compute_check_request)) and hasClickAction()).performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.gradey_ai_send)).assertIsDisplayed()
    }

    private class ScreenAIClient : GradeyAIClient {
        override val isConfigured = true
        var hold = false
        var chat = AIChat("one", "Revision", 1)
        var chats = emptyList<AIChat>()
        var messages = emptyList<AIMessage>()
        private val ready = AIStatus(true, false, "v1", 20, 20, null,
            compute = AICompute(1, "prices", 0, listOf(AIComputeAction("reply", 1, true))))
        override suspend fun status() = ready
        override suspend fun acceptConsent(termsVersion: String) = ready
        override suspend fun revokeConsent() = Unit
        override suspend fun conversations(scope: String) = chats
        override suspend fun create(scope: String, selectionID: String): AIChat {
            chat = chat.copy(contextSelectionID = selectionID, schoolScope = scope); chats = listOf(chat); return chat
        }
        override suspend fun load(id: String) = AIDetail(chat, messages, ready)
        override suspend fun delete(id: String) { chats = emptyList() }
        override suspend fun deleteAll(scope: String) { chats = emptyList() }
        override suspend fun recover(request: AIRequestReference) = AIRecovery("pending", null, ready)
        override fun reply(request: PendingQuestion): Flow<AIStreamEvent> = flow {
            emit(AIStreamEvent.Started("answer", ready))
            emit(AIStreamEvent.Delta("## First step\n\n**Read** the example.\n\n1. Find the denominator.\n2. Divide equally."))
            if (hold) awaitCancellation()
            val answer = AIMessage("answer", "assistant", "## First step\n\n**Read** the example.\n\n1. Find the denominator.\n2. Divide equally.", "complete", 2, request.id)
            messages = listOf(AIMessage(request.id, "user", request.text, "complete", 1, request.id), answer)
            emit(AIStreamEvent.Completed(answer, ready))
        }
    }
}
