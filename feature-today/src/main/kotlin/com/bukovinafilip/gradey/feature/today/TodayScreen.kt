package com.bukovinafilip.gradey.feature.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.bukovinafilip.gradey.domain.GradeMath
import com.bukovinafilip.gradey.domain.SchoolInsightState
import com.bukovinafilip.gradey.model.DashboardData
import com.bukovinafilip.gradey.model.LinkedSchoolAccount
import com.bukovinafilip.gradey.model.PersonalPlannerItem
import com.bukovinafilip.gradey.model.StravaCZMenu
import com.bukovinafilip.gradey.model.TimetableWeek
import com.bukovinafilip.gradey.ui.GradeyLazyColumn
import com.bukovinafilip.gradey.ui.GradeySpacing
import com.bukovinafilip.gradey.ui.GradeyTopBar
import com.bukovinafilip.gradey.ui.GradeyType
import com.bukovinafilip.gradey.ui.ReconnectNotice
import com.bukovinafilip.gradey.ui.StatDivider
import com.bukovinafilip.gradey.ui.StatTile
import com.bukovinafilip.gradey.ui.isScrolled
import com.bukovinafilip.gradey.ui.rememberSchoolTime

/**
 * Today tab (iOS 2.8.1 `TodayView`): reconnect notice, greeting with three stats, the Recap tile,
 * Now and next, Recent marks, Planner and Lunch. The shell draws the background and bottom bar.
 */
@Composable
fun TodayScreen(
    dashboard: DashboardData,
    timetable: TimetableWeek?,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onOpenAI: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenMarks: () -> Unit,
    onOpenTimetable: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenSubject: ((String) -> Unit)? = null,
    recapTile: (@Composable () -> Unit)? = null,
    mealMenu: StravaCZMenu? = null,
    hasMealAccount: Boolean = false,
    onOpenMeals: (() -> Unit)? = null,
    linkedAccounts: List<LinkedSchoolAccount> = emptyList(),
    activeAccountID: String? = null,
    onReconnectAccount: ((LinkedSchoolAccount) -> Unit)? = null,
    insightState: SchoolInsightState? = null,
    plannerItems: List<PersonalPlannerItem> = emptyList(),
    schoolScope: String? = null,
    onOpenPlanner: (() -> Unit)? = null,
    onOpenPlannerItem: ((String) -> Unit)? = null,
) {
    val locale = LocalConfiguration.current.locales[0]
    val listState = rememberLazyListState()
    val scrolled by remember { derivedStateOf { listState.isScrolled } }
    val subjects = dashboard.marksResponse.subjects
    val average = remember(subjects, locale) { GradeMath.formattedAverage(GradeMath.overallAverage(subjects), locale) }
    val insights = remember(insightState, schoolScope) { insightState?.takeIf { it.scope == schoolScope } }
    val newMarkIDs = remember(insights) {
        insights?.observations.orEmpty().filter { it.seenAt == null }.flatMap { it.addedMarkIDs }.toSet()
    }
    val sources = insights?.sources.orEmpty()
    val marksFailed = sources["marks"]?.failed == true
    val reconnect = remember(linkedAccounts, activeAccountID) { accountRequiringReconnect(linkedAccounts, activeAccountID) }
    val accountName = remember(linkedAccounts, activeAccountID) { linkedAccounts.firstOrNull { it.id == activeAccountID }?.displayName }

    Column(modifier.fillMaxSize()) {
        GradeyTopBar(
            title = stringResource(R.string.today_title),
            scrolled = scrolled,
            onOpenAI = onOpenAI, aiDescription = stringResource(R.string.today_gradey_ai),
            onRefresh = onRefresh, isRefreshing = isRefreshing, refreshDescription = stringResource(R.string.today_refresh),
            onOpenSettings = onOpenSettings, settingsDescription = stringResource(R.string.today_settings),
        )
        GradeyLazyColumn(Modifier.weight(1f).testTag("todayScroll"), state = listState) {
            if (reconnect != null) item(key = "reconnect", contentType = "reconnect") {
                ReconnectNotice(
                    title = stringResource(R.string.today_reconnect_title),
                    message = listOfNotNull(
                        reconnect.displayName.trim().takeIf { it.isNotEmpty() },
                        reconnect.actionRequiredReason?.trim()?.takeIf { it.isNotEmpty() } ?: stringResource(R.string.today_reconnect_fallback),
                    ).joinToString("\n"),
                    actionLabel = stringResource(R.string.today_reconnect_action),
                    onAction = { onReconnectAccount?.invoke(reconnect) },
                    modifier = Modifier.testTag("todaySchoolConnectionNotice"),
                    actionEnabled = onReconnectAccount != null,
                )
            }
            item(key = "hero", contentType = "hero") {
                TodayHero(
                    fullName = dashboard.user?.fullName, classAbbrev = dashboard.user?.classAbbrev, accountName = accountName,
                    average = average, timetable = timetable, newMarks = newMarkIDs.size,
                )
            }
            if (recapTile != null) item(key = "recap", contentType = "recap") { recapTile() }
            item(key = "nowAndNext", contentType = "nowAndNext") { NowAndNextSection(timetable, sources, onOpenTimetable) }
            item(key = "recentMarks", contentType = "recentMarks") {
                RecentMarksSection(subjects, newMarkIDs, marksFailed, onOpenMarks, onOpenSubject)
            }
            if (onOpenPlanner != null) item(key = "planner", contentType = "planner") {
                PlannerSection(plannerItems, schoolScope, onOpenPlanner, onOpenPlannerItem)
            }
            if (hasMealAccount) item(key = "lunch", contentType = "lunch") { LunchSection(mealMenu, onOpenMeals) }
        }
    }
}

