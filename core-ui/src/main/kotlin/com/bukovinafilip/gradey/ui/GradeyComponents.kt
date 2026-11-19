package com.bukovinafilip.gradey.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp
import com.bukovinafilip.gradey.domain.AbsenceRiskLevel
import com.bukovinafilip.gradey.domain.GradeBand

typealias BoxScopeContent = @Composable BoxScope.() -> Unit

// MARK: - Backgrounds

/**
 * The app-wide teal glow behind the main tabs (iOS `AuroraBackground.standard`). The three radial
 * gradients are built once per size in [drawWithCache], not on every frame.
 */
@Composable
fun AuroraBackground(modifier: Modifier = Modifier, content: BoxScopeContent = {}) {
    val colors = MaterialTheme.colorScheme
    val base = MaterialTheme.gradeyColors.groupedBackground
    val primary = colors.primary
    val secondary = colors.secondary
    CompositionLocalProvider(LocalContentColor provides colors.onBackground) {
        Box(
            modifier.fillMaxSize().drawWithCache {
                val w = size.width
                val h = size.height
                fun glow(color: Color, diameter: Float, center: Offset): Triple<Brush, Float, Offset> = Triple(
                    Brush.radialGradient(
                        0f to color, 0.55f to color.copy(alpha = color.alpha * 0.35f), 1f to Color.Transparent,
                        center = center, radius = diameter / 2f,
                    ),
                    diameter / 2f, center,
                )
                val glows = listOf(
                    glow(primary.copy(alpha = 0.30f), w * 1.6f, Offset(w * 0.52f, h * 0.02f)),
                    glow(secondary.copy(alpha = 0.22f), w * 1.15f, Offset(w * 1.05f, h * 0.26f)),
                    glow(primary.copy(alpha = 0.16f), w * 1.05f, Offset(-w * 0.08f, h * 0.62f)),
                )
                onDrawBehind {
                    drawRect(base)
                    glows.forEach { (brush, radius, center) -> drawCircle(brush, radius, center) }
                }
            },
            content = content,
        )
    }
}

/** Plain background for Settings, Gradey AI and sheets (iOS `SettingsBackground`). */
@Composable
fun SettingsBackground(modifier: Modifier = Modifier, content: BoxScopeContent) {
    val tokens = MaterialTheme.gradeyColors
    val tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.018f)
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
        Box(
            modifier.fillMaxSize().drawBehind {
                drawRect(if (tokens.isDark) tokens.settingsBackground else tokens.groupedBackground)
                if (!tokens.isDark) drawRect(tint)
            },
            content = content,
        )
    }
}

// MARK: - Screen scaffolding

/**
 * The scrolling column every main screen uses: 16 dp edge padding, [GradeyMetrics.sectionSpacing]
 * between items, and content capped at [maxWidth] and centred on wide screens.
 */
@Composable
fun GradeyLazyColumn(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    maxWidth: Dp = GradeyMetrics.contentMaxWidth,
    topPadding: Dp = GradeySpacing.sm,
    bottomPadding: Dp = GradeySpacing.xl,
    itemSpacing: Dp = GradeyMetrics.sectionSpacing,
    content: LazyListScope.() -> Unit,
) {
    // The list itself is capped and centred, so a caller's test tag sits on the scrollable node
    // and nothing is subcomposed to learn the width.
    LazyColumn(
        state = state,
        modifier = modifier.fillMaxSize().wrapContentWidth().widthIn(max = maxWidth + GradeyMetrics.screenPadding * 2).fillMaxWidth(),
        contentPadding = PaddingValues(start = GradeyMetrics.screenPadding, end = GradeyMetrics.screenPadding, top = topPadding, bottom = bottomPadding),
        verticalArrangement = Arrangement.spacedBy(itemSpacing),
        content = content,
    )
}

// MARK: - Headers

/**
 * Plain header that replaced the green hero cards: a secondary label, then a 52 sp number with an
 * optional suffix and a summary on the same baseline (iOS `MarksHero`, `AbsenceHero`).
 */
@Composable
fun PlainHeader(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
    valueSuffix: String? = null,
    summary: String? = null,
    below: (@Composable ColumnScope.() -> Unit)? = null,
) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = GradeySpacing.xs).semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label, style = GradeyType.subheadlineSemibold, color = MaterialTheme.gradeyColors.secondaryLabel)
        Row(horizontalArrangement = Arrangement.spacedBy(GradeySpacing.md)) {
            Row(Modifier.alignByBaseline(), horizontalArrangement = Arrangement.spacedBy(GradeySpacing.xs)) {
                Text(value, Modifier.alignByBaseline(), style = GradeyType.display(52).tabular(), color = valueColor, maxLines = 1)
                if (valueSuffix != null) {
                    Text(valueSuffix, Modifier.alignByBaseline(), style = GradeyType.title3.copy(fontWeight = FontWeight.SemiBold), color = MaterialTheme.gradeyColors.secondaryLabel, maxLines = 1)
                }
            }
            if (summary != null) {
                Text(summary, Modifier.alignByBaseline().weight(1f, fill = false), style = GradeyType.subheadline, color = MaterialTheme.gradeyColors.secondaryLabel, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        below?.invoke(this)
    }
}

/** Large Settings-style title with the circular close control (iOS `SettingsModalHeader`). */
@Composable
fun ModalHeader(title: String, onClose: () -> Unit, modifier: Modifier = Modifier, closeDescription: String? = null, size: Int = 36) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.md)) {
        Text(title, Modifier.weight(1f).semantics { heading() }, style = GradeyType.display(size), color = MaterialTheme.colorScheme.onSurface)
        ModalCloseButton(onClose, contentDescription = closeDescription, size = 48.dp)
    }
}

