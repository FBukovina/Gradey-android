package com.bukovinafilip.gradey.feature.subjects

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bukovinafilip.gradey.domain.GradeBand
import com.bukovinafilip.gradey.domain.GradeMath
import com.bukovinafilip.gradey.ui.GradeyIcon
import com.bukovinafilip.gradey.ui.GradeySpacing
import com.bukovinafilip.gradey.ui.GradeyType
import com.bukovinafilip.gradey.ui.HugeIcons
import com.bukovinafilip.gradey.ui.color
import com.bukovinafilip.gradey.ui.gradeyColors
import com.bukovinafilip.gradey.ui.staticColor
import com.bukovinafilip.gradey.ui.tabular
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min

private val LabelGap = 6.dp
private val LineWidth = 3.dp
private val HighlightDiameter = 13.dp
private val HighlightRing = 3.dp
private val MarkRing = 1.5.dp
private const val ScrubHoldMillis = 180L

/**
 * The subject page's "Average over time" chart (iOS `AverageHistoryChart`, detail style): a reversed
 * grade axis (1 on top), the running average as a 3 dp monotone line over an 18 % fill, every mark as a
 * weight-sized dot behind it, and a legend that becomes the average on the date under the finger while
 * scrubbing. Geometry and text are measured once per size; scrubbing only redraws.
 */
