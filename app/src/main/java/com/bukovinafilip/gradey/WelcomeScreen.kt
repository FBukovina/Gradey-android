package com.bukovinafilip.gradey

import com.bukovinafilip.gradey.ui.HugeIcons
import com.bukovinafilip.gradey.ui.HugeIcon

import androidx.compose.ui.res.stringResource
import com.bukovinafilip.gradey.R
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bukovinafilip.gradey.ui.*

internal const val PrivacyRevision = 2
internal const val PrivacyUrl = "https://help.bukovinafilip.com/en/articles/10-privacy-policy"
internal const val TermsUrl = "https://help.bukovinafilip.com/en/articles/11-terms-and-conditions"
internal const val HelpUrl = "https://help.bukovinafilip.com/en"

/** Mirrors the iOS self-attestation. No birth date or age is sent to a server. */
@Composable
internal fun WelcomeScreen(onComplete: (String) -> Unit) {
    var age by rememberSaveable { mutableStateOf<String?>(null) }
    var parentConfirmed by rememberSaveable { mutableStateOf(false) }
    var privacyAccepted by rememberSaveable { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    BackHandler(enabled = age != null) { age = null; parentConfirmed = false; privacyAccepted = false }
    GradeyScreen(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Gradey", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(if (age == null) stringResource(R.string.app_step_one) else stringResource(R.string.app_step_two), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LinearProgressIndicator(progress = { if (age == null) .5f else 1f }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        HugeIcon(if (age == null) HugeIcons.University else HugeIcons.SecurityLock, null, Modifier.size(52.dp), tint = MaterialTheme.colorScheme.primary)
        Text(if (age == null) stringResource(R.string.app_welcome_title) else stringResource(R.string.app_privacy_title), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Text(if (age == null) stringResource(R.string.app_welcome_body) else stringResource(R.string.app_privacy_body), color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (age == null) {
            listOf(
                Triple("sixteenOrOlder", stringResource(R.string.app_age_adult), stringResource(R.string.app_own_consent)),
                Triple("thirteenToFifteenWithParent", stringResource(R.string.app_age_teen), stringResource(R.string.app_parent_consent)),
                Triple("underThirteen", stringResource(R.string.app_age_child), stringResource(R.string.app_parent_consent)),
            ).forEach { (value, title, subtitle) ->
                Surface(onClick = { age = value }, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
                    Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(title, style = MaterialTheme.typography.titleMedium)
                            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                        }
                        HugeIcon(HugeIcons.ArrowRight01, null)
                    }
                }
            }
            Text(stringResource(R.string.app_age_local), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            GradeySectionCard {
                WelcomeDetail(stringResource(R.string.app_school_accounts), stringResource(R.string.app_school_data_body))
                HorizontalDivider()
                WelcomeDetail("Gradey ID", stringResource(R.string.app_id_data_body))
                HorizontalDivider()
                WelcomeDetail("Gradey AI", stringResource(R.string.app_ai_data_body))
                HorizontalDivider()
                WelcomeDetail(stringResource(R.string.app_control_title), stringResource(R.string.app_control_body))
            }
            if (age != "sixteenOrOlder") ConsentRow(stringResource(R.string.app_parent_confirm), parentConfirmed) { parentConfirmed = it }
            ConsentRow(stringResource(R.string.app_privacy_confirm), privacyAccepted) { privacyAccepted = it }
            Button(onClick = { onComplete(requireNotNull(age)) }, enabled = privacyAccepted && (age == "sixteenOrOlder" || parentConfirmed), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(stringResource(R.string.app_continue)) }
            TextButton(onClick = { age = null; parentConfirmed = false; privacyAccepted = false }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.app_change_age)) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            TextButton(onClick = { uriHandler.openUri(PrivacyUrl) }) { Text(stringResource(R.string.app_privacy)) }
            TextButton(onClick = { uriHandler.openUri(TermsUrl) }) { Text(stringResource(R.string.app_terms)) }
        }
    }
}

@Composable private fun WelcomeDetail(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable private fun ConsentRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(checked, role = Role.Checkbox, onValueChange = onChange).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onCheckedChange = null)
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

internal fun isValidAgeAttestation(value: String?): Boolean = value in setOf("sixteenOrOlder", "thirteenToFifteenWithParent", "underThirteen")

/** Policy-only upgrade: the caller retains the existing age attestation and records only the revision. */
@Composable
internal fun PrivacyUpdateScreen(onAccept: () -> Unit, onClose: (() -> Unit)? = null) {
    val uriHandler = LocalUriHandler.current
    val scroll = rememberScrollState()
    var laidOut by remember { mutableStateOf(false) }
    var viewportBounds by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    var finalTextBounds by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    var readToEnd by rememberSaveable(PrivacyRevision) { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf<Int?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val accessibility = remember(context) { context.getSystemService(android.view.accessibility.AccessibilityManager::class.java) }
    var accessibleReading by remember(accessibility) { mutableStateOf(accessibility?.isTouchExplorationEnabled == true) }
    DisposableEffect(accessibility) {
        val listener = android.view.accessibility.AccessibilityManager.TouchExplorationStateChangeListener { accessibleReading = it }
        accessibility?.addTouchExplorationStateChangeListener(listener)
        onDispose { accessibility?.removeTouchExplorationStateChangeListener(listener) }
    }
    val review = onClose != null
    LaunchedEffect(laidOut, scroll.maxValue, scroll.value, viewportBounds, finalTextBounds) {
        // Reaching the actual last text is sufficient; invisible button padding is not reading.
        val viewport = viewportBounds
        val last = finalTextBounds
        if (laidOut && ((!scroll.canScrollForward && scroll.maxValue != Int.MAX_VALUE) ||
                (viewport != null && last != null && last.top >= viewport.top && last.bottom <= viewport.bottom))) readToEnd = true
    }
    BackHandler { onClose?.invoke() }
    Surface(Modifier.fillMaxSize(), color = androidx.compose.ui.graphics.Color.Transparent, contentColor = MaterialTheme.colorScheme.onBackground) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp)) {
            if (!review) {
                LinearProgressIndicator(progress = { if (readToEnd || accessibleReading) 1f else
                    (scroll.value.toFloat() / scroll.maxValue.coerceAtLeast(1)).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
                if (!readToEnd && !accessibleReading) Text(stringResource(R.string.privacy_update_scroll),
                    modifier = Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box(Modifier.weight(1f).onGloballyPositioned { coordinates ->
                val position = coordinates.positionInWindow()
                viewportBounds = androidx.compose.ui.geometry.Rect(position.x, position.y, position.x + coordinates.size.width, position.y + coordinates.size.height)
                laidOut = true
            }) {
            Column(Modifier.verticalScroll(scroll).padding(vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    HugeIcon(HugeIcons.SecurityLock, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.weight(1f))
                    onClose?.let { close -> TextButton(onClick = close) { Text(stringResource(R.string.privacy_update_done)) } }
                }
                Text(stringResource(R.string.privacy_update_title), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.privacy_update_subtitle), color = MaterialTheme.colorScheme.onSurfaceVariant)
                listOf(
                    Triple(R.string.privacy_update_password_title, R.string.privacy_update_password_summary, R.string.privacy_update_password_detail),
                    Triple(R.string.privacy_update_tokens_title, R.string.privacy_update_tokens_summary, R.string.privacy_update_tokens_detail),
                    Triple(R.string.privacy_update_ai_title, R.string.privacy_update_ai_summary, R.string.privacy_update_ai_detail),
                    Triple(R.string.privacy_update_planner_title, R.string.privacy_update_planner_summary, R.string.privacy_update_planner_detail),
                    Triple(R.string.privacy_update_watch_title, R.string.privacy_update_watch_summary, R.string.privacy_update_watch_detail),
                ).forEachIndexed { index, (title, summary, detail) ->
                    Surface(onClick = { expanded = if (expanded == index) null else index }, shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(stringResource(title), modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                                HugeIcon(if (expanded == index) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01,
                                    contentDescription = stringResource(if (expanded == index) R.string.privacy_update_collapse else R.string.privacy_update_expand))
                            }
                            Text(stringResource(summary), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (expanded == index) Text(stringResource(detail), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Text(stringResource(R.string.privacy_update_footer), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { uriHandler.openUri(PrivacyUrl) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.privacy_update_full_policy), Modifier.onGloballyPositioned { coordinates ->
                        val position = coordinates.positionInWindow()
                        finalTextBounds = androidx.compose.ui.geometry.Rect(position.x, position.y, position.x + coordinates.size.width, position.y + coordinates.size.height)
                    })
                }
            }
            }
            if (!review) Button(onClick = onAccept, enabled = readToEnd || accessibleReading,
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp).heightIn(min = 52.dp)) { Text(stringResource(R.string.privacy_update_accept)) }
        }
    }
}
