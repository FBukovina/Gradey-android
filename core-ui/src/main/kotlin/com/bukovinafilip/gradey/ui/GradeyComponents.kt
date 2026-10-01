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

// MARK: - Backgrounds

/**
 * Layered teal "aurora" glow behind every signed-in screen. Positions and opacities mirror
 * `AuroraBackground.standard` on iOS; radial falloff stands in for the SwiftUI blur.
 */
@Composable
fun AuroraBackground(modifier: Modifier = Modifier, content: BoxScopeContent = {}) {
    val colors = MaterialTheme.colorScheme
    val base = MaterialTheme.gradeyColors.groupedBackground
    CompositionLocalProvider(LocalContentColor provides colors.onBackground) { Box(
        modifier.fillMaxSize().background(base).drawBehind {
            val w = size.width; val h = size.height
            fun glow(color: Color, diameter: Float, center: Offset) {
                drawCircle(
                    brush = Brush.radialGradient(0f to color, 0.55f to color.copy(alpha = color.alpha * 0.35f), 1f to Color.Transparent, center = center, radius = diameter / 2f),
                    radius = diameter / 2f, center = center,
                )
            }
            glow(colors.primary.copy(alpha = 0.30f), w * 1.6f, Offset(w * 0.52f, h * 0.02f))
            glow(colors.secondary.copy(alpha = 0.22f), w * 1.15f, Offset(w * 1.05f, h * 0.26f))
            glow(colors.primary.copy(alpha = 0.16f), w * 1.05f, Offset(-w * 0.08f, h * 0.62f))
        },
    ) { content() } }
}

typealias BoxScopeContent = @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit

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

// MARK: - Labels

/** Small uppercase tracked label used to separate content sections. */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text.uppercase(), modifier = modifier.semantics { heading() }, style = GradeyType.sectionHeader, color = color)
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

/** Small tonal capsule for metadata (type, weight, points, counts). */
@Composable
fun StatusChip(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant, icon: String? = null) {
    Surface(modifier, shape = CircleShape, color = color.copy(alpha = 0.14f)) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.xs)) {
            if (icon != null) GradeyIcon(icon, size = 12.dp, tint = color)
            Text(text, style = GradeyType.caption.copy(fontWeight = FontWeight.SemiBold), color = color, maxLines = 1)
        }
    }
}

/** Chevron used at the end of tappable rows. */
@Composable
fun DisclosureIcon(modifier: Modifier = Modifier, size: Dp = 13.dp) {
    GradeyIcon(HugeIcons.ArrowRight01, modifier, size = size, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
}

// MARK: - Rows

/** Rounded tile holding an icon, tinted 12 % of the icon color. */
@Composable
fun IconTile(icon: String, tint: Color, modifier: Modifier = Modifier, size: Dp = 34.dp, iconSize: Dp = 16.dp, cornerRadius: Dp = GradeyRadius.sm, background: Color = tint.copy(alpha = 0.12f)) {
    Box(modifier.size(size).clip(RoundedCornerShape(cornerRadius)).background(background), contentAlignment = Alignment.Center) {
        GradeyIcon(icon, size = iconSize, tint = tint)
    }
}

/** Icon tile + title/subtitle (iOS `TodayInfoRow`). */
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
        IconTile(icon, tint)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = GradeyType.subheadlineBold, color = MaterialTheme.colorScheme.onSurface)
            if (!subtitle.isNullOrBlank()) Text(subtitle, style = GradeyType.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing?.invoke(this)
    }
}

/** Tappable [InfoRow] with a disclosure chevron. */
@Composable
fun NavigationRow(icon: String, title: String, subtitle: String?, onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color = MaterialTheme.colorScheme.primary, enabled: Boolean = true) {
    Surface(onClick = onClick, enabled = enabled, color = Color.Transparent, shape = RoundedCornerShape(GradeyRadius.sm), modifier = modifier.fillMaxWidth()) {
        InfoRow(icon, title, subtitle, Modifier.padding(vertical = 6.dp), tint) { DisclosureIcon() }
    }
}

