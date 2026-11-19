package com.bukovinafilip.gradey.feature.ai

import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bukovinafilip.gradey.ui.GradeyColors
import com.bukovinafilip.gradey.ui.GradeyIcon
import com.bukovinafilip.gradey.ui.GradeySpacing
import com.bukovinafilip.gradey.ui.GradeyType
import com.bukovinafilip.gradey.ui.HugeIcons
import com.bukovinafilip.gradey.ui.brandGradient
import com.bukovinafilip.gradey.ui.gradeyColors
import kotlin.math.PI
import kotlin.math.sin

// iOS Gradey AI corner radii that have no GradeyRadius token (GradeyAIMessageRow, GradeyAIChatScreen, GradeyAITheme).
/** User bubble corners. */
internal val BubbleCorner = 22.dp
/** The user bubble's bottom-end "tail" corner. */
internal val BubbleTail = 6.dp
/** Composer and consent disclosure panel. */
internal val PanelCorner = 26.dp
/** Error banner, history rows and data-control rows. */
internal val TileCorner = 18.dp

/** Gradey AI's plain card surface (iOS `gradeyAISurface`): secondary grouped fill and a 6 % label hairline. */
@Composable
internal fun Modifier.aiSurface(shape: Shape): Modifier {
    val fill = MaterialTheme.gradeyColors.secondaryGroupedBackground
    val ring = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
    return this.clip(shape).background(fill, shape).border(1.dp, ring, shape)
}

/** 44 dp round top-bar button on the secondary grouped fill (iOS `GradeyAICircleButton`). */
@Composable
internal fun AICircleButton(icon: String, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Box(
        modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.gradeyColors.secondaryGroupedBackground)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        GradeyIcon(icon, size = 18.dp, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.35f))
    }
}

/** Gradient circle with an icon at half size; [AIOrbIcon] with sparkles is the core `AIOrb`. */
@Composable
internal fun AIOrbIcon(size: Dp, icon: String, modifier: Modifier = Modifier) {
    Box(
        modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.brandGradient).clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) { GradeyIcon(icon, size = size / 2, tint = GradeyColors.OnAccent) }
}

internal enum class AIBannerKind { PAUSED, ERROR }

/** Inline notice for paused service and request errors (iOS `GradeyAIBanner`): colour @12 %, radius 18. */
@Composable
internal fun AIBanner(kind: AIBannerKind, message: String, modifier: Modifier = Modifier, title: String? = null, onDismiss: (() -> Unit)? = null) {
    val tint = when (kind) {
        AIBannerKind.PAUSED -> MaterialTheme.gradeyColors.orange
        AIBannerKind.ERROR -> GradeyColors.Poor
    }
    val tokens = MaterialTheme.gradeyColors
    Row(
        modifier.fillMaxWidth().background(tint.copy(alpha = 0.12f), RoundedCornerShape(TileCorner)).padding(GradeySpacing.md),
        horizontalArrangement = Arrangement.spacedBy(GradeySpacing.sm),
    ) {
        GradeyIcon(if (kind == AIBannerKind.PAUSED) HugeIcons.PauseCircle else HugeIcons.Alert02, size = 17.dp, tint = tint)
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) { }, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (title != null) Text(title, style = GradeyType.subheadlineSemibold, color = MaterialTheme.colorScheme.onSurface)
            Text(
                message,
                style = if (title == null) GradeyType.subheadline else GradeyType.caption,
                color = if (title == null) MaterialTheme.colorScheme.onSurface else tokens.secondaryLabel,
            )
        }
        if (onDismiss != null) {
            val label = stringResource(R.string.gradey_ai_done)
            Box(
                Modifier.size(28.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                    .clickable(role = Role.Button, onClick = onDismiss).semantics { contentDescription = label },
                contentAlignment = Alignment.Center,
            ) { GradeyIcon(HugeIcons.Cancel01, size = 12.dp, tint = tokens.secondaryLabel) }
        }
    }
}

/**
 * Three staggered 7 dp gradient dots while a reply has no text yet (iOS `GradeyAIThinkingIndicator`).
 * The wave is read in `graphicsLayer`, so the dots animate without recomposing.
 */
@Composable
internal fun ThinkingIndicator(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val reduceMotion = remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    val label = stringResource(R.string.gradey_ai_responding)
    val gradient = MaterialTheme.colorScheme.brandGradient
    val phase = if (reduceMotion) null else {
        val transition = rememberInfiniteTransition(label = "thinking")
        transition.animateFloat(
            0f, (2 * PI).toFloat(),
            infiniteRepeatable(tween((2 * PI / 5 * 1000).toInt(), easing = LinearEasing), RepeatMode.Restart),
            label = "thinkingPhase",
        )
    }
    Row(
        modifier.height(22.dp).clearAndSetSemantics { contentDescription = label },
        horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(3) { index ->
            Box(
                Modifier.size(7.dp).graphicsLayer {
                    val wave = phase?.let { (sin(it.value - index * 0.8f) + 1f) / 2f } ?: 0.6f
                    alpha = 0.3f + 0.7f * wave
                    scaleX = 0.8f + 0.3f * wave
                    scaleY = scaleX
                }.background(gradient, CircleShape),
            )
        }
    }
}

/**
 * Centred orb with an optional display title, a status line and an optional action: loading,
 * load failure and the demo/setup notice (iOS `GradeyAIProgressState`, `GradeyAIUnavailableState`).
 */
@Composable
internal fun AIStatusState(
    message: String,
    modifier: Modifier = Modifier,
    title: String? = null,
    icon: String = HugeIcons.Sparkles,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = GradeySpacing.xxl).widthIn(max = 520.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(GradeySpacing.lg),
    ) {
        AIOrbIcon(64.dp, icon)
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(GradeySpacing.sm)) {
            if (title != null) Text(
                title, Modifier.semantics { heading() }, style = GradeyType.display(26),
                color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center,
            )
            Text(message, style = GradeyType.subheadline, color = MaterialTheme.gradeyColors.secondaryLabel, textAlign = TextAlign.Center)
        }
        if (action != null) Box(Modifier.widthIn(max = 280.dp)) { action() }
    }
}

/** Transparent → background fade behind pinned bottom controls (iOS `GradeyAIBottomFade`). */
@Composable
internal fun bottomFadeColors(): List<Color> {
    val base = MaterialTheme.gradeyColors.groupedBackground
    return remember(base) { listOf(base.copy(alpha = 0f), base.copy(alpha = 0.92f), base) }
}