/** Circular close control: 15 dp cross on primary @10 % with a hairline ring (iOS `GradelyModalCloseLabel`). */
@Composable
fun ModalCloseButton(onClick: () -> Unit, modifier: Modifier = Modifier, contentDescription: String? = null, size: Dp = 32.dp, icon: String = HugeIcons.Cancel01) {
    Box(
        modifier.size(max(size, GradeyMetrics.minTouch)).clip(CircleShape).clickable(onClick = onClick, role = Role.Button),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(size).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f), CircleShape)
                .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.09f), CircleShape),
            contentAlignment = Alignment.Center,
        ) { GradeyIcon(icon, size = 15.dp, tint = MaterialTheme.colorScheme.onSurface, contentDescription = contentDescription) }
    }
}

/** Small uppercase tracked label (iOS `SectionHeader`). */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.gradeyColors.secondaryLabel) {
    Text(text.uppercase(), modifier = modifier.semantics { heading() }, style = GradeyType.sectionHeader, color = color)
}

// MARK: - Plain sections

/**
 * Title of a plain section in title3 bold; a link title shows a chevron (iOS `PlainSectionTitle`).
 * [trailing] sits on the right, for example a segmented control.
 */
@Composable
fun PlainSectionTitle(
    title: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(modifier.fillMaxWidth().padding(horizontal = GradeySpacing.xs), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).then(
                if (onClick != null) Modifier.clip(RoundedCornerShape(GradeyRadius.xs)).clickable(onClick = onClick, role = Role.Button) else Modifier,
            ).heightIn(min = 32.dp).semantics { heading() },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(GradeySpacing.xs),
        ) {
            Text(title, style = GradeyType.title3, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f, fill = false))
            if (onClick != null) GradeyIcon(HugeIcons.ArrowRight01, size = 15.dp, tint = MaterialTheme.gradeyColors.secondaryLabel)
        }
        if (trailing != null) Row(verticalAlignment = Alignment.CenterVertically, content = trailing)
    }
}

/**
 * One row of a plain section: a full-width hairline above (unless [showDivider] is false) and the
 * content with 12 dp vertical padding, straight on the background (iOS `PlainSection` rows).
 * Works inside [PlainSection] and as a LazyColumn item.
 */
@Composable
fun PlainRow(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    showDivider: Boolean = true,
    enabled: Boolean = true,
    verticalAlignment: Alignment.Vertical = Alignment.CenterVertically,
    horizontalSpacing: Dp = GradeySpacing.md,
    onClickLabel: String? = null,
    content: @Composable RowScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth().padding(horizontal = GradeySpacing.xs)) {
        if (showDivider) Hairline()
        Row(
            Modifier.fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable(enabled = enabled, onClickLabel = onClickLabel, role = Role.Button, onClick = onClick) else Modifier)
                .heightIn(min = GradeyMetrics.minTouch)
                .padding(vertical = GradeySpacing.md),
            verticalAlignment = verticalAlignment,
            horizontalArrangement = Arrangement.spacedBy(horizontalSpacing),
            content = content,
        )
    }
}

/** Full-width hairline separator in the iOS separator colour. */
@Composable
fun Hairline(modifier: Modifier = Modifier, color: Color = MaterialTheme.gradeyColors.separator) {
    HorizontalDivider(modifier, thickness = GradeyMetrics.hairline, color = color)
}

/** Rows collected by [PlainSection]. */
class PlainSectionScope internal constructor() {
    internal val rows = mutableListOf<PlainSectionRow>()

    fun row(onClick: (() -> Unit)? = null, key: Any? = null, verticalAlignment: Alignment.Vertical = Alignment.CenterVertically, content: @Composable RowScope.() -> Unit) {
        rows += PlainSectionRow(key ?: rows.size, onClick, verticalAlignment, content)
    }
}

@Immutable
internal class PlainSectionRow(val key: Any, val onClick: (() -> Unit)?, val verticalAlignment: Alignment.Vertical, val content: @Composable RowScope.() -> Unit)

/**
 * A section with no card: optional title, then rows separated by hairlines, then an optional
 * caption footer (iOS `PlainSection`). Use [PlainRow] items directly for long lazy lists.
 */
@Composable
fun PlainSection(
    modifier: Modifier = Modifier,
    title: String? = null,
    onTitleClick: (() -> Unit)? = null,
    titleTrailing: (@Composable RowScope.() -> Unit)? = null,
    footer: String? = null,
    rows: PlainSectionScope.() -> Unit,
) {
    val scope = PlainSectionScope().apply(rows)
    Column(modifier.fillMaxWidth()) {
        if (title != null) {
            PlainSectionTitle(title, onClick = onTitleClick, trailing = titleTrailing)
            Spacer(Modifier.height(GradeySpacing.sm))
        }
        scope.rows.forEach { row ->
            androidx.compose.runtime.key(row.key) {
                PlainRow(onClick = row.onClick, verticalAlignment = row.verticalAlignment, content = row.content)
            }
        }
        if (footer != null) SectionFooter(footer)
    }
}

/** Caption under a section. */
@Composable
fun SectionFooter(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.fillMaxWidth().padding(start = GradeySpacing.xs, end = GradeySpacing.xs, top = GradeySpacing.xs), style = GradeyType.caption, color = MaterialTheme.gradeyColors.secondaryLabel)
}

/** Chevron at the end of tappable rows (tertiary label colour). */
@Composable
fun DisclosureIcon(modifier: Modifier = Modifier, size: Dp = 13.dp) {
    GradeyIcon(HugeIcons.ArrowRight01, modifier, size = size, tint = MaterialTheme.gradeyColors.tertiaryLabel)
}

// MARK: - Rows

