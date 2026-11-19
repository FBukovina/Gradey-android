package com.bukovinafilip.gradey.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.bukovinafilip.gradey.domain.GradeBand

/**
 * Every colour the app draws, mirrored from iOS 2.8.1 (`Assets.xcassets`, `DesignSystem.swift`).
 * Screens never write colour literals; they use these values or [GradeyExtendedColors].
 */
object GradeyColors {
    val PrimaryLight = Color(0xFF17A185)
    val PrimaryDark = Color(0xFF1AFFBE)
    val SecondaryLight = Color(0xFF1DA565)
    val SecondaryDark = Color(0xFF1FF98C)

    /** Dark ink on the brand gradient and on every Recap surface (iOS `Brand.onAccent`). */
    val OnAccent = Color(0xFF041814)

    val Excellent = Color(0xFF1E9F69)
    val GoodLight = Color(0xFF108A94)
    val GoodDark = Color(0xFF33C6D5)
    val Average = Color(0xFFD98F10)
    val Poor = Color(0xFFD83E4F)
    val Neutral = Color(0xFF8E8E93)

    val OrangeLight = Color(0xFFFF9500)
    val OrangeDark = Color(0xFFFF9F0A)
    val PurpleLight = Color(0xFFAF52DE)
    val PurpleDark = Color(0xFFBF5AF2)
    val TealLight = Color(0xFF30B0C7)
    val TealDark = Color(0xFF40C8E0)

    val White = Color(0xFFFFFFFF)
    val Black = Color(0xFF000000)

    /** Gradey Recap tiles and slides; text on them is always [OnAccent]. */
    object Recap {
        val Emerald = Color(0xFF3DDC97)
        val Teal = Color(0xFF3CCFCF)
        val Sky = Color(0xFF7CC8FF)
        val Lime = Color(0xFFC6F36B)
        val Amber = Color(0xFFFFC145)
        val Rose = Color(0xFFFF8FA3)
        val Lilac = Color(0xFFB9A6FF)
        val Ink = OnAccent
    }

    // Kept for source compatibility with screens that predate the light/dark band split.
    val Good = GoodLight
}

/** iOS `Spacing`. */
object GradeySpacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
}

/** iOS `Radius`; every rounded surface uses one of these. */
object GradeyRadius {
    val xs = 8.dp
    val sm = 12.dp
    val md = 16.dp
    val card = 20.dp
    val xl = 28.dp
}

/** iOS `ControlMetrics` and the shared content widths. */
object GradeyMetrics {
    val minTouch = 44.dp
    val fieldHeight = 48.dp
    val buttonHeight = 50.dp
    /** Main screens cap their column at this width and centre it. */
    val contentMaxWidth = 760.dp
    /** Subject detail, Planner, Settings and sheets. */
    val narrowContentMaxWidth = 640.dp
    /** Screen edge padding; headers and plain sections add [GradeySpacing.xs] more. */
    val screenPadding = 16.dp
    val sectionSpacing = 24.dp
    val hairline = 0.5.dp
}

/** iOS system colours that have no Material slot. */
@Immutable
data class GradeyExtendedColors(
    val groupedBackground: Color,
    val secondaryGroupedBackground: Color,
    val tertiaryFill: Color,
    val separator: Color,
    val label: Color,
    val secondaryLabel: Color,
    val tertiaryLabel: Color,
    val settingsBackground: Color,
    val good: Color,
    val orange: Color,
    val purple: Color,
    val teal: Color,
    val red: Color,
    val isDark: Boolean,
) {
    // Legacy names, mapped onto the new tokens until every screen is migrated.
    val tertiaryGroupedBackground: Color get() = tertiaryFill
    val settingsSurface: Color get() = Color.Transparent
    val settingsSurfaceBorder: Color get() = separator
}

private val LightExtended = GradeyExtendedColors(
    groupedBackground = Color(0xFFF2F2F7),
    secondaryGroupedBackground = GradeyColors.White,
    tertiaryFill = Color(0x1F767680),
    separator = Color(0x4A3C3C43),
    label = GradeyColors.Black,
    secondaryLabel = Color(0x993C3C43),
    tertiaryLabel = Color(0x4D3C3C43),
    settingsBackground = Color(0xFFEFF3F3),
    good = GradeyColors.GoodLight,
    orange = GradeyColors.OrangeLight,
    purple = GradeyColors.PurpleLight,
    teal = GradeyColors.TealLight,
    red = GradeyColors.Poor,
    isDark = false,
)