@Composable
internal fun AverageHistoryChart(data: AverageChartData, lineColor: Color, modifier: Modifier = Modifier) {
    val locale = LocalConfiguration.current.locales[0]
    val tokens = MaterialTheme.gradeyColors
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val measurer = rememberTextMeasurer()
    var selected by remember(data) { mutableIntStateOf(-1) }

    val axisFormat = remember(locale) { NumberFormat.getNumberInstance(locale).apply { minimumFractionDigits = 0; maximumFractionDigits = 1 } }
    val axisDates = remember(locale) { DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "dMMM"), locale) }
    val readoutDates = remember(locale) { DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "dMMMy"), locale) }
    val yStyle = GradeyType.caption2Semibold.tabular().copy(color = tokens.secondaryLabel)
    val xStyle = GradeyType.caption2.copy(color = tokens.secondaryLabel)
    val yLabels = remember(data, axisFormat, yStyle, measurer) { data.yTicks.map { measurer.measure(axisFormat.format(it), yStyle) } }
    val xLabels = remember(data, axisDates, xStyle, measurer) { data.xTicks.map { measurer.measure(axisDates.format(LocalDate.ofEpochDay(it)), xStyle) } }
    val trailingInset = remember(yLabels, density) { (yLabels.maxOfOrNull { it.size.width } ?: 0) + with(density) { LabelGap.roundToPx() } }

    val chartDescription = stringResource(R.string.subjects_average_over_time) + ": " +
        GradeMath.formattedAverage(data.points.first().value, locale) + " → " + GradeMath.formattedAverage(data.points.last().value, locale)
    val grid = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    val rule = tokens.secondaryLabel.copy(alpha = tokens.secondaryLabel.alpha * 0.5f)
    val highlightFill = tokens.secondaryGroupedBackground
    val isDark = tokens.isDark

    Column(modifier, verticalArrangement = Arrangement.spacedBy(GradeySpacing.sm)) {
        AverageReadout(
            sample = data.points.getOrNull(selected),
            lineColor = lineColor,
            dates = readoutDates,
            locale = locale,
        )
        Spacer(
            Modifier.weight(1f).fillMaxWidth()
                .semantics { contentDescription = chartDescription }
                .pointerInput(data, trailingInset) {
                    fun select(x: Float) {
                        val width = (size.width - trailingInset).coerceAtLeast(1)
                        val day = data.xStart + (x / width).coerceIn(0f, 1f) * (data.xEnd - data.xStart)
                        val index = nearestSample(data.points, day)
                        if (index != selected) {
                            selected = index
                            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        }
                    }
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val slop = viewConfiguration.touchSlop
                        var position = down.position
                        var drag = Offset.Zero
                        // Scrub after a short hold or a sideways drag; a vertical drag stays with the list.
                        val decision = withTimeoutOrNull(ScrubHoldMillis) {
                            var horizontal: Boolean? = null
                            while (horizontal == null) {
                                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                                if (change == null || !change.pressed || change.isConsumed) {
                                    horizontal = false
                                } else {
                                    drag += change.positionChange()
                                    position = change.position
                                    if (drag.getDistance() > slop) horizontal = abs(drag.x) > abs(drag.y)
                                }
                            }
                            horizontal
                        }
                        if (decision == false) return@awaitEachGesture
                        select(position.x)
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            change.consume()
                            select(change.position.x)
                        }
                        selected = -1
                    }
                }
                .drawWithCache {
                    val plotRight = (size.width - trailingInset).coerceAtLeast(1f)
                    val xLabelHeight = xLabels.maxOfOrNull { it.size.height } ?: 0
                    val plotBottom = (size.height - xLabelHeight - GradeySpacing.xs.toPx()).coerceAtLeast(1f)
                    val xSpan = data.xEnd - data.xStart
                    val ySpan = data.yWorst - data.yBest
                    fun xFor(day: Double): Float = ((day - data.xStart) / xSpan).toFloat() * plotRight
                    fun yFor(value: Double): Float = ((value - data.yBest) / ySpan).toFloat() * plotBottom

                    val points = data.points.map { Offset(xFor(it.day.toDouble()), yFor(it.value)) }
                    val line = if (points.size > 1) monotonePath(points) else null
                    val area = line?.let {
                        Path().apply {
                            addPath(it)
                            lineTo(points.last().x, plotBottom)
                            lineTo(points.first().x, plotBottom)
                            close()
                        }
                    }
                    val areaBrush = Brush.verticalGradient(listOf(lineColor.copy(alpha = 0.18f), Color.Transparent), startY = 0f, endY = plotBottom)
                    val dots = data.marks.map { mark ->
                        val radius = min(7.0 + mark.weight * 1.4, 16.0).toFloat() / 2f * density.density
                        Triple(Offset(xFor(mark.day.toDouble()), yFor(mark.value)), radius, mark.band.staticColor(isDark))
                    }
                    val gridEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 4.dp.toPx()))
                    val ruleEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx()))
                    val lineStroke = Stroke(LineWidth.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                    val ringWidth = MarkRing.toPx()
                    val highlightRadius = HighlightDiameter.toPx() / 2f
                    val highlightRingWidth = HighlightRing.toPx()
                    val labelGap = LabelGap.toPx()

                    onDrawBehind {
                        // Dashed whole-grade grid with trailing labels.
                        data.yTicks.forEachIndexed { index, tick ->
                            val y = yFor(tick)
                            drawLine(grid, Offset(0f, y), Offset(plotRight, y), strokeWidth = 1.dp.toPx(), pathEffect = gridEffect)
                            val label = yLabels[index]
                            val top = (y - label.size.height / 2f).coerceIn(0f, (size.height - label.size.height).coerceAtLeast(0f))
                            drawText(label, topLeft = Offset(plotRight + labelGap, top))
                        }
                        data.xTicks.forEachIndexed { index, day ->
                            val label = xLabels[index]
                            val left = (xFor(day.toDouble()) - label.size.width / 2f).coerceIn(0f, (plotRight - label.size.width).coerceAtLeast(0f))
                            drawText(label, topLeft = Offset(left, plotBottom + GradeySpacing.xs.toPx()))
                        }
                        val current = selected
                        clipRect(0f, 0f, plotRight, plotBottom) {
                            if (area != null) drawPath(area, areaBrush)
                            // Marks sit behind the line so the average stays the dominant read.
                            val dotAlpha = if (current >= 0) 0.55f else 1f
                            dots.forEach { (center, radius, color) ->
                                drawCircle(color.copy(alpha = 0.22f * dotAlpha), radius, center)
                                drawCircle(color.copy(alpha = dotAlpha), radius - ringWidth / 2f, center, style = Stroke(ringWidth))
                            }
                            if (line != null) drawPath(line, lineColor, style = lineStroke)
                            val highlighted = points.getOrNull(current)
                            if (highlighted != null) {
                                drawLine(rule, Offset(highlighted.x, 0f), Offset(highlighted.x, plotBottom), strokeWidth = 1.dp.toPx(), pathEffect = ruleEffect)
                            }
                            val dot = highlighted ?: points.lastOrNull()
                            if (dot != null) {
                                drawCircle(highlightFill, highlightRadius, dot)
                                drawCircle(lineColor, highlightRadius - highlightRingWidth / 2f, dot, style = Stroke(highlightRingWidth))
                            }
                        }
                    }
                },
        )
    }
}

