package com.shrey.buddy.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val scheme = darkColorScheme(
    primary = BuddyColors.Lime,
    onPrimary = BuddyColors.OnLime,
    primaryContainer = BuddyColors.LimeContainer,
    onPrimaryContainer = BuddyColors.Lime,
    secondary = BuddyColors.Voice,
    onSecondary = BuddyColors.Background,
    tertiary = BuddyColors.Fitness,
    onTertiary = BuddyColors.Background,
    background = BuddyColors.Background,
    onBackground = BuddyColors.TextPrimary,
    surface = BuddyColors.Background,
    onSurface = BuddyColors.TextPrimary,
    surfaceVariant = BuddyColors.SurfaceHigh,
    onSurfaceVariant = BuddyColors.TextSecondary,
    surfaceContainerLowest = BuddyColors.Background,
    surfaceContainerLow = BuddyColors.Surface,
    surfaceContainer = BuddyColors.Surface,
    surfaceContainerHigh = BuddyColors.SurfaceHigh,
    surfaceContainerHighest = BuddyColors.SurfaceHighest,
    outline = BuddyColors.Outline,
    outlineVariant = BuddyColors.Outline,
    error = BuddyColors.Red,
    onError = BuddyColors.Background,
)

private val shapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

/** Always dark — the "Dark & bold" direction chosen for Buddy. */
@Composable
fun BuddyTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = BuddyTypography, shapes = shapes, content = content)
}