/** Rounded tile holding an icon on 12 % of its tint (iOS 34×34 info-row tile). */
@Composable
fun IconTile(icon: String, tint: Color, modifier: Modifier = Modifier, size: Dp = 34.dp, iconSize: Dp = 16.dp, cornerRadius: Dp = GradeyRadius.sm, background: Color = tint.copy(alpha = 0.12f)) {
    Box(modifier.size(size).clip(RoundedCornerShape(cornerRadius)).background(background), contentAlignment = Alignment.Center) {
        GradeyIcon(icon, size = iconSize, tint = tint)
    }
}

/** Icon tile + bold title + caption subtitle (iOS `TodayInfoRow`). Place it inside a [PlainRow]. */
@Composable
fun RowScope.InfoRowContent(icon: String, title: String, subtitle: String?, tint: Color = MaterialTheme.colorScheme.primary) {
    IconTile(icon, tint)
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = GradeyType.subheadlineBold, color = MaterialTheme.colorScheme.onSurface)
        if (!subtitle.isNullOrBlank()) Text(subtitle, style = GradeyType.caption, color = MaterialTheme.gradeyColors.secondaryLabel)
    }
}

/** Stand-alone info row; kept for screens that lay rows out themselves. */
@Composable
fun InfoRow(
    icon: String,
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.primary,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(GradeySpacing.md), verticalAlignment = Alignment.CenterVertically) {
        InfoRowContent(icon, title, subtitle, tint)
        trailing?.invoke(this)
    }
}

/** Uppercase caption label over a bold value (iOS `StatTile`). */
@Composable
fun StatTile(title: String, value: String, modifier: Modifier = Modifier, icon: String? = null, valueColor: Color = MaterialTheme.colorScheme.onSurface) {
    Column(modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(GradeySpacing.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.xs)) {
            if (icon != null) GradeyIcon(icon, size = 11.dp, tint = MaterialTheme.gradeyColors.secondaryLabel)
            Text(title.uppercase(), style = GradeyType.caption2Semibold, color = MaterialTheme.gradeyColors.secondaryLabel, maxLines = 2)
        }
        Text(value, style = GradeyType.subheadlineBold.tabular(), color = valueColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** 1×30 divider between [StatTile]s on Today. */
@Composable
fun StatDivider(modifier: Modifier = Modifier) {
    Box(modifier.width(1.dp).height(30.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)))
}

// MARK: - Grade badge and chips

enum class GradeBadgeSize(val minWidth: Dp, val horizontalPadding: Dp, val verticalPadding: Dp) {
    SMALL(40.dp, GradeySpacing.sm, GradeySpacing.sm),
    REGULAR(52.dp, GradeySpacing.md, GradeySpacing.sm),
    LARGE(72.dp, GradeySpacing.lg, GradeySpacing.md),
}

/** Mark or average in its band colour on the band's soft fill, radius 16 (iOS `GradeBadge`). */
@Composable
fun GradeBadge(text: String, band: GradeBand, modifier: Modifier = Modifier, size: GradeBadgeSize = GradeBadgeSize.REGULAR) {
    val style = when (size) {
        GradeBadgeSize.SMALL -> GradeyType.headline
        GradeBadgeSize.REGULAR -> GradeyType.title3
        GradeBadgeSize.LARGE -> GradeyType.largeTitle
    }.tabular()
    Box(
        modifier.defaultMinSize(minWidth = size.minWidth).clip(RoundedCornerShape(GradeyRadius.md)).background(band.softColor())
            .padding(horizontal = size.horizontalPadding, vertical = size.verticalPadding),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = style, color = band.color(), maxLines = 1, textAlign = TextAlign.Center)
    }
}

/** Small tonal capsule for metadata (iOS `StatusChip`). */
@Composable
fun StatusChip(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.gradeyColors.secondaryLabel, icon: String? = null) {
    Row(
        modifier.background(color.copy(alpha = 0.14f), CircleShape).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.xs),
    ) {
        if (icon != null) GradeyIcon(icon, size = 12.dp, tint = color)
        Text(text, style = GradeyType.captionSemibold.tabular(), color = color, maxLines = 1)
    }
}

/** Compact capsule with a mark or count (iOS `InlineMark`, the latest-mark pill on Marks). */
@Composable
fun InlinePill(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text, modifier.background(color.copy(alpha = 0.14f), CircleShape).padding(horizontal = 8.dp, vertical = 3.dp),
        style = GradeyType.captionBold.tabular(), color = color, maxLines = 1,
    )
}

// MARK: - Absence risk

@Composable
fun AbsenceRiskLevel.riskColor(): Color = when (this) {
    AbsenceRiskLevel.OVER_LIMIT, AbsenceRiskLevel.HIGH -> GradeyColors.Poor
    AbsenceRiskLevel.WATCH -> MaterialTheme.gradeyColors.orange
    AbsenceRiskLevel.SAFE -> MaterialTheme.colorScheme.primary
    AbsenceRiskLevel.UNAVAILABLE -> MaterialTheme.gradeyColors.secondaryLabel
}

/** 6 dp capsule: absence relative to the school limit, or 0–100 % when unknown (iOS `RiskCapsuleBar`). */
@Composable
fun RiskCapsuleBar(percentage: Double, threshold: Double?, level: AbsenceRiskLevel, modifier: Modifier = Modifier) {
    val fraction = (if (threshold != null && threshold > 0) percentage / threshold else percentage / 100.0).coerceIn(0.0, 1.0).toFloat()
    val track = MaterialTheme.gradeyColors.tertiaryFill
    val fill = if (threshold == null) MaterialTheme.gradeyColors.secondaryLabel.copy(alpha = 0.5f) else level.riskColor()
    Box(
        modifier.fillMaxWidth().height(6.dp).clearAndSetSemantics { }.drawBehind {
            val radius = CornerRadius(size.height / 2f)
            drawRoundRect(track, cornerRadius = radius)
            if (fraction > 0f) {
                val width = (size.width * fraction).coerceAtLeast(6.dp.toPx())
                drawRoundRect(fill, size = size.copy(width = width), cornerRadius = radius)
            }
        },
    )
}

