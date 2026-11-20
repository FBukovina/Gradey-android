package com.bukovinafilip.gradey

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.bukovinafilip.gradey.ui.DisclosureIcon
import com.bukovinafilip.gradey.ui.GradeyColors
import com.bukovinafilip.gradey.ui.GradeyIcon
import com.bukovinafilip.gradey.ui.GradeyPrimaryButton
import com.bukovinafilip.gradey.ui.GradeyRadius
import com.bukovinafilip.gradey.ui.GradeySpacing
import com.bukovinafilip.gradey.ui.GradeyType
import com.bukovinafilip.gradey.ui.HugeIcons
import com.bukovinafilip.gradey.ui.ModalCloseButton
import com.bukovinafilip.gradey.ui.PlainLink
import com.bukovinafilip.gradey.ui.PlainRow
import com.bukovinafilip.gradey.ui.SectionHeader
import com.bukovinafilip.gradey.ui.gradeyColors

/**
 * The privacy notice the app ships (iOS `PrivacyPolicyRevision.current`). Raise it only when the
 * published policy changes in a way users must read, and refresh [PrivacyChanges] when you do:
 * everyone who accepted an earlier revision is asked again.
 *
 * Revision 3 adds school homework, Komens messages and report cards. Revision 2 removed the
 * Bakaláři password upload and documented the Planner, Gradey AI and the watch.
 */
internal const val PrivacyRevision = 3

/** The date on the published policy at help.bukovinafilip.com. */
internal val PrivacyEffectiveDate: java.time.LocalDate = java.time.LocalDate.of(2026, 11, 5)

/** One entry of "What changed" (iOS `PrivacyPolicyChange`): a title, one line, and the full explanation behind it. */
private class PrivacyChange(val id: String, val icon: String, val title: Int, val summary: Int, val detail: Int)

/** Only what this app does; the most important first. */
private val PrivacyChanges = listOf(
    PrivacyChange("homework", HugeIcons.BookOpen01, R.string.privacy_update_homework_title, R.string.privacy_update_homework_summary, R.string.privacy_update_homework_detail),
    PrivacyChange("messages", HugeIcons.Mail01, R.string.privacy_update_messages_title, R.string.privacy_update_messages_summary, R.string.privacy_update_messages_detail),
    PrivacyChange("reportCards", HugeIcons.GraduationScroll, R.string.privacy_update_report_cards_title, R.string.privacy_update_report_cards_summary, R.string.privacy_update_report_cards_detail),
)
internal const val PrivacyUrl = "https://help.bukovinafilip.com/en/articles/10-privacy-policy"
internal const val TermsUrl = "https://help.bukovinafilip.com/en/articles/11-terms-and-conditions"
internal const val HelpUrl = "https://help.bukovinafilip.com/en"

/** Width of every onboarding column (iOS onboarding content, 520 pt). */
private val OnboardingMaxWidth = 520.dp

/** Column of an onboarding step: centred, capped in width, inside the system bars. */
@Composable
private fun OnboardingColumn(modifier: Modifier = Modifier, scroll: ScrollState? = rememberScrollState(), content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier.widthIn(max = OnboardingMaxWidth).fillMaxSize().then(if (scroll != null) Modifier.verticalScroll(scroll) else Modifier)
                .padding(horizontal = 20.dp, vertical = GradeySpacing.lg),
            verticalArrangement = Arrangement.spacedBy(GradeySpacing.lg), content = content,
        )
    }
}

/** 4 dp capsule that fills with the brand colour; [fraction] is read while drawing. */
@Composable
private fun StepProgress(fraction: () -> Float, modifier: Modifier = Modifier) {
    val track = MaterialTheme.gradeyColors.tertiaryFill
    val fill = MaterialTheme.colorScheme.primary
    Box(
        modifier.fillMaxWidth().height(4.dp).clearAndSetSemantics { }.drawBehind {
            val radius = CornerRadius(size.height / 2f)
            drawRoundRect(track, cornerRadius = radius)
            val value = fraction().coerceIn(0f, 1f)
            if (value > 0f) drawRoundRect(fill, size = size.copy(width = (size.width * value).coerceAtLeast(size.height)), cornerRadius = radius)
        },
    )
}

