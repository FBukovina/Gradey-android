package com.bukovinafilip.gradey.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bukovinafilip.gradey.domain.GradeBand

object GradeyColors {
    val Primary = Color(0xFF17A185)
    val Secondary = Color(0xFF1DA565)
    val OnAccent = Color(0xFF041816)
    val Excellent = Color(0xFF1E9F69)
    val Good = Color(0xFF108A94)
    val Average = Color(0xFFD98F10)
    val Poor = Color(0xFFD83E4F)
}

object GradeySpacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
}

object GradeyRadius {
    val sm = 8.dp
    val md = 12.dp
    val card = 16.dp
}

private val LightScheme = lightColorScheme(
    primary = Color(0xFF007E68),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDF4EF),
    onPrimaryContainer = Color(0xFF00382D),
    secondary = Color(0xFF28745E),
    secondaryContainer = Color(0xFFDDF4EF),
    onSecondaryContainer = Color(0xFF123B30),
    tertiary = GradeyColors.Good,
    background = Color(0xFFF3F8F6),
    onBackground = Color(0xFF17211E),
    surface = Color.White,
    onSurface = Color(0xFF17211E),
    surfaceVariant = Color(0xFFE1ECE7),
    onSurfaceVariant = Color(0xFF53635C),
    surfaceContainer = Color.White,
    surfaceContainerLow = Color(0xFFF8FBF9),
    surfaceContainerHigh = Color(0xFFEAF2EE),
    surfaceContainerHighest = Color(0xFFE1ECE7),
    outline = Color(0xFF71837A),
    outlineVariant = Color(0xFFCAD9D1),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF1AFFBE),
    secondary = Color(0xFF1FF98C),
    tertiary = Color(0xFF5AD7E0),
)

@Composable
fun GradeyTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        content = content,
    )
}

@Composable
fun GradeyScreen(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(GradeySpacing.lg),
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier.padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(GradeySpacing.lg),
        ) {
            content()
        }
    }
}

@Composable
fun GradeyHero(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(GradeyRadius.card))
            .background(Brush.linearGradient(listOf(GradeyColors.Primary, GradeyColors.Secondary)))
            .padding(GradeySpacing.xl),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(GradeySpacing.sm)) {
            Text(title, style = MaterialTheme.typography.headlineMedium, color = GradeyColors.OnAccent, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = GradeyColors.OnAccent.copy(alpha = 0.84f))
        }
    }
}

@Composable
fun GradeySectionCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(GradeyRadius.card),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(
            modifier = Modifier.padding(GradeySpacing.lg),
            verticalArrangement = Arrangement.spacedBy(GradeySpacing.md),
        ) {
            if (title != null) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(GradeySpacing.xs))
            }
            content()
        }
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
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.SemiBold)
    }
}

fun GradeBand.color(): Color = when (this) {
    GradeBand.EXCELLENT -> GradeyColors.Excellent
    GradeBand.GOOD -> GradeyColors.Good
    GradeBand.AVERAGE -> GradeyColors.Average
    GradeBand.POOR -> GradeyColors.Poor
    GradeBand.NEUTRAL -> Color.Gray
}
