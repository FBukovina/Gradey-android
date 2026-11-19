package com.bukovinafilip.gradey.feature.recap

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.unit.dp
import com.bukovinafilip.gradey.domain.Recap
import com.bukovinafilip.gradey.ui.GradeyIcon
import com.bukovinafilip.gradey.ui.GradeyLazyColumn
import com.bukovinafilip.gradey.ui.GradeyMetrics
import com.bukovinafilip.gradey.ui.GradeySpacing
import com.bukovinafilip.gradey.ui.GradeyTopBar
import com.bukovinafilip.gradey.ui.GradeyType
import com.bukovinafilip.gradey.ui.HugeIcons
import com.bukovinafilip.gradey.ui.PlainSectionTitle
import com.bukovinafilip.gradey.ui.RecapColors
import com.bukovinafilip.gradey.ui.RecapMark
import com.bukovinafilip.gradey.ui.gradeyColors
import com.bukovinafilip.gradey.ui.isScrolled
import com.bukovinafilip.gradey.ui.tabular

/** Narrowest a small tile gets before the grid drops a column (iOS `GridItem(.adaptive(minimum: 150))`). */
private val TileMinWidth = 150.dp

/** One row of the list: a school year's title, or up to a row's worth of its tiles. */
private sealed interface RecapListRow {
    data class Title(val schoolYear: Int) : RecapListRow
    data class Tiles(val schoolYear: Int, val index: Int, val recaps: List<Recap>) : RecapListRow
}

/**
 * Every recap that is out (iOS `RecapHubView`): the newest one large, the rest in a grid by school
 * year. Pushed from Marks; the shell draws the background and the bottom bar.
 */
@Composable
fun RecapListScreen(overview: RecapOverview, onOpen: (String) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val texts = rememberRecapTexts()
    val listState = rememberLazyListState()
    val scrolled by remember { derivedStateOf { listState.isScrolled } }
    val contentWidth = minOf(LocalConfiguration.current.screenWidthDp.dp, GradeyMetrics.contentMaxWidth) - GradeyMetrics.screenPadding * 2
    val columns = ((contentWidth + GradeySpacing.md) / (TileMinWidth + GradeySpacing.md)).toInt().coerceAtLeast(1)
    val rows = remember(overview, columns) {
        overview.listed.drop(1).groupBy { it.period.schoolYearStart }.toSortedMap(compareByDescending { it }).flatMap { (schoolYear, recaps) ->
            listOf(RecapListRow.Title(schoolYear)) + recaps.chunked(columns).mapIndexed { index, chunk -> RecapListRow.Tiles(schoolYear, index, chunk) }
        }
    }
    Column(modifier.fillMaxSize()) {
        GradeyTopBar(title = stringResource(R.string.recap_title), scrolled = scrolled, onBack = onBack, backDescription = stringResource(R.string.recap_back))
        GradeyLazyColumn(Modifier.weight(1f).testTag("recapHub"), state = listState, itemSpacing = 0.dp) {
            val latest = overview.latest
            if (latest == null) item(key = "empty", contentType = "empty") {
                Text(
                    stringResource(R.string.recap_hub_empty), Modifier.padding(horizontal = GradeySpacing.xs),
                    style = GradeyType.subheadline, color = MaterialTheme.gradeyColors.secondaryLabel,
                )
            } else {
                item(key = "latest", contentType = "latest") { LatestTile(latest, texts) { onOpen(latest.period.id) } }
                items(
                    rows,
                    key = { row -> if (row is RecapListRow.Tiles) "tiles-${row.schoolYear}-${row.index}" else "title-${(row as RecapListRow.Title).schoolYear}" },
                    contentType = { it::class },
                ) { row ->
                    when (row) {
                        is RecapListRow.Title -> PlainSectionTitle(texts.schoolYear(row.schoolYear), Modifier.padding(top = GradeySpacing.xl))
                        is RecapListRow.Tiles -> Row(
                            Modifier.padding(top = GradeySpacing.md).height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(GradeySpacing.md),
                        ) {
                            row.recaps.forEach { recap -> SmallTile(recap, texts, Modifier.weight(1f).fillMaxHeight()) { onOpen(recap.period.id) } }
                            repeat(columns - row.recaps.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
    }
}

/** The newest recap: its headline large, with a play button beside it. */
@Composable
private fun LatestTile(recap: Recap, texts: RecapTexts, onClick: () -> Unit) {
    val period = recap.period
    // Beside the play button a large headline gets a word a line, so at large text sizes the button goes under it.
    val stacked = LocalDensity.current.fontScale >= 1.5f
    RecapTile(period, onClick, Modifier.testTag("recapRow-${period.id}"), padding = GradeySpacing.xl) {
        RecapTileHeader(period, texts, logoSize = 22.dp)
        Spacer(Modifier.height(GradeySpacing.xxl))
        val words: @Composable (Modifier) -> Unit = { wordsModifier ->
            Column(wordsModifier, verticalArrangement = Arrangement.spacedBy(GradeySpacing.sm)) {
                Text(texts.headline(period), style = GradeyType.display(38))
                Text(texts.summary(recap), style = GradeyType.headline.tabular(), color = RecapColors.Ink.copy(alpha = 0.8f))
            }
        }
        val play: @Composable () -> Unit = {
            Box(Modifier.size(GradeyMetrics.minTouch).background(RecapColors.Ink, CircleShape), contentAlignment = Alignment.Center) {
                GradeyIcon(HugeIcons.Play, size = 18.dp, tint = period.tileColor())
            }
        }
        if (stacked) Column(verticalArrangement = Arrangement.spacedBy(GradeySpacing.lg)) { words(Modifier); play() }
        else Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.md)) { words(Modifier.weight(1f)); play() }
    }
}

/** An earlier recap in the grid: the mark, its short title and what is in it. */
@Composable
private fun SmallTile(recap: Recap, texts: RecapTexts, modifier: Modifier = Modifier, onClick: () -> Unit) {
    RecapTile(recap.period, onClick, modifier.testTag("recapRow-${recap.period.id}"), padding = GradeySpacing.md) {
        // Room for a summary on two lines, so a row of tiles lines up.
        Column(Modifier.heightIn(min = 104.dp), verticalArrangement = Arrangement.spacedBy(GradeySpacing.xs)) {
            RecapMark(Modifier.size(18.dp))
            Spacer(Modifier.weight(1f).heightIn(min = GradeySpacing.md))
            Text(texts.shortTitle(recap.period), style = GradeyType.headline.tabular())
            Text(texts.summary(recap), style = GradeyType.captionSemibold.tabular(), color = RecapColors.Ink.copy(alpha = 0.8f))
        }
    }
}
