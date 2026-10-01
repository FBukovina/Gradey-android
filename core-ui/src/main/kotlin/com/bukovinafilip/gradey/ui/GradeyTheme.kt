package com.bukovinafilip.gradey.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bukovinafilip.gradey.domain.GradeBand

/**
 * Brand palette mirrored from the iOS asset catalog (`BrandPrimary`, `BrandSecondary`, `Brand.onAccent`).
 * Light values are the deep teal/emerald; dark values are the bright display variants.
 */
object GradeyColors {
    val PrimaryLight = Color(0xFF17A185)
    val PrimaryDark = Color(0xFF1AFFBE)
    val SecondaryLight = Color(0xFF1DA565)
    val SecondaryDark = Color(0xFF1FF98C)

    /** Dark teal ink for text and icons placed on the brand gradient. */
    val OnAccent = Color(0xFF041816)

    val Excellent = Color(0xFF1E9F69)
    val Good = Color(0xFF108A94)
    val Average = Color(0xFFD98F10)
    val Poor = Color(0xFFD83E4F)

    val OrangeLight = Color(0xFFFF9500)
    val OrangeDark = Color(0xFFFF9F0A)
    val PurpleLight = Color(0xFFAF52DE)
    val PurpleDark = Color(0xFFBF5AF2)
    val RedLight = Color(0xFFFF3B30)
    val RedDark = Color(0xFFFF453A)
}

object GradeySpacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
}

/** Corner radii shared with iOS `Radius`. */
object GradeyRadius {
    val sm = 12.dp
    val md = 16.dp
    val card = 20.dp
    val xl = 28.dp
}

/** Tokens that have no Material slot: grouped backgrounds, fills and separators from the iOS system palette. */
@Immutable
data class GradeyExtendedColors(
    val groupedBackground: Color,
    val secondaryGroupedBackground: Color,
    val tertiaryGroupedBackground: Color,
    val tertiaryFill: Color,
    val separator: Color,
    val secondaryLabel: Color,
    val settingsBackground: Color,
    val settingsSurface: Color,
    val settingsSurfaceBorder: Color,
    val orange: Color,
    val purple: Color,
    val red: Color,
    val isDark: Boolean,
)

private val LightExtended = GradeyExtendedColors(
    groupedBackground = Color(0xFFF2F2F7),
    secondaryGroupedBackground = Color.White,
    tertiaryGroupedBackground = Color(0xFFF2F2F7),
    tertiaryFill = Color(0x1F767680),
    separator = Color(0x1A3C3C43),
    secondaryLabel = Color(0x993C3C43),
    settingsBackground = Color(0xFFF2F2F7),
    settingsSurface = Color.White,
    settingsSurfaceBorder = GradeyColors.PrimaryLight.copy(alpha = 0.075f),
    orange = GradeyColors.OrangeLight,
    purple = GradeyColors.PurpleLight,
    red = GradeyColors.RedLight,
    isDark = false,
)

private val DarkExtended = GradeyExtendedColors(
    groupedBackground = Color(0xFF000000),
    secondaryGroupedBackground = Color(0xFF171D1A),
    tertiaryGroupedBackground = Color(0xFF242B27),
    tertiaryFill = Color(0x3D767680),
    separator = Color(0x2EFFFFFF),
    secondaryLabel = Color(0x99EBEBF5),
    settingsBackground = Color(0xFF020B0C),
    settingsSurface = Color(0xFF0B2421),
    settingsSurfaceBorder = Color(0x06FFFFFF),
    orange = GradeyColors.OrangeDark,
    purple = GradeyColors.PurpleDark,
    red = GradeyColors.RedDark,
    isDark = true,
)

private val LightScheme = lightColorScheme(
    primary = GradeyColors.PrimaryLight,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6F3EB),
    onPrimaryContainer = Color(0xFF00382D),
    secondary = GradeyColors.SecondaryLight,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDDF4E7),
    onSecondaryContainer = Color(0xFF123B30),
    tertiary = GradeyColors.Good,
    background = LightExtended.groupedBackground,
    onBackground = Color(0xFF000000),
    surface = Color.White,
    onSurface = Color(0xFF000000),
    surfaceVariant = Color(0xFFE9E9EE),
    onSurfaceVariant = Color(0xFF6E6E73),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF7F7FA),
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color(0xFFEFEFF4),
    surfaceContainerHighest = Color(0xFFE5E5EA),
    outline = Color(0xFF8E8E93),
    outlineVariant = Color(0xFFD8D8DC),
    error = GradeyColors.RedLight,
    onError = Color.White,
    errorContainer = Color(0xFFFFE1DF),
    onErrorContainer = Color(0xFF6B0F0A),
)

private val DarkScheme = darkColorScheme(
    primary = GradeyColors.PrimaryDark,
    onPrimary = GradeyColors.OnAccent,
    primaryContainer = Color(0xFF103A30),
    onPrimaryContainer = Color(0xFFB4FFE6),
    secondary = GradeyColors.SecondaryDark,
    onSecondary = GradeyColors.OnAccent,
    secondaryContainer = Color(0xFF123A28),
    onSecondaryContainer = Color(0xFFB9FFD9),
    tertiary = Color(0xFF5BD9E2),
    background = DarkExtended.groupedBackground,
    onBackground = Color.White,
    surface = DarkExtended.secondaryGroupedBackground,
    onSurface = Color.White,
    surfaceVariant = Color(0xFF242B27),
    onSurfaceVariant = Color(0xFF9DA4A0),
    surfaceContainerLowest = Color(0xFF0B100E),
    surfaceContainerLow = Color(0xFF111614),
    surfaceContainer = DarkExtended.secondaryGroupedBackground,
    surfaceContainerHigh = Color(0xFF1F2622),
    surfaceContainerHighest = Color(0xFF293029),
    outline = Color(0xFF6F7772),
    outlineVariant = Color(0xFF2E3632),
    error = GradeyColors.RedDark,
    onError = Color.White,
    errorContainer = Color(0xFF4A1512),
    onErrorContainer = Color(0xFFFFB4AB),
)

