package com.bukovinafilip.gradey.feature.ai

import android.content.Context

internal val AIAction.titleResource: Int get() = when (this) {
    AIAction.REPLY -> R.string.gradey_ai_action_reply
    AIAction.TOMORROW -> R.string.gradey_ai_action_tomorrow
    AIAction.PRIORITIES -> R.string.gradey_ai_action_study_priorities
    AIAction.WEEK -> R.string.gradey_ai_action_week_summary
    AIAction.SUBJECT -> R.string.gradey_ai_action_subject_help
    AIAction.TEST -> R.string.gradey_ai_action_test_preparation
}

internal val AIAction.promptResource: Int get() = when (this) {
    AIAction.REPLY -> R.string.gradey_ai_prompt_general1
    AIAction.TOMORROW -> R.string.gradey_ai_prompt_tomorrow
    AIAction.PRIORITIES -> R.string.gradey_ai_prompt_study_priorities
    AIAction.WEEK -> R.string.gradey_ai_prompt_week_summary
    AIAction.SUBJECT -> R.string.gradey_ai_prompt_subject_help
    AIAction.TEST -> R.string.gradey_ai_prompt_test_preparation
}

internal fun aiSectionResource(section: String): Int = when (section) {
    "marks" -> R.string.gradey_ai_context_section_marks
    "trends" -> R.string.gradey_ai_context_section_trends
    "timetable" -> R.string.gradey_ai_context_section_timetable
    "events" -> R.string.gradey_ai_context_section_events
    "insights" -> R.string.gradey_ai_context_section_insights
    else -> R.string.gradey_ai_context_section_other
}

internal fun localizedAIMessage(context: Context, message: String): String = context.getString(when (message) {
    "Gradey AI is not available in this build." -> R.string.gradey_ai_compute_unavailable
    "Gradey AI could not complete your request. Please try again." -> R.string.gradey_ai_error_could_not_complete
    "Gradey AI could not complete this request. Please try again." -> R.string.gradey_ai_error_could_not_complete
    "Enter a question of 2,000 characters or fewer." -> R.string.gradey_ai_error_invalid_prompt
    "You don’t have enough Gradey Compute for this action. Refresh to check your balance." -> R.string.gradey_ai_limit_reached
    "Choose a subject first." -> R.string.gradey_ai_context_choose_item
    "Choose a test first." -> R.string.gradey_ai_context_choose_item
    "Choose a subject before starting subject help." -> R.string.gradey_ai_context_choose_item
    "Choose a test from your Planner first." -> R.string.gradey_ai_context_choose_item
    "Start a new conversation to use this school context." -> R.string.gradey_ai_context_new_purpose
    "The selected school context is too large. Try a different action." -> R.string.gradey_ai_error_request_too_large
    "The school context is too large." -> R.string.gradey_ai_error_request_too_large
    "The request is too large." -> R.string.gradey_ai_error_request_too_large
    "The saved request status is unavailable. Please retry recovery." -> R.string.gradey_ai_compute_recovery_unavailable
    "The previous request status could not be confirmed. Try recovery again." -> R.string.gradey_ai_compute_recovery_unavailable
    "The saved answer is not available yet. Try recovery again." -> R.string.gradey_ai_compute_recovery_unavailable
    "Gradey AI couldn’t start a secure session. Please try again." -> R.string.gradey_ai_error_unauthenticated
    "Gradey AI couldn’t verify your session. Please refresh and try again." -> R.string.gradey_ai_error_unauthenticated
    "Your Gradey Compute limit has been reached. Refresh to see when it resets." -> R.string.gradey_ai_limit_reached
    "This conversation or AI service is no longer available. Refresh your conversations." -> R.string.gradey_ai_error_chat_unavailable
    "Gradey AI could not be reached. Reload the conversation before retrying your question." -> R.string.gradey_ai_load_failed_message
    "Check your connection and reload the conversation before retrying." -> R.string.gradey_ai_load_failed_message
    "The connection ended before the reply finished. Reload the conversation before retrying." -> R.string.gradey_ai_error_timeout
    "Gradey AI returned an unrecognized reply. Reload the conversation before retrying." -> R.string.gradey_ai_error_could_not_complete
    "Gradey AI could not finish this reply." -> R.string.gradey_ai_error_could_not_complete
    "The reply took too long. Try again." -> R.string.gradey_ai_error_timeout
    "The selected action or its Compute cost has changed. Check the updated cost before sending again." -> R.string.gradey_ai_compute_price_changed
    "This reply can’t be retried with changed content. Send a new message." -> R.string.gradey_ai_error_changed_request
    "AI couldn’t answer this request. Try rephrasing your question." -> R.string.gradey_ai_error_content_filter
    "Gradey AI is busy. Try again later." -> R.string.gradey_ai_error_busy
    "Privacy notice · %1\$s" -> R.string.ai_notice_version
    "Reply stopped. Reload to check the saved reply before trying again." -> R.string.ai_notice_stopped
    "Your previous answer is still being saved. Check again shortly; no new Compute will be spent." -> R.string.ai_notice_pending
    "Your saved answer was recovered without using more Compute." -> R.string.ai_notice_recovered
    "Your reply is ready. Refresh later to sync conversation history and your remaining questions." -> R.string.ai_notice_ready
    "The privacy notice changed. Review it and tap Agree again." -> R.string.ai_terms_changed
    "Your school account changed. Refresh Gradey AI to continue with this school." -> R.string.ai_school_changed
    "Connect your school account to use Gradey AI." -> R.string.ai_connect_school
    "Sign in to Gradey ID again to verify your AI account." -> R.string.ai_proof_needed
    "The request could not be saved for recovery. Free some device storage and try again." -> R.string.ai_recovery_save_failed
    else -> R.string.gradey_ai_error_could_not_complete
})