/** Mirrors the iOS self-attestation. No birth date or age is sent to a server. */
@Composable
internal fun WelcomeScreen(onComplete: (String) -> Unit) {
    var age by rememberSaveable { mutableStateOf<String?>(null) }
    var parentConfirmed by rememberSaveable { mutableStateOf(false) }
    var privacyAccepted by rememberSaveable { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    val secondary = MaterialTheme.gradeyColors.secondaryLabel
    val reset = { age = null; parentConfirmed = false; privacyAccepted = false }
    BackHandler(enabled = age != null, onBack = reset)
    OnboardingColumn(Modifier.testTag("welcomeScreen")) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.app_name), Modifier.weight(1f), style = GradeyType.headline, color = MaterialTheme.colorScheme.onSurface)
            Text(stringResource(if (age == null) R.string.app_step_one else R.string.app_step_two), style = GradeyType.caption, color = secondary)
        }
        StepProgress({ if (age == null) 0.5f else 1f })
        Column(Modifier.padding(top = GradeySpacing.md), verticalArrangement = Arrangement.spacedBy(GradeySpacing.sm)) {
            Text(
                stringResource(if (age == null) R.string.app_welcome_title else R.string.app_privacy_title), Modifier.semantics { heading() },
                style = GradeyType.display(32), color = MaterialTheme.colorScheme.onSurface,
            )
            Text(stringResource(if (age == null) R.string.app_welcome_body else R.string.app_privacy_body), style = GradeyType.callout, color = secondary)
        }
        if (age == null) {
            Column {
                listOf(
                    Triple("sixteenOrOlder", R.string.app_age_adult, R.string.app_own_consent),
                    Triple("thirteenToFifteenWithParent", R.string.app_age_teen, R.string.app_parent_consent),
                    Triple("underThirteen", R.string.app_age_child, R.string.app_parent_consent),
                ).forEach { (value, title, subtitle) ->
                    PlainRow(Modifier.testTag("welcomeAge-$value"), onClick = { age = value }) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(stringResource(title), style = GradeyType.headline, color = MaterialTheme.colorScheme.onSurface)
                            Text(stringResource(subtitle), style = GradeyType.subheadline, color = secondary)
                        }
                        DisclosureIcon()
                    }
                }
            }
            Text(stringResource(R.string.app_age_local), style = GradeyType.caption, color = secondary)
        } else {
            Column {
                WelcomeDetail(stringResource(R.string.app_school_accounts), stringResource(R.string.app_school_data_body))
                WelcomeDetail(stringResource(R.string.app_gradey_id), stringResource(R.string.app_id_data_body))
                WelcomeDetail(stringResource(R.string.app_ai), stringResource(R.string.app_ai_data_body))
                WelcomeDetail(stringResource(R.string.app_control_title), stringResource(R.string.app_control_body))
            }
            Column {
                if (age != "sixteenOrOlder") ConsentRow(stringResource(R.string.app_parent_confirm), parentConfirmed, "welcomeParentConsent") { parentConfirmed = it }
                ConsentRow(stringResource(R.string.app_privacy_confirm), privacyAccepted, "welcomePrivacyConsent") { privacyAccepted = it }
            }
            GradeyPrimaryButton(
                stringResource(R.string.app_continue), { onComplete(requireNotNull(age)) },
                Modifier.testTag("welcomeContinue"), enabled = privacyAccepted && (age == "sixteenOrOlder" || parentConfirmed),
            )
            PlainLink(stringResource(R.string.app_change_age), reset, showsArrow = false, fillWidth = true)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(GradeySpacing.lg, Alignment.CenterHorizontally)) {
            PlainLink(stringResource(R.string.app_privacy), { uriHandler.openUri(PrivacyUrl) }, showsArrow = false)
            PlainLink(stringResource(R.string.app_terms), { uriHandler.openUri(TermsUrl) }, showsArrow = false)
        }
    }
}

@Composable
private fun WelcomeDetail(title: String, body: String) {
    PlainRow {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = GradeyType.subheadlineBold, color = MaterialTheme.colorScheme.onSurface)
            Text(body, style = GradeyType.footnote, color = MaterialTheme.gradeyColors.secondaryLabel)
        }
    }
}

@Composable
private fun ConsentRow(text: String, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(GradeyRadius.xs)
    PlainRow(Modifier.toggleable(checked, role = Role.Checkbox, onValueChange = onChange).testTag(tag)) {
        Box(
            Modifier.size(24.dp).then(if (checked) Modifier.background(primary, shape) else Modifier.border(1.5.dp, MaterialTheme.gradeyColors.tertiaryLabel, shape)),
            contentAlignment = Alignment.Center,
        ) { if (checked) GradeyIcon(HugeIcons.Tick02, size = 14.dp, tint = GradeyColors.OnAccent) }
        Text(text, Modifier.weight(1f), style = GradeyType.subheadline, color = MaterialTheme.colorScheme.onSurface)
    }
}

internal fun isValidAgeAttestation(value: String?): Boolean = value in setOf("sixteenOrOlder", "thirteenToFifteenWithParent", "underThirteen")