// MARK: - Hero

/** Greeting in display 30 and three stats separated by hairline dividers (iOS `TodayHero`, no gradient slab). */
@Composable
private fun TodayHero(
    fullName: String?,
    classAbbrev: String?,
    accountName: String?,
    average: String,
    timetable: TimetableWeek?,
    newMarks: Int,
) {
    val clock = rememberSchoolTime()
    // Only the greeting slot and the date matter here, so the hero recomposes a few times a day.
    val slot by remember { derivedStateOf { greetingSlot(clock.value.hour) } }
    val today by remember { derivedStateOf { clock.value.toLocalDate() } }
    val name = remember(fullName, classAbbrev, accountName) { greetingName(fullName, classAbbrev, accountName) }
    val salutation = stringResource(
        when (slot) {
            GreetingSlot.MORNING -> R.string.today_greeting_morning
            GreetingSlot.AFTERNOON -> R.string.today_greeting_afternoon
            GreetingSlot.EVENING -> R.string.today_greeting_evening
        },
    )
    val lessons = remember(timetable, today) { activeLessonCount(timetable, today) }
    val accessibility = LocalDensity.current.fontScale > 1.3f

    Column(
        Modifier.fillMaxWidth().padding(horizontal = GradeySpacing.xs).testTag("todayHero"),
        verticalArrangement = Arrangement.spacedBy(GradeySpacing.lg),
    ) {
        Text(
            if (name != null) "$salutation, $name" else salutation,
            style = GradeyType.display(30), color = MaterialTheme.colorScheme.onSurface,
            maxLines = 3, overflow = TextOverflow.Ellipsis,
        )
        val averageLabel = stringResource(R.string.today_hero_average)
        val lessonsLabel = stringResource(R.string.today_hero_lessons_today)
        val newMarksLabel = stringResource(R.string.today_hero_new_marks)
        val lessonsValue = lessons?.toString() ?: "–"
        if (accessibility) {
            Column(verticalArrangement = Arrangement.spacedBy(GradeySpacing.md)) {
                StatTile(averageLabel, average, Modifier.testTag("todayStatAverage"))
                StatTile(lessonsLabel, lessonsValue, Modifier.testTag("todayStatLessons"))
                StatTile(newMarksLabel, newMarks.toString(), Modifier.testTag("todayStatNewMarks"))
            }
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.md)) {
                StatTile(averageLabel, average, Modifier.weight(1f).testTag("todayStatAverage"))
                StatDivider()
                StatTile(lessonsLabel, lessonsValue, Modifier.weight(1f).testTag("todayStatLessons"))
                StatDivider()
                StatTile(newMarksLabel, newMarks.toString(), Modifier.weight(1f).testTag("todayStatNewMarks"))
            }
        }
    }
}
