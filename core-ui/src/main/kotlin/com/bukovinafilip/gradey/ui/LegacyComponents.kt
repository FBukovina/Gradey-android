package com.bukovinafilip.gradey.ui


import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bukovinafilip.gradey.domain.AbsenceRiskLevel
import com.bukovinafilip.gradey.domain.GradeBand


// Legacy components. Every screen migrates to GradeyComponents.kt; this file is deleted once nothing uses it.

// MARK: - Card surface

/** Elevated content surface used throughout the app (iOS `Card`): 20 dp radius, hairline border, soft shadow. */
@Composable
fun GradeyCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    padding: PaddingValues = PaddingValues(GradeySpacing.lg),
    cornerRadius: Dp = GradeyRadius.card,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(GradeySpacing.md),
    enabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(cornerRadius)
    val border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
    val body: @Composable () -> Unit = { Column(Modifier.padding(padding), verticalArrangement = verticalArrangement, content = content) }
    if (onClick == null) Surface(modifier.fillMaxWidth(), shape = shape, color = containerColor, contentColor = contentColor, border = border, shadowElevation = 1.dp, content = body)
    else Surface(onClick = onClick, modifier = modifier.fillMaxWidth(), enabled = enabled, shape = shape, color = containerColor, contentColor = contentColor, border = border, shadowElevation = 1.dp, content = body)
}

/** Header row with a section label on the left and an optional trailing action. */
@Composable
fun SectionHeaderRow(text: String, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.sm)) {
        SectionHeader(text, Modifier.weight(1f))
        trailing()
    }
}

/** Capsule link with a trailing arrow (iOS `TodayLinkLabel`). */
@Composable
fun LinkPill(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, testTag: String? = null, enabled: Boolean = true) {
    val primary = MaterialTheme.colorScheme.primary
    Surface(
        onClick = onClick, enabled = enabled, shape = CircleShape, color = primary.copy(alpha = 0.12f),
        modifier = modifier.heightIn(min = 28.dp).then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
    ) {
        Row(Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.xs)) {
            Text(text, style = GradeyType.captionBold, color = primary, maxLines = 1)
            GradeyIcon(HugeIcons.ArrowRight01, size = 11.dp, tint = primary)
        }
    }
}

/** Tappable [InfoRow] with a disclosure chevron. */
@Composable
fun NavigationRow(icon: String, title: String, subtitle: String?, onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color = MaterialTheme.colorScheme.primary, enabled: Boolean = true) {
    Surface(onClick = onClick, enabled = enabled, color = Color.Transparent, shape = RoundedCornerShape(GradeyRadius.sm), modifier = modifier.fillMaxWidth()) {
        InfoRow(icon, title, subtitle, Modifier.padding(vertical = 6.dp), tint) { DisclosureIcon() }
    }
}

/** Circular progress ring showing absence relative to the school threshold (0–100 % when unknown). */
@Composable
fun AbsenceRiskRing(percentage: Double, threshold: Double?, level: AbsenceRiskLevel, modifier: Modifier = Modifier, size: Dp = 38.dp, lineWidth: Dp = 4.5.dp) {
    val fraction = (if (threshold != null && threshold > 0) percentage / threshold else percentage / 100.0).coerceIn(0.0, 1.0)
    val color = if (threshold == null) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f) else level.riskColor()
    Canvas(modifier.size(size)) {
        val stroke = lineWidth.toPx()
        val inset = Offset(stroke / 2f, stroke / 2f)
        val ring = Size(this.size.width - stroke, this.size.height - stroke)
        drawArc(color.copy(alpha = 0.22f), -90f, 360f, false, inset, ring, style = Stroke(stroke, cap = StrokeCap.Round))
        if (fraction > 0) drawArc(color, -90f, (fraction * 360).toFloat(), false, inset, ring, style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

/** Round glass-style icon button used in page headers and modal chrome. */
@Composable
fun GradeyIconButton(
    icon: String,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    iconSize: Dp = 20.dp,
    tint: Color = MaterialTheme.colorScheme.primary,
    containerColor: Color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    Surface(
        onClick = onClick, enabled = enabled && !loading, shape = CircleShape, color = containerColor,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
        modifier = modifier.size(size).semantics { role = Role.Button },
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (loading) CircularProgressIndicator(Modifier.size(iconSize), color = tint, strokeWidth = 2.dp)
            else GradeyIcon(icon, size = iconSize, tint = tint, contentDescription = contentDescription)
        }
    }
}

// MARK: - Page header

/**
 * Toolbar shared by the primary tabs: Gradey AI on the left, inline title, refresh + settings on
 * the right. Any action can be omitted; the layout keeps the title centred while it fits.
 */
@Composable
fun GradeyPageHeader(
    title: String,
    modifier: Modifier = Modifier,
    onOpenAI: (() -> Unit)? = null,
    aiDescription: String? = null,
    onRefresh: (() -> Unit)? = null,
    isRefreshing: Boolean = false,
    refreshDescription: String? = null,
    onOpenSettings: (() -> Unit)? = null,
    settingsDescription: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val glass = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)
    AdaptivePageHeader(modifier.fillMaxWidth()) {
        Box {
            when {
                leading != null -> leading()
                onOpenAI != null -> Surface(
                    onClick = onOpenAI, shape = CircleShape, color = glass,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)), modifier = Modifier.size(48.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        BrandGradientIcon(HugeIcons.Sparkles, size = 20.dp, contentDescription = aiDescription)
                    }
                }
                else -> Spacer(Modifier.size(48.dp))
            }
        }
        Text(title, style = GradeyType.headline, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Box {
            if (trailing != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.sm)) { trailing() }
            else if (onRefresh != null || onOpenSettings != null) Surface(
                shape = CircleShape, color = glass, border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
                modifier = Modifier.height(48.dp),
            ) {
                Row(Modifier.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (onRefresh != null) HeaderAction(HugeIcons.Refresh04, refreshDescription, onRefresh, loading = isRefreshing)
                    if (onOpenSettings != null) HeaderAction(HugeIcons.Settings01, settingsDescription, onOpenSettings)
                }
            } else Spacer(Modifier.size(48.dp))
        }
    }
}

