package com.bukovinafilip.gradey.feature.ai

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bukovinafilip.gradey.domain.GradeyAuthRepository
import com.bukovinafilip.gradey.domain.GradeyHistoryRepository
import com.bukovinafilip.gradey.domain.SchoolInsightStore
import com.bukovinafilip.gradey.domain.SchoolRepository
import com.bukovinafilip.gradey.model.PersonalPlannerItem
import com.bukovinafilip.gradey.ui.AIOrb
import com.bukovinafilip.gradey.ui.BrandGradientIcon
import com.bukovinafilip.gradey.ui.GradeyMetrics
import com.bukovinafilip.gradey.ui.GradeyPrimaryButton
import com.bukovinafilip.gradey.ui.GradeySpacing
import com.bukovinafilip.gradey.ui.GradeyType
import com.bukovinafilip.gradey.ui.Hairline
import com.bukovinafilip.gradey.ui.HugeIcons
import com.bukovinafilip.gradey.ui.brandGradient
import com.bukovinafilip.gradey.ui.gradeyColors
import java.security.SecureRandom
import java.time.LocalDate
import java.util.Base64

/**
 * Gradey AI as a full-screen surface (iOS `GradeyAIView`): loading, then the consent screen, then a
 * chat that opens on a new conversation. Past chats and data controls live in the History sheet.
 * The surface draws its own background and handles the system bars; [onClose] leaves it.
 * [onUpgrade] opens the Support options when today's Compute is used up (the CTA is hidden when null).
 * [hasEarlyAccess] shows the early-access pill next to the Compute allowance (iOS `GradeyAIEarlyAccessBadge`).
 */
@Composable
fun GradeyAIRoute(
    schoolRepository: SchoolRepository,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    gradeyAccountID: String? = null,
    isDemo: Boolean = false,
    historyRepository: GradeyHistoryRepository? = null,
    schoolAccountKey: String? = null,
    gradeyAuthRepository: GradeyAuthRepository? = null,
    plannerItems: List<PersonalPlannerItem>? = null,
    initialAction: AIAction? = null,
    initialSubjectID: String? = null,
    initialEventID: String? = null,
    initialWeek: String? = null,
    initialRequestKey: String? = null,
    insightStore: SchoolInsightStore? = null,
    onUpgrade: (() -> Unit)? = null,
    hasEarlyAccess: Boolean = false,
) {
    val activityContext = LocalContext.current
    val resources = androidx.compose.ui.platform.LocalResources.current
    val localeTag = androidx.compose.ui.platform.LocalConfiguration.current.locales[0].toLanguageTag()
    val context = activityContext.applicationContext
    val client = remember(context, gradeyAccountID, isDemo, gradeyAuthRepository) {
        if (isDemo) null else FirebaseGradeyAIClient(context, gradeyAccountID, gradeyAuthRepository)
    }
    val controller: GradeyAIController = viewModel(
        key = "gradey-ai:${AIIdentitySession.generation}:${System.identityHashCode(schoolRepository)}:$gradeyAccountID:$schoolAccountKey:$isDemo",
        factory = remember(client, schoolRepository, historyRepository, insightStore) { object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                GradeyAIController(client, schoolRepository, schoolScopeSalt(context), historyRepository, PreferencesAIRequestStore(context, gradeyAccountID), insightStore) as T
        } },
    )
    val state by controller.state.collectAsStateWithLifecycle()
    LaunchedEffect(controller, plannerItems, localeTag) {
        controller.plannerItems(plannerItems); controller.locale(localeTag); controller.loadChoices()
    }
    LaunchedEffect(controller, initialAction, initialSubjectID, initialEventID, initialWeek, initialRequestKey) {
        val action = initialAction ?: when {
            initialEventID != null -> AIAction.TEST
            initialSubjectID != null -> AIAction.SUBJECT
            initialWeek != null -> AIAction.WEEK
            else -> null
        }
        if (action != null) {
            val week = if (action == AIAction.WEEK) runCatching { LocalDate.parse(initialWeek?.take(10) ?: LocalDate.now().toString()) }
                .getOrDefault(LocalDate.now()).let { it.minusDays((it.dayOfWeek.value - 1).toLong()).toString() } else null
            controller.initialEntry(AISelection(action, subjectID = initialSubjectID, eventID = initialEventID, weekStart = week),
                initialRequestKey, resources.getString(action.promptResource))
        }
    }
    DisposableEffect(controller, activityContext) {
        onDispose { if (activityContext.findActivity()?.isChangingConfigurations != true) controller.stop() }
    }
    GradeyAIScreen(state, controller, isDemo, client?.isConfigured == true, onClose, modifier, onUpgrade, hasEarlyAccess)
}

