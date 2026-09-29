package com.bukovinafilip.gradey.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp

/**
 * Three children in reading order: leading action, title, trailing actions.
 * Keep the title centered when it fits; otherwise give it its own full-width row.
 * Measuring the actual text handles translations and Android's nonlinear font scaling.
 */
@Composable
fun AdaptivePageHeader(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content = content, modifier = modifier) { children, constraints ->
        require(children.size == 3)
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val leading = children[0].measure(loose)
        val title = children[1].measure(loose)
        val trailing = children[2].measure(loose)
        val gap = 8.dp.roundToPx()
        val width = constraints.maxWidth
        val actionHeight = maxOf(leading.height, trailing.height)
        val inline = title.width <= width - 2 * (maxOf(leading.width, trailing.width) + gap)
        val height = constraints.constrainHeight(maxOf(
            64.dp.roundToPx(),
            if (inline) maxOf(actionHeight, title.height) + gap * 2
            else title.height + actionHeight + gap * 3,
        ))
        layout(width, height) {
            if (inline) {
                leading.placeRelative(0, (height - leading.height) / 2)
                title.placeRelative((width - title.width) / 2, (height - title.height) / 2)
                trailing.placeRelative(width - trailing.width, (height - trailing.height) / 2)
            } else {
                title.placeRelative((width - title.width) / 2, gap)
                val actionsTop = title.height + gap * 2
                leading.placeRelative(0, actionsTop + (actionHeight - leading.height) / 2)
                trailing.placeRelative(width - trailing.width, actionsTop + (actionHeight - trailing.height) / 2)
            }
        }
    }
}
