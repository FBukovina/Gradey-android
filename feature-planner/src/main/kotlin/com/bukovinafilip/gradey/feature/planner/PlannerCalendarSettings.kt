package com.bukovinafilip.gradey.feature.planner

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.bukovinafilip.gradey.domain.PersonalPlanner
import com.bukovinafilip.gradey.domain.PlannerCalendarMirror
import com.bukovinafilip.gradey.domain.PlannerCalendarReport
import com.bukovinafilip.gradey.model.PersonalPlannerItem
import com.bukovinafilip.gradey.model.PlannerWritableCalendar
import com.bukovinafilip.gradey.ui.GradeySectionCard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private val CalendarPermissions = arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)

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
    GradeySectionCard(title = strings.getString(R.string.planner_calendar_sync_title)) {
        Text(strings.getString(R.string.planner_calendar_sync_explanation), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(strings.getString(R.string.planner_calendar_sync_toggle), modifier = Modifier.weight(1f))
            Switch(checked = item.calendarID != null, onCheckedChange = { requested ->
                if (requested) requestSelection() else onChange(item.copy(calendarID = null, calendarTitle = null))
            }, enabled = enabled && !loading && (item.calendarID != null || PersonalPlanner.orderingDate(item) != null))
        }
        if (PersonalPlanner.orderingDate(item) == null) Text(strings.getString(R.string.planner_calendar_date_required), style = MaterialTheme.typography.bodySmall)
        if (item.calendarID != null) {
            Text(item.calendarTitle ?: strings.getString(R.string.planner_calendar_selected))
            TextButton(onClick = ::requestSelection, enabled = enabled && !loading) { Text(strings.getString(R.string.planner_calendar_change)) }
            Text(strings.getString(R.string.planner_calendar_after_save), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (denied) {
            Text(strings.getString(R.string.planner_calendar_permission), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))) }) {
                Text(strings.getString(R.string.planner_calendar_settings))
            }
        }
        message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
    choices?.let { calendars -> AlertDialog(
        onDismissRequest = { choices = null },
        title = { Text(strings.getString(R.string.planner_calendar_choose)) },
        text = { LazyColumn(Modifier.heightIn(max = 360.dp)) {
            items(calendars, key = { it.id }) { calendar ->
                TextButton(onClick = { onChange(item.copy(calendarID = calendar.id, calendarTitle = calendar.title)); choices = null }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth()) {
                        Text(calendar.title, style = MaterialTheme.typography.titleSmall)
                        Text(calendar.accountName, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        } },
        confirmButton = { TextButton(onClick = { choices = null }) { Text(strings.getString(R.string.planner_cancel)) } },
    ) }
}

@Composable
internal fun PlannerCalendarStatus(report: PlannerCalendarReport?, error: Boolean, mirror: PlannerCalendarMirror, busy: Boolean, onRetry: () -> Unit) {
    val strings = LocalContext.current.resources
    var denied by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        denied = !CalendarPermissions.all { result[it] == true }
        if (!denied) onRetry()
    }
    if (report?.pending == 0 && !error) return
    GradeySectionCard(title = strings.getString(R.string.planner_calendar_sync_title)) {
        Text(strings.getString(if (report?.needsPermission == true || denied) R.string.planner_calendar_permission else R.string.planner_calendar_pending), color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = { if (mirror.hasAccess) onRetry() else permission.launch(CalendarPermissions) }, enabled = !busy) { Text(strings.getString(R.string.planner_calendar_retry)) }
    }
}
