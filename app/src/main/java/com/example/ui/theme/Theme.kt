package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val KestrelDarkColorScheme = darkColorScheme(
    primary = CyanElectric,
    onPrimary = ObsidianBg,
    primaryContainer = CyanContainerDark,
    onPrimaryContainer = CyanOnContainerDark,
    secondary = EmeraldPhosphor,
    onSecondary = ObsidianBg,
    secondaryContainer = EmeraldContainerDark,
    onSecondaryContainer = EmeraldOnContainerDark,
    tertiary = AmberTelemetry,
    onTertiary = ObsidianBg,
    tertiaryContainer = AmberContainerDark,
    onTertiaryContainer = AmberOnContainerDark,
    error = CoralAlert,
    onError = ObsidianBg,
    errorContainer = CoralContainerDark,
    onErrorContainer = CoralOnContainerDark,
    background = ObsidianBg,
    onBackground = TextPrimaryDark,
    surface = ObsidianSurface,
    onSurface = TextPrimaryDark,
    surfaceVariant = ObsidianSurfaceVariant,
    onSurfaceVariant = TextSecondaryDark,
    surfaceContainer = ObsidianSurface,
    surfaceContainerLow = ObsidianBg,
    surfaceContainerHigh = ObsidianSurfaceContainerHigh,
    outline = ObsidianOutline,
    outlineVariant = ObsidianOutlineVariant
)

private val KestrelLightColorScheme = lightColorScheme(
    primary = CyanLightPrimary,
    onPrimary = LabSurface,
    primaryContainer = CyanContainerLight,
    onPrimaryContainer = CyanOnContainerLight,
    secondary = EmeraldLightSecondary,
    onSecondary = LabSurface,
    secondaryContainer = EmeraldContainerLight,
    onSecondaryContainer = EmeraldOnContainerLight,
    tertiary = AmberLightTertiary,
    onTertiary = LabSurface,
    tertiaryContainer = AmberContainerLight,
    onTertiaryContainer = AmberOnContainerLight,
    error = CoralLightError,
    onError = LabSurface,
    errorContainer = CoralContainerLight,
    onErrorContainer = CoralOnContainerLight,
    background = LabBg,
    onBackground = TextPrimaryLight,
    surface = LabSurface,
    onSurface = TextPrimaryLight,
    surfaceVariant = LabSurfaceVariant,
    onSurfaceVariant = TextSecondaryLight,
    surfaceContainer = LabSurface,
    surfaceContainerLow = LabBg,
    surfaceContainerHigh = LabSurfaceContainerHigh,
    outline = LabOutline,
    outlineVariant = LabOutlineVariant
)

private val KestrelShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(26.dp)
)

@Composable
fun KestrelVMTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) KestrelDarkColorScheme else KestrelLightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = KestrelTypography,
        shapes = KestrelShapes,
        content = content
    )
}