/** Policy-only upgrade: the caller retains the existing age attestation and records only the revision. */
@Composable
internal fun PrivacyUpdateScreen(onAccept: () -> Unit, onClose: (() -> Unit)? = null) {
    val uriHandler = LocalUriHandler.current
    val scroll = rememberScrollState()
    var readToEnd by rememberSaveable(PrivacyRevision) { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf<Int?>(null) }
    val context = LocalContext.current
    val accessibility = remember(context) { context.getSystemService(android.view.accessibility.AccessibilityManager::class.java) }
    var accessibleReading by remember(accessibility) { mutableStateOf(accessibility?.isTouchExplorationEnabled == true) }
    DisposableEffect(accessibility) {
        val listener = android.view.accessibility.AccessibilityManager.TouchExplorationStateChangeListener { accessibleReading = it }
        accessibility?.addTouchExplorationStateChangeListener(listener)
        onDispose { accessibility?.removeTouchExplorationStateChangeListener(listener) }
    }
    val review = onClose != null
    // The end counts once the list has been measured and cannot scroll further.
    val atEnd by remember { derivedStateOf { scroll.maxValue != Int.MAX_VALUE && scroll.value >= scroll.maxValue } }
    LaunchedEffect(atEnd) { if (atEnd) readToEnd = true }
    BackHandler { onClose?.invoke() }
    val secondary = MaterialTheme.gradeyColors.secondaryLabel
    OnboardingColumn(Modifier.testTag("privacyUpdateScreen"), scroll = null) {
        if (!review) {
            StepProgress({ if (readToEnd || accessibleReading) 1f else scroll.value.toFloat() / scroll.maxValue.coerceAtLeast(1) })
            if (!readToEnd && !accessibleReading) Text(stringResource(R.string.privacy_update_scroll), style = GradeyType.caption, color = secondary)
        }
        Column(Modifier.weight(1f).verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(GradeySpacing.lg)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.md)) {
                Text(
                    stringResource(R.string.privacy_update_title), Modifier.weight(1f).semantics { heading() },
                    style = GradeyType.display(32), color = MaterialTheme.colorScheme.onSurface,
                )
                onClose?.let { close -> ModalCloseButton(close, contentDescription = stringResource(R.string.privacy_update_done), size = 44.dp) }
            }
            Column(verticalArrangement = Arrangement.spacedBy(GradeySpacing.xs)) {
                Text(stringResource(R.string.privacy_update_subtitle), style = GradeyType.callout, color = secondary)
                // In the app's language, not the device's.
                val locale = LocalConfiguration.current.locales[0]
                val effective = remember(locale) {
                    PrivacyEffectiveDate.format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.LONG).withLocale(locale))
                }
                Text(stringResource(R.string.privacy_update_effective, effective), style = GradeyType.footnote, color = secondary)
            }
            Column {
                SectionHeader(stringResource(R.string.privacy_update_changes_title), Modifier.padding(start = GradeySpacing.xs, end = GradeySpacing.xs, bottom = GradeySpacing.sm))
                PrivacyChanges.forEachIndexed { index, change ->
                    val open = expanded == index
                    PlainRow(
                        Modifier.animateContentSize(tween(250)).testTag("privacyChange-${change.id}"),
                        onClick = { expanded = if (open) null else index }, verticalAlignment = Alignment.Top,
                        onClickLabel = stringResource(if (open) R.string.privacy_update_collapse else R.string.privacy_update_expand),
                    ) {
                        GradeyIcon(change.icon, Modifier.padding(top = 2.dp), size = 17.dp, tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(GradeySpacing.xs)) {
                            Text(stringResource(change.title), style = GradeyType.headline, color = MaterialTheme.colorScheme.onSurface)
                            Text(stringResource(change.summary), style = GradeyType.subheadline, color = secondary)
                            if (open) Text(stringResource(change.detail), style = GradeyType.footnote, color = secondary)
                        }
                        GradeyIcon(
                            HugeIcons.ArrowDown01, Modifier.padding(top = GradeySpacing.xs).graphicsLayer { rotationZ = if (open) 180f else 0f },
                            size = 14.dp, tint = MaterialTheme.gradeyColors.tertiaryLabel,
                        )
                    }
                }
            }
            Text(stringResource(R.string.privacy_update_footer), style = GradeyType.caption, color = secondary)
            PlainLink(stringResource(R.string.privacy_update_full_policy), { uriHandler.openUri(PrivacyUrl) })
        }
        if (!review) GradeyPrimaryButton(
            stringResource(R.string.privacy_update_accept), onAccept, Modifier.testTag("privacyUpdateAccept"), enabled = readToEnd || accessibleReading,
        )
    }
}