/** Legend above the chart; while scrubbing it reads out the average on the selected date. */
@Composable
private fun AverageReadout(sample: AverageSample?, lineColor: Color, dates: DateTimeFormatter, locale: Locale) {
    val secondary = MaterialTheme.gradeyColors.secondaryLabel
    val style = GradeyType.captionSemibold
    val average = stringResource(R.string.subjects_average)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 20.dp)
            .then(if (sample == null) Modifier.clearAndSetSemantics { } else Modifier.semantics(mergeDescendants = true) { }),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(GradeySpacing.md),
    ) {
        if (sample != null) {
            Text(dates.format(LocalDate.ofEpochDay(sample.day)), Modifier.weight(1f), style = style, color = secondary, maxLines = 1)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.xs)) {
                Text(average, style = style, color = secondary, maxLines = 1)
                Text(GradeMath.formattedAverage(sample.value, locale), style = GradeyType.subheadlineBold.tabular(), color = lineColor, maxLines = 1)
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LegendDot(secondary, 9.dp)
                Text(stringResource(R.string.subjects_marks_section), style = style, color = secondary, maxLines = 1)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(14.dp, 3.dp).background(lineColor, CircleShape))
                Text(average, style = style, color = secondary, maxLines = 1)
            }
        }
    }
}

/** A mark dot as the legend shows it: 22 % fill and a 1.5 dp ring. */
@Composable
private fun LegendDot(color: Color, diameter: Dp) {
    Box(
        Modifier.size(diameter).drawBehind {
            val ring = MarkRing.toPx()
            drawCircle(color.copy(alpha = color.alpha * 0.22f))
            drawCircle(color, size.minDimension / 2f - ring / 2f, style = Stroke(ring))
        },
    )
}

/**
 * Change of the average across the charted period (iOS `AverageDeltaChip`): the arrow follows the
 * sign, red when the average got worse, brand green when it improved, on a 12 % capsule.
 */
@Composable
internal fun AverageDeltaChip(delta: Double, modifier: Modifier = Modifier) {
    val locale = LocalConfiguration.current.locales[0]
    val worse = delta > 0
    val color = if (worse) GradeBand.POOR.color() else MaterialTheme.colorScheme.primary
    val description = stringResource(if (worse) R.string.subjects_average_worsened else R.string.subjects_average_improved, abs(delta))
    val text = remember(delta, locale) { String.format(locale, "%+.2f", delta) }
    Row(
        modifier.background(color.copy(alpha = 0.12f), CircleShape)
            .padding(horizontal = GradeySpacing.sm, vertical = GradeySpacing.xs)
            .clearAndSetSemantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        GradeyIcon(if (delta < 0) HugeIcons.ArrowDownRight01 else HugeIcons.ArrowUpRight01, size = 12.dp, tint = color)
        Text(text, style = GradeyType.captionBold.tabular(), color = color, maxLines = 1)
    }
}

/**
 * Monotone cubic through [points] (the `.monotone` interpolation Swift Charts uses, after d3's
 * `curveMonotoneX`): it never overshoots between samples, so the average never appears to pass a
 * value it did not reach. Points must have strictly increasing x.
 */
internal fun monotonePath(points: List<Offset>): Path {
    val path = Path()
    if (points.isEmpty()) return path
    path.moveTo(points[0].x, points[0].y)
    val n = points.size
    if (n == 1) return path
    if (n == 2) {
        path.lineTo(points[1].x, points[1].y)
        return path
    }
    val tangents = FloatArray(n)
    for (i in 1 until n - 1) {
        val h0 = points[i].x - points[i - 1].x
        val h1 = points[i + 1].x - points[i].x
        val s0 = (points[i].y - points[i - 1].y) / h0
        val s1 = (points[i + 1].y - points[i].y) / h1
        val p = (s0 * h1 + s1 * h0) / (h0 + h1)
        val tangent = (sign(s0) + sign(s1)) * minOf(abs(s0), abs(s1), 0.5f * abs(p))
        tangents[i] = if (tangent.isFinite()) tangent else 0f
    }
    tangents[0] = endTangent(points[0], points[1], tangents[1])
    tangents[n - 1] = endTangent(points[n - 2], points[n - 1], tangents[n - 2])
    for (i in 0 until n - 1) {
        val start = points[i]
        val end = points[i + 1]
        val third = (end.x - start.x) / 3f
        path.cubicTo(start.x + third, start.y + third * tangents[i], end.x - third, end.y - third * tangents[i + 1], end.x, end.y)
    }
    return path
}

private fun sign(value: Float): Float = if (value < 0f) -1f else 1f

private fun endTangent(start: Offset, end: Offset, neighbour: Float): Float {
    val h = end.x - start.x
    return if (h != 0f) (3f * (end.y - start.y) / h - neighbour) / 2f else neighbour
}