private val DarkExtended = GradeyExtendedColors(
    groupedBackground = GradeyColors.Black,
    secondaryGroupedBackground = Color(0xFF1C1C1E),
    tertiaryFill = Color(0x3D767680),
    separator = Color(0x99545458),
    label = GradeyColors.White,
    secondaryLabel = Color(0x99EBEBF5),
    tertiaryLabel = Color(0x4DEBEBF5),
    settingsBackground = Color(0xFF020B0C),
    good = GradeyColors.GoodDark,
    orange = GradeyColors.OrangeDark,
    purple = GradeyColors.PurpleDark,
    teal = GradeyColors.TealDark,
    red = GradeyColors.Poor,
    isDark = true,
)

private val LightScheme = lightColorScheme(
    primary = GradeyColors.PrimaryLight,
    onPrimary = GradeyColors.OnAccent,
    primaryContainer = GradeyColors.PrimaryLight.copy(alpha = 0.14f),
    onPrimaryContainer = GradeyColors.PrimaryLight,
    secondary = GradeyColors.SecondaryLight,
    onSecondary = GradeyColors.OnAccent,
    secondaryContainer = GradeyColors.PrimaryLight.copy(alpha = 0.12f),
    onSecondaryContainer = GradeyColors.PrimaryLight,
    tertiary = GradeyColors.GoodLight,
    background = LightExtended.groupedBackground,
    onBackground = LightExtended.label,
    surface = LightExtended.groupedBackground,
    onSurface = LightExtended.label,
    surfaceVariant = LightExtended.tertiaryFill,
    onSurfaceVariant = LightExtended.secondaryLabel,
    surfaceContainerLowest = GradeyColors.White,
    surfaceContainerLow = LightExtended.secondaryGroupedBackground,
    surfaceContainer = LightExtended.secondaryGroupedBackground,
    surfaceContainerHigh = LightExtended.secondaryGroupedBackground,
    surfaceContainerHighest = Color(0xFFE5E5EA),
    outline = LightExtended.tertiaryLabel,
    outlineVariant = LightExtended.separator,
    error = GradeyColors.Poor,
    onError = GradeyColors.White,
    errorContainer = GradeyColors.Poor.copy(alpha = 0.12f),
    onErrorContainer = GradeyColors.Poor,
    scrim = GradeyColors.Black.copy(alpha = 0.32f),
)

private val DarkScheme = darkColorScheme(
    primary = GradeyColors.PrimaryDark,
    onPrimary = GradeyColors.OnAccent,
    primaryContainer = GradeyColors.PrimaryDark.copy(alpha = 0.14f),
    onPrimaryContainer = GradeyColors.PrimaryDark,
    secondary = GradeyColors.SecondaryDark,
    onSecondary = GradeyColors.OnAccent,
    secondaryContainer = GradeyColors.PrimaryDark.copy(alpha = 0.12f),
    onSecondaryContainer = GradeyColors.PrimaryDark,
    tertiary = GradeyColors.GoodDark,
    background = DarkExtended.groupedBackground,
    onBackground = DarkExtended.label,
    surface = DarkExtended.groupedBackground,
    onSurface = DarkExtended.label,
    surfaceVariant = DarkExtended.tertiaryFill,
    onSurfaceVariant = DarkExtended.secondaryLabel,
    surfaceContainerLowest = GradeyColors.Black,
    surfaceContainerLow = DarkExtended.secondaryGroupedBackground,
    surfaceContainer = DarkExtended.secondaryGroupedBackground,
    surfaceContainerHigh = DarkExtended.secondaryGroupedBackground,
    surfaceContainerHighest = Color(0xFF2C2C2E),
    outline = DarkExtended.tertiaryLabel,
    outlineVariant = DarkExtended.separator,
    error = GradeyColors.Poor,
    onError = GradeyColors.White,
    errorContainer = GradeyColors.Poor.copy(alpha = 0.18f),
    onErrorContainer = GradeyColors.Poor,
    scrim = GradeyColors.Black.copy(alpha = 0.5f),
)

val LocalGradeyColors = staticCompositionLocalOf { LightExtended }

/** Space Grotesk is the only typeface (iOS `GradelyFont`). Semibold resolves to the Bold face, as on iOS. */
val GradeyFontFamily: FontFamily = FontFamily(
    Font(R.font.space_grotesk_regular, FontWeight.Normal),
    Font(R.font.space_grotesk_medium, FontWeight.Medium),
    Font(R.font.space_grotesk_bold, FontWeight.SemiBold),
    Font(R.font.space_grotesk_bold, FontWeight.Bold),
)

/** Kept for callers that asked for the display face explicitly. */
val GradeyDisplayFontFamily: FontFamily = GradeyFontFamily

private val GradeyLineHeight = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

private fun style(size: Int, weight: FontWeight = FontWeight.Normal, tracking: TextUnit = 0.sp) = TextStyle(
    fontFamily = GradeyFontFamily,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = 1.28.em,
    letterSpacing = tracking,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = GradeyLineHeight,
)

