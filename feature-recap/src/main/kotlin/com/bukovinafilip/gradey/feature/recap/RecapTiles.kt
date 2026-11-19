package com.bukovinafilip.gradey.feature.recap

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bukovinafilip.gradey.domain.Recap
import com.bukovinafilip.gradey.domain.RecapKind
import com.bukovinafilip.gradey.domain.RecapPeriod
import com.bukovinafilip.gradey.ui.GradeyIcon
import com.bukovinafilip.gradey.ui.GradeyMetrics
import com.bukovinafilip.gradey.ui.GradeyRadius
import com.bukovinafilip.gradey.ui.GradeySpacing
import com.bukovinafilip.gradey.ui.GradeyType
import com.bukovinafilip.gradey.ui.HugeIcons
import com.bukovinafilip.gradey.ui.RecapColors
import com.bukovinafilip.gradey.ui.RecapLogo
import com.bukovinafilip.gradey.ui.tabular

/**
 * A flat tile in the recap's own colour with ink text (iOS `recapTile`). Recap is the one place
 * the app fills a surface, so it is set apart from everything else.
 */
@Composable
internal fun RecapTile(
    period: RecapPeriod,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    padding: Dp = GradeySpacing.lg,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(GradeyRadius.card)
    CompositionLocalProvider(LocalContentColor provides RecapColors.Ink) {
        Column(
            modifier.fillMaxWidth().clip(shape).background(period.tileColor()).clickable(role = Role.Button, onClick = onClick).padding(padding),
            content = content,
        )
    }
}

/** A tile's top line: the logo, and the school year for a period that has one. */
@Composable
internal fun RecapTileHeader(period: RecapPeriod, texts: RecapTexts, logoSize: Dp = 18.dp) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.sm)) {
        RecapLogo(size = logoSize)
        Box(Modifier.weight(1f))
        if (period.kind != RecapKind.CALENDAR_YEAR) Text(
            texts.schoolYear(period.schoolYearStart), style = GradeyType.subheadlineSemibold.tabular(), color = RecapColors.Ink.copy(alpha = 0.7f), maxLines = 1,
        )
    }
}

/** The tile on Marks under the report card (iOS `MarksRecapRow`): the newest recap, opening the list. */
@Composable
fun MarksRecapTile(recap: Recap, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val texts = rememberRecapTexts()
    RecapTile(recap.period, onClick, modifier.testTag("marksRecapRow")) {
        RecapTileHeader(recap.period, texts)
        Column(Modifier.padding(top = GradeySpacing.lg), verticalArrangement = Arrangement.spacedBy(GradeySpacing.xs)) {
            Text(texts.headline(recap.period), style = GradeyType.title2)
            Text(texts.summary(recap), style = GradeyType.subheadlineSemibold.tabular(), color = RecapColors.Ink.copy(alpha = 0.8f))
        }
    }
}

/**
 * Today's tile while a new recap is out, until it is opened or hidden (iOS `TodayRecapRow`). The ×
 * sits on the tile, not in it, so it never opens the story.
 */
@Composable
fun TodayRecapTile(recap: Recap, onOpen: () -> Unit, onHide: () -> Unit, modifier: Modifier = Modifier) {
    val texts = rememberRecapTexts()
    Box(modifier.fillMaxWidth()) {
        RecapTile(recap.period, onOpen, Modifier.testTag("todayRecapRow")) {
            RecapLogo()
            Column(
                Modifier.padding(top = GradeySpacing.lg, end = GradeySpacing.xl), verticalArrangement = Arrangement.spacedBy(GradeySpacing.xs),
            ) {
                Text(stringResource(R.string.recap_today_title, texts.headline(recap.period)), style = GradeyType.title2)
                Text(texts.summary(recap), style = GradeyType.subheadlineSemibold.tabular(), color = RecapColors.Ink.copy(alpha = 0.8f))
            }
        }
        val hideLabel = stringResource(R.string.recap_today_hide)
        Box(
            Modifier.align(Alignment.TopEnd).size(GradeyMetrics.minTouch).clip(CircleShape).clickable(role = Role.Button, onClick = onHide)
                .semantics { contentDescription = hideLabel }.testTag("todayRecapHideButton"),
            contentAlignment = Alignment.Center,
        ) { GradeyIcon(HugeIcons.Cancel01, size = 16.dp, tint = RecapColors.Ink.copy(alpha = 0.7f)) }
    }
}