// MARK: - Buttons

/**
 * Full-width gradient CTA (iOS `PrimaryButtonStyle`): onAccent headline, radius 16, a primary glow,
 * 0.98 scale and 0.85 opacity while pressed, 0.45 opacity when disabled.
 */
@Composable
fun GradeyPrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, loading: Boolean = false, icon: String? = null) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val active = enabled && !loading
    val scale by animateFloatAsState(if (active && pressed) 0.98f else 1f, tween(150), label = "pressScale")
    val alpha by animateFloatAsState(if (!enabled) 0.45f else if (pressed) 0.85f else 1f, tween(150), label = "pressAlpha")
    val shape = RoundedCornerShape(GradeyRadius.md)
    val primary = MaterialTheme.colorScheme.primary
    Box(
        modifier.fillMaxWidth().heightIn(min = GradeyMetrics.buttonHeight)
            .graphicsLayer { scaleX = scale; scaleY = scale; this.alpha = alpha }
            .then(if (enabled) Modifier.shadow(16.dp, shape, ambientColor = primary.copy(alpha = 0.45f), spotColor = primary.copy(alpha = 0.45f)) else Modifier)
            .clip(shape)
            .background(MaterialTheme.colorScheme.brandGradient)
            .clickable(interactionSource = interaction, indication = null, enabled = active, role = Role.Button, onClick = onClick)
            .padding(horizontal = GradeySpacing.lg, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) CircularProgressIndicator(Modifier.size(20.dp), color = GradeyColors.OnAccent, strokeWidth = 2.5.dp)
        else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.sm)) {
            if (icon != null) GradeyIcon(icon, size = 18.dp, tint = GradeyColors.OnAccent)
            Text(text, style = GradeyType.headline, color = GradeyColors.OnAccent, textAlign = TextAlign.Center)
        }
    }
}

/** Bordered secondary button: primary text on a 12 % primary fill (iOS `.bordered` tinted Brand.primary). */
@Composable
fun GradeyTonalButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: String? = null, color: Color = MaterialTheme.colorScheme.primary, loading: Boolean = false) {
    Row(
        modifier.heightIn(min = GradeyMetrics.minTouch).clip(RoundedCornerShape(GradeyRadius.md))
            .background(color.copy(alpha = if (enabled) 0.12f else 0.06f))
            .clickable(enabled = enabled && !loading, role = Role.Button, onClick = onClick)
            .padding(horizontal = GradeySpacing.lg, vertical = GradeySpacing.md),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.sm, Alignment.CenterHorizontally),
    ) {
        val tint = color.copy(alpha = if (enabled) 1f else 0.45f)
        if (loading) CircularProgressIndicator(Modifier.size(16.dp), color = tint, strokeWidth = 2.dp)
        else if (icon != null) GradeyIcon(icon, size = 16.dp, tint = tint)
        Text(text, style = GradeyType.subheadlineBold, color = tint, textAlign = TextAlign.Center)
    }
}

/** A secondary action as green text with a chevron (iOS `PlainLinkLabel`). */
@Composable
fun PlainLink(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, color: Color = MaterialTheme.colorScheme.primary, showsArrow: Boolean = true, fillWidth: Boolean = false) {
    Row(
        modifier.then(if (fillWidth) Modifier.fillMaxWidth() else Modifier).heightIn(min = GradeyMetrics.minTouch)
            .clip(RoundedCornerShape(GradeyRadius.xs)).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = GradeySpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(GradeySpacing.xs, if (fillWidth) Alignment.CenterHorizontally else Alignment.Start),
    ) {
        val tint = color.copy(alpha = if (enabled) 1f else 0.45f)
        Text(text, style = GradeyType.subheadlineSemibold, color = tint)
        if (showsArrow) GradeyIcon(HugeIcons.ArrowRight01, size = 13.dp, tint = tint)
    }
}

/** Destructive text action (sign out, delete). */
@Composable
fun DestructiveLink(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    PlainLink(text, onClick, modifier, enabled, color = GradeyColors.Poor, showsArrow = false)
}