@Composable
internal fun GradeyAIScreen(
    state: AIState,
    controller: GradeyAIController,
    isDemo: Boolean,
    configured: Boolean,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    onUpgrade: (() -> Unit)? = null,
    hasEarlyAccess: Boolean = false,
) {
    val status = state.status
    val available = configured && !isDemo
    val hasConsent = available && status?.consentRequired == false
    var showHistory by rememberSaveable { mutableStateOf(false) }
    var openingChatID by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state.busy, state.currentChat?.id) {
        if (!state.busy || state.currentChat?.id == openingChatID) openingChatID = null
    }
    val canStartNewChat = status != null && status.enabled && !status.consentRequired && status.remaining > 0 && !state.busy
    val chatTitle = state.currentChat?.title?.takeIf { state.messages.isNotEmpty() && openingChatID == null }

    Column(
        modifier.fillMaxSize().background(MaterialTheme.gradeyColors.groupedBackground)
            .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .testTag("gradeyAIView"),
    ) {
        AITopBar(
            title = chatTitle, hasConsent = hasConsent, canStartNewChat = canStartNewChat,
            onHistory = { showHistory = true }, onNewChat = controller::close,
            onClose = { controller.stop(); onClose() },
        )
        AIErrorBanner(error = state.error.takeIf { available && status != null }, onDismiss = controller::dismissError)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val bottomInset = Modifier.windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
            when {
                !available -> CenteredState(bottomInset) {
                    AIStatusState(
                        title = stringResource(if (isDemo) R.string.ai_demo_title else R.string.ai_setup_title),
                        message = stringResource(if (isDemo) R.string.ai_demo_message else R.string.ai_setup_message),
                    )
                }
                status == null && state.busy -> CenteredState(bottomInset.testTag("gradeyAILoading")) {
                    AIStatusState(stringResource(R.string.gradey_ai_loading))
                }
                status == null -> CenteredState(bottomInset.testTag("gradeyAIUnavailable")) {
                    val context = LocalContext.current
                    AIStatusState(
                        title = stringResource(R.string.gradey_ai_load_failed_title),
                        message = state.error?.let { localizedAIMessage(context, it) } ?: stringResource(R.string.gradey_ai_load_failed_message),
                        icon = HugeIcons.Alert02,
                        action = {
                            GradeyPrimaryButton(
                                stringResource(R.string.ai_retry), controller::refresh, Modifier.testTag("gradeyAIRetryLoadButton"),
                                icon = HugeIcons.Refresh04,
                            )
                        },
                    )
                }
                status.consentRequired -> AIConsentScreen(status, state.busy, controller::acceptConsent)
                else -> AIChatScreen(state, controller, opening = openingChatID != null, onUpgrade = onUpgrade, hasEarlyAccess = hasEarlyAccess)
            }
        }
    }
    if (showHistory && hasConsent) AIHistorySheet(
        chats = state.chats,
        currentChatID = state.currentChat?.id,
        busy = state.busy,
        sending = state.sending,
        onOpen = { chat -> openingChatID = chat.id; controller.open(chat); showHistory = false },
        onDelete = controller::delete,
        onDeleteAll = controller::deleteAll,
        onRevoke = controller::revoke,
        onDismiss = { showHistory = false },
    )
}