/** Compact label/value pair rendered on the brand gradient (iOS `StatTile`). */
@Composable
fun StatTile(title: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(GradeySpacing.xs)) {
        Text(title.uppercase(), style = GradeyType.caption2.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.4.sp), color = GradeyColors.OnAccent.copy(alpha = 0.7f), maxLines = 2)
        Text(value, style = GradeyType.subheadlineBold, color = GradeyColors.OnAccent, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// MARK: - Grade badge

enum class GradeBadgeSize(val minWidth: Dp, val horizontalPadding: Dp, val verticalPadding: Dp) {
    SMALL(40.dp, GradeySpacing.sm, GradeySpacing.sm),
    REGULAR(52.dp, GradeySpacing.md, GradeySpacing.sm),
    LARGE(72.dp, GradeySpacing.lg, GradeySpacing.md),
}

/** Tonal badge showing a grade or average, coloured by performance band. */
@Composable
fun GradeBadge(text: String, band: GradeBand, modifier: Modifier = Modifier, size: GradeBadgeSize = GradeBadgeSize.REGULAR) {
    val style = when (size) {
        GradeBadgeSize.SMALL -> GradeyType.headline.copy(fontWeight = FontWeight.Bold)
        GradeBadgeSize.REGULAR -> GradeyType.title3.copy(fontWeight = FontWeight.Bold)
        GradeBadgeSize.LARGE -> GradeyType.largeTitle
    }
    Box(
        modifier.defaultMinSize(minWidth = size.minWidth).clip(RoundedCornerShape(GradeyRadius.md)).background(band.softColor())
            .padding(horizontal = size.horizontalPadding, vertical = size.verticalPadding),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = style, color = band.color(), maxLines = 1, textAlign = TextAlign.Center)
    }
}

// MARK: - Risk ring

@Composable
fun AbsenceRiskLevel.riskColor(): Color = when (this) {
    AbsenceRiskLevel.OVER_LIMIT, AbsenceRiskLevel.HIGH -> GradeyColors.Poor
    AbsenceRiskLevel.WATCH -> MaterialTheme.gradeyColors.orange
    AbsenceRiskLevel.SAFE -> MaterialTheme.colorScheme.primary
    AbsenceRiskLevel.UNAVAILABLE -> MaterialTheme.colorScheme.onSurfaceVariant
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

// MARK: - Buttons

/** Full-width gradient CTA for the primary action on a screen (iOS `PrimaryButtonStyle`). */
@Composable
fun GradeyPrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, loading: Boolean = false, icon: String? = null) {
    val shape = RoundedCornerShape(GradeyRadius.md)
    val alpha = if (enabled) 1f else 0.45f
    Surface(
        onClick = onClick, enabled = enabled && !loading, shape = shape, color = Color.Transparent,
        modifier = modifier.fillMaxWidth().heightIn(min = 50.dp),
    ) {
        Box(Modifier.background(MaterialTheme.colorScheme.brandGradient, shape).padding(horizontal = GradeySpacing.lg, vertical = 14.dp), contentAlignment = Alignment.Center) {
            if (loading) CircularProgressIndicator(Modifier.size(20.dp), color = GradeyColors.OnAccent, strokeWidth = 2.5.dp)
            else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.sm)) {
                if (icon != null) GradeyIcon(icon, size = 18.dp, tint = GradeyColors.OnAccent.copy(alpha = alpha))
                Text(text, style = GradeyType.headline, color = GradeyColors.OnAccent.copy(alpha = alpha), textAlign = TextAlign.Center)
            }
        }
    }
}

/** Tonal secondary button: primary text on a 12 % primary fill. */
@Composable
fun GradeyTonalButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: String? = null, color: Color = MaterialTheme.colorScheme.primary) {
    Surface(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(GradeyRadius.md), color = color.copy(alpha = if (enabled) 0.12f else 0.06f), modifier = modifier.heightIn(min = 44.dp)) {
        Row(Modifier.padding(horizontal = GradeySpacing.lg, vertical = GradeySpacing.md), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.sm)) {
            val tint = color.copy(alpha = if (enabled) 1f else 0.45f)
            if (icon != null) GradeyIcon(icon, size = 16.dp, tint = tint)
            Text(text, style = GradeyType.subheadlineBold, color = tint, textAlign = TextAlign.Center)
        }
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

/** Icon filled with the brand gradient (used for the Gradey AI entry point). */
@Composable
fun BrandGradientIcon(icon: String, modifier: Modifier = Modifier, size: Dp = 20.dp, contentDescription: String? = null) {
    val fontSize = with(LocalDensity.current) { size.toSp() }
    val semantics = if (contentDescription == null) Modifier.clearAndSetSemantics { }
    else Modifier.semantics { this.contentDescription = contentDescription; role = Role.Image }
    Box(modifier.size(size).then(semantics), contentAlignment = Alignment.Center) {
        BasicText(
            text = icon, maxLines = 1, softWrap = false,
            style = TextStyle(
                fontFamily = HugeiconsFontFamily, fontSize = fontSize, lineHeight = fontSize,
                brush = MaterialTheme.colorScheme.brandGradient, fontFeatureSettings = "liga", textAlign = TextAlign.Center,
                platformStyle = PlatformTextStyle(includeFontPadding = false),
                lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
            ),
        )
    }
}

// MARK: - Settings surfaces

/** Quiet teal-tinted background shared by Settings and the modal surfaces launched from it. */
@Composable
fun SettingsBackground(modifier: Modifier = Modifier, content: BoxScopeContent) {
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
        Box(modifier.fillMaxSize().background(MaterialTheme.gradeyColors.settingsBackground), content = content)
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
        SettingsCloseButton(onDismiss, dismissDescription)
    }
}