/** Toolbar icon button: 20 dp glyph in primary in a 48 dp touch target. */
@Composable
fun ToolbarIconButton(icon: String, contentDescription: String?, onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color = MaterialTheme.colorScheme.primary, enabled: Boolean = true, loading: Boolean = false, badge: String? = null) {
    Box(modifier.size(48.dp).clip(CircleShape).clickable(enabled = enabled && !loading, role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        if (loading) RotatingIcon(icon, tint, contentDescription)
        else GradeyIcon(icon, size = 20.dp, tint = tint.copy(alpha = if (enabled) 1f else 0.45f), contentDescription = contentDescription)
        if (badge != null) {
            Text(
                badge, Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 4.dp).background(MaterialTheme.colorScheme.primary, CircleShape)
                    .padding(horizontal = 5.dp, vertical = 1.dp),
                style = GradeyType.caption2Bold.tabular(), color = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}

/** Refresh glyph that keeps turning while work is in progress (iOS refresh toolbar button). */
@Composable
private fun RotatingIcon(icon: String, tint: Color, contentDescription: String?) {
    val transition = rememberInfiniteTransition(label = "refresh")
    val angle by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "refreshAngle")
    GradeyIcon(icon, Modifier.graphicsLayer { rotationZ = angle }, size = 20.dp, tint = tint, contentDescription = contentDescription)
}

/** The sparkles button that opens Gradey AI, filled with the brand gradient. */
@Composable
fun GradeyAIButton(onClick: () -> Unit, contentDescription: String?, modifier: Modifier = Modifier) {
    Box(modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        BrandGradientIcon(HugeIcons.Sparkles, size = 20.dp, contentDescription = contentDescription)
    }
}

/** Icon filled with the brand gradient (Gradey AI). */
@Composable
fun BrandGradientIcon(icon: String, modifier: Modifier = Modifier, size: Dp = 20.dp, contentDescription: String? = null) {
    val fontSize = with(LocalDensity.current) { size.toSp() }
    val semantics = if (contentDescription == null) Modifier.clearAndSetSemantics { }
    else Modifier.semantics { this.contentDescription = contentDescription; role = Role.Image }
    val brush = MaterialTheme.colorScheme.brandGradient
    Box(modifier.size(size).then(semantics), contentAlignment = Alignment.Center) {
        BasicText(
            text = icon, maxLines = 1, softWrap = false,
            style = TextStyle(
                fontFamily = HugeiconsFontFamily, fontSize = fontSize, lineHeight = fontSize,
                brush = brush, fontFeatureSettings = "liga", textAlign = TextAlign.Center,
                platformStyle = PlatformTextStyle(includeFontPadding = false),
                lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
            ),
        )
    }
}

/** Gradient circle with an onAccent sparkle at half size (iOS `GradeyAIOrb`). */
@Composable
fun AIOrb(size: Dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.brandGradient).clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
        GradeyIcon(HugeIcons.Sparkles, size = size / 2, tint = GradeyColors.OnAccent)
    }
}

// MARK: - Top bar

/**
 * Toolbar of every main tab: Gradey AI on the left, the inline title in the middle, refresh and
 * Settings on the right. Pushed screens pass [onBack] instead of [onOpenAI]. The bar is transparent
 * until content scrolls under it, then fades in the screen background.
 */
@Composable
fun GradeyTopBar(
    title: String,
    modifier: Modifier = Modifier,
    scrolled: Boolean = false,
    onBack: (() -> Unit)? = null,
    backDescription: String? = null,
    onOpenAI: (() -> Unit)? = null,
    aiDescription: String? = null,
    onRefresh: (() -> Unit)? = null,
    isRefreshing: Boolean = false,
    refreshDescription: String? = null,
    onOpenSettings: (() -> Unit)? = null,
    settingsDescription: String? = null,
    onTitleClick: (() -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    val background = MaterialTheme.gradeyColors.groupedBackground
    val fade by animateFloatAsState(if (scrolled) 0.94f else 0f, tween(180), label = "topBarFade")
    val separator = MaterialTheme.gradeyColors.separator
    Layout(
        modifier = modifier.fillMaxWidth()
            .drawBehind {
                if (fade > 0f) {
                    drawRect(background.copy(alpha = fade))
                    drawRect(separator.copy(alpha = separator.alpha * fade / 0.94f), topLeft = Offset(0f, size.height - 0.5.dp.toPx()), size = size.copy(height = 0.5.dp.toPx()))
                }
            }
            .windowInsetsPadding(WindowInsets.statusBars)
            .height(56.dp)
            .padding(horizontal = GradeySpacing.xs),
        measurePolicy = TopBarMeasurePolicy,
        content = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            when {
                onBack != null -> ToolbarIconButton(HugeIcons.ArrowLeft01, backDescription, onBack, tint = MaterialTheme.colorScheme.onSurface)
                onOpenAI != null -> GradeyAIButton(onOpenAI, aiDescription)
            }
        }
        Row(
            Modifier
                .then(if (onTitleClick != null) Modifier.clip(RoundedCornerShape(GradeyRadius.xs)).clickable(role = Role.Button, onClick = onTitleClick) else Modifier)
                .padding(horizontal = GradeySpacing.sm, vertical = GradeySpacing.xs)
                .semantics { heading() },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.xs),
        ) {
            Text(title, style = GradeyType.headline, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (onTitleClick != null) GradeyIcon(HugeIcons.ArrowDown01, size = 13.dp, tint = MaterialTheme.gradeyColors.secondaryLabel)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            actions?.invoke(this)
            if (onRefresh != null) ToolbarIconButton(HugeIcons.Refresh04, refreshDescription, onRefresh, loading = isRefreshing)
            if (onOpenSettings != null) ToolbarIconButton(HugeIcons.Settings01, settingsDescription, onOpenSettings)
        }
        },
    )
}

/**
 * Leading buttons, title, trailing buttons. The title sits in the middle of the bar when it fits
 * between the two button groups mirrored; a longer title, or large text on a narrow phone, uses
 * all the room between the groups instead, so it is never drawn under a button.
 */
private val TopBarMeasurePolicy = MeasurePolicy { measurables, constraints ->
    val loose = constraints.copy(minWidth = 0, minHeight = 0)
    val start = measurables[0].measure(loose)
    val end = measurables[2].measure(loose)
    val width = constraints.maxWidth
    val height = constraints.maxHeight
    val centred = (width - maxOf(start.width, end.width) * 2).coerceAtLeast(0)
    val between = (width - start.width - end.width).coerceAtLeast(0)
    val fitsCentred = measurables[1].maxIntrinsicWidth(height) <= centred
    val title = measurables[1].measure(loose.copy(maxWidth = if (fitsCentred) centred else between))
    layout(width, height) {
        start.placeRelative(0, (height - start.height) / 2)
        end.placeRelative(width - end.width, (height - end.height) / 2)
        title.placeRelative(if (fitsCentred) (width - title.width) / 2 else start.width + (between - title.width) / 2, (height - title.height) / 2)
    }
}