/** Fills the content area and centres a state that may scroll at large text sizes. */
@Composable
private fun CenteredState(modifier: Modifier, content: @Composable () -> Unit) {
    LazyColumn(
        modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { item(key = "state") { content() } }
}

// MARK: - Top bar

/**
 * Custom top row (iOS `GradeyAIView.topBar`): History on the left once consent is given, the orb
 * and "Gradey AI" (or the open chat's title) in the middle, New chat and Close on the right.
 */
@Composable
private fun AITopBar(
    title: String?,
    hasConsent: Boolean,
    canStartNewChat: Boolean,
    onHistory: () -> Unit,
    onNewChat: () -> Unit,
    onClose: () -> Unit,
) {
    Box(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = GradeySpacing.md, bottom = GradeySpacing.sm),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.fillMaxWidth().padding(horizontal = GradeyMetrics.minTouch * 2 + GradeySpacing.sm * 2), contentAlignment = Alignment.Center) {
            if (title != null) {
                Text(
                    title, Modifier.semantics { heading() }, style = GradeyType.headline, color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.sm)) {
                    AIOrb(22.dp)
                    Text(
                        stringResource(R.string.gradey_ai_title), Modifier.semantics { heading() },
                        style = GradeyType.display(19), color = MaterialTheme.colorScheme.onSurface, maxLines = 1,
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.sm)) {
            if (hasConsent) AICircleButton(
                HugeIcons.MessageMultiple01, stringResource(R.string.gradey_ai_history_open), onHistory, Modifier.testTag("gradeyAIHistoryButton"),
            )
            Spacer(Modifier.weight(1f))
            if (hasConsent) AICircleButton(
                HugeIcons.Edit02, stringResource(R.string.gradey_ai_new_chat), onNewChat, Modifier.testTag("gradeyAINewChatButton"), enabled = canStartNewChat,
            )
            AICircleButton(HugeIcons.Cancel01, stringResource(R.string.gradey_ai_close), onClose, Modifier.testTag("modalDismissButton"))
        }
    }
}

/** Request error under the top bar, sliding in from the top over 200 ms (iOS `gradeyAIInlineError`). */
@Composable
private fun AIErrorBanner(error: String?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var lastError by remember { mutableStateOf(error) }
    SideEffect { if (error != null) lastError = error }
    val motion = tween<Float>(200, easing = LinearOutSlowInEasing)
    AnimatedVisibility(
        visible = error != null,
        enter = slideInVertically(tween(200, easing = LinearOutSlowInEasing)) { -it } + expandVertically(tween(200), expandFrom = Alignment.Top) + fadeIn(motion),
        exit = slideOutVertically(tween(200)) { -it } + shrinkVertically(tween(200), shrinkTowards = Alignment.Top) + fadeOut(tween(200)),
    ) {
        Box(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = GradeySpacing.sm), contentAlignment = Alignment.TopCenter) {
            val message = error ?: lastError
            if (message != null) AIBanner(
                AIBannerKind.ERROR, localizedAIMessage(context, message),
                Modifier.widthIn(max = GradeyMetrics.contentMaxWidth).testTag("gradeyAIInlineError"), onDismiss = onDismiss,
            )
        }
    }
}

// MARK: - Consent

/**
 * First-run disclosure (iOS `GradeyAIConsentScreen`): hero, one disclosure panel (AI system, school
 * data, processors, retention), the disclaimer and a pinned "Agree and continue" button.
 */
