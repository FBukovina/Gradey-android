package com.bukovinafilip.gradey.feature.planner

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.bukovinafilip.gradey.domain.PersonalPlanner
import com.bukovinafilip.gradey.domain.PlannerCalendarMirror
import com.bukovinafilip.gradey.domain.PlannerCalendarReport
import com.bukovinafilip.gradey.model.PersonalPlannerItem
import com.bukovinafilip.gradey.model.PlannerWritableCalendar
import com.bukovinafilip.gradey.ui.GradeyColors
import com.bukovinafilip.gradey.ui.GradeyDialog
import com.bukovinafilip.gradey.ui.GradeyMetrics
import com.bukovinafilip.gradey.ui.GradeySpacing
import com.bukovinafilip.gradey.ui.GradeyType
import com.bukovinafilip.gradey.ui.Hairline
import com.bukovinafilip.gradey.ui.HugeIcons
import com.bukovinafilip.gradey.ui.InlineNotice
import com.bukovinafilip.gradey.ui.LoadingState
import com.bukovinafilip.gradey.ui.PlainLink
import com.bukovinafilip.gradey.ui.SettingsGroup
import com.bukovinafilip.gradey.ui.SettingsNote
import com.bukovinafilip.gradey.ui.SettingsRowDivider
import com.bukovinafilip.gradey.ui.SettingsToggleRow
import com.bukovinafilip.gradey.ui.SettingsValueRow
import com.bukovinafilip.gradey.ui.gradeyColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private val CalendarPermissions = arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)

/** Calendar sync for one item (iOS `planner.calendar.section`): the toggle, the chosen calendar and why it may be unavailable. */
@Composable
internal fun PlannerCalendarSettings(item: PersonalPlannerItem, mirror: PlannerCalendarMirror, enabled: Boolean, onChange: (PersonalPlannerItem) -> Unit) {
    val context = LocalContext.current
    val strings = context.resources
    val scope = rememberCoroutineScope()
    var choices by remember { mutableStateOf<List<PlannerWritableCalendar>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var denied by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    fun choose() {
        loading = true
        message = null
        scope.launch {
            try {
                val available = mirror.calendars()
                if (available.isEmpty()) message = strings.getString(R.string.planner_calendar_no_writable)
                else choices = available
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { message = strings.getString(R.string.planner_calendar_unavailable) }
            finally { loading = false }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        denied = !CalendarPermissions.all { result[it] == true }
        if (!denied) choose()
    }
    fun requestSelection() {
        if (mirror.hasAccess) { denied = false; choose() }
        else permission.launch(CalendarPermissions)
    }
    val hasDate = PersonalPlanner.orderingDate(item) != null
    SettingsGroup(title = stringResource(R.string.planner_calendar_sync_title)) {
        SettingsToggleRow(
            title = stringResource(R.string.planner_calendar_sync_toggle), subtitle = null,
            checked = item.calendarID != null, enabled = enabled && !loading && (item.calendarID != null || hasDate),
            onChange = { requested -> if (requested) requestSelection() else onChange(item.copy(calendarID = null, calendarTitle = null)) },
        )
        if (item.calendarID != null) {
            SettingsRowDivider(leadingInset = GradeySpacing.xs)
            SettingsValueRow(
                stringResource(R.string.planner_calendar_change), item.calendarTitle ?: stringResource(R.string.planner_calendar_selected),
                onClick = ::requestSelection, enabled = enabled && !loading,
            )
        }
        Hairline(Modifier.padding(horizontal = GradeySpacing.xs))
        Column(Modifier.padding(top = GradeySpacing.sm), verticalArrangement = Arrangement.spacedBy(GradeySpacing.sm)) {
            SettingsNote(stringResource(R.string.planner_calendar_sync_explanation))
            if (!hasDate) SettingsNote(stringResource(R.string.planner_calendar_date_required))
            if (item.calendarID != null) SettingsNote(stringResource(R.string.planner_calendar_after_save))
            if (loading) LoadingState()
            if (denied) InlineNotice(stringResource(R.string.planner_calendar_permission)) {
                PlainLink(stringResource(R.string.planner_calendar_settings), {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
                }, showsArrow = false)
            }
            message?.let { InlineNotice(it) }
        }
    }
    choices?.let { calendars ->
        GradeyDialog(
            title = stringResource(R.string.planner_calendar_choose), onDismiss = { choices = null },
            confirmLabel = stringResource(R.string.planner_cancel), onConfirm = { choices = null },
        ) {
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(calendars, key = { it.id }) { calendar ->
                    Column(
                        Modifier.fillMaxWidth().heightIn(min = GradeyMetrics.minTouch)
                            .clickable(role = Role.Button) { onChange(item.copy(calendarID = calendar.id, calendarTitle = calendar.title)); choices = null }
                            .padding(vertical = GradeySpacing.sm),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(calendar.title, style = GradeyType.subheadlineBold, color = MaterialTheme.colorScheme.onSurface)
                        Text(calendar.accountName, style = GradeyType.caption, color = MaterialTheme.gradeyColors.secondaryLabel)
                    }
                }
            }
        }
    }
}

/** Notice that some Planner changes have not reached the calendar yet, with a retry. */
@Composable
internal fun PlannerCalendarStatus(report: PlannerCalendarReport?, error: Boolean, mirror: PlannerCalendarMirror, busy: Boolean, modifier: Modifier = Modifier, onRetry: () -> Unit) {
    var denied by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        denied = !CalendarPermissions.all { result[it] == true }
        if (!denied) onRetry()
    }
    if (report?.pending == 0 && !error) return
    InlineNotice(
        stringResource(if (report?.needsPermission == true || denied) R.string.planner_calendar_permission else R.string.planner_calendar_pending),
        modifier, color = MaterialTheme.gradeyColors.orange, icon = HugeIcons.Calendar03,
    ) {
        PlainLink(stringResource(R.string.planner_calendar_retry), { if (mirror.hasAccess) onRetry() else permission.launch(CalendarPermissions) }, enabled = !busy, showsArrow = false)
    }
}