/** True once a lazy list has scrolled away from its top, for [GradeyTopBar.scrolled]. */
val LazyListState.isScrolled: Boolean
    get() = firstVisibleItemIndex > 0 || firstVisibleItemScrollOffset > 0

// MARK: - Segmented control

private val SegmentAutoSize = TextAutoSize.StepBased(GradeyType.segment.fontSize * 0.7f, GradeyType.segment.fontSize)

/** iOS segmented control: tertiary-fill capsule track, raised thumb, Medium 13 / Bold 13 labels. */
@Composable
fun SegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.gradeyColors
    val thumb = if (tokens.isDark) Color(0xFF636366) else GradeyColors.White
    val position by animateFloatAsState(selectedIndex.toFloat(), tween(220), label = "segment")
    Box(
        modifier.height(36.dp).clip(CircleShape).background(tokens.tertiaryFill).padding(2.dp)
            .drawBehind {
                if (options.isNotEmpty()) {
                    val segment = size.width / options.size
                    drawRoundRect(
                        thumb, topLeft = Offset(segment * position, 0f), size = size.copy(width = segment),
                        cornerRadius = CornerRadius(size.height / 2f),
                    )
                }
            },
    ) {
        Row(Modifier.fillMaxSize()) {
            options.forEachIndexed { index, option ->
                val selected = index == selectedIndex
                Box(
                    Modifier.weight(1f).fillMaxSize().clip(CircleShape)
                        .clickable(role = Role.Tab, onClick = { if (!selected) onSelect(index) })
                        .semantics { this.selected = selected },
                    contentAlignment = Alignment.Center,
                ) {
                    // A long label at a large text size shrinks before it is cut off, like a UISegmentedControl.
                    Text(
                        option, style = if (selected) GradeyType.segmentSelected else GradeyType.segment,
                        color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        autoSize = SegmentAutoSize, modifier = Modifier.padding(horizontal = GradeySpacing.xs),
                    )
                }
            }
        }
    }
}

// MARK: - Text field

/** Filled text field: tertiary fill, radius 12, 12 dp padding (iOS `BrandFieldStyle`). */
@Composable
fun BrandTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    label: String? = null,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else 6,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Done,
    onImeAction: (() -> Unit)? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    leadingIcon: String? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    isError: Boolean = false,
) {
    val tokens = MaterialTheme.gradeyColors
    val onSurface = MaterialTheme.colorScheme.onSurface
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (label != null) Text(label, style = GradeyType.footnoteBold, color = tokens.secondaryLabel, modifier = Modifier.padding(horizontal = GradeySpacing.xs))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = singleLine,
            minLines = minLines,
            maxLines = maxLines,
            textStyle = GradeyType.body.copy(color = onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
            keyboardActions = KeyboardActions(onAny = { onImeAction?.invoke() }),
            visualTransformation = visualTransformation,
            decorationBox = { inner ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = GradeyMetrics.fieldHeight).clip(RoundedCornerShape(GradeyRadius.sm))
                        .background(tokens.tertiaryFill)
                        .then(if (isError) Modifier.border(1.dp, GradeyColors.Poor, RoundedCornerShape(GradeyRadius.sm)) else Modifier)
                        .padding(horizontal = GradeySpacing.md, vertical = GradeySpacing.md),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.sm),
                ) {
                    if (leadingIcon != null) GradeyIcon(leadingIcon, size = 17.dp, tint = tokens.secondaryLabel)
                    Box(Modifier.weight(1f)) {
                        if (value.isEmpty() && placeholder != null) Text(placeholder, style = GradeyType.body, color = tokens.tertiaryLabel, maxLines = 1)
                        inner()
                    }
                    trailing?.invoke(this)
                }
            },
        )
    }
}

// MARK: - Settings kit

/** Width of the icon column Settings rows align to. */
val SettingsIconFrame = 32.dp

/** Compact, unboxed Settings icon (iOS `SettingsIcon`). */
@Composable
fun SettingsIcon(icon: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.88f), size: Dp = 17.dp) {
    Box(modifier.size(SettingsIconFrame), contentAlignment = Alignment.Center) { GradeyIcon(icon, size = size, tint = color) }
}

/** A Settings group with no card: a hairline on top, rows below (iOS `SettingsSurface`). */
@Composable
fun SettingsGroup(modifier: Modifier = Modifier, title: String? = null, footer: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth()) {
        if (title != null) {
            Text(title, Modifier.padding(horizontal = GradeySpacing.xs).semantics { heading() }, style = GradeyType.headline, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(GradeySpacing.sm))
        }
        Hairline(Modifier.padding(horizontal = GradeySpacing.xs))
        content()
        if (footer != null) SectionFooter(footer, Modifier.padding(top = GradeySpacing.xs))
    }
}

/** Divider aligned with the text column of [SettingsActionRow]. */
@Composable
fun SettingsRowDivider(modifier: Modifier = Modifier, leadingInset: Dp = GradeySpacing.xs + SettingsIconFrame + GradeySpacing.md) {
    Hairline(modifier.padding(start = leadingInset, end = GradeySpacing.xs))
}

/**
 * Settings row: icon, body-medium title, optional orange status and caption subtitle, disclosure
 * (iOS `SettingsActionRow`). Minimum height 60.
 */