val LocalGradeyColors = staticCompositionLocalOf { LightExtended }

/** Space Grotesk Bold is the display face shared with iOS (`Font.gradelyDisplay`). */
val GradeyDisplayFontFamily: FontFamily = FontFamily(Font(R.font.space_grotesk_bold, FontWeight.Bold))

/** iOS Dynamic Type text styles expressed in sp, so screens can share one vocabulary with the Swift source. */
object GradeyType {
    fun display(size: Int = 38) = TextStyle(fontFamily = GradeyDisplayFontFamily, fontWeight = FontWeight.Bold, fontSize = size.sp, lineHeight = (size * 1.08f).sp, letterSpacing = (-0.01f * size).sp)
    val largeTitle = TextStyle(fontSize = 34.sp, lineHeight = 41.sp, fontWeight = FontWeight.Bold)
    val title1 = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold)
    val title2 = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold)
    val title3 = TextStyle(fontSize = 20.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold)
    val headline = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
    val body = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.Normal)
    val callout = TextStyle(fontSize = 16.sp, lineHeight = 21.sp, fontWeight = FontWeight.Normal)
    val subheadline = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal)
    val subheadlineBold = subheadline.copy(fontWeight = FontWeight.Bold)
    val footnote = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal)
    val footnoteBold = footnote.copy(fontWeight = FontWeight.Bold)
    val caption = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal)
    val captionBold = caption.copy(fontWeight = FontWeight.Bold)
    val caption2 = TextStyle(fontSize = 11.sp, lineHeight = 13.sp, fontWeight = FontWeight.Normal)
    val caption2Bold = caption2.copy(fontWeight = FontWeight.Bold)
    /** Uppercase tracked section label. */
    val sectionHeader = footnoteBold.copy(letterSpacing = 0.6.sp)
}

private val GradeyTypography = Typography(
    displayLarge = GradeyType.display(38),
    displayMedium = GradeyType.display(36),
    displaySmall = GradeyType.display(30),
    headlineLarge = GradeyType.largeTitle,
    headlineMedium = GradeyType.title1,
    headlineSmall = GradeyType.title2,
    titleLarge = GradeyType.title3,
    titleMedium = GradeyType.headline,
    titleSmall = GradeyType.subheadlineBold,
    bodyLarge = GradeyType.body,
    bodyMedium = GradeyType.subheadline,
    bodySmall = GradeyType.footnote,
    labelLarge = GradeyType.subheadline.copy(fontWeight = FontWeight.SemiBold),
    labelMedium = GradeyType.captionBold,
    labelSmall = GradeyType.caption2Bold,
)

private val GradeyShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(GradeyRadius.sm),
    medium = RoundedCornerShape(GradeyRadius.md),
    large = RoundedCornerShape(GradeyRadius.card),
    extraLarge = RoundedCornerShape(GradeyRadius.xl),
)

/** Keeps branded surfaces readable while preserving their light-mode appearance. */
@Composable
fun adaptiveColor(light: Color, dark: Color): Color =
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) dark else light

val MaterialTheme.gradeyColors: GradeyExtendedColors
    @Composable get() = LocalGradeyColors.current

/** Diagonal teal→emerald gradient used for hero surfaces and the primary CTA. */
val ColorScheme.brandGradient: Brush
    get() = Brush.linearGradient(listOf(primary, secondary))

@Composable
fun GradeyTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalGradeyColors provides if (darkTheme) DarkExtended else LightExtended) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkScheme else LightScheme,
            typography = GradeyTypography,
            shapes = GradeyShapes,
            content = content,
        )
    }
}

/** Transparent scrolling column over the app-wide aurora background. */
@Composable
fun GradeyScreen(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(GradeySpacing.lg),
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier.padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(GradeySpacing.lg),
    ) {
        content()
    }
}

/** Display-font page title with an optional secondary line; replaces the former gradient hero banner. */
@Composable
fun GradeyHero(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(GradeySpacing.xs)) {
        Text(title, style = GradeyType.display(34), color = MaterialTheme.colorScheme.onSurface)
        Text(subtitle, style = GradeyType.callout, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun GradeySectionCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable () -> Unit,
) {
    GradeyCard(modifier = modifier) {
        if (title != null) SectionHeader(title)
        content()
    }
}

@Composable
fun MetadataRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = GradeyType.subheadline, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = GradeyType.subheadlineBold)
    }
}

fun GradeBand.color(): Color = when (this) {
    GradeBand.EXCELLENT -> GradeyColors.Excellent
    GradeBand.GOOD -> GradeyColors.Good
    GradeBand.AVERAGE -> GradeyColors.Average
    GradeBand.POOR -> GradeyColors.Poor
    GradeBand.NEUTRAL -> Color(0xFF8E8E93)
}

/** Soft tonal fill for grade badges and chips. */
fun GradeBand.softColor(): Color = if (this == GradeBand.NEUTRAL) Color.Gray.copy(alpha = 0.16f) else color().copy(alpha = 0.14f)