@Composable
private fun HeaderAction(icon: String, description: String?, onClick: () -> Unit, loading: Boolean = false) {
    val tint = MaterialTheme.colorScheme.primary
    Surface(onClick = onClick, enabled = !loading, shape = CircleShape, color = Color.Transparent, modifier = Modifier.size(40.dp)) {
        Box(contentAlignment = Alignment.Center) {
            if (loading) CircularProgressIndicator(Modifier.size(18.dp), color = tint, strokeWidth = 2.dp)
            else GradeyIcon(icon, size = 20.dp, tint = tint, contentDescription = description)
        }
    }
}

/** The flat 28-point surface used inside Settings and Settings-adjacent sheets. */
@Composable
fun SettingsSurface(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(20.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(GradeySpacing.md),
    content: @Composable ColumnScope.() -> Unit,
) {
    val tokens = MaterialTheme.gradeyColors
    Surface(
        modifier.fillMaxWidth(), shape = RoundedCornerShape(GradeyRadius.xl), color = tokens.settingsSurface, contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, tokens.settingsSurfaceBorder), shadowElevation = 1.dp,
    ) {
        Column(Modifier.padding(padding), verticalArrangement = verticalArrangement, content = content)
    }
}

/** Large display title with the circular close control used by Settings-style sheets. */
@Composable
fun SettingsHeader(title: String, onDismiss: () -> Unit, modifier: Modifier = Modifier, dismissDescription: String? = null) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.md)) {
        Text(title, Modifier.weight(1f).semantics { heading() }, style = GradeyType.display(36), color = MaterialTheme.colorScheme.onSurface)
        ModalCloseButton(onDismiss, contentDescription = dismissDescription, size = 48.dp)
    }
}

/** Overview row inside a [SettingsSurface] group: icon, title, subtitle, optional status line, disclosure. */
@Composable
fun SettingsRow(
    icon: String,
    title: String,
    subtitle: String?,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    status: String? = null,
    statusIcon: String? = null,
    statusColor: Color = MaterialTheme.colorScheme.primary,
    selected: Boolean = false,
    enabled: Boolean = true,
    trailing: (@Composable RowScope.() -> Unit)? = { DisclosureIcon(size = 14.dp) },
) {
    val content: @Composable () -> Unit = {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 84.dp)
                .then(if (selected) Modifier.padding(GradeySpacing.xs).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f), RoundedCornerShape(GradeyRadius.xl - 4.dp)) else Modifier)
                .padding(horizontal = 20.dp, vertical = GradeySpacing.md),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.md),
        ) {
            SettingsIcon(icon)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(GradeySpacing.xs)) {
                Text(title, style = GradeyType.headline, color = MaterialTheme.colorScheme.onSurface)
                if (!subtitle.isNullOrBlank()) Text(subtitle, style = GradeyType.footnote, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (status != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.xs)) {
                    if (statusIcon != null) GradeyIcon(statusIcon, size = 13.dp, tint = statusColor)
                    Text(status, style = GradeyType.caption.copy(fontWeight = FontWeight.SemiBold), color = statusColor)
                }
            }
            trailing?.invoke(this)
        }
    }
    if (onClick == null) Box(modifier) { content() }
    else Surface(onClick = onClick, enabled = enabled, color = Color.Transparent, modifier = modifier.fillMaxWidth(), content = content)
}


/**
 * Lets a horizontally scrolling child extend [amount] past its parent's padding on both sides, so a
 * strip inside a card scrolls edge to edge (SwiftUI `contentMargins` + negative padding).
 */
fun Modifier.bleedHorizontally(amount: Dp): Modifier = layout { measurable, constraints ->
    val extra = (amount * 2).roundToPx()
    val placeable = measurable.measure(constraints.copy(maxWidth = constraints.maxWidth + extra, minWidth = (constraints.minWidth + extra).coerceAtMost(constraints.maxWidth + extra)))
    layout((placeable.width - extra).coerceAtLeast(0), placeable.height) { placeable.placeRelative(-extra / 2, 0) }
}

/** Transparent scrolling column; migrate to [GradeyLazyColumn]. */
@Composable
fun GradeyScreen(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(GradeySpacing.lg),
    content: @Composable () -> Unit,
) {
    Column(modifier = modifier.padding(contentPadding), verticalArrangement = Arrangement.spacedBy(GradeySpacing.lg)) { content() }
}

/** Display page title; migrate to [ModalHeader] or [PlainHeader]. */
@Composable
fun GradeyHero(title: String, subtitle: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(GradeySpacing.xs)) {
        Text(title, style = GradeyType.display(34), color = MaterialTheme.colorScheme.onSurface)
        Text(subtitle, style = GradeyType.callout, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun GradeySectionCard(modifier: Modifier = Modifier, title: String? = null, content: @Composable () -> Unit) {
    GradeyCard(modifier = modifier) {
        if (title != null) SectionHeader(title)
        content()
    }
}

@Composable
fun MetadataRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = GradeyType.subheadline, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = GradeyType.subheadlineBold)
    }
}

@Composable
fun SettingsCloseButton(onClick: () -> Unit, contentDescription: String? = null, modifier: Modifier = Modifier, size: Dp = 48.dp, icon: String = HugeIcons.Cancel01) {
    ModalCloseButton(onClick, modifier, contentDescription, size, icon)
}