@Composable
fun SettingsActionRow(
    icon: String?,
    title: String,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    status: String? = null,
    statusColor: Color = MaterialTheme.gradeyColors.orange,
    statusIcon: String? = HugeIcons.AlertCircle,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    iconColor: Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.88f),
    enabled: Boolean = true,
    selected: Boolean = false,
    trailing: (@Composable RowScope.() -> Unit)? = { DisclosureIcon(size = 14.dp) },
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 60.dp)
            .then(if (selected) Modifier.clip(RoundedCornerShape(GradeyRadius.sm)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)) else Modifier)
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = GradeySpacing.xs, vertical = GradeySpacing.sm),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.md),
    ) {
        if (icon != null) SettingsIcon(icon, color = iconColor)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = GradeyType.bodyMedium, color = titleColor.copy(alpha = if (enabled) 1f else 0.45f))
            if (status != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.xs)) {
                if (statusIcon != null) GradeyIcon(statusIcon, size = 12.dp, tint = statusColor)
                Text(status, style = GradeyType.captionSemibold, color = statusColor)
            }
            if (!subtitle.isNullOrBlank()) Text(subtitle, style = GradeyType.caption, color = MaterialTheme.gradeyColors.secondaryLabel)
        }
        trailing?.invoke(this)
    }
}

/** Switch row tinted Brand.primary. */
@Composable
fun SettingsToggleRow(title: String, subtitle: String?, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier, icon: String? = null) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = GradeySpacing.xs, vertical = GradeySpacing.sm),
        horizontalArrangement = Arrangement.spacedBy(GradeySpacing.md), verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) SettingsIcon(icon)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = GradeyType.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.45f))
            if (!subtitle.isNullOrBlank()) Text(subtitle, style = GradeyType.caption, color = MaterialTheme.gradeyColors.secondaryLabel)
        }
        Switch(
            checked = checked, onCheckedChange = null, enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedTrackColor = MaterialTheme.colorScheme.primary, checkedThumbColor = GradeyColors.White,
                uncheckedTrackColor = MaterialTheme.gradeyColors.tertiaryFill, uncheckedThumbColor = GradeyColors.White, uncheckedBorderColor = Color.Transparent,
            ),
        )
    }
}

/** Label on the left, value on the right, optional tap target. */
@Composable
fun SettingsValueRow(title: String, value: String?, modifier: Modifier = Modifier, icon: String? = null, onClick: (() -> Unit)? = null, enabled: Boolean = true, valueColor: Color = MaterialTheme.gradeyColors.secondaryLabel) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 48.dp)
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = GradeySpacing.xs, vertical = GradeySpacing.sm),
        horizontalArrangement = Arrangement.spacedBy(GradeySpacing.md), verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) SettingsIcon(icon)
        Text(title, Modifier.weight(1f), style = GradeyType.body, color = MaterialTheme.colorScheme.onSurface)
        if (value != null) Text(value, style = GradeyType.subheadline, color = valueColor, textAlign = TextAlign.End, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp))
        if (onClick != null) DisclosureIcon(size = 14.dp)
    }
}

/** Explanatory caption under a Settings group. */
@Composable
fun SettingsNote(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.padding(horizontal = GradeySpacing.xs), style = GradeyType.caption, color = MaterialTheme.gradeyColors.secondaryLabel)
}

/** 44 dp circle with initials in bold brand green on green @14 % (iOS profile row). */
@Composable
fun AccountAvatar(name: String?, modifier: Modifier = Modifier, size: Dp = 44.dp) {
    val initials = remember(name) {
        name.orEmpty().trim().split(' ', '\t', '\n').filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercaseChar().toString() }
    }
    val primary = MaterialTheme.colorScheme.primary
    Box(modifier.size(size).clip(CircleShape).background(primary.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
        if (initials.isEmpty()) GradeyIcon(HugeIcons.User, size = size * 0.42f, tint = primary)
        else Text(initials, style = GradeyType.headline.copy(fontSize = (size.value * 0.36f).sp), color = primary)
    }
}

// MARK: - States

/** Centred icon, headline and secondary message (iOS empty states). */
@Composable
fun EmptyState(title: String, modifier: Modifier = Modifier, message: String? = null, icon: String? = null, action: (@Composable () -> Unit)? = null) {
    Column(
        modifier.fillMaxWidth().padding(vertical = GradeySpacing.xl, horizontal = GradeySpacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(GradeySpacing.sm),
    ) {
        if (icon != null) GradeyIcon(icon, size = 26.dp, tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.65f))
        Text(title, style = GradeyType.headline, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
        if (message != null) Text(message, style = GradeyType.subheadline, color = MaterialTheme.gradeyColors.secondaryLabel, textAlign = TextAlign.Center)
        if (action != null) action()
    }
}

/** Spinner with an optional caption. */
@Composable
fun LoadingState(modifier: Modifier = Modifier, text: String? = null) {
    Column(
        modifier.fillMaxWidth().padding(vertical = GradeySpacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(GradeySpacing.md),
    ) {
        CircularProgressIndicator(Modifier.size(28.dp), color = MaterialTheme.colorScheme.primary, strokeWidth = 2.5.dp)
        if (text != null) Text(text, style = GradeyType.subheadline, color = MaterialTheme.gradeyColors.secondaryLabel, textAlign = TextAlign.Center)
    }
}

/** Error with a retry link, same layout as [EmptyState]. */
@Composable
fun ErrorState(message: String, modifier: Modifier = Modifier, title: String? = null, retryLabel: String? = null, onRetry: (() -> Unit)? = null) {
    EmptyState(
        title = title ?: message, modifier = modifier, message = if (title != null) message else null, icon = HugeIcons.AlertCircle,
        action = if (onRetry != null && retryLabel != null) { { PlainLink(retryLabel, onRetry, showsArrow = false) } } else null,
    )
}

/** Inline error or warning line: icon and text in the given colour on its 12 % fill. */
@Composable
fun InlineNotice(text: String, modifier: Modifier = Modifier, color: Color = GradeyColors.Poor, icon: String = HugeIcons.AlertCircle, action: (@Composable RowScope.() -> Unit)? = null) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(GradeyRadius.sm)).background(color.copy(alpha = 0.12f)).padding(GradeySpacing.md),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.sm),
    ) {
        GradeyIcon(icon, size = 16.dp, tint = color)
        Text(text, Modifier.weight(1f), style = GradeyType.footnote, color = MaterialTheme.colorScheme.onSurface)
        action?.invoke(this)
    }
}