@Composable
fun SettingsCloseButton(onClick: () -> Unit, contentDescription: String? = null, modifier: Modifier = Modifier, size: Dp = 48.dp, icon: String = HugeIcons.Cancel01) {
    Surface(
        onClick = onClick, shape = CircleShape, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.09f)), modifier = modifier.size(size),
    ) {
        Box(contentAlignment = Alignment.Center) { GradeyIcon(icon, size = 15.dp, tint = MaterialTheme.colorScheme.onSurface, contentDescription = contentDescription) }
    }
}

/** Compact, unboxed Settings icon in the 32 dp frame the rows align to. */
@Composable
fun SettingsIcon(icon: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.88f), size: Dp = 17.dp) {
    Box(modifier.size(SettingsIconFrame), contentAlignment = Alignment.Center) { GradeyIcon(icon, size = size, tint = color) }
}

val SettingsIconFrame = 32.dp

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

/** Divider aligned with the text column of [SettingsRow]. */
@Composable
fun SettingsRowDivider(modifier: Modifier = Modifier, leadingInset: Dp = 20.dp + SettingsIconFrame + GradeySpacing.md) {
    HorizontalDivider(modifier.padding(start = leadingInset), color = MaterialTheme.gradeyColors.separator)
}

/** Gradient avatar with initials, falling back to a user glyph. */
@Composable
fun AccountAvatar(name: String?, modifier: Modifier = Modifier, size: Dp = 60.dp) {
    val initials = name.orEmpty().trim().split(Regex("\\s+")).filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercaseChar().toString() }
    Box(
        modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.brandGradient)
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.38f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (initials.isEmpty()) GradeyIcon(HugeIcons.User, size = size * 0.36f, tint = GradeyColors.OnAccent)
        else Text(initials, style = GradeyType.title2.copy(fontSize = (size.value * 0.30f).sp, lineHeight = (size.value * 0.34f).sp), color = GradeyColors.OnAccent)
    }
}

/** Switch row used in Settings detail pages. */
@Composable
fun SettingsToggleRow(title: String, subtitle: String?, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier, icon: String? = null) {
    Row(modifier.fillMaxWidth().heightIn(min = 56.dp), horizontalArrangement = Arrangement.spacedBy(GradeySpacing.md), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) SettingsIcon(icon)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = GradeyType.subheadlineBold, color = MaterialTheme.colorScheme.onSurface)
            if (!subtitle.isNullOrBlank()) Text(subtitle, style = GradeyType.footnote, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        androidx.compose.material3.Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

/** Inline value row: label on the left, value on the right, optional tap target. */
@Composable
fun SettingsValueRow(title: String, value: String?, modifier: Modifier = Modifier, icon: String? = null, onClick: (() -> Unit)? = null, enabled: Boolean = true, valueColor: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    val row: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), horizontalArrangement = Arrangement.spacedBy(GradeySpacing.md), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) SettingsIcon(icon)
            Text(title, Modifier.weight(1f), style = GradeyType.subheadline, color = MaterialTheme.colorScheme.onSurface)
            if (value != null) Text(value, style = GradeyType.subheadline, color = valueColor, textAlign = TextAlign.End, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 200.dp))
            if (onClick != null) DisclosureIcon(size = 14.dp)
        }
    }
    if (onClick == null) Box(modifier) { row() }
    else Surface(onClick = onClick, enabled = enabled, color = Color.Transparent, shape = RoundedCornerShape(GradeyRadius.sm), modifier = modifier.fillMaxWidth(), content = row)
}

/** Explanatory footnote under a settings group. */
@Composable
fun SettingsNote(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.padding(horizontal = GradeySpacing.xs), style = GradeyType.footnote, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Provides [LocalContentColor] for content placed on the brand gradient. */
@Composable
fun OnAccentContent(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalContentColor provides GradeyColors.OnAccent, content = content)
}

/** Fixed-width spacer used in dense rows. */
@Composable
fun HorizontalGap(width: Dp = GradeySpacing.sm) = Spacer(Modifier.width(width))

/**
 * Lets a horizontally scrolling child extend [amount] past its parent's padding on both sides, so a
 * strip inside a card scrolls edge to edge (SwiftUI `contentMargins` + negative padding).
 */
fun Modifier.bleedHorizontally(amount: Dp): Modifier = layout { measurable, constraints ->
    val extra = (amount * 2).roundToPx()
    val placeable = measurable.measure(constraints.copy(maxWidth = constraints.maxWidth + extra, minWidth = (constraints.minWidth + extra).coerceAtMost(constraints.maxWidth + extra)))
    layout((placeable.width - extra).coerceAtLeast(0), placeable.height) { placeable.placeRelative(-extra / 2, 0) }
}