/**
 * iOS text styles in sp (default Dynamic Type size). `headline` is Bold, like `Font.gradely(.headline)`.
 * Numbers use [tabular] so digits line up (`.monospacedDigit()`).
 */
object GradeyType {
    /** Bold display sizes: 52 hero numbers, 42 Meals balance, 38 Recap, 36 Settings, 32 AI, 30 greeting. */
    fun display(size: Int = 38): TextStyle = style(size, FontWeight.Bold).copy(lineHeight = 1.12.em)

    val largeTitle = style(34, FontWeight.Bold)
    val title1 = style(28, FontWeight.Bold)
    val title2 = style(22, FontWeight.Bold)
    val title3 = style(20, FontWeight.Bold)
    val headline = style(17, FontWeight.Bold)
    val body = style(17)
    val bodyMedium = style(17, FontWeight.Medium)
    val callout = style(16)
    val subheadline = style(15)
    val subheadlineMedium = style(15, FontWeight.Medium)
    val subheadlineSemibold = style(15, FontWeight.SemiBold)
    val subheadlineBold = style(15, FontWeight.Bold)
    val footnote = style(13)
    val footnoteBold = style(13, FontWeight.Bold)
    val caption = style(12)
    val captionSemibold = style(12, FontWeight.SemiBold)
    val captionBold = style(12, FontWeight.Bold)
    val caption2 = style(11)
    val caption2Medium = style(11, FontWeight.Medium)
    val caption2Semibold = style(11, FontWeight.SemiBold)
    val caption2Bold = style(11, FontWeight.Bold)

    /** Uppercase section label (iOS `SectionHeader`). */
    val sectionHeader = style(13, FontWeight.Bold, 0.6.sp)

    // Tab bar and segmented control (iOS `GradelyFont` appearance setup).
    val tabLabel = style(11, FontWeight.Medium)
    val segment = style(13, FontWeight.Medium)
    val segmentSelected = style(13, FontWeight.Bold)
}

/** Monospaced digits (`.monospacedDigit()`). */
fun TextStyle.tabular(): TextStyle = copy(fontFeatureSettings = "tnum")

private val GradeyTypography = Typography(
    displayLarge = GradeyType.display(52),
    displayMedium = GradeyType.display(38),
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
    labelLarge = GradeyType.subheadlineSemibold,
    labelMedium = GradeyType.captionSemibold,
    labelSmall = GradeyType.caption2Semibold,
)

private val GradeyShapes = Shapes(
    extraSmall = RoundedCornerShape(GradeyRadius.xs),
    small = RoundedCornerShape(GradeyRadius.sm),
    medium = RoundedCornerShape(GradeyRadius.md),
    large = RoundedCornerShape(GradeyRadius.card),
    extraLarge = RoundedCornerShape(GradeyRadius.xl),
)

/** Picks the light or dark variant of a token for the current theme. */
@Composable
fun adaptiveColor(light: Color, dark: Color): Color = if (MaterialTheme.gradeyColors.isDark) dark else light

val MaterialTheme.gradeyColors: GradeyExtendedColors
    @Composable get() = LocalGradeyColors.current

/** Diagonal brand gradient (iOS `Brand.gradient`): primary at the top-left to secondary at the bottom-right. */
val ColorScheme.brandGradient: Brush
    get() = Brush.linearGradient(listOf(primary, secondary))

@Composable
fun GradeyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
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

/** Grade band colours (iOS `GradeBand.foregroundColor`). */
@Composable
fun GradeBand.color(): Color = when (this) {
    GradeBand.EXCELLENT -> GradeyColors.Excellent
    GradeBand.GOOD -> MaterialTheme.gradeyColors.good
    GradeBand.AVERAGE -> GradeyColors.Average
    GradeBand.POOR -> GradeyColors.Poor
    GradeBand.NEUTRAL -> MaterialTheme.gradeyColors.secondaryLabel
}

/** The band colour outside composition, for charts and canvases that resolve the theme themselves. */
fun GradeBand.staticColor(dark: Boolean): Color = when (this) {
    GradeBand.EXCELLENT -> GradeyColors.Excellent
    GradeBand.GOOD -> if (dark) GradeyColors.GoodDark else GradeyColors.GoodLight
    GradeBand.AVERAGE -> GradeyColors.Average
    GradeBand.POOR -> GradeyColors.Poor
    GradeBand.NEUTRAL -> GradeyColors.Neutral
}

/** Soft tonal fill for grade badges and chips (14 %, neutral grey 16 %). */
@Composable
fun GradeBand.softColor(): Color = if (this == GradeBand.NEUTRAL) GradeyColors.Neutral.copy(alpha = 0.16f) else color().copy(alpha = 0.14f)
