package com.bukovinafilip.gradey

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bukovinafilip.gradey.ui.GradeyIcon
import com.bukovinafilip.gradey.ui.GradeyType
import com.bukovinafilip.gradey.ui.HugeIcons
import com.bukovinafilip.gradey.ui.gradeyColors

/** What the shell animates between: the destination and how deep the back stack is. */
@Immutable
internal data class ShellDestination(val tab: AppTab, val depth: Int)

internal fun GradeyState.shellDestination() = ShellDestination(tab, backStack.size)

private const val PushMillis = 320
private const val FadeThroughOut = 90
private const val FadeThroughIn = 210

/**
 * Animates between destinations like Android's own motion: tabs fade through, pushed screens slide in
 * from the end and back out again, full-screen surfaces rise from the bottom. Each destination keeps
 * its saved state (scroll positions, expanded rows) for as long as [scopeKey] stays the same.
 */
@Composable
internal fun DestinationHost(
    destination: ShellDestination,
    scopeKey: String,
    modifier: Modifier = Modifier,
    content: @Composable (AppTab) -> Unit,
) {
    val holder = rememberSaveableStateHolder()
    AnimatedContent(
        targetState = destination,
        modifier = modifier.fillMaxSize(),
        contentKey = { it.tab },
        transitionSpec = { shellTransition(initialState, targetState) },
        label = "destination",
    ) { target ->
        holder.SaveableStateProvider("${target.tab.name}:$scopeKey") {
            Box(Modifier.fillMaxSize()) { content(target.tab) }
        }
    }
}

private fun shellTransition(from: ShellDestination, to: ShellDestination): ContentTransform {
    val opening = to.tab.kind == DestinationKind.FULL_SCREEN && from.tab.kind != DestinationKind.FULL_SCREEN
    val closing = from.tab.kind == DestinationKind.FULL_SCREEN && to.tab.kind != DestinationKind.FULL_SCREEN
    return when {
        opening -> (slideInVertically(tween(PushMillis, easing = FastOutSlowInEasing)) { it / 6 } + fadeIn(tween(PushMillis)))
            .togetherWith(fadeOut(tween(PushMillis, delayMillis = 60)))
        closing -> fadeIn(tween(PushMillis))
            .togetherWith(slideOutVertically(tween(PushMillis, easing = FastOutSlowInEasing)) { it / 6 } + fadeOut(tween(PushMillis - 80)))
            .apply { targetContentZIndex = -1f }
        to.depth > from.depth -> (slideInHorizontally(tween(PushMillis, easing = FastOutSlowInEasing)) { it } )
            .togetherWith(slideOutHorizontally(tween(PushMillis, easing = FastOutSlowInEasing)) { -it / 4 } + fadeOut(tween(PushMillis)))
        to.depth < from.depth -> (slideInHorizontally(tween(PushMillis, easing = FastOutSlowInEasing)) { -it / 4 } + fadeIn(tween(PushMillis)))
            .togetherWith(slideOutHorizontally(tween(PushMillis, easing = FastOutSlowInEasing)) { it })
            .apply { targetContentZIndex = -1f }
        else -> (fadeIn(tween(FadeThroughIn, delayMillis = FadeThroughOut, easing = LinearOutSlowInEasing)) +
            scaleIn(tween(FadeThroughIn, delayMillis = FadeThroughOut, easing = LinearOutSlowInEasing), initialScale = 0.96f))
            .togetherWith(fadeOut(tween(FadeThroughOut)))
    }
}

internal val CoreTabs = listOf(AppTab.TODAY, AppTab.SUBJECTS, AppTab.ABSENCE, AppTab.TIMETABLE)
internal val AllTabs = CoreTabs + AppTab.STRAVACZ

/** Tab glyphs match the iOS tab bar: sun, checkmark badge, calendar-remove, calendar, restaurant. */
private fun AppTab.tabIcon(): String = when (this) {
    AppTab.TODAY -> HugeIcons.Sun03
    AppTab.SUBJECTS -> HugeIcons.CheckmarkBadge02
    AppTab.ABSENCE -> HugeIcons.CalendarRemove02
    AppTab.TIMETABLE -> HugeIcons.Calendar03
    else -> HugeIcons.Restaurant02
}

/** The Android bottom bar: Hugeicons, Space Grotesk labels and the brand tint, on the screen background. */
@Composable
internal fun GradeyNavigationBar(selected: AppTab, showMeals: Boolean, onSelect: (AppTab) -> Unit, modifier: Modifier = Modifier) {
    val tokens = MaterialTheme.gradeyColors
    val primary = MaterialTheme.colorScheme.primary
    val separator = tokens.separator
    NavigationBar(
        modifier = modifier.drawBehind {
            drawLine(separator, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 0.5.dp.toPx())
        },
        containerColor = tokens.groupedBackground,
        tonalElevation = 0.dp,
    ) {
        (if (showMeals) AllTabs else CoreTabs).forEach { tab ->
            val label = stringResource(tab.labelRes)
            NavigationBarItem(
                selected = selected == tab,
                onClick = { onSelect(tab) },
                modifier = Modifier.testTag("tab-${tab.name.lowercase()}"),
                icon = { GradeyIcon(tab.tabIcon(), size = 24.dp) },
                label = { Text(label, style = GradeyType.tabLabel, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = primary, selectedTextColor = primary, indicatorColor = primary.copy(alpha = 0.12f),
                    unselectedIconColor = tokens.secondaryLabel, unselectedTextColor = tokens.secondaryLabel,
                ),
            )
        }
    }
}

/** Shows or hides the bottom bar with a short slide so pushed and full-screen destinations feel native. */
@Composable
internal fun BottomBarVisibility(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(tween(220)) + fadeIn(tween(220)),
        exit = shrinkVertically(tween(220)) + fadeOut(tween(160)),
    ) { content() }
}