/** The one remaining card: school-reconnect warning with an orange action (iOS Today notice). */
@Composable
fun ReconnectNotice(title: String, message: String, actionLabel: String, onAction: () -> Unit, modifier: Modifier = Modifier, actionEnabled: Boolean = true) {
    val orange = MaterialTheme.gradeyColors.orange
    val shape = RoundedCornerShape(GradeyRadius.card)
    Column(
        modifier.fillMaxWidth().clip(shape).background(MaterialTheme.gradeyColors.secondaryGroupedBackground)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)), shape)
            .padding(GradeySpacing.lg),
        verticalArrangement = Arrangement.spacedBy(GradeySpacing.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.sm)) {
            GradeyIcon(HugeIcons.Alert02, size = 18.dp, tint = orange)
            Text(title, style = GradeyType.headline, color = MaterialTheme.colorScheme.onSurface)
        }
        Text(message, style = GradeyType.subheadline, color = MaterialTheme.gradeyColors.secondaryLabel)
        Box(
            Modifier.fillMaxWidth().heightIn(min = GradeyMetrics.minTouch).clip(RoundedCornerShape(GradeyRadius.sm)).background(orange)
                .clickable(enabled = actionEnabled, role = Role.Button, onClick = onAction),
            contentAlignment = Alignment.Center,
        ) { Text(actionLabel, style = GradeyType.headline, color = GradeyColors.White) }
    }
}

// MARK: - Sheets and dialogs

/**
 * Bottom sheet in the Settings background with a drag handle; content is capped at 640 dp and
 * padded 20 dp (replaces the three earlier sheet styles).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GradeySheet(onDismiss: () -> Unit, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    GradeySheet(onDismiss, rememberModalBottomSheetState(skipPartiallyExpanded = true), modifier, content)
}

/** [GradeySheet] for callers that drive the sheet themselves, for example to animate it closed. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GradeySheet(
    onDismiss: () -> Unit,
    sheetState: SheetState,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val tokens = MaterialTheme.gradeyColors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        sheetState = sheetState,
        containerColor = if (tokens.isDark) tokens.settingsBackground else tokens.groupedBackground,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(topStart = GradeyRadius.xl, topEnd = GradeyRadius.xl),
        tonalElevation = 0.dp,
        dragHandle = {
            Box(Modifier.padding(top = 8.dp, bottom = 4.dp).size(36.dp, 5.dp).background(tokens.tertiaryLabel, CircleShape))
        },
    ) {
        Column(
            Modifier.fillMaxWidth().widthIn(max = GradeyMetrics.narrowContentMaxWidth).align(Alignment.CenterHorizontally)
                .padding(start = 20.dp, end = 20.dp, bottom = GradeySpacing.xl)
                .windowInsetsPadding(WindowInsets.navigationBars),
            verticalArrangement = Arrangement.spacedBy(GradeySpacing.lg),
            content = content,
        )
    }
}

/** Alert dialog in Gradey type and colours; a destructive confirm is red. */
@Composable
fun GradeyDialog(
    title: String,
    onDismiss: () -> Unit,
    confirmLabel: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    text: String? = null,
    dismissLabel: String? = null,
    destructive: Boolean = false,
    confirmEnabled: Boolean = true,
    body: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val tokens = MaterialTheme.gradeyColors
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        containerColor = tokens.secondaryGroupedBackground,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = tokens.secondaryLabel,
        shape = RoundedCornerShape(GradeyRadius.xl),
        title = { Text(title, style = GradeyType.title3) },
        text = if (text != null || body != null) {
            {
                Column(verticalArrangement = Arrangement.spacedBy(GradeySpacing.md)) {
                    if (text != null) Text(text, style = GradeyType.subheadline)
                    body?.invoke(this)
                }
            }
        } else null,
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = confirmEnabled) {
                Text(confirmLabel, style = GradeyType.subheadlineBold, color = if (destructive) GradeyColors.Poor else MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = if (dismissLabel != null) {
            { TextButton(onClick = onDismiss) { Text(dismissLabel, style = GradeyType.subheadlineSemibold, color = MaterialTheme.colorScheme.primary) } }
        } else null,
    )
}

// MARK: - Misc

/** Text with a strike-through when [struck] is true (cancelled lessons, removed absences). */
fun TextStyle.struck(struck: Boolean): TextStyle = if (struck) copy(textDecoration = TextDecoration.LineThrough) else this

/** Provides [LocalContentColor] for content on the brand gradient. */
@Composable
fun OnAccentContent(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalContentColor provides GradeyColors.OnAccent, content = content)
}

/** Fixed-width spacer for dense rows. */
@Composable
fun HorizontalGap(width: Dp = GradeySpacing.sm) = Spacer(Modifier.width(width))

/** Lets a horizontally scrolling strip extend past its parent's padding on both sides. */
fun Modifier.bleedToEdges(amount: Dp): Modifier = layout { measurable, constraints ->
    val extra = (amount * 2).roundToPx()
    val placeable = measurable.measure(constraints.copy(maxWidth = constraints.maxWidth + extra, minWidth = (constraints.minWidth + extra).coerceAtMost(constraints.maxWidth + extra)))
    layout((placeable.width - extra).coerceAtLeast(0), placeable.height) { placeable.placeRelative(-extra / 2, 0) }
}
