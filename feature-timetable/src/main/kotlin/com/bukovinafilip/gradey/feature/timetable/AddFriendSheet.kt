package com.bukovinafilip.gradey.feature.timetable

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.bukovinafilip.gradey.domain.FriendRepository
import com.bukovinafilip.gradey.domain.SchoolDirectoryProvider
import com.bukovinafilip.gradey.domain.SchoolDirectorySearch
import com.bukovinafilip.gradey.model.Friend
import com.bukovinafilip.gradey.model.PublicTimetableClass
import com.bukovinafilip.gradey.model.SchoolDirectorySchool
import com.bukovinafilip.gradey.ui.BrandTextField
import com.bukovinafilip.gradey.ui.DisclosureIcon
import com.bukovinafilip.gradey.ui.GradeyIcon
import com.bukovinafilip.gradey.ui.GradeyMetrics
import com.bukovinafilip.gradey.ui.GradeyPrimaryButton
import com.bukovinafilip.gradey.ui.GradeySheet
import com.bukovinafilip.gradey.ui.GradeySpacing
import com.bukovinafilip.gradey.ui.GradeyTonalButton
import com.bukovinafilip.gradey.ui.GradeyType
import com.bukovinafilip.gradey.ui.Hairline
import com.bukovinafilip.gradey.ui.HugeIcons
import com.bukovinafilip.gradey.ui.InlineNotice
import com.bukovinafilip.gradey.ui.LoadingState
import com.bukovinafilip.gradey.ui.ModalCloseButton
import com.bukovinafilip.gradey.ui.PlainLink
import com.bukovinafilip.gradey.ui.SectionHeader
import com.bukovinafilip.gradey.ui.SettingsGroup
import com.bukovinafilip.gradey.ui.SettingsIcon
import com.bukovinafilip.gradey.ui.SettingsRowDivider
import com.bukovinafilip.gradey.ui.SettingsToggleRow
import com.bukovinafilip.gradey.ui.ToolbarIconButton
import com.bukovinafilip.gradey.ui.gradeyColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private enum class AddFriendStep { METHOD, BAKALARI, PUBLIC_CLASS, GROUPS }

