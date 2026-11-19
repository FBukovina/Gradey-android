package com.bukovinafilip.gradey.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Gradey Recap's solid slide colours (iOS `RecapPalette`). Light and saturated, so the dark [Ink]
 * text keeps at least 4.5 : 1 on every one, in light and dark mode alike. Recap is the one place
 * the app fills a surface, so it is set apart from everything else.
 */
object RecapColors {
    val Ink = GradeyColors.OnAccent
    val Emerald = Color(0xFF3DDC97)
    val Teal = Color(0xFF3CCFCF)
    val Sky = Color(0xFF7CC8FF)
    val Lime = Color(0xFFC6F36B)
    val Amber = Color(0xFFFFC145)
    val Rose = Color(0xFFFF8FA3)
    val Lilac = Color(0xFFB9A6FF)
}

/**
 * The Recap mark (iOS `RecapMark`): a ring that rewinds, with a solid head at the top that points
 * back. One colour, so it sits on every slide and tile colour.
 */
@Composable
fun RecapMark(modifier: Modifier = Modifier, color: Color = LocalContentColor.current) {
    Canvas(modifier.clearAndSetSemantics { }) { drawRecapMark(color) }
}

private fun DrawScope.drawRecapMark(color: Color) {
    val side = size.minDimension
    val origin = Offset(center.x - side / 2f, center.y - side / 2f)
    val radius = side * 0.335f
    // Off centre, so the head stays inside the frame.
    val ring = Offset(origin.x + side * 0.52f, origin.y + side * 0.58f)
    // From the top, around the right and the bottom, up the left side.
    drawArc(
        color = color, startAngle = -90f, sweepAngle = 288f, useCenter = false,
        topLeft = Offset(ring.x - radius, ring.y - radius), size = Size(radius * 2f, radius * 2f),
        style = Stroke(width = side * 0.14f, cap = StrokeCap.Round),
    )
    val half = side * 0.2f
    val baseX = ring.x + side * 0.01f
    val middle = ring.y - radius
    val head = Path().apply {
        moveTo(baseX, middle - half)
        lineTo(baseX - side * 0.25f, middle)
        lineTo(baseX, middle + half)
        close()
    }
    val paint = Paint().apply {
        this.color = color
        pathEffect = PathEffect.cornerPathEffect(side * 0.035f)
    }
    drawIntoCanvas { it.drawPath(head, paint) }
}

/**
 * The Gradey Recap logo (iOS `RecapLogo`): the mark next to "Recap", in the content colour around
 * it. A fixed size, like any logo, so the mark and the word stay in proportion at every text size.
 *
 * @param size the height of "Recap"; the mark is as tall.
 * @param showsGradey a small "Gradey" above, where the logo stands alone.
 */
@Composable
fun RecapLogo(modifier: Modifier = Modifier, size: Dp = 18.dp, showsGradey: Boolean = false, color: Color = LocalContentColor.current) {
    val density = LocalDensity.current
    val wordSize = with(density) { size.toSp() }
    Column(
        modifier.clearAndSetSemantics { contentDescription = "Gradey Recap" },
        verticalArrangement = Arrangement.spacedBy(size * 0.08f),
    ) {
        if (showsGradey) BasicText(
            "Gradey",
            style = GradeyType.headline.copy(fontSize = wordSize * 0.5f, lineHeight = wordSize * 0.6f, fontWeight = FontWeight.Bold, color = color.copy(alpha = 0.7f)),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(size * 0.25f)) {
            RecapMark(Modifier.size(size), color)
            BasicText("Recap", style = GradeyType.headline.copy(fontSize = wordSize, lineHeight = wordSize * 1.15f, fontWeight = FontWeight.Bold, color = color), maxLines = 1)
        }
    }
}