@Composable
private fun AIConsentScreen(status: AIStatus, busy: Boolean, onAccept: () -> Unit) {
    val itemWidth = Modifier.widthIn(max = GradeyMetrics.narrowContentMaxWidth).fillMaxWidth()
    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().testTag("gradeyAIConsentView"),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = GradeySpacing.md, bottom = GradeySpacing.xl),
            verticalArrangement = Arrangement.spacedBy(GradeySpacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item(key = "hero") {
                Column(
                    itemWidth.padding(top = GradeySpacing.sm),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(GradeySpacing.md),
                ) {
                    AIOrb(64.dp, Modifier.padding(bottom = GradeySpacing.xs))
                    Text(
                        stringResource(R.string.gradey_ai_consent_title), Modifier.semantics { heading() },
                        style = GradeyType.display(32).copy(brush = MaterialTheme.colorScheme.brandGradient, textAlign = TextAlign.Center),
                    )
                    Text(
                        stringResource(R.string.gradey_ai_consent_subtitle), style = GradeyType.callout,
                        color = MaterialTheme.gradeyColors.secondaryLabel, textAlign = TextAlign.Center,
                    )
                }
            }
            if (!status.enabled) item(key = "paused") {
                AIBanner(
                    AIBannerKind.PAUSED, stringResource(R.string.gradey_ai_availability_paused_message), itemWidth,
                    title = stringResource(R.string.gradey_ai_availability_paused_title),
                )
            }
            item(key = "disclosures") {
                Column(itemWidth.aiSurface(RoundedCornerShape(PanelCorner))) {
                    DisclosureRow(
                        HugeIcons.Sparkles, R.string.gradey_ai_consent_system_title, R.string.gradey_ai_consent_system_message,
                        Modifier.testTag("gradeyAIAIActDisclosure"),
                    )
                    DisclosureDivider()
                    DisclosureRow(HugeIcons.Analytics01, R.string.gradey_ai_consent_school_data_title, R.string.gradey_ai_consent_selected_context)
                    DisclosureDivider()
                    DisclosureRow(HugeIcons.Cloud, R.string.gradey_ai_consent_processors_title, R.string.gradey_ai_consent_processors_message)
                    DisclosureDivider()
                    DisclosureRow(HugeIcons.Reload, R.string.gradey_ai_consent_retention_title, R.string.gradey_ai_consent_selected_retention)
                }
            }
            item(key = "disclaimer") {
                Text(
                    stringResource(R.string.gradey_ai_disclaimer), itemWidth.padding(horizontal = GradeySpacing.xs),
                    style = GradeyType.footnote, color = MaterialTheme.gradeyColors.secondaryLabel,
                )
            }
        }
        Box(
            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
                .padding(start = 20.dp, end = 20.dp, top = GradeySpacing.lg, bottom = GradeySpacing.md),
            contentAlignment = Alignment.Center,
        ) {
            GradeyPrimaryButton(
                stringResource(R.string.gradey_ai_consent_action), onAccept,
                Modifier.widthIn(max = GradeyMetrics.narrowContentMaxWidth).testTag("gradeyAIConsentButton"),
                enabled = status.termsVersion.isNotBlank(), loading = busy,
            )
        }
    }
}

@Composable
private fun DisclosureRow(icon: String, title: Int, message: Int, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = GradeySpacing.lg).semantics(mergeDescendants = true) { },
        horizontalArrangement = Arrangement.spacedBy(GradeySpacing.md),
    ) {
        Box(Modifier.size(36.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f), CircleShape), contentAlignment = Alignment.Center) {
            BrandGradientIcon(icon, size = 17.dp)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(GradeySpacing.xs)) {
            Text(stringResource(title), style = GradeyType.headline, color = MaterialTheme.colorScheme.onSurface)
            Text(stringResource(message), style = GradeyType.subheadline, color = MaterialTheme.gradeyColors.secondaryLabel)
        }
    }
}

@Composable
private fun DisclosureDivider() = Hairline(Modifier.padding(start = 18.dp + 36.dp + GradeySpacing.md))

@Synchronized
internal fun schoolScopeSalt(context: Context): ByteArray {
    val store = context.getSharedPreferences("gradey-ai-context", Context.MODE_PRIVATE)
    val stored = store.getString("scope-salt-v1", null)?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
    if (stored != null && stored.size >= 16) return stored
    return ByteArray(32).also { bytes ->
        SecureRandom().nextBytes(bytes)
        check(store.edit().putString("scope-salt-v1", Base64.getEncoder().encodeToString(bytes)).commit()) { "The private AI scope could not be saved." }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