/**
 * The Add friend flow (iOS `AddFriendView`): a name, then one of two ways to get their timetable:
 * their own Bakaláři login, kept on this device, or the school's public class timetable.
 *
 * @param ownSchoolURL the user's own Bakaláři school, suggested first: friends are often classmates.
 * @param errorMessage puts a failure into the app's words.
 * @param onAdded the finished friend; the caller saves them and closes the sheet.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AddFriendSheet(
    repository: FriendRepository,
    directory: SchoolDirectoryProvider,
    ownSchoolURL: String?,
    errorMessage: (Exception) -> String,
    onAdded: (Friend) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var step by rememberSaveable { mutableStateOf(AddFriendStep.METHOD) }
    var name by rememberSaveable { mutableStateOf("") }
    var schoolSearch by rememberSaveable { mutableStateOf("") }
    var schoolURL by rememberSaveable { mutableStateOf("") }
    var schoolName by rememberSaveable { mutableStateOf<String?>(null) }
    var searching by remember { mutableStateOf(false) }
    var schools by remember { mutableStateOf(emptyList<SchoolDirectorySchool>()) }
    var username by rememberSaveable { mutableStateOf("") }
    // Never saved with the screen's state: a password must not be written to the saved instance.
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var classes by remember { mutableStateOf(emptyList<PublicTimetableClass>()) }
    var publicURL by remember { mutableStateOf<String?>(null) }
    var selectedClass by remember { mutableStateOf<PublicTimetableClass?>(null) }
    var groups by remember { mutableStateOf(emptyList<String>()) }
    var selectedGroups by remember { mutableStateOf(emptySet<String>()) }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val trimmedName = name.trim()

    fun resetClasses() { classes = emptyList(); publicURL = null; selectedClass = null; groups = emptyList(); selectedGroups = emptySet() }
    fun selectSchool(school: SchoolDirectorySchool) {
        schoolSearch = school.trimmedName; schoolURL = school.trimmedSchoolURL; schoolName = school.trimmedName; searching = false; resetClasses()
    }
    fun run(block: suspend () -> Unit) {
        if (working) return
        working = true; error = null
        scope.launch {
            try { block() } catch (cancelled: CancellationException) { throw cancelled } catch (failure: Exception) { error = errorMessage(failure) } finally { working = false }
        }
    }
    fun goBack() {
        error = null
        step = if (step == AddFriendStep.GROUPS) AddFriendStep.PUBLIC_CLASS else AddFriendStep.METHOD
    }
    fun finishPublic() {
        val url = publicURL ?: return
        val schoolClass = selectedClass ?: return
        // Every group chosen is the same as no filter at all.
        onAdded(repository.makePublicFriend(trimmedName, url, schoolName, schoolClass, if (groups.isEmpty() || selectedGroups.size == groups.size) null else groups.filter(selectedGroups::contains)))
    }

    LaunchedEffect(directory) {
        fun preselectOwnSchool() {
            if (schoolURL.isNotEmpty() || schoolSearch.isNotEmpty() || ownSchoolURL.isNullOrBlank()) return
            val ownHost = ownSchoolURL.substringAfter("://").substringBefore('/').lowercase()
            schools.firstOrNull { it.trimmedSchoolURL.substringAfter("://").substringBefore('/').lowercase() == ownHost }?.let(::selectSchool)
                ?: run { schoolURL = ownSchoolURL; schoolSearch = ownSchoolURL.substringAfter("://").trimEnd('/') }
        }
        schools = runCatching { directory.cachedSchools() }.getOrDefault(emptyList())
        preselectOwnSchool()
        runCatching { directory.refreshDirectory() }.getOrNull()?.let { schools = it; preselectOwnSchool() }
    }

    GradeySheet(onDismiss = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).testTag("addFriendView"),
            verticalArrangement = Arrangement.spacedBy(GradeySpacing.lg),
        ) {
            Row(Modifier.fillMaxWidth().heightIn(min = GradeyMetrics.minTouch), verticalAlignment = Alignment.CenterVertically) {
                if (step != AddFriendStep.METHOD) PlainLink(stringResource(R.string.friends_back), ::goBack, Modifier.testTag("addFriendBackButton"), enabled = !working, showsArrow = false)
                Box(Modifier.weight(1f))
                ModalCloseButton(
                    onClick = { scope.launch { sheetState.hide() }.invokeOnCompletion { if (!sheetState.isVisible) onDismiss() } },
                    contentDescription = stringResource(R.string.friends_cancel),
                )
            }
            when (step) {
                AddFriendStep.METHOD -> {
                    Hero(stringResource(R.string.friends_add_title), stringResource(R.string.friends_add_message))
                    BrandTextField(
                        name, { name = it.take(60) }, Modifier.fillMaxWidth().testTag("addFriendNameField"),
                        placeholder = stringResource(R.string.friends_add_name), imeAction = ImeAction.Done,
                    )
                    Column(Modifier.alpha(if (trimmedName.isEmpty()) 0.45f else 1f), verticalArrangement = Arrangement.spacedBy(GradeySpacing.sm)) {
                        SectionHeader(stringResource(R.string.friends_add_how), Modifier.padding(horizontal = GradeySpacing.xs))
                        SettingsGroup {
                            MethodRow(
                                HugeIcons.Key01, stringResource(R.string.friends_method_bakalari_title), stringResource(R.string.friends_method_bakalari_message),
                                enabled = trimmedName.isNotEmpty(), tag = "addFriendMethod-bakalari",
                            ) { error = null; step = AddFriendStep.BAKALARI }
                            SettingsRowDivider()
                            MethodRow(
                                HugeIcons.Globe02, stringResource(R.string.friends_method_public_title), stringResource(R.string.friends_method_public_message),
                                enabled = trimmedName.isNotEmpty(), tag = "addFriendMethod-publicClass",
                            ) { error = null; step = AddFriendStep.PUBLIC_CLASS }
                        }
                    }
                }
                AddFriendStep.BAKALARI, AddFriendStep.PUBLIC_CLASS -> {
                    val bakalari = step == AddFriendStep.BAKALARI
                    Hero(
                        stringResource(if (bakalari) R.string.friends_bakalari_title else R.string.friends_public_title),
                        if (bakalari) stringResource(R.string.friends_bakalari_message, trimmedName) else stringResource(R.string.friends_public_message),
                    )
                    // School search: the directory first; an address typed in full is offered when it finds nothing.
                    val results = remember(schoolSearch, schools, searching) { if (searching) SchoolDirectorySearch.results(schoolSearch, schools, limit = 6) else emptyList() }
                    val typed = schoolSearch.trim().takeIf { searching && results.isEmpty() && '.' in it && ' ' !in it }
                    Column(verticalArrangement = Arrangement.spacedBy(GradeySpacing.sm)) {
                        BrandTextField(
                            schoolSearch, { schoolSearch = it; searching = true; schoolURL = ""; schoolName = null; resetClasses() },
                            Modifier.fillMaxWidth().testTag("addFriendSchoolField"), placeholder = stringResource(R.string.friends_school_search),
                            keyboardType = KeyboardType.Uri, leadingIcon = HugeIcons.Search01,
                            trailing = if (schoolURL.isNotEmpty()) ({ GradeyIcon(HugeIcons.CheckmarkCircle02, size = 16.dp, tint = MaterialTheme.colorScheme.primary) }) else null,
                        )
                        results.forEachIndexed { index, school ->
                            if (index > 0) Hairline()
                            SchoolResult(school.trimmedName, "${school.trimmedTown} · ${school.trimmedSchoolURL}") { selectSchool(school) }
                        }
                        if (typed != null) SchoolResult(stringResource(R.string.friends_school_use_address, typed), null) {
                            schoolURL = typed; schoolName = null; searching = false; resetClasses()
                        }
                    }
                    if (bakalari) {
                        BrandTextField(
                            username, { username = it }, Modifier.fillMaxWidth().testTag("addFriendUsernameField"),
                            placeholder = stringResource(R.string.friends_username), imeAction = ImeAction.Next,
                        )
                        BrandTextField(
                            password, { password = it }, Modifier.fillMaxWidth().testTag("addFriendPasswordField"),
                            placeholder = stringResource(R.string.friends_password), keyboardType = KeyboardType.Password,
                            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            trailing = {
                                ToolbarIconButton(
                                    if (passwordVisible) HugeIcons.ViewOff else HugeIcons.View,
                                    stringResource(if (passwordVisible) R.string.friends_hide_password else R.string.friends_show_password),
                                    { passwordVisible = !passwordVisible }, tint = MaterialTheme.gradeyColors.secondaryLabel,
                                )
                            },
                        )
                        error?.let { InlineNotice(it, Modifier.testTag("addFriendError")) }
                        GradeyPrimaryButton(
                            stringResource(R.string.friends_bakalari_connect),
                            { run { val friend = repository.connectBakalari(trimmedName, schoolURL, schoolName, username, password); password = ""; onAdded(friend) } },
                            Modifier.fillMaxWidth().testTag("addFriendConnectButton"),
                            enabled = !working && schoolURL.isNotEmpty() && username.isNotBlank() && password.isNotEmpty(), loading = working,
                        )
                        Text(stringResource(R.string.friends_bakalari_privacy), style = GradeyType.caption, color = MaterialTheme.gradeyColors.secondaryLabel)
                    } else if (classes.isEmpty()) {
                        error?.let { InlineNotice(it, Modifier.testTag("addFriendError")) }
                        GradeyPrimaryButton(
                            stringResource(R.string.friends_public_show_classes),
                            { run { val (url, found) = repository.publicClasses(schoolURL); publicURL = url; classes = found } },
                            Modifier.fillMaxWidth().testTag("addFriendShowClassesButton"), enabled = !working && schoolURL.isNotEmpty(), loading = working,
                        )
                    } else {
                        SectionHeader(stringResource(R.string.friends_public_class), Modifier.padding(horizontal = GradeySpacing.xs))
                        error?.let { InlineNotice(it, Modifier.testTag("addFriendError")) }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(GradeySpacing.sm), verticalArrangement = Arrangement.spacedBy(GradeySpacing.sm)) {
                            classes.forEach { schoolClass ->
                                GradeyTonalButton(
                                    schoolClass.name,
                                    {
                                        // A class with groups asks which ones the friend is in.
                                        run {
                                            selectedClass = schoolClass
                                            val found = repository.publicGroups(publicURL ?: return@run, schoolClass.id)
                                            if (found.isEmpty()) finishPublic() else { groups = found; selectedGroups = found.toSet(); step = AddFriendStep.GROUPS }
                                        }
                                    },
                                    Modifier.widthIn(min = 76.dp).testTag("addFriendClass-${schoolClass.id}"), enabled = !working,
                                )
                            }
                        }
                        if (working) LoadingState()
                    }
                }
                AddFriendStep.GROUPS -> {
                    Hero(stringResource(R.string.friends_groups_title), stringResource(R.string.friends_groups_message, trimmedName))
                    SettingsGroup {
                        groups.forEachIndexed { index, group ->
                            if (index > 0) SettingsRowDivider()
                            SettingsToggleRow(group, null, group in selectedGroups, enabled = true, onChange = { on -> selectedGroups = if (on) selectedGroups + group else selectedGroups - group })
                        }
                    }
                    GradeyPrimaryButton(stringResource(R.string.friends_add_save), ::finishPublic, Modifier.fillMaxWidth().testTag("addFriendSaveGroupsButton"))
                }
            }
        }
    }
}

@Composable
private fun Hero(title: String, message: String) {
    Column(verticalArrangement = Arrangement.spacedBy(GradeySpacing.xs)) {
        Text(title, Modifier.semantics { heading() }, style = GradeyType.title1, color = MaterialTheme.colorScheme.onSurface)
        Text(message, style = GradeyType.subheadline, color = MaterialTheme.gradeyColors.secondaryLabel)
    }
}

@Composable
private fun MethodRow(icon: String, title: String, message: String, enabled: Boolean, tag: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = GradeySpacing.xs, vertical = GradeySpacing.md).testTag(tag),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.md),
    ) {
        SettingsIcon(icon)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = GradeyType.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(message, style = GradeyType.caption, color = MaterialTheme.gradeyColors.secondaryLabel)
        }
        DisclosureIcon()
    }
}

@Composable
private fun SchoolResult(title: String, subtitle: String?, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(horizontal = GradeySpacing.xs, vertical = GradeySpacing.sm),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(title, style = GradeyType.subheadlineSemibold, color = MaterialTheme.colorScheme.onSurface, maxLines = 2)
        if (subtitle != null) Text(subtitle, style = GradeyType.caption, color = MaterialTheme.gradeyColors.secondaryLabel, maxLines = 1)
    }
}
